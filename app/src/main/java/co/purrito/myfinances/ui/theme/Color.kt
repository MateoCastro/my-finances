package co.purrito.myfinances.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/* =====================================================================
 * Tokens de color — fuente de verdad del modo OSCURO: el archivo de
 * estilos CSS del diseño (tema "Deep Navy Dark", tokens oklch
 * convertidos a sRGB).
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
 *
 * El modo CLARO es la contraparte derivada (no hay mock): mismo tinte
 * navy en grises y texto, acentos un paso más oscuros para mantener
 * contraste sobre blanco (los vivos del oscuro se lavan en fondo
 * claro), y chips con texto tono 700 en lugar de 300.
 *
 * Las pantallas leen los tokens por los nombres de siempre
 * (IncomeGreen, AccentPink, ...): son getters @Composable que
 * resuelven la paleta activa vía [LocalAppColors].
 * ===================================================================== */

@Immutable
data class ChipColors(val container: Color, val content: Color)

@Immutable
data class AppColors(
    val isLight: Boolean,

    // Fondo y superficies — las cards se separan del fondo por tono,
    // no por sombra.
    val background: Color,
    val surface: Color,        // cards y bottom nav
    val surfaceVariant: Color, // surface-elevated: chips neutros, diálogos
    val onSurface: Color,      // texto principal
    val onSurfaceVariant: Color, // texto secundario (muted-foreground)
    val outline: Color,

    /** Token `accent` del mock: se usa con alpha (header de día). */
    val accent: Color,
    /** Borde de inputs con foco (preferencia del usuario sobre el ring azul). */
    val focusedBorder: Color,

    // Acentos: azul = `primary` del CSS (focus, selección de fechas);
    // rosa-rojo = acento de ACCIÓN (FAB, tab activa del bottom nav).
    val primaryBlue: Color,
    val accentPink: Color,

    // Semánticos de dinero — el monto es la información protagonista.
    val income: Color,
    val onIncome: Color,       // contenido sobre botón verde (Aprobar)
    val expense: Color,

    /** Ámbar de advertencias/pendientes: base (fondos, bordes) y texto. */
    val warning: Color,
    val warningText: Color,

    // Chips de día de semana en los headers del registro.
    val dayBadgeWeekday: ChipColors,
    val dayBadgeThursday: ChipColors,
    val dayBadgeFriday: ChipColors,
    val dayBadgeSaturday: ChipColors,
    val dayBadgeSunday: ChipColors,

    /** Chip neutro (transferencias, sin categoría). */
    val neutralChip: ChipColors,

    /** Paleta de chips por categoría (asignación estable por id). */
    val categoryChipPalette: List<ChipColors>,

    // Entradas de la pantalla "Más": ícono azul sobre cuadro tintado.
    val entryIconTint: Color,
    val entryIconBg: Color
)

// ---------------------------------------------------------------------
// Oscuro — el diseño de referencia (valores verificados contra el mock)
// ---------------------------------------------------------------------

