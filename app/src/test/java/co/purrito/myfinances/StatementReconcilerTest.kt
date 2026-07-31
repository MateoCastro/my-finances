package co.purrito.myfinances

import co.purrito.myfinances.data.model.DeferredPurchase
import co.purrito.myfinances.data.model.Transaction
import co.purrito.myfinances.data.model.TransactionSource
import co.purrito.myfinances.data.model.TransactionStatus
import co.purrito.myfinances.data.model.TransactionType
import co.purrito.myfinances.service.LineOutcome
import co.purrito.myfinances.service.ParsedStatementLine
import co.purrito.myfinances.service.StatementReconciler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* =====================================================================
 * Tests del motor de reconciliación de extractos (Hito 4). Datos
 * sintéticos: pinean el comportamiento esperado para que, cuando llegue
 * un extracto real y se ajusten heurísticas, las regresiones salten.
 * ===================================================================== */

class StatementReconcilerTest {

    private val tc = 2L
    private val day = 24L * 60 * 60 * 1000
    private val financialCat = 99L

    private fun line(
        date: Long,
        amount: Long,
        desc: String,
        type: TransactionType = TransactionType.EXPENSE,
        instCurrent: Int? = null,
        instTotal: Int? = null,
        charge: Boolean = false
    ) = ParsedStatementLine(date, amount, type, desc, instCurrent, instTotal, charge)

    private fun confirmedTx(id: Long, date: Long, amount: Long) = Transaction(
        id = id, accountId = tc, type = TransactionType.EXPENSE, amountMinor = amount,
        dateMillis = date, source = TransactionSource.MANUAL, status = TransactionStatus.CONFIRMED
    )

    // --- Deduplicación ------------------------------------------------

    @Test
    fun `linea que ya existe se marca como duplicada`() {
        val existing = listOf(confirmedTx(10L, date = 5 * day, amount = 50_000_00))
        val plan = StatementReconciler.reconcile(
            accountId = tc,
            lines = listOf(line(date = 5 * day, amount = 50_000_00, desc = "EXITO")),
            existing = existing,
            openDeferred = emptyList(),
            financialCategoryId = financialCat,
            statementBalanceMinor = null,
            computedBalanceMinor = 0L
        )
        assertEquals(1, plan.duplicateCount)
        assertEquals(0, plan.newTransactions.size)
        assertEquals(10L, (plan.outcomes.first() as LineOutcome.Duplicate).matchedTransactionId)
    }

    @Test
    fun `dedup tolera diferencia de un dia`() {
        val existing = listOf(confirmedTx(10L, date = 5 * day, amount = 50_000_00))
        val plan = StatementReconciler.reconcile(
            tc, listOf(line(date = 6 * day, amount = 50_000_00, desc = "EXITO")),
            existing, emptyList(), financialCat, null, 0L
        )
        assertEquals(1, plan.duplicateCount)
    }

    @Test
    fun `dos dias de diferencia ya NO es duplicado`() {
        val existing = listOf(confirmedTx(10L, date = 5 * day, amount = 50_000_00))
        val plan = StatementReconciler.reconcile(
            tc, listOf(line(date = 7 * day, amount = 50_000_00, desc = "EXITO")),
            existing, emptyList(), financialCat, null, 0L
        )
        assertEquals(0, plan.duplicateCount)
        assertEquals(1, plan.newTransactions.size)
    }

    @Test
    fun `una transaccion existente no matchea dos lineas`() {
        val existing = listOf(confirmedTx(10L, date = 5 * day, amount = 50_000_00))
        val plan = StatementReconciler.reconcile(
            tc,
            listOf(
                line(date = 5 * day, amount = 50_000_00, desc = "EXITO"),
                line(date = 5 * day, amount = 50_000_00, desc = "EXITO")
            ),
            existing, emptyList(), financialCat, null, 0L
        )
        assertEquals(1, plan.duplicateCount)
        assertEquals(1, plan.newTransactions.size) // la segunda es nueva
    }

    // --- Líneas nuevas ------------------------------------------------

