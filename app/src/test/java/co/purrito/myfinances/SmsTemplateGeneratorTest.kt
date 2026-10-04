package co.purrito.myfinances

import co.purrito.myfinances.data.model.SmsTemplate
import co.purrito.myfinances.data.model.TransactionType
import co.purrito.myfinances.domain.SmsTemplateGenerator
import co.purrito.myfinances.service.SmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/* =====================================================================
 * Tests del generador de plantillas (Hito 7): "enseñar" un SMS y que la
 * plantilla resultante reconozca los SIGUIENTES SMS del mismo tipo (otro
 * monto, comercio, hora, relleno de espacios) pero no los de otro tipo
 * o de otra tarjeta. SMS reales anonimizados (montos/comercios falsos).
 * ===================================================================== */

class SmsTemplateGeneratorTest {

    private val davivienda =
        "DAVIVIENDA: Compra . Aprobado(a), \$25,300, Tarjeta   *9999, Hora 01:46,Lugar DLO*Didi              ."

    /** Enseña `body` marcando los textos dados y devuelve la plantilla. */
    private fun teach(
        sender: String,
        body: String,
        amountText: String,
        merchantText: String? = null
    ): SmsTemplate {
        val a = body.indexOf(amountText).also { check(it >= 0) }
        val m = merchantText?.let { t -> body.indexOf(t).also { check(it >= 0) } }
        return SmsTemplate(
            bankName = "Test",
            accountId = 1L,
            senderPattern = SmsTemplateGenerator.senderPattern(sender),
            bodyPattern = SmsTemplateGenerator.bodyPattern(
                body,
                a until a + amountText.length,
                m?.let { it until it + merchantText.length }
            ),
            resultingType = TransactionType.EXPENSE
        )
    }

    private fun parse(t: SmsTemplate, sender: String, body: String) =
        SmsParser.parse(sender, body, 0L, listOf(t))

    // --- Davivienda (el formato que llega hoy, remitente 87188) -------

    @Test
    fun `la plantilla ensenada reconoce su propio SMS`() {
        val t = teach("87188", davivienda, "25,300", "DLO*Didi")
        val r = parse(t, "87188", davivienda)!!
        assertEquals(2_530_000L, r.amountMinor)
        assertEquals("DLO*Didi", r.merchantRaw)
    }

    @Test
    fun `reconoce otra compra con distinto monto comercio hora y espacios`() {
        val t = teach("87188", davivienda, "25,300", "DLO*Didi")
        val r = parse(
            t, "87188",
            "DAVIVIENDA: Compra . Aprobado(a), \$1,250,000, Tarjeta *9999, Hora 19:22,Lugar UBER RIDES ."
        )!!
        assertEquals(125_000_000L, r.amountMinor)
        assertEquals("UBER RIDES", r.merchantRaw)
    }

    @Test
    fun `una plantilla de compra no reconoce un avance`() {
        val t = teach("87188", davivienda, "25,300", "DLO*Didi")
        assertNull(
            parse(
                t, "87188",
                "DAVIVIENDA: Avance . Aprobado(a), \$111,000, Tarjeta *9999, Hora 18:01,Lugar AUTORIZ DAV."
            )
        )
    }

    @Test
    fun `una plantilla no reconoce los SMS de otra tarjeta`() {
        val t = teach("87188", davivienda, "25,300", "DLO*Didi")
        assertNull(parse(t, "87188", davivienda.replace("*9999", "*1111")))
    }

    @Test
    fun `remitente corto acepta otros codigos cortos pero no celulares`() {
        val t = teach("87188", davivienda, "25,300", "DLO*Didi")
        assertNotNull(parse(t, "890077", davivienda))
        assertNull(parse(t, "3001234567", davivienda))
    }

    @Test
    fun `remitente largo o alfanumerico se toma exacto`() {
        assertEquals("""^\+573001234567$""", SmsTemplateGenerator.senderPattern("+573001234567"))
        assertEquals("^Nequi$", SmsTemplateGenerator.senderPattern("Nequi"))
    }

    @Test
    fun `sin comercio marcado reconoce solo el monto`() {
        val t = teach("87188", davivienda, "25,300")
        val r = parse(t, "87188", davivienda)!!
        assertEquals(2_530_000L, r.amountMinor)
        assertNull(r.merchantRaw)
    }

    // --- Bancolombia --------------------------------------------------

    @Test
    fun `COP y signo pesos son intercambiables antes del monto`() {
        val ejemplo = "Bancolombia: Compraste COP44.444,00 en CORRAL VIVA LAURELES con tu T.Cred *1111, " +
            "el 01/01/2021 a las 16:33. Si tienes dudas, encuentranos aqui: 6045444444. Estamos cerca."
        val t = teach("85784", ejemplo, "44.444,00", "CORRAL VIVA LAURELES")
        val r = parse(
            t, "85540",
            "Bancolombia: Compraste \$33.335,00 en FARMATODO ALTO DE PA con tu T.Cred *1111, " +
                "el 05/05/2025 a las 22:22. Otro texto de cierre distinto."
        )!!
        assertEquals(3_333_500L, r.amountMinor)
        assertEquals("FARMATODO ALTO DE PA", r.merchantRaw)
    }

