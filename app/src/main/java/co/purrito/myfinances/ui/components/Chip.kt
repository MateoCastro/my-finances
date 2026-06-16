package co.purrito.myfinances.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.purrito.myfinances.ui.theme.ChipColors

/**
 * Pill de la app: categoría en el registro, día de semana en los
 * headers, estado en el inbox. Contenedor tintado + texto del tono
 * (pares en [ChipColors] / `chipColorsFor`). Pill completo por
 * defecto (rounded-full del mock); el badge de día usa 6dp.
 *
 * @param border opcional (ej: el pill "pending" del inbox lo lleva).
 * @param contentPadding ajustable para pills más grandes/chicos.
 */
@Composable
fun Chip(
    text: String,
    colors: ChipColors,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(50),
    fontWeight: FontWeight = FontWeight.SemiBold,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 7.dp, vertical = 2.dp)
) {
    Box(
        modifier = modifier
            .background(colors.container, shape)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = fontWeight,
            color = colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
