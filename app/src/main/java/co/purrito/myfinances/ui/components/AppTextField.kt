package co.purrito.myfinances.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.purrito.myfinances.ui.theme.FocusedBorder
import co.purrito.myfinances.ui.theme.Motion

/* =====================================================================
 * Sistema de inputs del mock:
 *   bg-background · border 1px (outline) · rounded-xl (medium) ·
 *   px-3 py-2.5 · texto 12sp · placeholder muted al 60%
 * Focus: borde blanco suave (NO el ring azul grueso por defecto),
 * con transición de color.
 * ===================================================================== */

/** Label pequeño encima de un campo (mock: 10sp medium muted, mb-1). */
@Composable
fun FieldLabel(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    // Mayúscula inicial por defecto (descripciones, nombres, búsqueda);
    // los campos numéricos pasan sus propias KeyboardOptions y no aplica
    keyboardOptions: KeyboardOptions =
        KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    focusRequester: FocusRequester? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    // Solo presentación (ej: separador de miles en montos); el valor
    // del campo no cambia
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        targetValue = if (focused) FocusedBorder else MaterialTheme.colorScheme.outline,
        animationSpec = tween(Motion.Fast),
        label = "fieldBorder"
    )

    var fieldModifier = modifier
    if (focusRequester != null) fieldModifier = fieldModifier.focusRequester(focusRequester)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = fieldModifier,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        textStyle = fieldTextStyle(),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
        decorationBox = { innerTextField ->
            FieldDecoration(
                showPlaceholder = value.isEmpty(),
                placeholder = placeholder,
                borderColor = borderColor,
                leading = leading,
                trailing = trailing,
                innerTextField = innerTextField
            )
        }
    )
}

/**
 * Variante con [TextFieldValue] para cuando hay que controlar la
 * SELECCIÓN además del texto (ej: autocompletar y dejar el cursor al
 * final). Misma apariencia que la variante de String.
 */
@Composable
fun AppTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions =
        KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    focusRequester: FocusRequester? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        targetValue = if (focused) FocusedBorder else MaterialTheme.colorScheme.outline,
        animationSpec = tween(Motion.Fast),
        label = "fieldBorder"
    )

    var fieldModifier = modifier
    if (focusRequester != null) fieldModifier = fieldModifier.focusRequester(focusRequester)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = fieldModifier,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        interactionSource = interactionSource,
        textStyle = fieldTextStyle(),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
        decorationBox = { innerTextField ->
            FieldDecoration(
                showPlaceholder = value.text.isEmpty(),
                placeholder = placeholder,
                borderColor = borderColor,
                leading = leading,
                trailing = trailing,
                innerTextField = innerTextField
            )
        }
    )
}

@Composable
private fun FieldDecoration(
    showPlaceholder: Boolean,
    placeholder: String?,
    borderColor: androidx.compose.ui.graphics.Color,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    innerTextField: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background, MaterialTheme.shapes.medium)
            .border(1.dp, borderColor, MaterialTheme.shapes.medium)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(8.dp))
        }
        Box(modifier = Modifier.weight(1f)) {
            if (showPlaceholder && placeholder != null) {
                Text(
                    placeholder,
                    style = fieldTextStyle(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            innerTextField()
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}

/**
 * Campo con la MISMA apariencia que [AppTextField] pero que abre algo
 * al tocarlo (fecha → date picker, dropdown → menú) en lugar de
 * recibir texto.
 */
@Composable
fun ClickableField(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaceholder: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    // Al tocar este campo (fecha/dropdown) se limpia el foco del campo
    // de texto activo: así, al cerrar el menú/diálogo, el foco NO vuelve
    // al texto y el teclado no se reabre solo.
    val focusManager = LocalFocusManager.current
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    focusManager.clearFocus()
                    onClick()
                }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = fieldTextStyle(
                color = if (isPlaceholder)
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.onSurface
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
private fun fieldTextStyle(
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
): TextStyle = MaterialTheme.typography.bodyMedium.copy(
    fontSize = 12.sp,
    lineHeight = 16.sp,
    color = color
)