val DarkAppColors = AppColors(
    isLight = false,
    background = Color(0xFF010306),
    surface = Color(0xFF03080F),
    surfaceVariant = Color(0xFF0A121C),
    onSurface = Color(0xFFEFF2F6),
    onSurfaceVariant = Color(0xFF6A7683),
    // Verificado contra el inspector del mock: oklab(≈1 0 0 / 0.035)
    // = blanco puro al 3.5%. (El 7% del CSS inicial se veía muy marcado.)
    outline = Color(0x09FFFFFF),
    // oklch(0.19 0.024 252): header de día = accent al 30%, un poco
    // más claro que el fondo de la pantalla
    accent = Color(0xFF0C141E),
    focusedBorder = Color(0x4DFFFFFF),
    primaryBlue = Color(0xFF4793FF),
    accentPink = Color(0xFFFF3567),
    income = Color(0xFF4BC957),
    onIncome = Color(0xFF010306),
    expense = Color(0xFFFF3567),
    warning = Color(0xFFF59E0B),
    warningText = Color(0xFFFBBF24),
    // Del mock (tailwind 600 al 80% + texto 100/200): lun-mié slate,
    // jue índigo, vie sky, sáb cian, dom rosa.
    dayBadgeWeekday = ChipColors(container = Color(0xCC475569), content = Color(0xFFE2E8F0)),
    dayBadgeThursday = ChipColors(container = Color(0xCC4F46E5), content = Color(0xFFE0E7FF)),
    dayBadgeFriday = ChipColors(container = Color(0xCC0284C7), content = Color(0xFFE0F2FE)),
    dayBadgeSaturday = ChipColors(container = Color(0xCC0891B2), content = Color(0xFFCFFAFE)),
    dayBadgeSunday = ChipColors(container = Color(0xCCE11D48), content = Color(0xFFFFE4E6)),
    neutralChip = ChipColors(container = Color(0x3364748B), content = Color(0xFFCBD5E1)),
    // Paleta del mock: tailwind color-500 al 20% de fondo + color-300 de texto
    categoryChipPalette = listOf(
        ChipColors(container = Color(0x33EF4444), content = Color(0xFFFCA5A5)), // red
        ChipColors(container = Color(0x33F59E0B), content = Color(0xFFFCD34D)), // amber
        ChipColors(container = Color(0x338B5CF6), content = Color(0xFFC4B5FD)), // violet
        ChipColors(container = Color(0x33EAB308), content = Color(0xFFFDE047)), // yellow
        ChipColors(container = Color(0x3310B981), content = Color(0xFF6EE7B7)), // emerald
        ChipColors(container = Color(0x33F97316), content = Color(0xFFFDBA74)), // orange
        ChipColors(container = Color(0x3322C55E), content = Color(0xFF86EFAC)), // green
        ChipColors(container = Color(0x333B82F6), content = Color(0xFF93C5FD))  // blue
    ),
    entryIconTint = Color(0xFF7FB1F2),
    entryIconBg = Color(0xFF1C2C45)
)

// ---------------------------------------------------------------------
// Claro — gris azulado frío de fondo, cards blancas, texto navy.
// ---------------------------------------------------------------------

val LightAppColors = AppColors(
    isLight = true,
    background = Color(0xFFF2F4F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8ECF2),
    onSurface = Color(0xFF0E1621),
    onSurfaceVariant = Color(0xFF5F6B79),
    outline = Color(0x140E1621),       // navy al 8%
    accent = Color(0xFFDCE3EC),
    focusedBorder = Color(0x660E1621),
    primaryBlue = Color(0xFF2F74E6),
    accentPink = Color(0xFFE5285A),
    // Verde/rojo un paso más oscuros que en oscuro: ≥4.5:1 sobre blanco
    income = Color(0xFF16883E),
    onIncome = Color(0xFFFFFFFF),
    expense = Color(0xFFE11D48),
    warning = Color(0xFFF59E0B),
    warningText = Color(0xFFB45309),
    // Mismos tonos del oscuro, sólidos (al 80% se lavan sobre blanco)
    dayBadgeWeekday = ChipColors(container = Color(0xFF64748B), content = Color(0xFFFFFFFF)),
    dayBadgeThursday = ChipColors(container = Color(0xFF4F46E5), content = Color(0xFFFFFFFF)),
    dayBadgeFriday = ChipColors(container = Color(0xFF0284C7), content = Color(0xFFFFFFFF)),
    dayBadgeSaturday = ChipColors(container = Color(0xFF0891B2), content = Color(0xFFFFFFFF)),
    dayBadgeSunday = ChipColors(container = Color(0xFFE11D48), content = Color(0xFFFFFFFF)),
    neutralChip = ChipColors(container = Color(0x2464748B), content = Color(0xFF475569)),
    // Tailwind color-500 al 15% de fondo + color-700 de texto
    categoryChipPalette = listOf(
        ChipColors(container = Color(0x26EF4444), content = Color(0xFFB91C1C)), // red
        ChipColors(container = Color(0x26F59E0B), content = Color(0xFFB45309)), // amber
        ChipColors(container = Color(0x268B5CF6), content = Color(0xFF6D28D9)), // violet
        ChipColors(container = Color(0x26EAB308), content = Color(0xFFA16207)), // yellow
        ChipColors(container = Color(0x2610B981), content = Color(0xFF047857)), // emerald
        ChipColors(container = Color(0x26F97316), content = Color(0xFFC2410C)), // orange
        ChipColors(container = Color(0x2622C55E), content = Color(0xFF15803D)), // green
        ChipColors(container = Color(0x263B82F6), content = Color(0xFF1D4ED8))  // blue
    ),
    entryIconTint = Color(0xFF2F6FD0),
    entryIconBg = Color(0xFFDCE8FA)
)

