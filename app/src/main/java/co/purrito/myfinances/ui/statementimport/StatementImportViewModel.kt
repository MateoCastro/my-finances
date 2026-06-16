package co.purrito.myfinances.ui.statementimport

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.purrito.myfinances.data.AppDatabase
import co.purrito.myfinances.R
import co.purrito.myfinances.data.model.AccountType
import co.purrito.myfinances.data.model.DeferredPurchase
import co.purrito.myfinances.data.model.TransactionType
import co.purrito.myfinances.service.BancolombiaPdfStatementParser
import co.purrito.myfinances.service.BancolombiaStatementParser
import co.purrito.myfinances.service.DaviviendaStatementParser
import co.purrito.myfinances.service.LineOutcome
import co.purrito.myfinances.service.PdfTextExtractor
import co.purrito.myfinances.service.StatementReconciler
import co.purrito.myfinances.service.XlsxReader
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* =====================================================================
 * Importación de extractos (Hito 4) — orquestación.
 *
 * Lee el .xlsx (XlsxReader), elige la hoja PESOS, parsea
 * (BancolombiaStatementParser), reconcilia (StatementReconciler) y
 * persiste el plan: nuevas → inbox PENDING (con alias aplicado), cuotas
 * → actualiza el plan de facturación, y un ajuste PENDING si el saldo
 * del extracto no cuadra con la deuda calculada.
 * ===================================================================== */

sealed interface ImportState {
    data object Idle : ImportState
    data object Loading : ImportState
    data class Success(
        val accountName: String,
        val newCount: Int,
        val updatedCount: Int,
        val duplicateCount: Int
    ) : ImportState
    /** Errores con string de recurso para i18n; arg opcional (ej: últimos 4). */
    data class Error(val kind: ErrorKind, val arg: String? = null) : ImportState
}

enum class ErrorKind { INVALID_FILE, ACCOUNT_NOT_FOUND, READ_FAILED }

// Nombres por defecto (es/en) de la categoría de cargos del banco. Se
// busca por estos para tolerar el rename del usuario entre idiomas.
private val FINANCIAL_CATEGORY_NAMES = listOf("Costos financieros", "Financial costs")
private const val DAY = 24L * 60 * 60 * 1000

class StatementImportViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state

    fun reset() { _state.value = ImportState.Idle }

    fun import(uri: Uri) {
        _state.value = ImportState.Loading
        viewModelScope.launch {
            val result = runCatching { runImport(uri) }
            _state.value = result.getOrElse { ImportState.Error(ErrorKind.READ_FAILED) }
        }
    }

    private suspend fun runImport(uri: Uri): ImportState = withContext(Dispatchers.IO) {
        val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use {
            it.readBytes()
        } ?: return@withContext ImportState.Error(ErrorKind.READ_FAILED)

        // Enruta por formato:
        //  - zip "PK"  → .xlsx Bancolombia (lee celdas)
        //  - "%PDF"    → PDF: extrae texto y detecta el banco por su nombre
        //  - resto     → texto plano (.txt de Davivienda)
        val parsed = when {
            isZip(bytes) -> {
                val sheets = XlsxReader.readSheets(ByteArrayInputStream(bytes))
                val sheet = BancolombiaStatementParser.pickStatementSheet(sheets)
                    ?: return@withContext ImportState.Error(ErrorKind.INVALID_FILE)
                BancolombiaStatementParser.parse(sheet)
            }
            isPdf(bytes) -> {
                val text = PdfTextExtractor.extract(getApplication(), bytes)
                    ?: return@withContext ImportState.Error(ErrorKind.READ_FAILED)
                parseByBank(text)
            }
            else -> {
                // .txt: bytes latin-1; las descripciones que importan son
                // ASCII, así ISO-8859-1 nunca falla.
                parseByBank(String(bytes, Charsets.ISO_8859_1))
            }
        }
        if (parsed.lines.isEmpty()) return@withContext ImportState.Error(ErrorKind.INVALID_FILE)

        val account = parsed.lastFourDigits?.let { db.accountDao().findByLastFour(it) }
            ?: return@withContext ImportState.Error(
                ErrorKind.ACCOUNT_NOT_FOUND, parsed.lastFourDigits
            )

        val from = (parsed.periodFromMillis ?: 0L) - 2 * DAY
        val to = (parsed.periodToMillis ?: Long.MAX_VALUE) + 2 * DAY
        val existing = db.transactionDao().getForReconciliation(account.id, from, to)
        // Limpia planes huérfanos (de transacciones borradas) para que no
        // absorban líneas del extracto como cuotas en vez de crearlas.
        db.deferredPurchaseDao().deleteOrphans()
        val openDeferred = db.deferredPurchaseDao().getOpenByCard(account.id)
        // Deuda a la FECHA DE CORTE (no all-time): así un pago hecho
        // después del corte no se cancela con el ajuste de saldo.
        val computedBalance = db.accountDao()
            .balanceOfUpTo(account.id, parsed.periodToMillis ?: Long.MAX_VALUE) ?: 0L
        val financialCategoryId = financialCategoryId()

        val plan = StatementReconciler.reconcile(
            accountId = account.id,
            lines = parsed.lines,
            existing = existing,
            openDeferred = openDeferred,
            financialCategoryId = financialCategoryId,
            statementBalanceMinor = parsed.statementBalanceMinor,
            computedBalanceMinor = computedBalance
        )

        persist(plan, openDeferred)

        ImportState.Success(
            accountName = account.name,
            newCount = plan.newCount,
            updatedCount = plan.updatedCount,
            duplicateCount = plan.duplicateCount
        )
    }

    private suspend fun persist(
        plan: co.purrito.myfinances.service.StatementImportPlan,
        openDeferred: List<co.purrito.myfinances.data.model.DeferredPurchase>
    ) {
        // Para enrutar bien los abonos: en un extracto, una TRANSFER es
        // siempre un pago/abono que ENTRA a la tarjeta → la TC es el
        // DESTINO (To). El origen (From) se precarga con un banco.
        val allAccounts = db.accountDao().getAll()

        // Nuevas → inbox PENDING, aplicando el diccionario de alias a las
        // descripciones crípticas (igual que el SmsReceiver).
        for (outcome in plan.outcomes.filterIsInstance<LineOutcome.New>()) {
            val tx = outcome.transaction
            if (tx.externalRef != null && db.transactionDao().existsByExternalRef(tx.externalRef)) {
                continue // ya importado en una corrida anterior
            }
            val alias = tx.merchantRaw?.let { db.merchantAliasDao().findMatch(it) }
            val merchantName = alias?.displayName ?: tx.merchantRaw

            // Compra diferida del extracto (>1 cuota que aún no tiene plan):
            // se precarga el plan con los datos REALES del extracto (cuotas
            // totales, cuota actual facturada y valor de la cuota) y se liga
            // a la transacción PENDING. El inbox lo mostrará ya como diferido.
            val line = outcome.line
            val total = line.installmentTotal
            val deferredId = if (total != null && total > 1) {
                val billed = line.installmentCurrent ?: 1
                db.deferredPurchaseDao().insert(
                    DeferredPurchase(
                        accountId = tx.accountId,
                        merchant = merchantName
                            ?: getApplication<Application>().getString(R.string.deferred_purchase),
                        purchaseDateMillis = tx.dateMillis,
                        totalAmountMinor = tx.amountMinor,
                        totalInstallments = total,
                        installmentAmountMinor = line.installmentAmountMinor,
                        billedInstallments = billed,
                        closed = billed >= total
                    )
                )
            } else null

            // Dirección de las transferencias de extracto:
            //  - abono/pago (cardIsOrigin=false): TC = DESTINO, origen = banco
            //  - avance      (cardIsOrigin=true):  TC = ORIGEN,  destino = banco
            // La "otra cuenta" (banco) se precarga como mejor estimación.
            val isTransfer = tx.type == TransactionType.TRANSFER
            val otherBank = if (isTransfer) {
                allAccounts.firstOrNull { it.id != tx.accountId && it.type == AccountType.BANK }
                    ?: allAccounts.firstOrNull { it.id != tx.accountId }
            } else null
            val finalAccountId: Long
            val finalCounter: Long?
            when {
                !isTransfer -> { finalAccountId = tx.accountId; finalCounter = tx.counterAccountId }
                line.cardIsOrigin -> { finalAccountId = tx.accountId; finalCounter = otherBank?.id } // avance
                else -> { finalAccountId = otherBank?.id ?: tx.accountId; finalCounter = tx.accountId } // abono
            }

            db.transactionDao().insert(
                tx.copy(
                    accountId = finalAccountId,
                    counterAccountId = finalCounter,
                    description = alias?.displayName ?: tx.description,
                    categoryId = if (isTransfer) null
                                 else (tx.categoryId ?: alias?.defaultCategoryId),
                    deferredPurchaseId = deferredId
                )
            )
        }

        // Cuotas → actualizar el plan de facturación.
        for (upd in plan.installmentUpdates) {
            val purchase = openDeferred.firstOrNull { it.id == upd.deferredPurchaseId } ?: continue
            db.deferredPurchaseDao().update(
                purchase.copy(
                    billedInstallments = upd.newBilledInstallments,
                    installmentAmountMinor = upd.installmentAmountMinor,
                    closed = upd.closed
                )
            )
        }

        // Ajuste de saldo (si el extracto no cuadra con la deuda): se le
        // pone descripción para que no aparezca como "Sin descripción".
        plan.balanceAdjustment?.let {
            db.transactionDao().insert(
                it.copy(
                    description = getApplication<Application>()
                        .getString(R.string.statement_adjustment)
                )
            )
        }
    }

    /**
     * Categoría de cargos del banco. Solo la BUSCA (por el nombre por
     * defecto en es o en) — NO la crea: crearla por nombre exacto cada
     * import generaba duplicados cuando el usuario la había renombrado.
     * Si no existe, los cargos quedan sin categoría (el usuario asigna).
     */
    private suspend fun financialCategoryId(): Long? =
        FINANCIAL_CATEGORY_NAMES.firstNotNullOfOrNull { db.categoryDao().findByName(it)?.id }

    /** Texto de extracto → parser del banco correspondiente. */
    private fun parseByBank(text: String): co.purrito.myfinances.service.ParsedStatement = when {
        text.contains("Davivienda", ignoreCase = true) ->
            DaviviendaStatementParser.parse(text)
        else -> // Bancolombia es el otro banco soportado (PDF de texto)
            BancolombiaPdfStatementParser.parse(text)
    }

    /** Los .xlsx (zip) empiezan con "PK"; los PDF con "%PDF". */
    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    private fun isPdf(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()
}
