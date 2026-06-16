package co.purrito.myfinances.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Card estándar de la app (inspector del mock): fondo surface, borde
 * 1px blanco al 3.5% (outline), radio 17dp (shapes.medium), sin
 * sombra. Las cards SIEMPRE llevan el borde — el contraste con el
 * fondo (#010306 vs #03080F) es sutil y el borde las delimita.
 *
 * @param color para cards tintadas (ej: header del detalle de cuenta).
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit
) {
    Surface(
        shape = shape,
        color = color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier
    ) {
        content()
    }
}
