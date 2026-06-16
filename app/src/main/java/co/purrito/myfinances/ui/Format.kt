package co.purrito.myfinances.ui

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/* =====================================================================
 * Utilidades de formato — compartidas por todas las pantallas.
 * El formateo SOLO existe en la capa de UI: por dentro el dinero
 * siempre viaja como Long en centavos.
 *
 * MONEDA: fija en es-CO (el formato del peso no depende del idioma
 * de la app). FECHAS: usan Locale.getDefault(), que cambia con el
 * idioma elegido. Los formatters de fecha se crean por llamada: la
 * activity se recrea al cambiar idioma, pero un formatter cacheado
 * en un top-level val del proceso quedaría con el locale viejo.
 * ===================================================================== */

private val copLocale = Locale("es", "CO")

private val copFormat: NumberFormat =
    NumberFormat.getCurrencyInstance(copLocale).apply {
        maximumFractionDigits = 0
    }

/** 198_500_000 (centavos) -> "$ 1.985.000" */
fun formatCop(amountMinor: Long): String = copFormat.format(amountMinor / 100.0)

/**
 * Versión compacta para espacios reducidos (centro de la dona):
 * 130_811_000_00 (centavos) -> "$1,3M" · 50_000_000 -> "$500K"
 */
fun formatCopCompact(amountMinor: Long): String {
    val pesos = amountMinor / 100.0
    return when {
        pesos >= 1_000_000 -> {
            val millions = String.format(copLocale, "%.1f", pesos / 1_000_000)
                .removeSuffix(",0")
            "\$${millions}M"
        }
        pesos >= 1_000 -> "\$${(pesos / 1_000).toLong()}K"
        else -> formatCop(amountMinor)
    }
}

/** Epoch millis -> "9 jun 2026" / "Jun 9, 2026" según idioma */
fun formatDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))

/** Epoch millis -> "7 jun 13:42" para las cards del inbox */
fun formatShortDateTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
        .format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.getDefault()))

/** YearMonth -> "junio 2026" / "June 2026" (textos en prosa) */
fun formatMonth(month: YearMonth): String =
    month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))

/**
 * YearMonth -> "jun 2026" / "Jun 2026" para los selectores de mes:
 * el nombre completo ("septiembre") desbordaba el ancho fijo del label.
 * En es algunos meses abrevian con punto ("sept.") — se quita.
 */
fun formatMonthShort(month: YearMonth): String =
    month.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()))
        .replace(".", "")
