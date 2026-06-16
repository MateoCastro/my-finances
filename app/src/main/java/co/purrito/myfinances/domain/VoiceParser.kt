package co.purrito.myfinances.domain

import co.purrito.myfinances.data.model.AccountType
import co.purrito.myfinances.data.model.TransactionType

/* =====================================================================
 * Parser de voz (Hito 6) — LÓGICA PURA (sin Android, testeable JUnit).
 *
 * Recibe el texto transcrito ("gasté veinte mil en empanadas") y extrae
 * monto, descripción, tipo y la cuenta inferida (por su TIPO). La
 * resolución contra la BD (qué cuenta concreta, qué categoría) la hace
 * el ViewModel: aquí solo vive la gramática, que es la parte difícil y
 * verificable.
 *
 * Reglas (sin LLM):
 *  - Patrón: [verbo] [monto] (en|de|por|para) [descripción]
 *  - Monto: dígitos ("20000", "20.000") o palabras ("veinte mil",
 *    "un millón", "ciento cincuenta mil"). Mezcla soportada: "20 mil".
 *  - Tipo: EXPENSE por defecto; "me pagaron"/"recibí"/… → INCOME.
 *  - Cuenta: CASH por defecto; "con tarjeta" → CREDIT_CARD;
 *    "de la cuenta"/"débito" → BANK.
 *
 * NUNCA pierde la captura: si no entiende el monto, devuelve amountMinor
 * = null pero conserva rawText para guardarlo como PENDING a completar.
 * Devuelve null SOLO si el texto viene en blanco (nada que guardar).
 * ===================================================================== */

data class ParsedVoice(
    val rawText: String,
    val amountMinor: Long?,            // null = no se entendió el monto
    val description: String?,
    val type: TransactionType,
    val accountTypeHint: AccountType
)

object VoiceParser {

    fun parse(text: String): ParsedVoice? {
        val raw = text.trim()
        if (raw.isBlank()) return null

        val normJoin = raw.lowercase().let(::deaccent)
        val type = detectType(normJoin)
        val accountHint = detectAccountHint(normJoin)

        val tokens = raw.split(Regex("\\s+"))
            .map(::trimEdges)
            .filter { it.isNotEmpty() }

        val span = findAmountSpan(tokens)
        val amountMinor = span
            ?.let { spanToPesos(tokens.subList(it.first, it.last + 1)) }
            ?.let { it * 100 }

        val description = extractDescription(tokens, span)

        return ParsedVoice(raw, amountMinor, description, type, accountHint)
    }

    // -----------------------------------------------------------------
    // Tipo y cuenta: keywords sobre el texto normalizado
    // -----------------------------------------------------------------

    private val INCOME_KEYWORDS = listOf(
        "me pagaron", "me pago", "recibi", "ingreso", "ingrese", "gane",
        "cobre", "abono", "abonaron", "deposito", "depositaron", "salario",
        "sueldo", "reembolso", "devolucion"
    )

    private fun detectType(norm: String): TransactionType =
        if (INCOME_KEYWORDS.any { norm.contains(it) }) TransactionType.INCOME
        else TransactionType.EXPENSE

    private val CARD_KEYWORDS = listOf("tarjeta", "credito")
    private val BANK_KEYWORDS =
        listOf("cuenta", "debito", "transferencia", "transferi", "ahorros")

    private fun detectAccountHint(norm: String): AccountType = when {
        CARD_KEYWORDS.any { norm.contains(it) } -> AccountType.CREDIT_CARD
        BANK_KEYWORDS.any { norm.contains(it) } -> AccountType.BANK
        else -> AccountType.CASH // la voz es principalmente para efectivo
    }

    // -----------------------------------------------------------------
    // Monto: números en palabras y dígitos
    // -----------------------------------------------------------------

    private val UNITS: Map<String, Long> = buildMap {
        listOf(
            "cero" to 0L, "uno" to 1, "un" to 1, "una" to 1, "dos" to 2,
            "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6, "siete" to 7,
            "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12,
            "trece" to 13, "catorce" to 14, "quince" to 15, "dieciseis" to 16,
            "diecisiete" to 17, "dieciocho" to 18, "diecinueve" to 19,
            "veinte" to 20, "veintiuno" to 21, "veintiun" to 21, "veintiuna" to 21,
            "veintidos" to 22, "veintitres" to 23, "veinticuatro" to 24,
            "veinticinco" to 25, "veintiseis" to 26, "veintisiete" to 27,
            "veintiocho" to 28, "veintinueve" to 29, "treinta" to 30,
            "cuarenta" to 40, "cincuenta" to 50, "sesenta" to 60, "setenta" to 70,
            "ochenta" to 80, "noventa" to 90, "cien" to 100, "ciento" to 100,
            "doscientos" to 200, "doscientas" to 200, "trescientos" to 300,
            "trescientas" to 300, "cuatrocientos" to 400, "cuatrocientas" to 400,
            "quinientos" to 500, "quinientas" to 500, "seiscientos" to 600,
            "seiscientas" to 600, "setecientos" to 700, "setecientas" to 700,
            "ochocientos" to 800, "ochocientas" to 800, "novecientos" to 900,
            "novecientas" to 900
        ).forEach { (k, v) -> put(k, v.toLong()) }
    }
    private val MILLON = setOf("millon", "millones")

