package co.purrito.myfinances.service

import co.purrito.myfinances.data.model.TransactionType
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/* =====================================================================
 * Parser del extracto de TC Bancolombia en PDF (Hito 4) — LÓGICA PURA.
 *
 * Distinto al .xlsx (que lee celdas): el PDF→texto trae cada movimiento
 * en una línea con la descripción y la AUTORIZACIÓN pegadas:
 *   BOLD*Taller MedecarR01659 14/05/2026 $ 2.957.198,00 1/1 $ ... $ 0,00
 *   INTERESES CORRIENTES18/05/2026 $ 38.136,38 $ 38.136,38 $ 0,00
 *   ABONO SUCURSAL VIRTUALC01683 02/05/2026 $ -1.542.287,00 $ ... $ 0,00
 *
 * Estrategia (tolerante a espaciado): anclar en la fecha dd/MM/yyyy; lo
 * de antes es desc+autorización ([A-Z]\d{5} al final); lo de después,
 * valor, cuotas "N/M", valor cuota, % y saldo. Las cabeceras se
 * triplican en la extracción pero no traen fecha → se ignoran.
 * ===================================================================== */

object BancolombiaPdfStatementParser {

    private val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val zone: ZoneId = ZoneId.systemDefault()
    private val dateRe = Regex("""\d{2}/\d{2}/\d{4}""")
    private val authRe = Regex("""[A-Z]\d{5}$""")
    private val installmentRe = Regex("""\d+/\d+""")

    private val financialKeywords = listOf(
        "INTERES", "MANEJO", "SEGURO", "COMISION", "MORA", "SOBREGIRO", "CMF"
    )

    fun parse(text: String): ParsedStatement {
        val lines = mutableListOf<ParsedStatementLine>()

        for (raw in text.lines()) {
            // Quitar '$' y '%' y colapsar espacios: la fecha y los números
            // quedan como tokens limpios, independiente del espaciado.
            val s = raw.replace("$", "").replace("%", "").trim().replace(Regex("\\s+"), " ")
            if (s.isEmpty()) continue

            val dm = dateRe.find(s) ?: continue
            val prefix = s.substring(0, dm.range.first).trim()
            if (prefix.isEmpty()) continue
            val date = parseDate(dm.value) ?: continue
            val rest = s.substring(dm.range.last + 1).trim()

            val auth = authRe.find(prefix)?.value
            val desc = (if (auth != null) prefix.removeSuffix(auth) else prefix).trim()

            val tokens = rest.split(" ").filter { it.isNotBlank() }
            val valorRaw = tokens.getOrNull(0) ?: continue
            val amount = SmsParser.parseAmountToMinor(valorRaw) ?: continue
            if (amount == 0L) continue
            val negative = valorRaw.startsWith("-")

            // Cuotas "N/M" en el segundo token (si las hay)
            var instCurrent: Int? = null
            var instTotal: Int? = null
            var instAmount: Long? = null
            val cuotaToken = tokens.getOrNull(1)?.takeIf { it.matches(installmentRe) }
            if (cuotaToken != null) {
                val parts = cuotaToken.split("/")
                val cur = parts[0].toIntOrNull()
                val tot = parts[1].toIntOrNull()
                if (tot != null && tot > 1) {
                    instCurrent = cur
                    instTotal = tot
                    instAmount = tokens.getOrNull(2)?.let { SmsParser.parseAmountToMinor(it) }
                }
            }

            // Un avance es una transferencia con la TC de ORIGEN (sale
            // deuda de la tarjeta). Detectado por palabra clave.
            val isAvance = !negative && desc.uppercase().contains("AVANCE")

            // Cargo del banco: sin autorización ni cuotas y positivo
            // (los intereses/cuota de manejo así aparecen). Las compras
            // siempre traen autorización; los abonos son negativos.
            val isCharge = !negative && !isAvance && auth == null && cuotaToken == null &&
                (financialKeywords.any { desc.uppercase().contains(it) } || true)

            // negativo = abono (TC destino); avance = TC origen; resto compra
            val type = when {
                negative || isAvance -> TransactionType.TRANSFER
                else -> TransactionType.EXPENSE
            }

            lines += ParsedStatementLine(
                dateMillis = date,
                amountMinor = amount,
                type = type,
                rawDescription = desc,
                installmentCurrent = instCurrent,
                installmentTotal = instTotal,
                isFinancialCharge = isCharge,
                installmentAmountMinor = instAmount,
                cardIsOrigin = isAvance
            )
        }

        val dates = lines.map { it.dateMillis }
        return ParsedStatement(
            lines = lines,
            statementBalanceMinor = findBalance(text),
            periodFromMillis = dates.minOrNull(),
            periodToMillis = dates.maxOrNull(),
            lastFourDigits = findLastFour(text)
        )
    }

    private fun parseDate(s: String): Long? = runCatching {
        LocalDate.parse(s, dateFmt).atStartOfDay(zone).toInstant().toEpochMilli()
    }.getOrNull()

    /**
     * "Pago Total:" y su valor van en líneas separadas y triplicados; hay
     * uno en dólares (0) y otro en pesos. Tomamos el de MAYOR magnitud.
     */
    private fun findBalance(text: String): Long? {
        val matches = Regex("""Pago Total:?[\s$]*([\d.,]+)""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNull { SmsParser.parseAmountToMinor(it.groupValues[1]) }
            .toList()
        val max = matches.maxOrNull() ?: return null
        return -max
    }

    /** "*********1659" → "1659". */
    private fun findLastFour(text: String): String? {
        val m = Regex("""\*+(\d{4})""").find(text) ?: return null
        return m.groupValues[1]
    }
}
