package co.purrito.myfinances

import co.purrito.myfinances.data.model.TransactionType
import co.purrito.myfinances.service.DaviviendaStatementParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* =====================================================================
 * Tests del parser Davivienda con un fixture SINTÉTICO (datos falsos)
 * que replica el layout real del extracto (PDF→texto estilo PdfBox, sin
 * -layout). No se versiona ningún dato real del usuario.
 * ===================================================================== */

class DaviviendaStatementParserTest {

    private val parsed by lazy {
        val bytes = javaClass.classLoader!!.getResourceAsStream("sample_davivienda.txt")!!
            .use { it.readBytes() }
        DaviviendaStatementParser.parse(String(bytes, Charsets.ISO_8859_1))
    }

    @Test
    fun `ultimos cuatro digitos de la tarjeta`() {
        assertEquals("9999", parsed.lastFourDigits)
    }

    @Test
    fun `deuda del periodo es el pago total negativo`() {
        assertEquals(-500_000_00L, parsed.statementBalanceMinor)
    }

    @Test
    fun `compra de contado no es diferida`() {
        val tienda = parsed.lines.first {
            it.rawDescription == "TIENDA EJEMPLO" && it.amountMinor == 50_000_00L
        }
        assertEquals(TransactionType.EXPENSE, tienda.type)
        assertNull(tienda.installmentTotal)
    }

    @Test
    fun `cuota de manejo e interes son cargos financieros`() {
        assertTrue(parsed.lines.any { it.rawDescription.contains("MANEJO") && it.isFinancialCharge })
        assertTrue(parsed.lines.any { it.rawDescription.contains("INTERES") && it.isFinancialCharge })
    }

    @Test
    fun `compra diferida trae cuota actual total y valor por cuota`() {
        val dif = parsed.lines.first { it.installmentTotal == 5 }
        assertEquals(2, dif.installmentCurrent)
        assertEquals(300_000_00L, dif.amountMinor)          // total de la compra
        assertEquals(66_000_00L, dif.installmentAmountMinor) // valor de la cuota
    }

    @Test
    fun `un avance es transferencia con la TC como origen`() {
        val avance = parsed.lines.first { it.rawDescription.contains("AVANCE") }
        assertEquals(TransactionType.TRANSFER, avance.type)
        assertTrue("la TC debe ser el origen del avance", avance.cardIsOrigin)
        assertEquals(400_000_00L, avance.amountMinor)
    }

    @Test
    fun `el pago se captura como transferencia (extraccion sin layout)`() {
        // abono = TRANSFER con la TC como DESTINO (no origen)
        val pago = parsed.lines.first { it.type == TransactionType.TRANSFER && !it.cardIsOrigin }
        assertEquals(200_000_00L, pago.amountMinor)
    }

    @Test
    fun `cuota de seguro de otros cargos es cargo financiero`() {
        val seguro = parsed.lines.firstOrNull { it.rawDescription.contains("SEGURO") }
        assertNotNull(seguro)
        assertTrue(seguro!!.isFinancialCharge)
        assertEquals(2_000_00L, seguro.amountMinor)
    }
}
