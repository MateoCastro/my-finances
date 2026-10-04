package co.purrito.myfinances

import co.purrito.myfinances.data.model.Transaction
import co.purrito.myfinances.data.model.TransactionSource
import co.purrito.myfinances.data.model.TransactionStatus
import co.purrito.myfinances.data.model.TransactionType
import co.purrito.myfinances.service.SmsParseResult
import co.purrito.myfinances.service.SmsRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/* =====================================================================
 * Recuperación de SMS perdidos: un SMS no se ofrece si el movimiento ya
 * entró por otro camino (a mano o desde un extracto). Datos sintéticos.
 * ===================================================================== */

class SmsRecoveryTest {

    private val tc = 4L
    private val bank = 2L
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val t0 = 100 * day // medianoche de un día cualquiera

    private fun sms(amount: Long, type: TransactionType = TransactionType.EXPENSE) = SmsParseResult(
        templateId = 1L, accountId = tc, counterAccountId = null, amountMinor = amount,
        type = type, merchantRaw = "UBER RIDES", externalRef = "ref"
    )

    private fun tx(
        id: Long, amount: Long, date: Long,
        type: TransactionType = TransactionType.EXPENSE,
        account: Long = tc, counter: Long? = null,
        source: TransactionSource = TransactionSource.MANUAL,
        status: TransactionStatus = TransactionStatus.CONFIRMED
    ) = Transaction(
        id = id, accountId = account, counterAccountId = counter, type = type,
        amountMinor = amount, dateMillis = date, source = source, status = status
    )

    @Test
    fun `registrado a mano a otra hora del mismo dia ya esta en la app`() {
        val manual = tx(1L, 13_850_00, t0 + 20 * hour)
        assertEquals(manual, SmsRecovery.findRecorded(sms(13_850_00), t0 + 19 * hour, listOf(manual)))
    }

    @Test
    fun `linea de extracto aun PENDING en el inbox ya esta en la app`() {
        // Las líneas del extracto llegan a medianoche; el SMS, de madrugada
        val linea = tx(
            2L, 22_958_00, t0, source = TransactionSource.STATEMENT,
            status = TransactionStatus.PENDING
        )
        assertEquals(linea, SmsRecovery.findRecorded(sms(22_958_00), t0 + 5 * hour, listOf(linea)))
    }

    @Test
    fun `un monto distinto no es el mismo movimiento`() {
        // Cobro preliminar del SMS (8.949) vs cobro definitivo (9.708)
        val definitivo = tx(3L, 9_708_00, t0)
        assertNull(SmsRecovery.findRecorded(sms(8_949_00), t0 + 2 * hour, listOf(definitivo)))
    }

    @Test
    fun `tolera los centavos perdidos al registrar en pesos enteros`() {
        val manual = tx(1L, 13_850_00, t0)
        assertEquals(manual, SmsRecovery.findRecorded(sms(13_850_40), t0 + hour, listOf(manual)))
    }

    @Test
    fun `dos dias de diferencia ya no es el mismo movimiento`() {
        val viejo = tx(1L, 13_850_00, t0)
        assertNull(SmsRecovery.findRecorded(sms(13_850_00), t0 + 2 * day + hour, listOf(viejo)))
    }

    @Test
    fun `un avance coincide con el avance y no con un pago del mismo valor`() {
        val avance = sms(500_000_00, TransactionType.TRANSFER)
        val pagoALaTarjeta = tx(5L, 500_000_00, t0, TransactionType.TRANSFER, account = bank, counter = tc)
        val avanceDelExtracto = tx(6L, 500_000_00, t0, TransactionType.TRANSFER, account = tc, counter = bank)
        assertNull(SmsRecovery.findRecorded(avance, t0 + hour, listOf(pagoALaTarjeta)))
        assertEquals(
            avanceDelExtracto,
            SmsRecovery.findRecorded(avance, t0 + hour, listOf(pagoALaTarjeta, avanceDelExtracto))
        )
    }

    @Test
    fun `un movimiento ya usado no cubre un segundo SMS igual`() {
        val manual = tx(1L, 7_000_00, t0)
        assertNull(SmsRecovery.findRecorded(sms(7_000_00), t0 + hour, listOf(manual), consumed = setOf(1L)))
    }
}
