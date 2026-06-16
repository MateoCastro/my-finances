package co.purrito.myfinances

import co.purrito.myfinances.data.model.TransactionType
import co.purrito.myfinances.service.DefaultSmsTemplates
import co.purrito.myfinances.service.SmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/* =====================================================================
 * Tests del parser con los SMS REALES del usuario (anonimizados).
 * Si un banco cambia el formato, el test que falle señala exactamente
 * qué plantilla hay que ajustar.
 * ===================================================================== */

class SmsParserTest {

    private val cuenta = 1L
    private val tcBancolombia = 2L
    private val efectivo = 3L
    private val tcDavivienda = 4L

    private val templates =
        DefaultSmsTemplates.bancolombia(cuenta, tcBancolombia, efectivo) +
        DefaultSmsTemplates.davivienda(tcDavivienda)

    private fun parse(sender: String, body: String) =
        SmsParser.parse(sender, body, messageTimestampMillis = 0L, templates = templates)

    // --- Normalización de montos ------------------------------------

    @Test
    fun `monto colombiano con decimales`() {
        assertEquals(4_444_444L, SmsParser.parseAmountToMinor("44.444,44"))
    }

    @Test
    fun `monto colombiano con decimales en cero`() {
        assertEquals(3_333_500L, SmsParser.parseAmountToMinor("33.335,00"))
    }

    @Test
    fun `monto americano con decimales`() {
        assertEquals(1_000_000L, SmsParser.parseAmountToMinor("10,000.00"))
    }

    @Test
    fun `monto sin decimales con coma de miles`() {
        assertEquals(10_000_000L, SmsParser.parseAmountToMinor("100,000"))
    }

    @Test
    fun `monto pequeno sin decimales`() {
        assertEquals(888_800L, SmsParser.parseAmountToMinor("8,888"))
    }

    // --- Bancolombia: compras ----------------------------------------

    @Test
    fun `compra TC variante asociada`() {
        val r = parse(
            "85540",
            "Bancolombia: Compraste COP44.444,44 en MERCADO PAGO*ZXVENTU, el 01/01/2001 a las 18:14. " +
                "Esta compra esta asociada a T.Cred *1111. Si tienes dudas, encuentranos aqui: 01800000000. Siempre contigo."
        )
        assertNotNull(r)
        assertEquals(tcBancolombia, r!!.accountId)
        assertEquals(TransactionType.EXPENSE, r.type)
        assertEquals(4_444_444L, r.amountMinor)
        assertEquals("MERCADO PAGO*ZXVENTU", r.merchantRaw)
    }

    @Test
    fun `compra TC variante con tu TCred`() {
        val r = parse(
            "85784",
            "Bancolombia: Compraste COP44.444,00 en CORRAL VIVA LAURELES con tu T.Cred *1111, el 01/01/2021 a las 16:33. " +
                "Si tienes dudas, encuentranos aqui: 6045444444 o 018000000000. Estamos cerca."
        )
        assertNotNull(r)
        assertEquals(tcBancolombia, r!!.accountId)
        assertEquals(TransactionType.EXPENSE, r.type)
        assertEquals(4_444_400L, r.amountMinor)
        assertEquals("CORRAL VIVA LAURELES", r.merchantRaw)
    }

    @Test
    fun `compra tarjeta debito`() {
        val r = parse(
            "85784",
            "Bancolombia: Compraste \$33.335,00 en FARMATODO ALTO DE PA con tu T.Deb *1111, el 05/05/2025 a las 22:22. " +
                "Si tienes dudas, encuentranos aqui: 6045444444 o 018000000000. Estamos cerca."
        )
        assertNotNull(r)
        assertEquals(cuenta, r!!.accountId)
        assertEquals(TransactionType.EXPENSE, r.type)
        assertEquals(3_333_500L, r.amountMinor)
        assertEquals("FARMATODO ALTO DE PA", r.merchantRaw)
    }

    // --- Bancolombia: avances, pagos de TC y retiros -------------------

    @Test
    fun `avance TC es transferencia a la cuenta`() {
        val r = parse(
            "85540",
            "Bancolombia: Hiciste un avance de \$100,000 en tu SUC VIRTUAL el 19:19 01/01/2021 desde tu " +
                "T.Credito *1111 a la cuenta *7777. ¿Dudas? Llamanos al 6045444444."
        )
        assertNotNull(r)
        assertEquals(tcBancolombia, r!!.accountId)
        assertEquals(TransactionType.TRANSFER, r.type)
        assertEquals(cuenta, r.counterAccountId)
        assertEquals(10_000_000L, r.amountMinor)
    }

