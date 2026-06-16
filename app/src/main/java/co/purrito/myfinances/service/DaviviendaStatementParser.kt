package co.purrito.myfinances.service

import co.purrito.myfinances.data.model.TransactionType
import java.time.LocalDate
import java.time.ZoneId

/* =====================================================================
 * Parser del extracto de TC Davivienda (Hito 4) — LÓGICA PURA.
 *
 * El extracto llega como TEXTO plano (PDF → .txt), con tablas de ancho
 * fijo. A diferencia del .xlsx de Bancolombia, aquí trabajamos línea a
 * línea. Estrategia: quitar los '$', colapsar espacios y clasificar
 * cada línea con regex (matchEntire, sin anclas) en uno de tres tipos:
 *   - movimiento (compra/cargo/cuota): fecha, desc, valor, "N de M", ...
 *   - pago/abono: fecha + 8 importes (en "Detalle aplicación de pagos")
 *   - otro cargo (seguro): fecha, desc, nº transacción, valor
 *
 * Fechas: "24May2026" (mes abreviado en español).
 * Montos: "$11,538" / "$1,044,110" → SmsParser.parseAmountToMinor.
 * ===================================================================== */

object DaviviendaStatementParser {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val months = mapOf(
        "ENE" to 1, "FEB" to 2, "MAR" to 3, "ABR" to 4, "MAY" to 5, "JUN" to 6,
        "JUL" to 7, "AGO" to 8, "SEP" to 9, "SET" to 9, "OCT" to 10, "NOV" to 11, "DIC" to 12
    )

    private val financialKeywords = listOf(
        "INTERES", "MANEJO", "SEGURO", "COMISION", "MORA", "SOBREGIRO", "CARGO"
    )

    // Sobre la línea con los '$' removidos y los espacios colapsados:
    private val MOVEMENT = Regex(
        """(\d{1,2})([A-Za-z]{3})(\d{4}) (.+?) ([\d.,]+) (\d+) ?de ?(\d+) ([\d.,]+) ([\d.,]+) ([\d.,]+) (\d+) (\d+) ([\d.,]+)"""
    )
    // Pago/abono: fecha seguida DIRECTO de importes (sin texto). Tolera
    // tanto la extracción con -layout (8 columnas) como la de PdfBox (solo
    // el total). Los movimientos llevan "N de M" (texto) → no colisionan.
    private val PAYMENT = Regex(
        """(\d{1,2})([A-Za-z]{3})(\d{4}) ([\d.,]+)(?: [\d.,]+)*"""
    )
    private val OTHER_CHARGE = Regex(
        """(\d{1,2})([A-Za-z]{3})(\d{4}) (.+?) (\d{6,}) ([\d.,]+)"""
    )

    fun parse(text: String): ParsedStatement {
        val lines = mutableListOf<ParsedStatementLine>()

        for (raw in text.lines()) {
            val s = raw.replace("$", "").trim().replace(Regex("\\s+"), " ")
            if (s.isEmpty()) continue

            MOVEMENT.matchEntire(s)?.let { m ->
                val (d, mon, y, desc, valor, cur, tot, _, valorPagar) = m.destructured
                val date = parseDate(d, mon, y) ?: return@let
                val amount = SmsParser.parseAmountToMinor(valor) ?: return@let
                val total = tot.toIntOrNull()
                val deferred = total != null && total > 1
                val descTrim = desc.trim()
                // Un avance es, en el fondo, una transferencia: sale deuda
                // de la TC (origen). Lo demás es compra/cargo (EXPENSE).
                val isAvance = descTrim.uppercase().contains("AVANCE")
                val isCharge = !deferred && !isAvance &&
                    financialKeywords.any { descTrim.uppercase().contains(it) }
                lines += ParsedStatementLine(
                    dateMillis = date,
                    amountMinor = amount,
                    type = if (isAvance) TransactionType.TRANSFER else TransactionType.EXPENSE,
                    rawDescription = descTrim,
                    installmentCurrent = if (deferred) cur.toIntOrNull() else null,
                    installmentTotal = if (deferred) total else null,
                    isFinancialCharge = isCharge,
                    installmentAmountMinor = if (deferred) SmsParser.parseAmountToMinor(valorPagar) else null,
                    cardIsOrigin = isAvance
                )
                return@let
            } ?: PAYMENT.matchEntire(s)?.let { m ->
                val (d, mon, y, total) = m.destructured
                val date = parseDate(d, mon, y) ?: return@let
                val amount = SmsParser.parseAmountToMinor(total) ?: return@let
                if (amount == 0L) return@let
                lines += ParsedStatementLine(
                    dateMillis = date,
                    amountMinor = amount,
                    type = TransactionType.TRANSFER,   // pago/abono → reduce deuda
                    rawDescription = "Pago/abono"
                )
                return@let
            } ?: OTHER_CHARGE.matchEntire(s)?.let { m ->
                val (d, mon, y, desc, _, valor) = m.destructured
                val date = parseDate(d, mon, y) ?: return@let
                val amount = SmsParser.parseAmountToMinor(valor) ?: return@let
                val descTrim = desc.trim()
                lines += ParsedStatementLine(
                    dateMillis = date,
                    amountMinor = amount,
                    type = TransactionType.EXPENSE,
                    rawDescription = descTrim,
                    isFinancialCharge = financialKeywords.any { descTrim.uppercase().contains(it) }
                )
            }
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

    private fun parseDate(day: String, mon: String, year: String): Long? {
        val m = months[mon.uppercase()] ?: return null
        val d = day.toIntOrNull() ?: return null
        val y = year.toIntOrNull() ?: return null
        return runCatching {
            LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /** "Pago total ... $1,044,110" en la misma línea → deuda (negativa). */
    private fun findBalance(text: String): Long? {
        val m = Regex("""Pago total\s*\$\s?([\d.,]+)""").find(text) ?: return null
        return SmsParser.parseAmountToMinor(m.groupValues[1])?.let { -it }
    }

    /** "4283 **** **** 2812" → "2812". */
    private fun findLastFour(text: String): String? {
        val m = Regex("""\*{4}\s*\*{4}\s*(\d{4})""").find(text) ?: return null
        return m.groupValues[1]
    }
}