    private fun isNumericToken(n: String): Boolean =
        n == "mil" || n == "y" || n in MILLON || n in UNITS || isDigitToken(n)

    private fun isDigitToken(n: String): Boolean =
        n.isNotEmpty() && n.all { it.isDigit() || it == '.' || it == ',' } && n.any { it.isDigit() }

    private fun digitsOf(n: String): String = n.filter { it.isDigit() }

    /** Primer tramo contiguo de tokens numéricos que contenga un número real (no solo "y"). */
    private fun findAmountSpan(tokens: List<String>): IntRange? {
        var i = 0
        while (i < tokens.size) {
            if (isNumericToken(norm(tokens[i]))) {
                var j = i
                while (j + 1 < tokens.size && isNumericToken(norm(tokens[j + 1]))) j++
                var s = i
                var e = j
                while (s <= e && norm(tokens[s]) == "y") s++
                while (e >= s && norm(tokens[e]) == "y") e--
                val hasReal = (s..e).any { norm(tokens[it]).let { n -> n != "y" && isNumericToken(n) } }
                if (s <= e && hasReal) return s..e
                i = j + 1
            } else i++
        }
        return null
    }

    /**
     * Acumulador clásico: las unidades se suman a `current`; "mil" y
     * "millón" cierran el grupo escalándolo y lo vuelcan a `total`.
     * Un token de dígitos cuenta como su valor literal ("20.000" → 20000)
     * y también funciona como multiplicador si lo sigue "mil" ("20 mil").
     */
    private fun spanToPesos(tokens: List<String>): Long? {
        var total = 0L
        var current = 0L
        var any = false
        var i = 0
        while (i < tokens.size) {
            val n = norm(tokens[i])
            when {
                n == "y" -> Unit
                n == "mil" -> {
                    if (current == 0L) current = 1
                    total += current * 1000; current = 0; any = true
                }
                n in MILLON -> {
                    if (current == 0L) current = 1
                    total += current * 1_000_000; current = 0; any = true
                }
                n in UNITS -> { current += UNITS.getValue(n); any = true }
                isDigitToken(n) -> {
                    // El reconocedor de voz separa los miles con espacio
                    // ("20 000" = 20000): se concatenan los tokens de
                    // dígitos siguientes que tengan EXACTAMENTE 3 dígitos.
                    val sb = StringBuilder(digitsOf(n))
                    while (i + 1 < tokens.size) {
                        val next = norm(tokens[i + 1])
                        if (isDigitToken(next) && digitsOf(next).length == 3) {
                            sb.append(digitsOf(next)); i++
                        } else break
                    }
                    current += sb.toString().toLongOrNull() ?: 0L
                    any = true
                }
            }
            i++
        }
        total += current
        return if (any) total else null
    }

    // -----------------------------------------------------------------
    // Descripción: lo que queda tras el monto, sin conectores ni
    // keywords de cuenta/tipo. Heurística: filtra una lista de palabras
    // función; el usuario confirma/edita en el inbox.
    // -----------------------------------------------------------------

    private val LEADING_VERBS = setOf(
        "gaste", "pague", "compre", "comprar", "pagar", "gastar", "me",
        "pagaron", "recibi", "ingrese", "ingreso", "gane", "cobre", "saque",
        "retire", "deposite", "abone", "inverti", "pago"
    )

    private val STRIP = setOf(
        "en", "de", "del", "por", "para", "a", "al", "con", "la", "el", "lo",
        "los", "las", "un", "una", "unos", "unas", "mi", "mis", "tu", "tus",
        "tarjeta", "credito", "cuenta", "debito", "efectivo", "transferencia",
        "banco", "plata", "pesos", "peso"
    )

    private fun extractDescription(tokens: List<String>, span: IntRange?): String? {
        val start = if (span != null) span.last + 1 else dropLeadingVerbs(tokens)
        if (start >= tokens.size) return null
        val words = tokens.subList(start, tokens.size).filter { t ->
            val n = norm(t)
            n.isNotEmpty() && n !in STRIP && !isNumericToken(n)
        }
        return words.joinToString(" ").trim().ifBlank { null }
    }

    private fun dropLeadingVerbs(tokens: List<String>): Int {
        var i = 0
        while (i < tokens.size && norm(tokens[i]) in LEADING_VERBS) i++
        return i
    }

    // -----------------------------------------------------------------
    // Normalización
    // -----------------------------------------------------------------

    /** Quita signos de los bordes, conservando letras/dígitos y . , internos. */
    private fun trimEdges(t: String): String =
        t.trim { ch -> !(ch.isLetterOrDigit() || ch == '.' || ch == ',') }

    private fun norm(t: String): String = deaccent(t.lowercase())

    private fun deaccent(s: String): String = buildString(s.length) {
        for (ch in s) append(
            when (ch) {
                'á' -> 'a'; 'é' -> 'e'; 'í' -> 'i'; 'ó' -> 'o'; 'ú', 'ü' -> 'u'
                'ñ' -> 'n'
                else -> ch
            }
        )
    }
}