    @Test
    fun `pago de TC es transferencia cuenta a tarjeta`() {
        val r = parse(
            "85784",
            "Bancolombia: Pagaste \$1,111,111 en la tarjeta de credito *1111 desde la cuenta *1111, " +
                "el 11/11/2011 11:11. ¿Dudas? Llamanos al 018000000000. Estamos cerca."
        )
        assertNotNull(r)
        assertEquals(cuenta, r!!.accountId)
        assertEquals(TransactionType.TRANSFER, r.type)
        assertEquals(tcBancolombia, r.counterAccountId)
        assertEquals(111_111_100L, r.amountMinor)
    }

    @Test
    fun `retiro de cajero es transferencia a efectivo`() {
        val r = parse(
            "85540",
            "Bancolombia: Retiraste \$111.111,00 en OFIX33_2 de tu T.Deb **1111 el 01/01/2001 a las 18:51. " +
                "Si tienes dudas, llamanos al 6045111111. Estamos cerca."
        )
        assertNotNull(r)
        assertEquals(cuenta, r!!.accountId)
        assertEquals(TransactionType.TRANSFER, r.type)
        assertEquals(efectivo, r.counterAccountId)
        assertEquals(11_111_100L, r.amountMinor)
        assertEquals("OFIX33_2", r.merchantRaw)
    }

    // --- Bancolombia: pagos y transferencias salientes ----------------

    @Test
    fun `pago por boton bancolombia`() {
        val r = parse(
            "85784",
            "Bancolombia: Transferiste \$10,000.00 por Boton Bancolombia a Wompi SAS desde producto *2222. " +
                "03/03/2023 09:09:09 ¿Dudas? 018000000000."
        )
        assertNotNull(r)
        assertEquals(cuenta, r!!.accountId)
        assertEquals(TransactionType.EXPENSE, r.type)
        assertEquals(1_000_000L, r.amountMinor)
        assertEquals("Wompi SAS", r.merchantRaw)
    }

    @Test
    fun `pago por PSE`() {
        val r = parse(
            "85540",
            "Bancolombia: Pagaste \$222,222.00 a Empresa Recaudo desde tu producto 1111 el " +
                "10/01/2011 11:06:46. ¿Dudas? Llamanos al 6045111111. Estamos cerca."
        )
        assertNotNull(r)
        assertEquals(cuenta, r!!.accountId)
        assertEquals(TransactionType.EXPENSE, r.type)
        assertEquals(22_222_200L, r.amountMinor)
        assertEquals("Empresa Recaudo", r.merchantRaw)
    }

    @Test
    fun `pago por codigo QR`() {
        val r = parse(
            "87400",
            "Bancolombia: MATEO CASTRO PEREZ pagaste \$11,111.00 por codigo QR desde tu cuenta *1111 a la " +
                "llave 0091111111 el 21/01/2021 a las 00:01. Con codigo QR es facil y de una. Dudas al 018000000000."
        )
        assertNotNull(r)
        assertEquals(cuenta, r!!.accountId)
        assertEquals(TransactionType.EXPENSE, r.type)
        assertEquals(1_111_100L, r.amountMinor)
    }

    @Test
    fun `transferencia enviada por llave`() {
        val r = parse(
            "87400",
            "Bancolombia: MATEO, transferiste \$55,555.00 a la llave @keyexample desde tu cuenta *1111 a " +
                "PEPE ALBERTO PEREZ PEREZ el 11/11/21 a las 17:19. Con Bre-b es de una y gratis. Dudas al 018000000000."
        )
        assertNotNull(r)
        assertEquals(cuenta, r!!.accountId)
        assertEquals(TransactionType.EXPENSE, r.type)
        assertEquals(5_555_500L, r.amountMinor)
        assertEquals("PEPE ALBERTO PEREZ PEREZ", r.merchantRaw)
    }

    @Test
    fun `transferencia enviada generica`() {
        val r = parse(
            "87400",
            "Bancolombia: Transferiste \$111,111.00 desde tu cuenta 1111 a la cuenta *3111111111 el " +
                "01/01/2021 a las 11:51. ¿Dudas? Llamanos al 018000000000. Estamos cerca."
        )
        assertNotNull(r)
        assertEquals(cuenta, r!!.accountId)
        assertEquals(TransactionType.EXPENSE, r.type)
        assertEquals(11_111_100L, r.amountMinor)
    }

    // --- Bancolombia: ingresos -----------------------------------------

    @Test
    fun `transferencia recibida generica`() {
        val r = parse(
            "85784",
            "Bancolombia: Recibiste una transferencia por \$44,444 de PEPE PEREZ en tu cuenta **1111, el " +
                "05/01/2021 a las 17:48. Si tienes dudas, hablemos: 018000000000. Siempre a tu lado."
        )
        assertNotNull(r)
        assertEquals(cuenta, r!!.accountId)
        assertEquals(TransactionType.INCOME, r.type)
        assertEquals(4_444_400L, r.amountMinor)
        assertEquals("PEPE PEREZ", r.merchantRaw)
    }