/** Paleta activa; la provee [MyFinancesTheme] según el modo del sistema. */
val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

/** Acceso corto a la paleta activa. */
val AppTheme: AppColors
    @Composable @ReadOnlyComposable get() = LocalAppColors.current

// ---------------------------------------------------------------------
// Nombres históricos de los tokens (usados en todas las pantallas).
// ---------------------------------------------------------------------

val Accent: Color @Composable @ReadOnlyComposable get() = AppTheme.accent
val FocusedBorder: Color @Composable @ReadOnlyComposable get() = AppTheme.focusedBorder

val PrimaryBlue: Color @Composable @ReadOnlyComposable get() = AppTheme.primaryBlue
val AccentPink: Color @Composable @ReadOnlyComposable get() = AppTheme.accentPink

/** FAB de agregar: acento con ícono blanco. */
val FabContainer: Color @Composable @ReadOnlyComposable get() = AppTheme.accentPink
val FabContent = Color(0xFFFFFFFF)

/** Ingresos: verde (token `income`). */
val IncomeGreen: Color @Composable @ReadOnlyComposable get() = AppTheme.income

/** Gastos: rojo rosado (token `expense`). */
val ExpenseRed: Color @Composable @ReadOnlyComposable get() = AppTheme.expense

val DayBadgeWeekday: ChipColors @Composable @ReadOnlyComposable get() = AppTheme.dayBadgeWeekday
val DayBadgeThursday: ChipColors @Composable @ReadOnlyComposable get() = AppTheme.dayBadgeThursday
val DayBadgeFriday: ChipColors @Composable @ReadOnlyComposable get() = AppTheme.dayBadgeFriday
val DayBadgeSaturday: ChipColors @Composable @ReadOnlyComposable get() = AppTheme.dayBadgeSaturday
val DayBadgeSunday: ChipColors @Composable @ReadOnlyComposable get() = AppTheme.dayBadgeSunday

val NeutralChip: ChipColors @Composable @ReadOnlyComposable get() = AppTheme.neutralChip

// ---------------------------------------------------------------------
// Chips de categoría: pill con contenedor tintado y texto del mismo
// tono. La paleta se asigna por categoría (estable por id).
// ---------------------------------------------------------------------

/** Asignación estable de chip por categoría (paleta por id). */
@Composable
@ReadOnlyComposable
fun chipColorsFor(categoryId: Long?): ChipColors {
    val palette = AppTheme.categoryChipPalette
    return palette[((categoryId ?: 0L) % palette.size).toInt()]
}

/**
 * Si la categoría tiene color configurado por el usuario (colorArgb),
 * se deriva el par chip de él; si no, cae a la paleta por id. En modo
 * claro el texto se oscurece: los colores elegibles son tonos vivos
 * pensados para fondo oscuro y sobre blanco quedarían ilegibles.
 */
@Composable
@ReadOnlyComposable
fun chipColorsFor(categoryId: Long?, colorArgb: Int?): ChipColors =
    colorArgb?.let { argb ->
        val color = Color(argb)
        val content = if (AppTheme.isLight) lerp(color, Color.Black, 0.25f) else color
        ChipColors(container = color.copy(alpha = 0.2f), content = content)
    } ?: chipColorsFor(categoryId)