    @Test
    fun `linea nueva nace PENDING source STATEMENT`() {
        val plan = StatementReconciler.reconcile(
            tc, listOf(line(date = 3 * day, amount = 12_000_00, desc = "PAYU*RAPPI BOG")),
            emptyList(), emptyList(), financialCat, null, 0L
        )
        val tx = plan.newTransactions.single()
        assertEquals(TransactionStatus.PENDING, tx.status)
        assertEquals(TransactionSource.STATEMENT, tx.source)
        assertEquals("PAYU*RAPPI BOG", tx.merchantRaw)
        assertEquals(12_000_00, tx.amountMinor)
        assertTrue(tx.externalRef != null)
    }

    @Test
    fun `cargo financiero se preclasifica en costos financieros`() {
        val plan = StatementReconciler.reconcile(
            tc,
            listOf(line(date = 3 * day, amount = 8_900_00, desc = "INTERES CORRIENTE", charge = true)),
            emptyList(), emptyList(), financialCat, null, 0L
        )
        assertEquals(financialCat, plan.newTransactions.single().categoryId)
    }

    @Test
    fun `mismo extracto produce el mismo externalRef por linea`() {
        fun ref() = StatementReconciler.reconcile(
            tc, listOf(line(date = 3 * day, amount = 12_000_00, desc = "RAPPI")),
            emptyList(), emptyList(), financialCat, null, 0L
        ).newTransactions.single().externalRef
        assertEquals(ref(), ref())
    }

    // --- Cuotas de diferidos ------------------------------------------

    @Test
    fun `cuota matchea diferido por total de cuotas y NO crea transaccion`() {
        val purchase = DeferredPurchase(
            id = 7L, accountId = tc, merchant = "FALABELLA",
            purchaseDateMillis = 0L, totalAmountMinor = 1_200_000_00,
            totalInstallments = 12, billedInstallments = 2
        )
        val plan = StatementReconciler.reconcile(
            tc,
            listOf(line(date = 3 * day, amount = 110_000_00, desc = "FALABELLA CUOTA 3/12",
                instCurrent = 3, instTotal = 12)),
            existing = emptyList(),
            openDeferred = listOf(purchase),
            financialCategoryId = financialCat,
            statementBalanceMinor = null,
            computedBalanceMinor = 0L
        )
        assertEquals(0, plan.newTransactions.size)
        assertEquals(1, plan.updatedCount)
        val upd = plan.installmentUpdates.single()
        assertEquals(7L, upd.deferredPurchaseId)
        assertEquals(3, upd.newBilledInstallments)
        assertEquals(110_000_00, upd.installmentAmountMinor) // valor real (con interés)
        assertTrue(!upd.closed)
    }

    @Test
    fun `ultima cuota cierra el plan`() {
        val purchase = DeferredPurchase(
            id = 7L, accountId = tc, merchant = "FALABELLA",
            purchaseDateMillis = 0L, totalAmountMinor = 1_200_000_00,
            totalInstallments = 12, billedInstallments = 11
        )
        val plan = StatementReconciler.reconcile(
            tc,
            listOf(line(date = 3 * day, amount = 110_000_00, desc = "FALABELLA",
                instCurrent = 12, instTotal = 12)),
            emptyList(), listOf(purchase), financialCat, null, 0L
        )
        assertTrue(plan.installmentUpdates.single().closed)
    }

    @Test
    fun `compra nueva 1 de N no la absorbe un plan viejo con igual total`() {
        // Plan viejo "NETFLIX" de 6 cuotas que ya facturó 3. Llega una compra
        // NUEVA "MERCADO PAGO 1/6" (otro comercio, otro monto). No debe
        // tragarse como cuota del plan viejo: es una compra nueva al inbox.
        val viejo = DeferredPurchase(
            id = 7L, accountId = tc, merchant = "NETFLIX",
            purchaseDateMillis = 0L, totalAmountMinor = 60_000_00,
            totalInstallments = 6, billedInstallments = 3
        )
        val plan = StatementReconciler.reconcile(
            tc,
            listOf(line(date = 3 * day, amount = 999_000_00, desc = "MERCADO PAGO",
                instCurrent = 1, instTotal = 6)),
            emptyList(), listOf(viejo), financialCat, null, 0L
        )
        assertEquals(0, plan.updatedCount)
        assertEquals(1, plan.newTransactions.size)
        assertEquals(999_000_00, plan.newTransactions.single().amountMinor)
    }

