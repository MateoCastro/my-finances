package co.purrito.myfinances.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide

/**
 * Dropdown de la app con el estilo de inputs del mock: label pequeño
 * encima + campo compacto con chevron, menú en surface. Para incluir
 * una opción "ninguna", usar T nullable: `listOf(null) + opciones`.
 */
@Composable
fun <T> DropdownField(
    label: String,
    options: List<T>,
    selectedLabel: String,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        FieldLabel(label)
        Box {
            ClickableField(
                text = selectedLabel,
                isPlaceholder = selectedLabel.isEmpty(),
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                trailing = {
                    Icon(
                        Lucide.ChevronDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = MaterialTheme.shapes.medium,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                // Tope: con muchas categorías el menú scrollea en lugar
                // de desbordar la pantalla
                modifier = Modifier.heightIn(max = 280.dp)
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                optionLabel(option),
                                fontSize = 12.sp
                            )
                        },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
