package co.purrito.myfinances.domain

/* =====================================================================
 * Generador de plantillas SMS a partir de UN ejemplo (Hito 7) — PURO.
 *
 * El usuario no escribe expresiones regulares: muestra un SMS real y
 * marca dónde está el monto (y opcionalmente el comercio). De ahí sale
 * el `bodyPattern` de una SmsTemplate:
 *
 *   "DAVIVIENDA: Compra . Aprobado(a), $25,300, Tarjeta   *9999, Hora 01:46,Lugar DLO*Didi    ."
 *                                       ^^^^^^ monto                              ^^^^^^^^ comercio
 *   →  DAVIVIENDA:\s*Compra\s*\.\s*Aprobado\(a\),\s*(?:COP|\$)?\s*(?<amount>…),\s*Tarjeta\s*\*9999,
 *      \s*Hora\s*\d+:\d+,Lugar\s*(?<merchant>.+?)\s*\.
 *
 * Reglas de generalización del texto fijo (lo que NO se marcó):
 *  - Palabras y signos: literales (el parser compara sin mayúsculas).
 *    Así "Compra" y "Avance" siguen siendo plantillas distintas.
 *  - Espacios: `\s*` (los bancos cambian el relleno de espacios).
 *  - Números: `\d+` (hora, fecha, referencias cambian en cada SMS)…
 *  - …EXCEPTO los dígitos tras '*' (últimos 4 de la tarjeta/cuenta): se
 *    dejan literales para que la plantilla de una tarjeta no capture los
 *    SMS de otra del mismo banco (cada plantilla enruta a UNA cuenta).
 *  - "$" o "COP" justo antes del monto: `(?:COP|\$)?` (Bancolombia
 *    alterna ambos según el canal).
 *  - Tras el último campo solo se conservan unas pocas piezas (las que
 *    delimitan el comercio): la cola del mensaje (teléfonos, eslóganes)
 *    cambia sin aviso y no aporta.
 * ===================================================================== */

object SmsTemplateGenerator {

    /** Piezas de texto fijo que se conservan después del último campo. */
    private const val SUFFIX_TOKENS = 3

    private const val AMOUNT_GROUP = """(?<amount>\d(?:[\d.,]*\d)?)"""

    private val numberRun = Regex("""\d(?:[\d.,]*\d)?""")
    private val groupedNumber = Regex("""\d{1,3}(?:[.,]\d{3})+(?:[.,]\d{2})?""")
    private val currencyBefore = Regex("""(?:\$|COP)\s?$""", RegexOption.IGNORE_CASE)
    private const val REGEX_SPECIAL = "\\^$.|?*+()[]{}"

    /**
     * Posibles montos del SMS, en orden de aparición: números con "$"/"COP"
     * delante o con separadores de miles ("25,300", "44.444,44"). Deja
     * fuera horas, fechas, teléfonos y los últimos 4 de la tarjeta.
     */
    fun amountCandidates(body: String): List<IntRange> =
        numberRun.findAll(body)
            .filter { m ->
                val before = body.substring(0, m.range.first)
                !before.endsWith("*") &&
                    (currencyBefore.containsMatchIn(before) || groupedNumber.matches(m.value))
            }
            .map { it.range }
            .toList()

    /**
     * El `bodyPattern` para el SMS con los campos marcados (rangos sobre
     * `body`, inclusivos). El comercio es opcional; se recortan sus
     * espacios de los bordes.
     */
    fun bodyPattern(body: String, amount: IntRange, merchant: IntRange? = null): String {
        val fields = buildList {
            add(Field(amount, isAmount = true))
            merchant?.let { trimRange(body, it) }?.let { add(Field(it, isAmount = false)) }
        }.sortedBy { it.range.first }
        require(fields.zipWithNext().none { (a, b) -> a.range.last >= b.range.first }) {
            "El monto y el comercio no pueden superponerse"
        }

        val out = StringBuilder()
        var cursor = 0
        fields.forEachIndexed { i, field ->
            var literal = generalize(body, cursor, field.range.first, maxTokens = Int.MAX_VALUE)
            if (field.isAmount) {
                // "$"/"COP" inmediatamente antes del monto → opcional e intercambiable
                val currency = currencyBefore.find(body.substring(cursor, field.range.first))
                if (currency != null) {
                    literal = generalize(
                        body, cursor, cursor + currency.range.first, maxTokens = Int.MAX_VALUE
                    ) + """(?:COP|\$)?\s*"""
                }
                out.append(literal).append(AMOUNT_GROUP)
            } else {
                // Lazy si hay texto fijo después que lo delimite
                val hasTerminator = i < fields.lastIndex ||
                    body.substring(field.range.last + 1).isNotBlank()
                out.append(literal).append(if (hasTerminator) "(?<merchant>.+?)" else "(?<merchant>.+)")
            }
            cursor = field.range.last + 1
        }
        out.append(generalize(body, cursor, body.length, maxTokens = SUFFIX_TOKENS))
        return out.toString()
    }

    /**
     * Remitente: los códigos cortos numéricos de los bancos rotan (Davivienda
     * pasó de 89xxxx a 87188), así que se acepta cualquier código corto y el
     * CUERPO es el que distingue al banco. Un número largo o un nombre
     * alfanumérico se toma exacto.
     */
    fun senderPattern(sender: String): String {
        val s = sender.trim()
        return if (s.all { it.isDigit() } && s.length <= 6) """^\d{4,6}$"""
        else "^" + escape(s) + "$"
    }

    /** "DAVIVIENDA: Compra…" → "Davivienda"; "Bancolombia: …" → "Bancolombia". */
    fun guessBankName(body: String): String? {
        val head = body.substringBefore(':', missingDelimiterValue = "").trim()
        if (head.isEmpty() || head.length > 30 || head.any { it.isDigit() }) return null
        return head.lowercase().replaceFirstChar { it.uppercase() }
    }

    private data class Field(val range: IntRange, val isAmount: Boolean)

    private fun trimRange(body: String, range: IntRange): IntRange? {
        var first = range.first
        var last = range.last
        while (first <= last && body[first].isWhitespace()) first++
        while (last >= first && body[last].isWhitespace()) last--
        return if (first <= last) first..last else null
    }

    /**
     * Texto fijo body[from, to) → regex generalizada. `maxTokens` limita
     * cuántas piezas no-espacio se conservan (para la cola del mensaje).
     */
    private fun generalize(body: String, from: Int, to: Int, maxTokens: Int): String {
        val out = StringBuilder()
        var i = from
        var tokens = 0
        while (i < to && tokens < maxTokens) {
            val c = body[i]
            when {
                c.isWhitespace() -> {
                    while (i < to && body[i].isWhitespace()) i++
                    out.append("""\s*""")
                }
                c.isDigit() -> {
                    val start = i
                    while (i < to && body[i].isDigit()) i++
                    val afterStar = start > 0 && body[start - 1] == '*'
                    out.append(if (afterStar) body.substring(start, i) else """\d+""")
                    tokens++
                }
                c.isLetter() -> {
                    val start = i
                    while (i < to && body[i].isLetter()) i++
                    out.append(body.substring(start, i))
                    tokens++
                }
                else -> {
                    out.append(escape(c.toString()))
                    i++
                    tokens++
                }
            }
        }
        return out.toString()
    }

    private fun escape(s: String): String = buildString {
        s.forEach { c -> if (c in REGEX_SPECIAL) append('\\'); append(c) }
    }
}