    @Test
    fun `comercio antes del monto`() {
        val ejemplo = "Bancolombia: Recibiste una transferencia por \$44,444 de PEPE PEREZ en tu cuenta **1111, " +
            "el 05/01/2021 a las 17:48."
        val t = teach("85784", ejemplo, "44,444", "PEPE PEREZ").copy(resultingType = TransactionType.INCOME)
        val r = parse(
            t, "85784",
            "Bancolombia: Recibiste una transferencia por \$1,500,000 de ANA MARIA GOMEZ en tu cuenta **1111, " +
                "el 09/09/2026 a las 08:00."
        )!!
        assertEquals(150_000_000L, r.amountMinor)
        assertEquals("ANA MARIA GOMEZ", r.merchantRaw)
        assertEquals(TransactionType.INCOME, r.type)
    }

    @Test
    fun `cada SMS real del repositorio se reconoce a si mismo al ensenarlo`() {
        val reales = listOf(
            "85540" to "Bancolombia: Compraste COP44.444,44 en MERCADO PAGO*ZXVENTU, el 01/01/2001 a las 18:14. " +
                "Esta compra esta asociada a T.Cred *1111. Si tienes dudas, encuentranos aqui: 01800000000. Siempre contigo.",
            "85540" to "Bancolombia: Hiciste un avance de \$100,000 en tu SUC VIRTUAL el 19:19 01/01/2021 desde tu " +
                "T.Credito *1111 a la cuenta *7777. ¿Dudas? Llamanos al 6045444444.",
            "85784" to "Bancolombia: Pagaste \$1,111,111 en la tarjeta de credito *1111 desde la cuenta *1111, " +
                "el 11/11/2011 11:11. ¿Dudas? Llamanos al 018000000000. Estamos cerca.",
            "85540" to "Bancolombia: Retiraste \$111.111,00 en OFIX33_2 de tu T.Deb **1111 el 01/01/2001 a las 18:51.",
            "85784" to "Bancolombia: Transferiste \$10,000.00 por Boton Bancolombia a Wompi SAS desde producto *2222. " +
                "03/03/2023 09:09:09 ¿Dudas? 018000000000.",
            "87400" to "Bancolombia: MATEO CASTRO PEREZ pagaste \$11,111.00 por codigo QR desde tu cuenta *1111 a la " +
                "llave 0091111111 el 21/01/2021 a las 00:01. Con codigo QR es facil y de una.",
            "85540" to "Bancolombia: Recibiste un pago de Nomina de EMPRESA por \$1,111,111.00 en tu cuenta de Ahorros el " +
                "15/05/2020 a las 11:11.",
            "891000" to "DAVIVIENDA: Avance . Aprobado(a), \$111,000, Tarjeta *1111, Hora 18:01,Lugar AUTORIZ DAVIPAYTRE DAV."
        )
        for ((sender, body) in reales) {
            val amountRange = SmsTemplateGenerator.amountCandidates(body).first()
            val t = SmsTemplate(
                bankName = "Test", accountId = 1L,
                senderPattern = SmsTemplateGenerator.senderPattern(sender),
                bodyPattern = SmsTemplateGenerator.bodyPattern(body, amountRange),
                resultingType = TransactionType.EXPENSE
            )
            val expected = SmsParser.parseAmountToMinor(body.substring(amountRange))
            assertEquals(body, expected, parse(t, sender, body)?.amountMinor)
        }
    }

    // --- Candidatos de monto -------------------------------------------

    @Test
    fun `candidatos de monto ignoran hora tarjeta fecha y telefonos`() {
        fun texts(body: String) = SmsTemplateGenerator.amountCandidates(body).map { body.substring(it) }
        assertEquals(listOf("25,300"), texts(davivienda))
        assertEquals(
            listOf("1,111,111"),
            texts(
                "Bancolombia: Pagaste \$1,111,111 en la tarjeta de credito *1111 desde la cuenta *1111, " +
                    "el 11/11/2011 11:11. ¿Dudas? Llamanos al 018000000000."
            )
        )
        assertEquals(listOf("44.444,44"), texts("Compraste COP44.444,44 en X, el 01/01/2001 a las 18:14."))
    }

    @Test
    fun `monto y comercio no pueden superponerse`() {
        assertThrows(IllegalArgumentException::class.java) {
            SmsTemplateGenerator.bodyPattern(davivienda, 35..40, 30..38)
        }
    }

    @Test
    fun `nombre del banco desde el encabezado del SMS`() {
        assertEquals("Davivienda", SmsTemplateGenerator.guessBankName(davivienda))
        assertEquals("Bancolombia", SmsTemplateGenerator.guessBankName("Bancolombia: Compraste COP1,00"))
        assertNull(SmsTemplateGenerator.guessBankName("Sin encabezado de banco"))
    }
}
