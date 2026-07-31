package co.purrito.myfinances.service

import co.purrito.myfinances.data.model.DeferredPurchase
import co.purrito.myfinances.data.model.Transaction
import co.purrito.myfinances.data.model.TransactionSource
import co.purrito.myfinances.data.model.TransactionStatus
import co.purrito.myfinances.data.model.TransactionType
import java.security.MessageDigest
import kotlin.math.abs

/* =====================================================================
 * Reconciliación de extractos (Hito 4) — NÚCLEO PURO.
 *
 * Igual que SmsParser, esta capa no depende de Android: recibe las
 * líneas ya parseadas del extracto + el estado actual de la BD y
 * decide, por línea, qué hacer. El parser por banco (PDF/CSV) y el
 * importador que persiste el plan se construyen encima.
 *
 * Una `ParsedStatementLine` es la representación neutral de una línea
 * del extracto, independiente del formato del banco. El parser concreto
 * (cuando haya un extracto real de ejemplo) produce estas líneas.
 * ===================================================================== */

data class ParsedStatementLine(
    val dateMillis: Long,
    val amountMinor: Long,            // monto del MOVIMIENTO (total), positivo, centavos
    val type: TransactionType,        // EXPENSE compra/cargo · TRANSFER pago · INCOME abono
    val rawDescription: String,       // texto crudo ("PAYU*RAPPI BOG")
    /** "CUOTA 3/12" → current=3, total=12. null si no es una cuota (o 1/1). */
    val installmentCurrent: Int? = null,
    val installmentTotal: Int? = null,
    /** Intereses, cuota de manejo, seguros, comisiones de avance. */
    val isFinancialCharge: Boolean = false,
    /** Valor de la cuota del extracto (con interés). null si no aplica. */
    val installmentAmountMinor: Long? = null,
    /**
     * Solo para TRANSFER: si la TC es el ORIGEN (avance, sale dinero →
     * más deuda) o el DESTINO (abono/pago → menos deuda, valor por
     * defecto). Determina la dirección al persistir y el efecto en saldo.
     */
    val cardIsOrigin: Boolean = false
)

/** Qué se decidió para cada línea del extracto. */
sealed interface LineOutcome {
    val line: ParsedStatementLine

    /** Ya existía en la BD (mismo monto, fecha ±1 día). No se duplica. */
    data class Duplicate(
        override val line: ParsedStatementLine,
        val matchedTransactionId: Long
    ) : LineOutcome

    /**
     * Es una cuota de una compra diferida ya registrada: no crea una
     * transacción nueva (la compra completa ya está en la BD), solo
     * actualiza el plan de facturación.
     */
    data class InstallmentBilled(
        override val line: ParsedStatementLine,
        val deferredPurchaseId: Long,
        val newBilledInstallments: Int,
        val installmentAmountMinor: Long,
        val closed: Boolean
    ) : LineOutcome

    /** No matchea nada: cae al inbox como PENDING con source=STATEMENT. */
    data class New(
        override val line: ParsedStatementLine,
        val transaction: Transaction
    ) : LineOutcome
}

data class StatementImportPlan(
    val outcomes: List<LineOutcome>,
    /** Ajuste PENDING si el saldo del extracto difiere del proyectado. */
    val balanceAdjustment: Transaction?
) {
    val newTransactions: List<Transaction>
        get() = outcomes.filterIsInstance<LineOutcome.New>().map { it.transaction }
    val installmentUpdates: List<LineOutcome.InstallmentBilled>
        get() = outcomes.filterIsInstance<LineOutcome.InstallmentBilled>()
    val duplicates: List<LineOutcome.Duplicate>
        get() = outcomes.filterIsInstance<LineOutcome.Duplicate>()

    // Para el resumen "X nuevas, Y cuotas actualizadas, Z ya existían"
    val newCount: Int get() = newTransactions.size + if (balanceAdjustment != null) 1 else 0
    val updatedCount: Int get() = installmentUpdates.size
    val duplicateCount: Int get() = duplicates.size
}

