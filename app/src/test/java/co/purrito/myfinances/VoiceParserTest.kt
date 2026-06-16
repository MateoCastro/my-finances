package co.purrito.myfinances

import co.purrito.myfinances.data.model.AccountType
import co.purrito.myfinances.data.model.TransactionType
import co.purrito.myfinances.domain.VoiceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/* =====================================================================
 * Tests del parser de voz (Hito 6). Lógica pura: frases dictadas →
 * monto (centavos), descripción, tipo y cuenta inferida.
 * ===================================================================== */

class VoiceParserTest {

    @Test
    fun `monto en digitos`() {
        val p = VoiceParser.parse("gasté 20000 en empanadas")!!
        assertEquals(20_000_00L, p.amountMinor)
        assertEquals("empanadas", p.description)
        assertEquals(TransactionType.EXPENSE, p.type)
        assertEquals(AccountType.CASH, p.accountTypeHint)
    }

    @Test
    fun `monto con separador de miles en digitos`() {
        val p = VoiceParser.parse("pagué 20.000 por el almuerzo")!!
        assertEquals(20_000_00L, p.amountMinor)
        assertEquals("almuerzo", p.description)
    }

    @Test
    fun `digito mas palabra mil`() {
        val p = VoiceParser.parse("20 mil empanadas")!!
        assertEquals(20_000_00L, p.amountMinor)
        assertEquals("empanadas", p.description)
    }

    @Test
    fun `digitos con miles separados por espacio`() {
        // El reconocedor transcribe "20000" como "20 000"
        val p = VoiceParser.parse("gaste 20 000 en empanadas")!!
        assertEquals(20_000_00L, p.amountMinor)
        assertEquals("empanadas", p.description)
    }

    @Test
    fun `millones con miles separados por espacio`() {
        val p = VoiceParser.parse("gasté 1 200 000 en tecnología")!!
        assertEquals(1_200_000_00L, p.amountMinor)
    }

    @Test
    fun `monto en palabras veinte mil`() {
        val p = VoiceParser.parse("veinte mil en empanadas")!!
        assertEquals(20_000_00L, p.amountMinor)
        assertEquals("empanadas", p.description)
    }

    @Test
    fun `cincuenta mil sin descripcion`() {
        val p = VoiceParser.parse("gasté cincuenta mil")!!
        assertEquals(50_000_00L, p.amountMinor)
        assertNull(p.description)
    }

    @Test
    fun `un millon`() {
        val p = VoiceParser.parse("gasté un millón en el mercado")!!
        assertEquals(1_000_000_00L, p.amountMinor)
        assertEquals("mercado", p.description)
    }

    @Test
    fun `ciento cincuenta mil`() {
        val p = VoiceParser.parse("ciento cincuenta mil en tecnología")!!
        assertEquals(150_000_00L, p.amountMinor)
        assertEquals("tecnologia", deaccentForTest(p.description!!))
    }

    @Test
    fun `treinta y cinco mil`() {
        val p = VoiceParser.parse("treinta y cinco mil")!!
        assertEquals(35_000_00L, p.amountMinor)
    }

    @Test
    fun `pesos como palabra no es parte del monto`() {
        val p = VoiceParser.parse("veinte mil pesos en domicilio")!!
        assertEquals(20_000_00L, p.amountMinor)
        assertEquals("domicilio", p.description)
    }

    @Test
    fun `ingreso detectado por me pagaron`() {
        val p = VoiceParser.parse("me pagaron 2 millones")!!
        assertEquals(TransactionType.INCOME, p.type)
        assertEquals(2_000_000_00L, p.amountMinor)
    }

    @Test
    fun `cuenta tarjeta de credito por keyword`() {
        val p = VoiceParser.parse("gasté 50 mil con tarjeta en mercado")!!
        assertEquals(AccountType.CREDIT_CARD, p.accountTypeHint)
        assertEquals("mercado", p.description)
    }

    @Test
    fun `cuenta bancaria por keyword`() {
        val p = VoiceParser.parse("pagué 30 mil de la cuenta")!!
        assertEquals(AccountType.BANK, p.accountTypeHint)
        // "de la cuenta" son todas palabras función → sin descripción
        assertNull(p.description)
    }

    @Test
    fun `sin monto conserva descripcion y no se pierde`() {
        val p = VoiceParser.parse("compré pan")!!
        assertNull(p.amountMinor)
        assertEquals("pan", p.description)
        assertEquals("compré pan", p.rawText)
    }

    @Test
    fun `texto en blanco devuelve null`() {
        assertNull(VoiceParser.parse("   "))
    }

    private fun deaccentForTest(s: String): String =
        s.replace('á', 'a').replace('é', 'e').replace('í', 'i')
            .replace('ó', 'o').replace('ú', 'u')
}
