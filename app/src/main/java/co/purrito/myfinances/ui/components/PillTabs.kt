package co.purrito.myfinances.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import co.purrito.myfinances.ui.theme.AccentPink
import co.purrito.myfinances.ui.theme.Motion

/**
 * Selector tipo pill del diseño: contenedor oscuro redondeado con UNA
 * píldora rosa que se DESLIZA hasta la opción activa. Sin ripple: el
 * feedback es el propio movimiento de la píldora. Lo usan el modal de
 * transacción (Gasto/Ingreso/Transf.), el inbox y el selector de
 * idioma.
 */
@Composable
fun PillTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // Color de la píldora activa. Por defecto rosa; los selectores de
    // tipo (Gasto/Ingreso/Transf.) pasan el color según el tipo activo
    // (igual que el color del monto en el form).
    activeColor: Color = AccentPink
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
            .padding(4.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val optionWidth = maxWidth / options.size
            val pillOffset by animateDpAsState(
                targetValue = optionWidth * selectedIndex,
                animationSpec = tween(Motion.Fast),
                label = "pillOffset"
            )
            val pillColor by animateColorAsState(
                targetValue = activeColor,
                animationSpec = tween(Motion.Fast),
                label = "pillColor"
            )

            // La píldora activa: única, deslizándose entre opciones
            Box(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .offset(x = pillOffset)
                        .width(optionWidth)
                        .fillMaxHeight()
                        .background(pillColor, RoundedCornerShape(50))
                )
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    val textColor by animateColorAsState(
                        targetValue = if (selected) Color.White
                                      else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(Motion.Fast),
                        label = "pillText"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSelect(index) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleSmall,
                            color = textColor
                        )
                    }
                }
            }
        }
    }
}
