package co.purrito.myfinances

import co.purrito.myfinances.data.model.DeferredPurchase
import co.purrito.myfinances.data.model.Transaction
import co.purrito.myfinances.data.model.TransactionSource
import co.purrito.myfinances.data.model.TransactionStatus
import co.purrito.myfinances.data.model.TransactionType
import co.purrito.myfinances.service.CardLedger
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

    /** Tarjeta sin movimientos en la app, salvo el saldo inicial. */
    private fun ledger(initial: Long, txs: List<Transaction> = emptyList(), cutoff: Long = 100 * day) =
        CardLedger(initialBalanceMinor = initial, transactions = txs, cutoffMillis = cutoff)

    private val bank = 1L

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
            ledger = null
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
            existing, emptyList(), financialCat, null, null
        )
        assertEquals(1, plan.duplicateCount)
    }

    @Test
    fun `dos dias de diferencia ya NO es duplicado`() {
        val existing = listOf(confirmedTx(10L, date = 5 * day, amount = 50_000_00))
        val plan = StatementReconciler.reconcile(
            tc, listOf(line(date = 7 * day, amount = 50_000_00, desc = "EXITO")),
            existing, emptyList(), financialCat, null, null
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
            existing, emptyList(), financialCat, null, null
        )
        assertEquals(1, plan.duplicateCount)
        assertEquals(1, plan.newTransactions.size) // la segunda es nueva
    }

    // --- Líneas nuevas ------------------------------------------------

    @Test
    fun `linea nueva nace PENDING source STATEMENT`() {
        val plan = StatementReconciler.reconcile(
            tc, listOf(line(date = 3 * day, amount = 12_000_00, desc = "PAYU*RAPPI BOG")),
            emptyList(), emptyList(), financialCat, null, null
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
            emptyList(), emptyList(), financialCat, null, null
        )
        assertEquals(financialCat, plan.newTransactions.single().categoryId)
    }

    @Test
    fun `mismo extracto produce el mismo externalRef por linea`() {
        fun ref() = StatementReconciler.reconcile(
            tc, listOf(line(date = 3 * day, amount = 12_000_00, desc = "RAPPI")),
            emptyList(), emptyList(), financialCat, null, null
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
            ledger = null
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
            emptyList(), listOf(purchase), financialCat, null, null
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
            emptyList(), listOf(viejo), financialCat, null, null
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
            emptyList(), listOf(a, b), financialCat, null, null
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
            emptyList(), listOf(falabella, alkosto), financialCat, null, null
        )
        assertEquals(8L, plan.installmentUpdates.single().deferredPurchaseId)
    }

    // --- Verificación de saldo ----------------------------------------

    @Test
    fun `sin saldo de extracto no hay ajuste`() {
        val plan = StatementReconciler.reconcile(
            tc, listOf(line(date = 3 * day, amount = 50_000_00, desc = "X")),
            emptyList(), emptyList(), financialCat, statementBalanceMinor = null,
            ledger = ledger(-100_000_00)
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
            ledger = ledger(-100_000_00)
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
            ledger = ledger(-100_000_00)
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
            ledger = ledger(-100_000_00)
        )
        val adj = plan.balanceAdjustment!!
        assertEquals(TransactionType.INCOME, adj.type)
        assertEquals(10_000_00, adj.amountMinor)
    }

    // --- Regresiones con datos reales (sintetizados) ------------------

    @Test
    fun `dedup tolera centavos perdidos al editar en pesos enteros`() {
        // Intereses del extracto 45.678,42; en la app quedaron 45.678,00
        // porque el inbox/formulario trabajan en pesos enteros.
        val existing = listOf(confirmedTx(10L, date = 15 * day, amount = 45_678_00))
        val plan = StatementReconciler.reconcile(
            tc, listOf(line(date = 15 * day, amount = 45_678_42, desc = "INTERESES CORRIENTES", charge = true)),
            existing, emptyList(), financialCat, null, null
        )
        assertEquals(1, plan.duplicateCount)
    }

    @Test
    fun `una compra no es duplicado de un abono del mismo valor`() {
        val abono = Transaction(
            id = 10L, accountId = bank, counterAccountId = tc, type = TransactionType.TRANSFER,
            amountMinor = 50_000_00, dateMillis = 5 * day,
            source = TransactionSource.SMS, status = TransactionStatus.CONFIRMED
        )
        val plan = StatementReconciler.reconcile(
            tc, listOf(line(date = 5 * day, amount = 50_000_00, desc = "EXITO")),
            listOf(abono), emptyList(), financialCat, null, null
        )
        assertEquals(0, plan.duplicateCount)
        assertEquals(1, plan.newTransactions.size)
    }

    @Test
    fun `linea ya importada se reconoce por su huella aunque se edite el monto`() {
        val lines = listOf(line(date = 5 * day, amount = 12_345_67, desc = "CARGO RARO"))
        val first = StatementReconciler.reconcile(tc, lines, emptyList(), emptyList(), financialCat, null, null)
        val edited = first.newTransactions.single().copy(id = 50L, amountMinor = 20_000_00)
        val again = StatementReconciler.reconcile(tc, lines, listOf(edited), emptyList(), financialCat, null, null)
        assertEquals(50L, (again.outcomes.single() as LineOutcome.Duplicate).matchedTransactionId)
    }

    @Test
    fun `reimportar con movimientos aun PENDING en el inbox no infla el ajuste`() {
        // Deuda previa conciliada -100k. El extracto trae una compra de 50k
        // que la importación anterior dejó PENDING → el saldo cuadra.
        val previa = confirmedTx(1L, date = 1 * day, amount = 100_000_00)
            .copy(reconciled = true, source = TransactionSource.STATEMENT)
        val pendiente = confirmedTx(2L, date = 10 * day, amount = 50_000_00)
            .copy(status = TransactionStatus.PENDING, source = TransactionSource.STATEMENT)
        val plan = StatementReconciler.reconcile(
            tc, listOf(line(date = 10 * day, amount = 50_000_00, desc = "MERCADO PAGO")),
            listOf(pendiente), emptyList(), financialCat,
            statementBalanceMinor = -150_000_00,
            ledger = ledger(0L, listOf(previa, pendiente))
        )
        assertNull(plan.balanceAdjustment)
    }

    @Test
    fun `movimientos que ningun extracto confirma no generan ajuste`() {
        // Pago a la parte en DÓLARES de la tarjeta (el "Pago total" en pesos
        // no lo incluye) y una compra del día de corte que el banco factura
        // el próximo mes: ninguno aparece en este extracto → fuera del ajuste.
        val previa = confirmedTx(1L, date = 1 * day, amount = 100_000_00)
            .copy(reconciled = true, source = TransactionSource.STATEMENT)
        val pagoUsd = Transaction(
            id = 2L, accountId = bank, counterAccountId = tc, type = TransactionType.TRANSFER,
            amountMinor = 50_000_00, dateMillis = 4 * day,
            source = TransactionSource.SMS, status = TransactionStatus.CONFIRMED
        )
        val delCorte = confirmedTx(3L, date = 14 * day, amount = 80_000_00)
        val plan = StatementReconciler.reconcile(
            tc, listOf(line(date = 2 * day, amount = 20_000_00, desc = "RESTAURANTE DEMO")),
            emptyList(), emptyList(), financialCat,
            statementBalanceMinor = -120_000_00,
            ledger = ledger(0L, listOf(previa, pagoUsd, delCorte))
        )
        assertNull(plan.balanceAdjustment)
        assertEquals(setOf(2L, 3L), plan.unreconciledTransactions.map { it.id }.toSet())
    }

    @Test
    fun `diferencia de redondeo menor a 100 pesos no genera ajuste`() {
        val plan = StatementReconciler.reconcile(
            tc, emptyList(), emptyList(), emptyList(), financialCat,
            statementBalanceMinor = -100_004_00, ledger = ledger(-100_000_00)
        )
        assertNull(plan.balanceAdjustment)
    }

    @Test
    fun `compra del corte anterior cuenta cuando el siguiente extracto la confirma`() {
        val previa = confirmedTx(1L, date = 1 * day, amount = 100_000_00)
            .copy(reconciled = true, source = TransactionSource.STATEMENT)
        val delCorte = confirmedTx(3L, date = 14 * day, amount = 80_000_00)
        val plan = StatementReconciler.reconcile(
            tc, listOf(line(date = 14 * day, amount = 80_000_00, desc = "TIENDA DEMO")),
            listOf(delCorte), emptyList(), financialCat,
            statementBalanceMinor = -180_000_00,
            ledger = ledger(0L, listOf(previa, delCorte), cutoff = 45 * day)
        )
        assertEquals(1, plan.duplicateCount)
        assertNull(plan.balanceAdjustment)
        assertTrue(plan.unreconciledTransactions.isEmpty())
    }

    @Test
    fun `movimiento ya conciliado sin linea en este extracto si cuenta`() {
        val previa = confirmedTx(1L, date = 1 * day, amount = 100_000_00)
            .copy(reconciled = true, source = TransactionSource.STATEMENT)
        val plan = StatementReconciler.reconcile(
            tc, emptyList(), emptyList(), emptyList(), financialCat,
            statementBalanceMinor = -90_000_00,
            ledger = ledger(0L, listOf(previa))
        )
        assertEquals(TransactionType.INCOME, plan.balanceAdjustment!!.type)
        assertEquals(10_000_00, plan.balanceAdjustment!!.amountMinor)
    }

    @Test
    fun `primera importacion da por facturado lo anterior a los movimientos nuevos`() {
        // Sin extractos previos: la compra vieja ya está en el saldo anterior
        val vieja = confirmedTx(1L, date = 1 * day, amount = 100_000_00)
        val plan = StatementReconciler.reconcile(
            tc, listOf(line(date = 20 * day, amount = 50_000_00, desc = "X")),
            emptyList(), emptyList(), financialCat,
            statementBalanceMinor = -150_000_00,
            ledger = ledger(0L, listOf(vieja))
        )
        assertNull(plan.balanceAdjustment)
    }

    @Test
    fun `el ajuste tiene huella estable por extracto`() {
        fun adj() = StatementReconciler.reconcile(
            tc, emptyList(), emptyList(), emptyList(), financialCat,
            statementBalanceMinor = -90_000_00, ledger = ledger(-100_000_00)
        ).balanceAdjustment!!.externalRef
        assertEquals(adj(), adj())
        assertEquals(StatementReconciler.adjustmentRef(tc, 100 * day), adj())
    }

    @Test
    fun `cuota de agregador no se factura en el plan de otra compra`() {
        // Dos compras MERCADO PAGO a 3 cuotas. El plan de 300.000 está
        // cerrado (dañado); la línea 2/3 de 300.000 NO debe facturarse en
        // el plan de 90.000 solo por coincidir comercio y nº de cuotas.
        val otro = DeferredPurchase(25L, tc, "MERCADO PAGO", 60 * day, 90_000_00, 3, 1)
        val plan = StatementReconciler.reconcile(
            tc,
            listOf(line(date = 16 * day, amount = 300_000_00, desc = "MERCADO PAGO",
                instCurrent = 2, instTotal = 3)),
            emptyList(), listOf(otro), financialCat, null, null
        )
        assertEquals(0, plan.updatedCount)
    }

    @Test
    fun `cuota se empareja por monto total y fecha de compra`() {
        val a = DeferredPurchase(7L, tc, "Regalo", 16 * day, 300_000_00, 3, 1)
        val b = DeferredPurchase(8L, tc, "Otro regalo", 60 * day, 300_000_00, 3, 1)
        val plan = StatementReconciler.reconcile(
            tc,
            listOf(line(date = 60 * day, amount = 300_000_00, desc = "MERCADO PAGO",
                instCurrent = 2, instTotal = 3)),
            emptyList(), listOf(a, b), financialCat, null, null
        )
        assertEquals(8L, plan.installmentUpdates.single().deferredPurchaseId)
    }
}
