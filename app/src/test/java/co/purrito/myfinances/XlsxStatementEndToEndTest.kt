package co.purrito.myfinances

import co.purrito.myfinances.service.BancolombiaStatementParser
import co.purrito.myfinances.service.XlsxReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* =====================================================================
 * End-to-end: lee el .xlsx REAL (fixture anonimizado en test/resources)
 * con XlsxReader, elige la hoja PESOS y la parsea. Verifica que el
 * lector + parser funcionan juntos sobre un archivo de banco de verdad.
 * ===================================================================== */

class XlsxStatementEndToEndTest {

    private fun openFixture() =
        javaClass.classLoader!!.getResourceAsStream("sample_statement.xlsx")!!

    @Test
    fun `lee y parsea el extracto real`() {
        val sheets = openFixture().use { XlsxReader.readSheets(it) }
        assertTrue("debe haber al menos una hoja", sheets.isNotEmpty())

        val pesos = BancolombiaStatementParser.pickStatementSheet(sheets)
        assertNotNull("debe identificar la hoja PESOS", pesos)

        val parsed = BancolombiaStatementParser.parse(pesos!!)

        // Deuda del periodo (Pago total 10.360.299,00 → negativa)
        assertEquals(-1_036_029_900L, parsed.statementBalanceMinor)
        assertEquals("1659", parsed.lastFourDigits)

        // Movimientos reales del extracto (durante + antes del periodo)
        assertTrue("debe extraer movimientos", parsed.lines.size >= 10)

        // Una compra de contado conocida
        assertTrue(parsed.lines.any { it.rawDescription == "MINISO" && it.amountMinor == 14_900_00L })

        // Un diferido conocido con su valor por cuota
        val hotel = parsed.lines.firstOrNull { it.rawDescription == "HOTEL ARENA" }
        assertNotNull(hotel)
        assertEquals(4, hotel!!.installmentTotal)
        assertEquals(1_362_650_50L, hotel.installmentAmountMinor)

        // El interés corriente queda marcado como cargo financiero
        assertTrue(parsed.lines.any { it.rawDescription.contains("INTERES") && it.isFinancialCharge })
    }
}