object StatementReconciler {

    private const val ONE_DAY_MILLIS = 24L * 60 * 60 * 1000

    /** Por debajo de esto no vale la pena un ajuste de saldo (1 peso). */
    private const val MIN_ADJUSTMENT_MINOR = 100L

    /**
     * @param accountId         la cuenta (TC) del extracto
     * @param lines             líneas ya parseadas del extracto
     * @param existing          transacciones de la cuenta en el rango del
     *                          extracto (cualquier status) para deduplicar
     * @param openDeferred      compras diferidas abiertas de la cuenta
     * @param financialCategoryId  id de "Costos financieros" (cargos del banco)
     * @param statementBalanceMinor saldo (deuda, negativo) que reporta el
     *                          extracto; null si el parser no lo extrajo
     * @param computedBalanceMinor  deuda actual calculada en la app (CONFIRMED)
     */
    fun reconcile(
        accountId: Long,
        lines: List<ParsedStatementLine>,
        existing: List<Transaction>,
        openDeferred: List<DeferredPurchase>,
        financialCategoryId: Long?,
        statementBalanceMinor: Long?,
        computedBalanceMinor: Long,
        dayToleranceMillis: Long = ONE_DAY_MILLIS
    ): StatementImportPlan {
        val consumedTx = mutableSetOf<Long>()        // existentes ya matcheadas
        val consumedPurchases = mutableSetOf<Long>() // planes ya facturados aquí

        val outcomes = lines.mapIndexed { index, line ->
            // 1) ¿Es una cuota de un diferido ya registrado?
            val installmentMatch = matchInstallment(line, openDeferred, consumedPurchases)
            if (installmentMatch != null) {
                consumedPurchases += installmentMatch.id
                val billed = line.installmentCurrent!!
                return@mapIndexed LineOutcome.InstallmentBilled(
                    line = line,
                    deferredPurchaseId = installmentMatch.id,
                    newBilledInstallments = billed,
                    // valor por cuota del extracto (con interés); si el
                    // parser no lo trajo, cae al monto del movimiento
                    installmentAmountMinor = line.installmentAmountMinor ?: line.amountMinor,
                    closed = billed >= installmentMatch.totalInstallments
                )
            }

            // 2) ¿Ya existe? (mismo monto, fecha ±1 día, no consumida aún)
            val dup = existing.firstOrNull { t ->
                t.id !in consumedTx &&
                    t.amountMinor == line.amountMinor &&
                    abs(t.dateMillis - line.dateMillis) <= dayToleranceMillis
            }
            if (dup != null) {
                consumedTx += dup.id
                return@mapIndexed LineOutcome.Duplicate(line, dup.id)
            }

            // 3) Nueva: al inbox como PENDING, source=STATEMENT. Los cargos
            //    financieros nacen ya clasificados como "Costos financieros".
            LineOutcome.New(
                line = line,
                transaction = Transaction(
                    accountId = accountId,
                    type = line.type,
                    amountMinor = line.amountMinor,
                    categoryId = if (line.isFinancialCharge) financialCategoryId else null,
                    dateMillis = line.dateMillis,
                    merchantRaw = line.rawDescription,
                    source = TransactionSource.STATEMENT,
                    status = TransactionStatus.PENDING,
                    externalRef = statementRef(accountId, line, index)
                )
            )
        }

        val adjustment = buildBalanceAdjustment(
            accountId, outcomes, financialCategoryId,
            statementBalanceMinor, computedBalanceMinor
        )

        return StatementImportPlan(outcomes, adjustment)
    }

