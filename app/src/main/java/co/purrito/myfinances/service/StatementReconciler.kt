package co.purrito.myfinances.service

import co.purrito.myfinances.data.model.DeferredPurchase
import co.purrito.myfinances.data.model.Transaction
import co.purrito.myfinances.data.model.TransactionSource
import co.purrito.myfinances.data.model.TransactionStatus
import co.purrito.myfinances.data.model.TransactionType
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sign

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

/**
 * Estado de la tarjeta en la app para verificar el saldo del extracto.
 * @param transactions  TODAS las transacciones de la tarjeta (origen o
 *                      destino), cualquier status
 * @param cutoffMillis  fin del día de corte del extracto
 */
data class CardLedger(
    val initialBalanceMinor: Long,
    val transactions: List<Transaction>,
    val cutoffMillis: Long
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
    val balanceAdjustment: Transaction?,
    /**
     * Movimientos de la app en la tarjeta, dentro del periodo del extracto,
     * que NINGÚN extracto ha confirmado: compras que el banco factura en el
     * próximo corte, movimientos en dólares (otra sección del extracto) o
     * errores. Se EXCLUYEN de la verificación de saldo (también los más
     * viejos; estos se listan solo para informar lo del periodo).
     */
    val unreconciledTransactions: List<Transaction> = emptyList()
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

    /**
     * Por debajo de esto no vale la pena un ajuste de saldo ($100): es el
     * redondeo de centavos (el "Pago total" viene en pesos enteros y las
     * cuotas traen centavos), no un desfase real.
     */
    private const val MIN_ADJUSTMENT_MINOR = 100_00L

    /**
     * Tolerancia de monto para considerar que una transacción de la app y
     * una línea del extracto son la misma: menos de 1 peso. El formulario
     * y el inbox trabajan en pesos enteros, así que un movimiento editado
     * a mano pierde los centavos del extracto (intereses 45.678,42 →
     * 45.678). Con match exacto, al reimportar, esa línea volvía a entrar
     * como "nueva" y descuadraba el ajuste de saldo.
     */
    private const val AMOUNT_TOLERANCE_MINOR = 100L

    /** Ventana para emparejar una cuota con la fecha de compra de su plan. */
    private const val PURCHASE_DATE_TOLERANCE_MILLIS = 3 * ONE_DAY_MILLIS

    /**
     * @param accountId         la cuenta (TC) del extracto
     * @param lines             líneas ya parseadas del extracto
     * @param existing          transacciones de la cuenta en el rango del
     *                          extracto (cualquier status) para deduplicar
     * @param openDeferred      compras diferidas abiertas de la cuenta
     * @param financialCategoryId  id de "Costos financieros" (cargos del banco)
     * @param statementBalanceMinor saldo (deuda, negativo) que reporta el
     *                          extracto; null si el parser no lo extrajo
     * @param ledger            la tarjeta en la app, para verificar el
     *                          saldo; null = no verificar (sin ajuste)
     */
    fun reconcile(
        accountId: Long,
        lines: List<ParsedStatementLine>,
        existing: List<Transaction>,
        openDeferred: List<DeferredPurchase>,
        financialCategoryId: Long?,
        statementBalanceMinor: Long?,
        ledger: CardLedger?,
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

            // 2) ¿Ya existe? Primero la misma línea de una importación
            //    anterior (misma huella, aunque el usuario haya editado el
            //    monto); si no, mismo monto (±1 peso), fecha ±1 día y mismo
            //    sentido sobre la deuda (una compra no "es" un abono del
            //    mismo valor). Si hay varias, la más parecida.
            val ref = statementRef(accountId, line, index)
            val lineEffect = balanceEffect(line)
            val dup = existing.firstOrNull { it.id !in consumedTx && it.externalRef == ref }
                ?: existing
                .filter { t ->
                    t.id !in consumedTx &&
                        abs(t.amountMinor - line.amountMinor) < AMOUNT_TOLERANCE_MINOR &&
                        abs(t.dateMillis - line.dateMillis) <= dayToleranceMillis &&
                        t.effectOn(accountId).sign == lineEffect.sign
                }
                .minWithOrNull(
                    compareBy<Transaction> { abs(it.amountMinor - line.amountMinor) }
                        .thenBy { abs(it.dateMillis - line.dateMillis) }
                )
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
                    externalRef = ref,
                    reconciled = true
                )
            )
        }

        if (statementBalanceMinor == null || ledger == null) {
            return StatementImportPlan(outcomes, balanceAdjustment = null)
        }

        val check = checkBalance(accountId, lines, outcomes, ledger, statementBalanceMinor)
        val adjustment = check.differenceMinor
            .takeIf { abs(it) >= MIN_ADJUSTMENT_MINOR }
            ?.let { diff ->
                // diff < 0 → falta deuda → EXPENSE; diff > 0 → sobra deuda → INCOME
                Transaction(
                    accountId = accountId,
                    type = if (diff < 0) TransactionType.EXPENSE else TransactionType.INCOME,
                    amountMinor = abs(diff),
                    categoryId = financialCategoryId,
                    // fechado como la última línea del extracto (el corte)
                    dateMillis = lines.maxOfOrNull { it.dateMillis } ?: ledger.cutoffMillis,
                    merchantRaw = null,
                    source = TransactionSource.STATEMENT,
                    status = TransactionStatus.PENDING,
                    // Una huella por extracto (cuenta + corte): al reimportar
                    // el mismo extracto se REEMPLAZA el ajuste pendiente en
                    // vez de sumar otro.
                    externalRef = adjustmentRef(accountId, ledger.cutoffMillis),
                    reconciled = true
                )
            }

        return StatementImportPlan(outcomes, adjustment, check.excluded)
    }

    /** Huella del ajuste de saldo de un extracto (ver [reconcile]). */
    fun adjustmentRef(accountId: Long, cutoffMillis: Long): String =
        sha256("adjustment|$accountId|$cutoffMillis")

    private class BalanceCheck(val differenceMinor: Long, val excluded: List<Transaction>)

    /**
     * El extracto es la verdad: tras importar y confirmar todo, la deuda de
     * la app debe igualar el saldo del extracto. Se compara SOLO contra lo
     * que el banco ya conoce:
     *
     *  - movimientos de la app que ESTE extracto confirma (duplicados,
     *    aunque sigan PENDING en el inbox, y la compra original de cada
     *    cuota facturada),
     *  - los que un extracto anterior ya confirmó (`reconciled`) o que
     *    nacieron de uno (source STATEMENT: intereses, ajustes...),
     *  - las líneas nuevas (se asume que el usuario las confirmará).
     *
     * Lo demás —compras que el banco factura en el próximo corte, compras
     * y pagos en DÓLARES (el "Pago total" en pesos no los incluye) o
     * errores— queda FUERA. Antes entraba al cálculo y producía ajustes
     * considerables cada mes que después no se revertían.
     */
    private fun checkBalance(
        accountId: Long,
        lines: List<ParsedStatementLine>,
        outcomes: List<LineOutcome>,
        ledger: CardLedger,
        statementBalanceMinor: Long
    ): BalanceCheck {
        val matchedIds = outcomes.filterIsInstance<LineOutcome.Duplicate>()
            .map { it.matchedTransactionId }.toSet()
        val billedPlans = outcomes.filterIsInstance<LineOutcome.InstallmentBilled>()
            .map { it.deferredPurchaseId }.toSet()

        // Primera importación de esta tarjeta: nada está marcado aún, así
        // que lo anterior a los movimientos nuevos del extracto se da por
        // facturado en extractos previos (está en el "saldo anterior").
        val firstImport = ledger.transactions.none { it.source == TransactionSource.STATEMENT }
        val newMovementsFrom = lines
            .filter { (it.installmentCurrent ?: 1) <= 1 }
            .minOfOrNull { it.dateMillis } ?: Long.MIN_VALUE

        val counted = mutableListOf<Transaction>()
        val excluded = mutableListOf<Transaction>()
        for (t in ledger.transactions) {
            val confirmedByThisStatement = t.id in matchedIds ||
                (t.deferredPurchaseId != null && t.deferredPurchaseId in billedPlans)
            when {
                confirmedByThisStatement -> counted += t
                t.status != TransactionStatus.CONFIRMED -> Unit  // inbox: aún no cuenta
                t.dateMillis > ledger.cutoffMillis -> Unit        // posterior al corte
                t.reconciled || t.source == TransactionSource.STATEMENT -> counted += t
                firstImport && t.dateMillis < newMovementsFrom -> counted += t
                else -> excluded += t
            }
        }

        val projected = ledger.initialBalanceMinor +
            counted.sumOf { it.effectOn(accountId) } +
            outcomes.filterIsInstance<LineOutcome.New>().sumOf { balanceEffect(it.line) }
        return BalanceCheck(
            statementBalanceMinor - projected,
            excluded.filter { it.dateMillis >= newMovementsFrom }
        )
    }

    /**
     * Empareja una línea "CUOTA x/y" con una compra diferida abierta.
     *
     * Las cuotas traen la FECHA y el MONTO TOTAL de la compra original
     * (Bancolombia y Davivienda), así que esa es la señal fuerte:
     *  0) La línea debe AVANZAR el plan (`current > billedInstallments`):
     *     una compra NUEVA "1/6" no la absorbe un plan viejo de 6 cuotas.
     *  1) Mismo capital total (±1 peso) y fecha de compra cercana.
     *  2) Mismo capital total (el plan pudo nacer con otra fecha).
     *  3) Mismo comercio Y fecha de compra cercana.
     * Ya NO se acepta un plan solo por comercio o por ser el único
     * candidato: en agregadores ("MERCADO PAGO") eso facturaba la cuota en
     * el plan de OTRA compra (ej. la 2/3 de una compra de 300.000 en el
     * plan de una de 90.000). Si nada aplica, sigue el flujo normal.
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

        fun sameAmount(p: DeferredPurchase) =
            abs(p.totalAmountMinor - line.amountMinor) < AMOUNT_TOLERANCE_MINOR
        fun nearDate(p: DeferredPurchase) =
            abs(p.purchaseDateMillis - line.dateMillis) <= PURCHASE_DATE_TOLERANCE_MILLIS
        fun sameMerchant(p: DeferredPurchase) =
            line.rawDescription.contains(p.merchant, ignoreCase = true) ||
                p.merchant.contains(line.rawDescription, ignoreCase = true)

        return candidates.firstOrNull { sameAmount(it) && nearDate(it) }
            ?: candidates.firstOrNull { sameAmount(it) }
            ?: candidates.firstOrNull { sameMerchant(it) && nearDate(it) }
    }

    /** Efecto de una línea sobre la deuda de la TC (positivo = menos deuda). */
    private fun balanceEffect(line: ParsedStatementLine): Long = when (line.type) {
        TransactionType.EXPENSE -> -line.amountMinor   // compra/cargo: más deuda
        TransactionType.INCOME -> line.amountMinor      // abono/devolución
        // TRANSFER: avance (TC origen) suma deuda; pago/abono la reduce.
        TransactionType.TRANSFER ->
            if (line.cardIsOrigin) -line.amountMinor else line.amountMinor
    }

    /** Huella de deduplicación del extracto: incluye el nº de línea. */
    private fun statementRef(accountId: Long, line: ParsedStatementLine, index: Int): String =
        sha256("$accountId|${line.dateMillis}|${line.amountMinor}|${line.rawDescription}|$index")

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
