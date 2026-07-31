package co.purrito.myfinances

import co.purrito.myfinances.data.model.TransactionType
import co.purrito.myfinances.service.BancolombiaPdfStatementParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* =====================================================================
 * Tests del parser de Bancolombia PDF con un fixture SINTÉTICO que
 * replica el texto extraído del PDF (desc+autorización pegadas, cabeceras
 * triplicadas, "Pago Total" en dólares y pesos). Sin datos reales.
 * ===================================================================== */

class BancolombiaPdfStatementParserTest {

    private val parsed by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("sample_bancolombia_pdf.txt")!!
            .use { it.readBytes() }.toString(Charsets.UTF_8)
        BancolombiaPdfStatementParser.parse(text)
    }

    @Test
    fun `ultimos cuatro de la tarjeta`() {
        assertEquals("4321", parsed.lastFourDigits)
    }

    @Test
    fun `toma el pago total de mayor magnitud (pesos, no dolares)`() {
        assertEquals(-1_200_000_00L, parsed.statementBalanceMinor)
    }

    @Test
    fun `interes corriente es cargo financiero sin autorizacion`() {
        val interes = parsed.lines.first { it.rawDescription == "INTERESES CORRIENTES" }
        assertTrue(interes.isFinancialCharge)
        assertEquals(5_000_00L, interes.amountMinor)
    }

    @Test
    fun `compra de contado separa descripcion de autorizacion`() {
        val tienda = parsed.lines.first { it.rawDescription == "TIENDA DEMO" }
        assertEquals(TransactionType.EXPENSE, tienda.type)
        assertEquals(100_000_00L, tienda.amountMinor)
        assertNull(tienda.installmentTotal)   // 1/1 no es diferido
    }

    @Test
    fun `abono negativo es transferencia con magnitud positiva`() {
        val abono = parsed.lines.first { it.rawDescription == "ABONO DEMO" }
        assertEquals(TransactionType.TRANSFER, abono.type)
        assertEquals(300_000_00L, abono.amountMinor)
    }

    @Test
    fun `compra diferida trae cuota y valor por cuota`() {
        val viaje = parsed.lines.first { it.rawDescription == "VIAJE DEMO" }
        assertEquals(1, viaje.installmentCurrent)
        assertEquals(4, viaje.installmentTotal)
        assertEquals(800_000_00L, viaje.amountMinor)
        assertEquals(200_000_00L, viaje.installmentAmountMinor)
    }

    @Test
    fun `cuota de periodo anterior tambien se captura`() {
        val vieja = parsed.lines.first { it.rawDescription == "COMPRA VIEJA DEMO" }
        assertEquals(3, vieja.installmentCurrent)
        assertEquals(6, vieja.installmentTotal)
    }

    @Test
    fun `los movimientos de la seccion en dolares se omiten`() {
        // La app es COP-only: un movimiento en USD ("5,94") no debe entrar
        // como si fueran 594 centavos de peso.
        assertTrue(parsed.lines.none { it.rawDescription.contains("USA") })
        assertTrue(parsed.lines.none { it.rawDescription == "STREAMING DEMO" })
        assertNull(parsed.lines.firstOrNull { it.amountMinor == 5_94L })
    }

    @Test
    fun `solo se parsean los cinco movimientos en pesos`() {
        // 5 en pesos (interés, tienda, abono, viaje, compra vieja); los 2 en
        // dólares quedan fuera.
        assertEquals(5, parsed.lines.size)
    }
}
