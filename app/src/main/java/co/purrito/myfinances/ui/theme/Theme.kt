package co.purrito.myfinances.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/* =====================================================================
 * El tema sigue el modo del sistema: oscuro (el diseño de referencia,
 * "Deep Navy Dark") o claro (su contraparte derivada). Ambos esquemas
 * salen de la misma [AppColors], así Material3 y los tokens propios
 * (montos, chips, acentos) nunca se desincronizan.
 *
 * Sin dynamic color: pisaría la paleta del diseño con los colores del
 * wallpaper del usuario.
 * ===================================================================== */

private fun AppColors.toColorScheme(): ColorScheme {
    // primary azul (= --primary/--ring del CSS): focus de inputs,
    // selección del date picker. El acento rosa de ACCIÓN se aplica
    // directo donde corresponde (FAB, bottom nav).
    val base = if (isLight) lightColorScheme() else darkColorScheme()
    val scheme = base.copy(
        primary = primaryBlue,
        onPrimary = FabContent,
        primaryContainer = primaryBlue,
        onPrimaryContainer = FabContent,
        secondary = onSurfaceVariant,
        tertiary = primaryBlue,
        background = background,
        surface = surface,
        surfaceVariant = surfaceVariant,
        onBackground = onSurface,
        onSurface = onSurface,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
        error = expense
    )
    // Los contenedores por defecto de M3 claro son lavanda (date picker,
    // menús sin color explícito): se neutralizan a la gama de la app.
    // En oscuro se conservan los de siempre.
    return if (!isLight) scheme else scheme.copy(
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surfaceVariant,
        surfaceBright = surface,
        surfaceDim = surfaceVariant,
        secondaryContainer = surfaceVariant,
        onSecondaryContainer = onSurface,
        outlineVariant = outline
    )
}

private val DarkColorScheme = DarkAppColors.toColorScheme()
private val LightColorScheme = LightAppColors.toColorScheme()

/**
 * Springs de los componentes de Material3 (sheets, drags, etc.):
 * críticamente amortiguados (sin rebote) y con rigidez moderada-baja
 * para un settle suave y un poco más largo. El default de M3 es más
 * rígido y el final del movimiento se percibe brusco/"laggy".
 *
 *  - spatial: movimiento (apertura/cierre del sheet, posiciones)
 *  - effects: fades y colores (más rápidos: no deben arrastrarse)
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private object SmoothMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.95f, stiffness = 280f)

    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1f, stiffness = 550f)

    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.95f, stiffness = 180f)

    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1f, stiffness = 700f)

    // OJO: ModalBottomSheet usa DefaultSpatial para ABRIR pero
    // FastEffects para CERRAR (visto en el código de material3
    // 1.5.0-alpha08). Con el valor típico de "fast" (>1000) el cierre
    // era un latigazo; igualado al spatial (280) quedaba simétrico y
    // lento. Punto medio: cierre algo más ágil que la apertura.
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1f, stiffness = 400f)

    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1f, stiffness = 400f)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MyFinancesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalAppColors provides if (darkTheme) DarkAppColors else LightAppColors
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            motionScheme = SmoothMotionScheme,
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}
