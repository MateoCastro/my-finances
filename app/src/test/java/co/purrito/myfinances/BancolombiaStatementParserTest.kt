package co.purrito.myfinances

import co.purrito.myfinances.data.model.TransactionType
import co.purrito.myfinances.service.BancolombiaStatementParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/* =====================================================================
 * Tests del parser de extracto Bancolombia con las filas REALES del
 * archivo del usuario (hoja PESOS, may 2026, anonimizado en origen).
 * ===================================================================== */

class BancolombiaStatementParserTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private fun date(d: Int, m: Int, y: Int) =
        LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    // Filas relevantes tal cual salen del .xlsx (cabecera + movimientos).
    private val rows: List<List<String>> = listOf(
        listOf("Pago total", "10.360.299,00"),
        listOf("Información de la Tarjeta", "************1659", ""),
        // header sección "durante el periodo"
        listOf("Número de autorización", "Fecha", "Movimientos", "Valor Movimiento",
            "Número de cuotas", "Valor cuota/abono", "Interés mensual (%)", "Interés anual (%)", "Saldo pendiente"),
        listOf("", "18/05/2026", "INTERESES CORRIENTES", "38.136,38", "", "38.136,38", "", "", "0,00"),
        listOf("R01659", "14/05/2026", "BOLD*Taller Vehicular", "2.957.198,00", "1/1", "2.957.198,00", "0,0000", "00,0000", "0,00"),
        listOf("F07071", "10/05/2026", "RESTAURANTE EL GORDO", "110.440,00", "1/1", "110.440,00", "0,0000", "00,0000", "0,00"),
        listOf("C01683", "02/05/2026", "ABONO SUCURSAL VIRTUAL", "-1.542.287,00", "", "-1.542.287,00", "", "", "0,00"),
        listOf("H05865", "01/05/2026", "HOTEL ARENA", "5.450.602,00", "1/4", "1.362.650,50", "2,0849", "28,0967", "4.087.951,50"),
        listOf("R00296", "30/04/2026", "MINISO", "14.900,00", "1/1", "14.900,00", "0,0000", "00,0000", "0,00"),
        listOf("R02433", "30/04/2026", "DIAN - PSE", "1.600.000,00", "1/8", "200.000,00", "1,9915", "26,6974", "1.400.000,00"),
        listOf("R08929", "24/04/2026", "TIENDA D1", "129.750,00", "1/1", "129.750,00", "0,0000", "00,0000", "0,00"),
        listOf(""),
        listOf("Movimientos antes del periodo"),
        listOf("Número de autorización", "Fecha", "Movimientos", "Valor Movimiento",
            "Número de cuotas", "Valor cuota/abono", "Interés mensual (%)", "Interés anual (%)", "Saldo pendiente"),
        listOf("T01191", "25/11/2025", "MERCADO PAGO", "214.544,00", "6/6", "35.757,35", "0,0000", "00,0000", "0,00"),
        listOf("T07470", "12/11/2025", "MERCADO PAGO", "2.534.800,00", "7/12", "23.514,91", "0,0000", "00,0000", "0,00")
    )

    private val parsed by lazy { BancolombiaStatementParser.parse(rows) }

    @Test
    fun `extrae todas las lineas de movimiento ignorando cabeceras`() {
        // 8 "durante" + 2 "antes" = 10 (headers/blank/titulo NO cuentan)
        assertEquals(10, parsed.lines.size)
    }

    @Test
    fun `saldo del extracto es la deuda negativa`() {
        assertEquals(-1_036_029_900L, parsed.statementBalanceMinor)
    }

    @Test
    fun `ultimos cuatro digitos de la tarjeta`() {
        assertEquals("1659", parsed.lastFourDigits)
    }

    @Test
    fun `compra de contado 1-1 no se marca como diferido`() {
        val miniso = parsed.lines.first { it.rawDescription == "MINISO" }
        assertEquals(TransactionType.EXPENSE, miniso.type)
        assertEquals(14_900_00, miniso.amountMinor)
        assertNull(miniso.installmentTotal)
    }

    @Test
    fun `interes corriente es cargo financiero`() {
        val interes = parsed.lines.first { it.rawDescription.contains("INTERESES") }
        assertTrue(interes.isFinancialCharge)
        assertEquals(38_136_38, interes.amountMinor)
        assertEquals(TransactionType.EXPENSE, interes.type)
    }

    @Test
    fun `abono es transferencia con monto positivo`() {
        val abono = parsed.lines.first { it.rawDescription.contains("ABONO") }
        assertEquals(TransactionType.TRANSFER, abono.type)
        assertEquals(1_542_287_00, abono.amountMinor) // magnitud, sin signo
    }

    @Test
    fun `compra diferida trae cuota actual total y valor por cuota`() {
        val hotel = parsed.lines.first { it.rawDescription == "HOTEL ARENA" }
        assertEquals(1, hotel.installmentCurrent)
        assertEquals(4, hotel.installmentTotal)
        assertEquals(5_450_602_00, hotel.amountMinor)             // total de la compra
        assertEquals(1_362_650_50L, hotel.installmentAmountMinor) // valor de la cuota
    }

    @Test
    fun `cuota de periodo anterior conserva su progreso`() {
        val mp = parsed.lines.first { it.installmentTotal == 12 }
        assertEquals(7, mp.installmentCurrent)
        assertEquals(23_514_91L, mp.installmentAmountMinor)
    }

    @Test
    fun `rango de fechas cubre del mas antiguo al mas reciente`() {
        assertEquals(date(12, 11, 2025), parsed.periodFromMillis)
        assertEquals(date(18, 5, 2026), parsed.periodToMillis)
    }
}