    /**
     * Empareja una línea "CUOTA x/y" con una compra diferida abierta.
     *
     * Criterio (de más a menos fuerte):
     *  1) La línea debe AVANZAR el plan: su cuota actual va más allá de lo
     *     ya facturado (`current > billedInstallments`). Esto impide que una
     *     compra NUEVA "1/6" sea absorbida por un plan viejo del mismo nº de
     *     cuotas que ya facturó alguna (antes `singleOrNull` la tragaba solo
     *     por coincidir el total → la compra nueva no entraba al inbox).
     *  2) Mismo capital total: el "valor movimiento" del extracto es el
     *     total de la compra; si iguala `totalAmountMinor` del plan, es la
     *     misma compra (señal única aunque el comercio se repita).
     *  3) Correspondencia de comercio (substring en cualquier dirección).
     *  4) Continuación (no primera cuota) con un único candidato que avanza:
     *     se confía aunque el texto del extracto no matchee el comercio.
     * Si nada aplica, devuelve null y la línea sigue el flujo normal (nueva).
     */
    private fun matchInstallment(
        line: ParsedStatementLine,
        openDeferred: List<DeferredPurchase>,
        consumedPurchases: Set<Long>
    ): DeferredPurchase? {
        val current = line.installmentCurrent ?: return null
        val total = line.installmentTotal ?: return null
        val candidates = openDeferred.filter {
            it.id !in consumedPurchases &&
                it.totalInstallments == total &&
                current > it.billedInstallments
        }
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { it.totalAmountMinor == line.amountMinor }?.let { return it }
        candidates.firstOrNull { p ->
            line.rawDescription.contains(p.merchant, ignoreCase = true) ||
                p.merchant.contains(line.rawDescription, ignoreCase = true)
        }?.let { return it }
        return if (current > 1) candidates.singleOrNull() else null
    }

    /**
     * El extracto es la verdad: tras importar y confirmar todo, la deuda
     * de la app debe igualar el saldo del extracto. Proyecta la deuda
     * actual + el efecto de las líneas NUEVAS y, si aún difiere del
     * extracto, crea un ajuste PENDING (Costos financieros) por la
     * diferencia. Las cuotas y duplicados ya están en la deuda calculada.
     */
    private fun buildBalanceAdjustment(
        accountId: Long,
        outcomes: List<LineOutcome>,
        financialCategoryId: Long?,
        statementBalanceMinor: Long?,
        computedBalanceMinor: Long
    ): Transaction? {
        if (statementBalanceMinor == null) return null

        val newEffect = outcomes.filterIsInstance<LineOutcome.New>().sumOf { balanceEffect(it.line) }
        val projected = computedBalanceMinor + newEffect
        val diff = statementBalanceMinor - projected
        if (abs(diff) < MIN_ADJUSTMENT_MINOR) return null

        // diff < 0 → falta deuda → EXPENSE; diff > 0 → sobra deuda → INCOME
        val (type, amount) = if (diff < 0) {
            TransactionType.EXPENSE to -diff
        } else {
            TransactionType.INCOME to diff
        }
        return Transaction(
            accountId = accountId,
            type = type,
            amountMinor = amount,
            categoryId = financialCategoryId,
            dateMillis = statementAdjustmentDate(outcomes),
            merchantRaw = null,
            source = TransactionSource.STATEMENT,
            status = TransactionStatus.PENDING,
            externalRef = null
        )
    }

    /** Efecto de una línea sobre la deuda de la TC (positivo = menos deuda). */
    private fun balanceEffect(line: ParsedStatementLine): Long = when (line.type) {
        TransactionType.EXPENSE -> -line.amountMinor   // compra/cargo: más deuda
        TransactionType.INCOME -> line.amountMinor      // abono/devolución
        // TRANSFER: avance (TC origen) suma deuda; pago/abono la reduce.
        TransactionType.TRANSFER ->
            if (line.cardIsOrigin) -line.amountMinor else line.amountMinor
    }

    /** El ajuste se fecha como la última línea del extracto (o 0 si vacío). */
    private fun statementAdjustmentDate(outcomes: List<LineOutcome>): Long =
        outcomes.maxOfOrNull { it.line.dateMillis } ?: 0L

    /** Huella de deduplicación del extracto: incluye el nº de línea. */
    private fun statementRef(accountId: Long, line: ParsedStatementLine, index: Int): String =
        sha256("$accountId|${line.dateMillis}|${line.amountMinor}|${line.rawDescription}|$index")

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
