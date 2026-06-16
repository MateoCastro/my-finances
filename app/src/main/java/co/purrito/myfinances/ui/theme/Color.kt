package co.purrito.myfinances.ui.theme

import androidx.compose.ui.graphics.Color

/* =====================================================================
 * Tokens de color — fuente de verdad: el archivo de estilos CSS del
 * diseño (tema "Deep Navy Dark", tokens oklch convertidos a sRGB).
 *
 *   background        oklch(0.09 0.018 252) → #010306
 *   card/surface      oklch(0.13 0.02  252) → #03080F
 *   surface-elevated  oklch(0.18 0.024 252) → #0A121C
 *   foreground        oklch(0.96 0.006 248) → #EFF2F6
 *   muted-foreground  oklch(0.56 0.025 250) → #6A7683
 *   border            white al 7%           → #FFFFFF @ 0x12
 *   primary           oklch(0.67 0.18  258) → #4793FF (azul: focus/ring)
 *   income            oklch(0.74 0.19  145) → #4BC957
 *   expense           oklch(0.67 0.24   14) → #FF3567
 * ===================================================================== */

// ---------------------------------------------------------------------
// Fondo y superficies — navy casi negro; las cards se separan del
// fondo por tono, no por sombra.
// ---------------------------------------------------------------------

val DarkBackground = Color(0xFF010306)
val DarkSurface = Color(0xFF03080F)        // cards y bottom nav
val DarkSurfaceVariant = Color(0xFF0A121C) // surface-elevated: chips neutros
val DarkOnSurface = Color(0xFFEFF2F6)      // texto principal
val DarkOnSurfaceVariant = Color(0xFF6A7683) // texto secundario (muted-foreground)
// Verificado contra el inspector del mock: oklab(≈1 0 0 / 0.035)
// = blanco puro al 3.5%. (El 7% del CSS inicial se veía muy marcado.)
val DarkOutline = Color(0x09FFFFFF)        // border: blanco al 3.5%

/**
 * Token `accent` del mock: oklch(0.19 0.024 252) = #0C141E. Se usa
 * con alpha (ej: header de día = accent al 30%, un poco más claro
 * que el fondo de la pantalla).
 */
val Accent = Color(0xFF0C141E)

/**
 * Borde de inputs con foco: blanco suave (preferencia del usuario
 * sobre el ring azul primary/50 del mock).
 */
val FocusedBorder = Color(0x4DFFFFFF)

// ---------------------------------------------------------------------
// Acentos. El azul es el `primary` del CSS (focus, selección de
// fechas); el rosa-rojo es el acento de ACCIÓN del mock (FAB, tab
// activa del bottom nav), misma familia que expense/destructive.
// ---------------------------------------------------------------------

val PrimaryBlue = Color(0xFF4793FF)
val AccentPink = Color(0xFFFF3567)

/** FAB de agregar: acento con ícono blanco. */
val FabContainer = AccentPink
val FabContent = Color(0xFFFFFFFF)

// ---------------------------------------------------------------------
// Semánticos de dinero — el monto es la información protagonista.
// ---------------------------------------------------------------------

/** Ingresos: verde (token `income`). */
val IncomeGreen = Color(0xFF4BC957)

/** Gastos: rojo rosado (token `expense`). */
val ExpenseRed = Color(0xFFFF3567)

// ---------------------------------------------------------------------
// Chips de día de semana en los headers del registro.
// ---------------------------------------------------------------------

// Del mock (tailwind 600 al 80% + texto 100/200): lun-mié slate,
// jue índigo, vie sky, sáb cian, dom rosa.
val DayBadgeWeekday = ChipColors(container = Color(0xCC475569), content = Color(0xFFE2E8F0))
val DayBadgeThursday = ChipColors(container = Color(0xCC4F46E5), content = Color(0xFFE0E7FF))
val DayBadgeFriday = ChipColors(container = Color(0xCC0284C7), content = Color(0xFFE0F2FE))
val DayBadgeSaturday = ChipColors(container = Color(0xCC0891B2), content = Color(0xFFCFFAFE))
val DayBadgeSunday = ChipColors(container = Color(0xCCE11D48), content = Color(0xFFFFE4E6))

/** Chip neutro (transferencias, sin categoría): slate del mock. */
val NeutralChip = ChipColors(container = Color(0x3364748B), content = Color(0xFFCBD5E1))

// ---------------------------------------------------------------------
// Chips de categoría: pill con contenedor oscuro tintado y texto del
// mismo tono claro. La paleta se asigna por categoría (estable por id).
// ---------------------------------------------------------------------

data class ChipColors(val container: Color, val content: Color)

// Paleta del mock: tailwind color-500 al 20% de fondo + color-300 de texto
val CategoryChipPalette = listOf(
    ChipColors(container = Color(0x33EF4444), content = Color(0xFFFCA5A5)), // red
    ChipColors(container = Color(0x33F59E0B), content = Color(0xFFFCD34D)), // amber
    ChipColors(container = Color(0x338B5CF6), content = Color(0xFFC4B5FD)), // violet
    ChipColors(container = Color(0x33EAB308), content = Color(0xFFFDE047)), // yellow
    ChipColors(container = Color(0x3310B981), content = Color(0xFF6EE7B7)), // emerald
    ChipColors(container = Color(0x33F97316), content = Color(0xFFFDBA74)), // orange
    ChipColors(container = Color(0x3322C55E), content = Color(0xFF86EFAC)), // green
    ChipColors(container = Color(0x333B82F6), content = Color(0xFF93C5FD))  // blue
)

/** Asignación estable de chip por categoría (paleta por id). */
fun chipColorsFor(categoryId: Long?): ChipColors =
    CategoryChipPalette[((categoryId ?: 0L) % CategoryChipPalette.size).toInt()]

/**
 * Si la categoría tiene color configurado por el usuario (colorArgb),
 * se deriva el par chip de él; si no, cae a la paleta por id.
 */
fun chipColorsFor(categoryId: Long?, colorArgb: Int?): ChipColors =
    colorArgb?.let { argb ->
        val color = Color(argb)
        ChipColors(container = color.copy(alpha = 0.2f), content = color)
    } ?: chipColorsFor(categoryId)