    @Test
    fun `plan correcto se elige por monto total cuando el comercio se repite`() {
        // Dos planes MERCADO PAGO de 6 cuotas; la línea coincide en capital
        // total con uno → se factura ESE, no el otro.
        val a = DeferredPurchase(7L, tc, "MERCADO PAGO", 0L, 999_000_00, 6, 1)
        val b = DeferredPurchase(8L, tc, "MERCADO PAGO", 0L, 210_798_00, 6, 1)
        val plan = StatementReconciler.reconcile(
            tc,
            listOf(line(date = 3 * day, amount = 210_798_00, desc = "MERCADO PAGO",
                instCurrent = 2, instTotal = 6)),
            emptyList(), listOf(a, b), financialCat, null, 0L
        )
        assertEquals(8L, plan.installmentUpdates.single().deferredPurchaseId)
    }

    @Test
    fun `ambiguedad de cuotas se resuelve por comercio`() {
        val falabella = DeferredPurchase(7L, tc, "FALABELLA", 0L, 1_200_000_00, 12, 2)
        val alkosto = DeferredPurchase(8L, tc, "ALKOSTO", 0L, 900_000_00, 12, 1)
        val plan = StatementReconciler.reconcile(
            tc,
            listOf(line(date = 3 * day, amount = 80_000_00, desc = "ALKOSTO BOG CUOTA 2/12",
                instCurrent = 2, instTotal = 12)),
            emptyList(), listOf(falabella, alkosto), financialCat, null, 0L
        )
        assertEquals(8L, plan.installmentUpdates.single().deferredPurchaseId)
    }

    // --- Verificación de saldo ----------------------------------------

    @Test
    fun `sin saldo de extracto no hay ajuste`() {
        val plan = StatementReconciler.reconcile(
            tc, listOf(line(date = 3 * day, amount = 50_000_00, desc = "X")),
            emptyList(), emptyList(), financialCat, statementBalanceMinor = null,
            computedBalanceMinor = -100_000_00
        )
        assertNull(plan.balanceAdjustment)
    }

    @Test
    fun `saldo cuadra tras proyectar las nuevas no genera ajuste`() {
        // deuda actual -100k; una compra nueva de 50k → proyectado -150k.
        // el extracto reporta -150k exacto → sin ajuste.
        val plan = StatementReconciler.reconcile(
            tc, listOf(line(date = 3 * day, amount = 50_000_00, desc = "X")),
            emptyList(), emptyList(), financialCat,
            statementBalanceMinor = -150_000_00,
            computedBalanceMinor = -100_000_00
        )
        assertNull(plan.balanceAdjustment)
    }

    @Test
    fun `diferencia de saldo genera ajuste EXPENSE por mas deuda`() {
        // proyectado -150k, extracto -158.9k → faltan 8.9k de deuda (interés)
        val plan = StatementReconciler.reconcile(
            tc, listOf(line(date = 3 * day, amount = 50_000_00, desc = "X")),
            emptyList(), emptyList(), financialCat,
            statementBalanceMinor = -158_900_00,
            computedBalanceMinor = -100_000_00
        )
        val adj = plan.balanceAdjustment!!
        assertEquals(TransactionType.EXPENSE, adj.type)
        assertEquals(8_900_00, adj.amountMinor)
        assertEquals(financialCat, adj.categoryId)
        assertEquals(TransactionStatus.PENDING, adj.status)
        assertEquals(TransactionSource.STATEMENT, adj.source)
    }

    @Test
    fun `diferencia inversa genera ajuste INCOME`() {
        val plan = StatementReconciler.reconcile(
            tc, emptyList(), emptyList(), emptyList(), financialCat,
            statementBalanceMinor = -90_000_00,
            computedBalanceMinor = -100_000_00
        )
        val adj = plan.balanceAdjustment!!
        assertEquals(TransactionType.INCOME, adj.type)
        assertEquals(10_000_00, adj.amountMinor)
    }
}
