package co.purrito.myfinances.service

import android.content.Context
import co.purrito.myfinances.R
import co.purrito.myfinances.data.AppDatabase
import co.purrito.myfinances.data.model.AccountType
import co.purrito.myfinances.data.model.DeferredPurchase
import co.purrito.myfinances.data.model.TransactionStatus
import co.purrito.myfinances.data.model.TransactionType
import java.io.ByteArrayInputStream

/* =====================================================================
 * Importación de extractos (Hito 4) — orquestación.
 *
 * Detecta el formato, parsea, reconcilia (StatementReconciler) y
 * persiste el plan: nuevas → inbox PENDING (con alias aplicado), cuotas
 * → actualiza el plan de facturación, movimientos confirmados → se
 * marcan `reconciled`, y un ajuste PENDING si el saldo del extracto no
 * cuadra con la deuda calculada.
 *
 * Recibe la BD por parámetro (no AppDatabase.get) para poder correr la
 * importación completa contra una COPIA de la BD al verificar cambios.
 * ===================================================================== */

sealed interface ImportResult {
    data class Success(
        val accountName: String,
        val newCount: Int,
        val updatedCount: Int,
        val duplicateCount: Int,
        /** Ajuste de saldo creado (con signo: negativo = más deuda), o null. */
        val adjustmentMinor: Long?,
        /** Movimientos de la tarjeta que ningún extracto ha confirmado aún. */
        val unreconciledCount: Int,
        val unreconciledMinor: Long,
        /** Compras en dólares del extracto (no se importan), en centavos de USD. */
        val foreignPurchaseCount: Int,
        val foreignPurchasesMinor: Long
    ) : ImportResult

    data class Error(val kind: ImportErrorKind, val arg: String? = null) : ImportResult
}

enum class ImportErrorKind { INVALID_FILE, ACCOUNT_NOT_FOUND, READ_FAILED }

// Nombres por defecto (es/en) de la categoría de cargos del banco. Se
// busca por estos para tolerar el rename del usuario entre idiomas.
private val FINANCIAL_CATEGORY_NAMES = listOf("Costos financieros", "Financial costs")
private const val DAY = 24L * 60 * 60 * 1000