    @Test
    fun `transferencia recibida por llave`() {
        val r = parse(
            "85540",
            "Bancolombia: MATEO, recibiste una transferencia de PEPE ALBERTO PEREZ PEREZ por \$11,111.00 en tu " +
                "cuenta *1111 conectada a la llave @mykey el 30/10/21 a las 18:57. Con llaves es de una y gratis. Dudas al 018000000000."
        )
        assertNotNull(r)
        assertEquals(cuenta, r!!.accountId)
        assertEquals(TransactionType.INCOME, r.type)
        assertEquals(1_111_100L, r.amountMinor)
        assertEquals("PEPE ALBERTO PEREZ PEREZ", r.merchantRaw)
    }

    @Test
    fun `pago de nomina`() {
        val r = parse(
            "85540",
            "Bancolombia: Recibiste un pago de Nomina de EMPRESA por \$1,111,111.00 en tu cuenta de Ahorros el " +
                "15/05/2020 a las 11:11. Si tienes dudas, llamanos al 018000000000. A tu lado siempre."
        )
        assertNotNull(r)
        assertEquals(cuenta, r!!.accountId)
        assertEquals(TransactionType.INCOME, r.type)
        assertEquals(111_111_100L, r.amountMinor)
        assertEquals("EMPRESA", r.merchantRaw)
    }

    // --- Davivienda -----------------------------------------------------

    @Test
    fun `compra TC davivienda`() {
        val r = parse(
            "890077",
            "DAVIVIENDA: Compra . Aprobado(a), \$8,888, Tarjeta   *1111, Hora 19:22,Lugar UBER RIDES            ."
        )
        assertNotNull(r)
        assertEquals(tcDavivienda, r!!.accountId)
        assertEquals(TransactionType.EXPENSE, r.type)
        assertEquals(888_800L, r.amountMinor)
        assertEquals("UBER RIDES", r.merchantRaw)
    }

    @Test
    fun `compra TC davivienda mercadopago`() {
        val r = parse(
            "890077",
            "DAVIVIENDA: Compra . Aprobado(a), \$1,111, Tarjeta   *1111, Hora 16:06,Lugar MERCADOPAGO           ."
        )
        assertNotNull(r)
        assertEquals(tcDavivienda, r!!.accountId)
        assertEquals(111_100L, r.amountMinor)
        assertEquals("MERCADOPAGO", r.merchantRaw)
    }

    @Test
    fun `avance TC davivienda`() {
        val r = parse(
            "891000",
            "DAVIVIENDA: Avance . Aprobado(a), \$111,000, Tarjeta *1111, Hora 18:01,Lugar AUTORIZ DAVIPAYTRE DAV."
        )
        assertNotNull(r)
        assertEquals(tcDavivienda, r!!.accountId)
        assertEquals(TransactionType.TRANSFER, r.type)
        assertNull(r.counterAccountId)
        assertEquals(11_100_000L, r.amountMinor)
    }

    // --- Negativos --------------------------------------------------------

    @Test
    fun `remitente desconocido no produce nada`() {
        val r = parse(
            "3001234567",
            "Bancolombia: Compraste COP44.444,00 en CORRAL VIVA LAURELES con tu T.Cred *1111, el 01/01/2021 a las 16:33."
        )
        assertNull(r)
    }

    @Test
    fun `sms publicitario del banco no produce nada`() {
        val r = parse(
            "85540",
            "Bancolombia te invita a descubrir los beneficios de tu tarjeta. Conoce mas en nuestra app."
        )
        assertNull(r)
    }

    @Test
    fun `mismo sms produce el mismo externalRef`() {
        val body = "DAVIVIENDA: Compra . Aprobado(a), \$8,888, Tarjeta   *1111, Hora 19:22,Lugar UBER RIDES            ."
        val a = SmsParser.parse("890077", body, 1234L, templates)
        val b = SmsParser.parse("890077", body, 1234L, templates)
        assertEquals(a!!.externalRef, b!!.externalRef)
    }

    @Test
    fun `mismo sms con distinto timestamp produce distinto externalRef`() {
        val body = "DAVIVIENDA: Compra . Aprobado(a), \$8,888, Tarjeta   *1111, Hora 19:22,Lugar UBER RIDES            ."
        val a = SmsParser.parse("890077", body, 1234L, templates)
        val b = SmsParser.parse("890077", body, 9999L, templates)
        org.junit.Assert.assertNotEquals(a!!.externalRef, b!!.externalRef)
    }
}
