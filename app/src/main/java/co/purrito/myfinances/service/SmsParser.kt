package co.purrito.myfinances.service

import co.purrito.myfinances.data.model.SmsTemplate
import co.purrito.myfinances.data.model.TransactionType
import java.security.MessageDigest

data class SmsParseResult(
    val accountId: Long,
    val counterAccountId: Long?,
    val amountMinor: Long,
    val type: TransactionType,
    val merchantRaw: String?,
    val externalRef: String
)

/* =====================================================================
 * Parser de SMS bancarios.
 *
 * Lógica pura (sin efectos): recibe los datos del SMS y la lista de
 * plantillas activas, devuelve un resultado o null si ninguna aplica.
 * El BroadcastReceiver es quien llama a esto y persiste el resultado.
 *
 * Convención de grupos nombrados en bodyPattern:
 *   (?<amount>...)   — obligatorio, monto en pesos
 *   (?<merchant>...) — opcional, texto del comercio
 *   (?<lastFour>...) — opcional (reservado para futura multitarjeta)
 * ===================================================================== */

object SmsParser {

    fun parse(
        sender: String,
        body: String,
        messageTimestampMillis: Long,
        templates: List<SmsTemplate>
    ): SmsParseResult? {
        for (template in templates) {
            if (!Regex(template.senderPattern, RegexOption.IGNORE_CASE).containsMatchIn(sender)) continue

            val match = Regex(
                template.bodyPattern,
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(body) ?: continue

            val amountRaw = match.groupOrNull("amount") ?: continue
            val amountMinor = parseAmountToMinor(amountRaw) ?: continue
            val merchant = match.groupOrNull("merchant")?.trim()?.ifBlank { null }

            return SmsParseResult(
                accountId = template.accountId,
                counterAccountId = template.counterAccountId,
                amountMinor = amountMinor,
                type = template.resultingType,
                merchantRaw = merchant,
                externalRef = sha256("$sender|$body|$messageTimestampMillis")
            )
        }
        return null
    }

    /**
     * Convierte el monto capturado a centavos. Los bancos colombianos
     * mezclan formatos incluso dentro del mismo banco:
     *   "44.444,44"    → col: miles con punto, decimales con coma
     *   "10,000.00"    → us:  miles con coma, decimales con punto
     *   "100,000"      → sin decimales, separador de miles
     *   "8,888"        → sin decimales
     *
     * Regla: si el ÚLTIMO separador va seguido de exactamente 2 dígitos
     * al final, es el separador decimal; si no, todo es separador de
     * miles. (Los montos de miles siempre agrupan de a 3 dígitos.)
     */
    internal fun parseAmountToMinor(raw: String): Long? {
        val s = raw.trim()
        val lastSep = s.lastIndexOfAny(charArrayOf('.', ','))

        return if (lastSep >= 0 && s.length - lastSep - 1 == 2) {
            val integerPart = s.substring(0, lastSep).filter { it.isDigit() }
            val centsPart = s.substring(lastSep + 1)
            val integer = integerPart.toLongOrNull() ?: return null
            val cents = centsPart.toLongOrNull() ?: return null
            integer * 100 + cents
        } else {
            s.filter { it.isDigit() }.toLongOrNull()?.times(100)
        }
    }

    /**
     * En la JVM, pedir un grupo nombrado que NO existe en el patrón
     * lanza IllegalArgumentException (no devuelve null). Como los
     * grupos merchant/lastFour son opcionales por plantilla, este
     * acceso debe ser tolerante.
     */
    private fun MatchResult.groupOrNull(name: String): String? =
        runCatching { groups[name]?.value }.getOrNull()

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
