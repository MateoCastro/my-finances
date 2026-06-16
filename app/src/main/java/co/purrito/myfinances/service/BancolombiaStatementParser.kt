package co.purrito.myfinances.service

import co.purrito.myfinances.data.model.TransactionType
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/* =====================================================================
 * Parser del extracto de TC Bancolombia (Hito 4) — LÓGICA PURA.
 *
 * Recibe las filas ya extraídas del .xlsx (la hoja PESOS) como
 * List<List<String>> y produce un ParsedStatement neutral. La lectura
 * del .xlsx en sí (zip + XML) vive aparte, en la capa Android, para que
 * esta lógica sea testeable con JUnit contra extractos reales.
 *
 * Estructura observada (extracto real, may 2026):
 *  - Cabecera: cliente, "Información de la Tarjeta" (****1659),
 *    "Pago total" (= deuda del periodo), periodo facturado.
 *  - Sección "Movimientos durante el periodo" con header:
 *      Nº autorización | Fecha | Movimientos | Valor Movimiento |
 *      Número de cuotas | Valor cuota/abono | Int. mensual | Int. anual |
 *      Saldo pendiente
 *  - Sección "Movimientos antes del periodo" (mismas columnas): cuotas
 *    de compras diferidas de meses anteriores que se siguen facturando.
 *
 * Reglas:
 *  - Montos: formato mixto col/us → SmsParser.parseAmountToMinor.
 *  - Valor negativo (empieza con '-') = abono/pago → TRANSFER.
 *  - "x/y" con y>1 = cuota de diferido; "1/1" = compra de contado.
 *  - Cargos del banco (intereses, manejo, seguros): sin autorización ni
 *    cuotas, valor positivo → isFinancialCharge.
 * ===================================================================== */

data class ParsedStatement(
    val lines: List<ParsedStatementLine>,
    val statementBalanceMinor: Long?,   // deuda del periodo (NEGATIVA)
    val periodFromMillis: Long?,        // min fecha de movimientos
    val periodToMillis: Long?,          // max fecha de movimientos
    val lastFourDigits: String?
)

object BancolombiaStatementParser {

    private val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val zone: ZoneId = ZoneId.systemDefault()

    // Cargos del banco (no son compras del usuario): preclasificables.
    private val financialKeywords = listOf(
        "INTERES", "INTERESES", "CUOTA DE MANEJO", "MANEJO", "SEGURO",
        "COMISION", "COMISIÓN", "MORA", "SOBREGIRO"
    )

    private const val COL_AUTH = 0
    private const val COL_DATE = 1
    private const val COL_DESC = 2
    private const val COL_VALUE = 3
    private const val COL_INSTALLMENTS = 4
    private const val COL_INSTALLMENT_VALUE = 5

    /**
     * Elige la hoja del libro a parsear: la de "Moneda: PESOS" (la de
     * DÓLARES suele venir vacía). Si no la identifica por moneda, cae a
     * la hoja que produzca más movimientos.
     */
    fun pickStatementSheet(sheets: List<List<List<String>>>): List<List<String>>? {
        if (sheets.isEmpty()) return null
        sheets.firstOrNull { rows ->
            rows.any { r ->
                r.getOrNull(0)?.trim().equals("Moneda:", ignoreCase = true) &&
                    r.getOrNull(1)?.trim().equals("PESOS", ignoreCase = true)
            }
        }?.let { return it }
        return sheets.maxByOrNull { parse(it).lines.size }
    }

    fun parse(rows: List<List<String>>): ParsedStatement {
        val lines = mutableListOf<ParsedStatementLine>()

        for (row in rows) {
            val dateStr = row.getOrNull(COL_DATE)?.trim().orEmpty()
            val date = parseDate(dateStr) ?: continue  // las filas sin fecha válida no son movimientos
            val valueRaw = row.getOrNull(COL_VALUE)?.trim().orEmpty()
            val amount = SmsParser.parseAmountToMinor(valueRaw) ?: continue
            if (amount == 0L) continue

            val desc = row.getOrNull(COL_DESC)?.trim().orEmpty()
            val auth = row.getOrNull(COL_AUTH)?.trim().orEmpty()
            val isPayment = valueRaw.startsWith("-")

            val (instCurrent, instTotal) = parseInstallments(row.getOrNull(COL_INSTALLMENTS))
            val instValue = row.getOrNull(COL_INSTALLMENT_VALUE)?.trim()
                ?.let { SmsParser.parseAmountToMinor(it) }

            // Avance = transferencia con la TC de ORIGEN (sale deuda).
            val isAvance = !isPayment && desc.uppercase().contains("AVANCE")
            val isCharge = !isPayment && !isAvance && instTotal == null && auth.isBlank() &&
                financialKeywords.any { desc.uppercase().contains(it) }

            // pago (negativo) = TC destino; avance = TC origen; resto compra
            val type = if (isPayment || isAvance) TransactionType.TRANSFER
                       else TransactionType.EXPENSE

            lines += ParsedStatementLine(
                dateMillis = date,
                amountMinor = amount,
                type = type,
                rawDescription = desc,
                installmentCurrent = instCurrent,
                installmentTotal = instTotal,
                isFinancialCharge = isCharge,
                installmentAmountMinor = if (instTotal != null) instValue else null,
                cardIsOrigin = isAvance
            )
        }

        val dates = lines.map { it.dateMillis }
        return ParsedStatement(
            lines = lines,
            statementBalanceMinor = findBalance(rows),
            periodFromMillis = dates.minOrNull(),
            periodToMillis = dates.maxOrNull(),
            lastFourDigits = findLastFour(rows)
        )
    }

    private fun parseDate(s: String): Long? = runCatching {
        LocalDate.parse(s, dateFmt).atStartOfDay(zone).toInstant().toEpochMilli()
    }.getOrNull()

    /** "1/4" → (1,4). "1/1" → (null,null) [contado]. blanco → (null,null). */
    private fun parseInstallments(raw: String?): Pair<Int?, Int?> {
        val parts = raw?.trim()?.split("/") ?: return null to null
        if (parts.size != 2) return null to null
        val current = parts[0].trim().toIntOrNull() ?: return null to null
        val total = parts[1].trim().toIntOrNull() ?: return null to null
        return if (total > 1) current to total else null to null
    }

    /** Deuda del periodo = "Pago total" (positiva en el extracto → negativa = deuda). */
    private fun findBalance(rows: List<List<String>>): Long? {
        val row = rows.firstOrNull { it.getOrNull(0)?.trim().equals("Pago total", ignoreCase = true) }
            ?: return null
        val raw = row.getOrNull(1)?.trim() ?: return null
        val magnitude = SmsParser.parseAmountToMinor(raw) ?: return null
        return -magnitude
    }

    /** "************1659" → "1659". */
    private fun findLastFour(rows: List<List<String>>): String? {
        val row = rows.firstOrNull {
            it.getOrNull(0)?.trim().equals("Información de la Tarjeta", ignoreCase = true)
        } ?: return null
        val masked = row.getOrNull(1)?.trim()?.filter { it.isDigit() } ?: return null
        return masked.takeLast(4).ifBlank { null }
    }
}