class StatementImporter(
    private val context: Context,
    private val db: AppDatabase
) {

    suspend fun import(bytes: ByteArray): ImportResult {
        // Enruta por formato:
        //  - zip "PK"  → .xlsx Bancolombia (lee celdas)
        //  - "%PDF"    → PDF: extrae texto y detecta el banco por su nombre
        //  - resto     → texto plano (.txt de Davivienda)
        val parsed = when {
            isZip(bytes) -> {
                val sheets = XlsxReader.readSheets(ByteArrayInputStream(bytes))
                val sheet = BancolombiaStatementParser.pickStatementSheet(sheets)
                    ?: return ImportResult.Error(ImportErrorKind.INVALID_FILE)
                BancolombiaStatementParser.parse(sheet)
            }
            isPdf(bytes) -> {
                val text = PdfTextExtractor.extract(context, bytes)
                    ?: return ImportResult.Error(ImportErrorKind.READ_FAILED)
                parseByBank(text)
            }
            // .txt: bytes latin-1; las descripciones que importan son
            // ASCII, así ISO-8859-1 nunca falla.
            else -> parseByBank(String(bytes, Charsets.ISO_8859_1))
        }
        if (parsed.lines.isEmpty()) return ImportResult.Error(ImportErrorKind.INVALID_FILE)

        val account = parsed.lastFourDigits?.let { db.accountDao().findByLastFour(it) }
            ?: return ImportResult.Error(ImportErrorKind.ACCOUNT_NOT_FOUND, parsed.lastFourDigits)

        val from = (parsed.periodFromMillis ?: 0L) - 2 * DAY
        val to = (parsed.periodToMillis ?: Long.MAX_VALUE) + 2 * DAY
        val existing = db.transactionDao().getForReconciliation(account.id, from, to)
        // Limpia planes huérfanos (de transacciones borradas) para que no
        // absorban líneas del extracto como cuotas en vez de crearlas.
        db.deferredPurchaseDao().deleteOrphans()
        val openDeferred = db.deferredPurchaseDao().getOpenByCard(account.id)

        // La tarjeta en la app, hasta el FIN del día de corte (la última
        // línea del extracto): lo posterior —ej. un pago hecho después del
        // corte— no entra a la verificación de saldo.
        val ledger = parsed.periodToMillis?.let { lastLine ->
            CardLedger(
                initialBalanceMinor = account.initialBalanceMinor,
                transactions = db.transactionDao().getCardLedger(account.id),
                cutoffMillis = lastLine + DAY - 1
            )
        }

        val plan = StatementReconciler.reconcile(
            accountId = account.id,
            lines = parsed.lines,
            existing = existing,
            openDeferred = openDeferred,
            financialCategoryId = financialCategoryId(),
            statementBalanceMinor = parsed.statementBalanceMinor,
            ledger = ledger
        )

        persist(plan, openDeferred)
        ledger?.let { persistAdjustment(plan, account.id, it.cutoffMillis) }

        val adjustment = plan.balanceAdjustment
        return ImportResult.Success(
            accountName = account.name,
            newCount = plan.newCount,
            updatedCount = plan.updatedCount,
            duplicateCount = plan.duplicateCount,
            adjustmentMinor = adjustment?.let {
                if (it.type == TransactionType.EXPENSE) -it.amountMinor else it.amountMinor
            },
            unreconciledCount = plan.unreconciledTransactions.size,
            unreconciledMinor = plan.unreconciledTransactions.sumOf { it.amountMinor },
            foreignPurchaseCount = parsed.foreignPurchaseCount,
            foreignPurchasesMinor = parsed.foreignPurchasesMinor
        )
    }

    private suspend fun persist(plan: StatementImportPlan, openDeferred: List<DeferredPurchase>) {
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
                        // El texto CRUDO del extracto (no el alias): es con
                        // lo que se emparejan las cuotas de los próximos
                        // extractos. El nombre visible vive en la
                        // transacción ancla, que el usuario sí edita.
                        merchant = tx.merchantRaw
                            ?: context.getString(R.string.deferred_purchase),
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

        // Lo que este extracto confirmó queda conciliado: el próximo
        // extracto ya lo da por conocido al verificar el saldo.
        plan.duplicates.map { it.matchedTransactionId }.takeIf { it.isNotEmpty() }
            ?.let { db.transactionDao().markReconciled(it) }
        plan.installmentUpdates.map { it.deferredPurchaseId }.takeIf { it.isNotEmpty() }
            ?.let { db.transactionDao().markReconciledByPlans(it) }
    }

    /**
     * Ajuste de saldo: uno por extracto (huella = cuenta + corte). Si al
     * reimportar ya hay uno PENDING, se reemplaza con el valor nuevo (o se
     * borra si ya no hace falta); si el usuario ya lo confirmó, se respeta
     * (y como cuenta en el saldo, el nuevo cálculo ya no lo repite).
     */
    private suspend fun persistAdjustment(
        plan: StatementImportPlan,
        accountId: Long,
        cutoffMillis: Long
    ) {
        val adjustment = plan.balanceAdjustment
        val previous = db.transactionDao()
            .findByExternalRef(StatementReconciler.adjustmentRef(accountId, cutoffMillis))
        when {
            adjustment == null -> {
                // Ya cuadra: un ajuste pendiente de una corrida anterior sobra
                if (previous?.status == TransactionStatus.PENDING) db.transactionDao().delete(previous)
            }
            previous == null -> db.transactionDao().insert(
                adjustment.copy(description = context.getString(R.string.statement_adjustment))
            )
            previous.status == TransactionStatus.PENDING -> db.transactionDao().update(
                previous.copy(type = adjustment.type, amountMinor = adjustment.amountMinor)
            )
            else -> Unit
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
    private fun parseByBank(text: String): ParsedStatement = when {
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
