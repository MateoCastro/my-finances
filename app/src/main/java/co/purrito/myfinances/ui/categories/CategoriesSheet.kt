package co.purrito.myfinances.ui.categories

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.SheetValue
import androidx.compose.ui.graphics.graphicsLayer
import co.purrito.myfinances.ui.components.AppTextField
import co.purrito.myfinances.ui.theme.Motion
import co.purrito.myfinances.R
import co.purrito.myfinances.data.model.Category
import co.purrito.myfinances.ui.components.Chip
import co.purrito.myfinances.ui.theme.ChipColors
import co.purrito.myfinances.ui.theme.ExpenseRed
import co.purrito.myfinances.ui.theme.IncomeGreen
import co.purrito.myfinances.ui.theme.PrimaryBlue
import co.purrito.myfinances.ui.theme.chipColorsFor
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X

/* =====================================================================
 * Categorías como MODAL (mock) — se abre desde la tab "Más":
 *
 *  ╭──────────────────────────────────╮
 *  │ ✕  Categorías          [+ Nueva] │
 *  │ (Gastos) (Ingresos)  N categorías│
 *  │ ╭──────────────────────────────╮ │  banner inline al eliminar:
 *  │ │ ¿Eliminar "Food"? [Canc][Del]│ │  borde rojo, sin diálogo
 *  │ ╰──────────────────────────────╯ │
 *  │ ╭──────────────────────────────╮ │
 *  │ │ Comida          ● ✎ 🗑        │ │  dot de color + acciones con
 *  │ │ [Gasto]                      │ │  estado pressed (mock 24/25)
 *  │ ╰──────────────────────────────╯ │
 *  ╰──────────────────────────────────╯
 *
 * El editor es un segundo sheet: NOMBRE + TIPO (Gasto/Ingreso) +
 * COLOR (paleta con check). El color se persiste en colorArgb y se
 * refleja en chips del registro, stats e inbox.
 * Eliminar sigue siendo soft-delete (estilo Money Manager).
 * ===================================================================== */

/** Paleta del selector de color (mock: 2 filas de 8). */
private val colorPalette = listOf(
    Color(0xFFEF4444), Color(0xFFF97316), Color(0xFFF59E0B), Color(0xFFEAB308),
    Color(0xFF22C55E), Color(0xFF10B981), Color(0xFF14B8A6), Color(0xFF06B6D4),
    Color(0xFF3B82F6), Color(0xFF8B5CF6), Color(0xFFA855F7), Color(0xFFEC4899),
    Color(0xFFFB923C), Color(0xFF94A3B8), Color(0xFF6B7280), Color(0xFF1D4ED8)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesSheet(
    onDismiss: () -> Unit,
    viewModel: CategoriesViewModel = viewModel()
) {
    val categories by viewModel.categories.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Cierre animado: hide() primero, desmontar después (ver
    // AddTransactionSheet para el porqué).
    val animatedDismiss: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    // Fade del contenido sincronizado con el desplazamiento del sheet
    // (la misma combinación slide+fade de las transiciones de pantalla):
    // targetValue pasa a Hidden apenas inicia el cierre, por cualquier
    // vía (backdrop, X, guardar), así el fade acompaña todo el recorrido.
    val contentAlpha by animateFloatAsState(
        targetValue = if (sheetState.targetValue == SheetValue.Hidden) 0f else 1f,
        animationSpec = tween(Motion.Fast),
        label = "sheetContentFade"
    )

    var tabIndex by rememberSaveable { mutableStateOf(0) } // 0 = gastos, 1 = ingresos
    val showIncome = tabIndex == 1
    val visible = categories.filter { it.isIncome == showIncome }

    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }

    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) } // null = nueva

    ModalBottomSheet(
        onDismissRequest = animatedDismiss,
        sheetState = sheetState,
        // Fondo del inspector: lab(0.643...) = #000306 ≈ background,
        // más oscuro que surface: así las cards contrastan
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = { WindowInsets(0) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = contentAlpha }
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: cerrar + título + Nueva
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = animatedDismiss) {
                    Icon(
                        Lucide.X,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(stringResource(R.string.categories_title), style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        editingId = null
                        editorOpen = true
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryBlue,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        Lucide.Plus,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(" " + stringResource(R.string.new_label), style = MaterialTheme.typography.titleSmall)
                }
            }

            // Filtros Gastos/Ingresos + contador
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterPill(stringResource(R.string.expenses), selected = tabIndex == 0) { tabIndex = 0 }
                FilterPill(stringResource(R.string.income), selected = tabIndex == 1) { tabIndex = 1 }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    pluralStringResource(R.plurals.categories_count, visible.size, visible.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (visible.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (showIncome) stringResource(R.string.no_income_categories)
                        else stringResource(R.string.no_expense_categories),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visible, key = { it.id }) { category ->
                        // Al tocar eliminar, la PROPIA card transiciona a
                        // su estado de confirmación (borde rojo), in-place
                        CategoryCard(
                            category = category,
                            deleting = category.id == deletingId,
                            onEdit = {
                                editingId = category.id
                                editorOpen = true
                            },
                            onDelete = { deletingId = category.id },
                            onCancelDelete = { deletingId = null },
                            onConfirmDelete = {
                                viewModel.delete(category)
                                deletingId = null
                            }
                        )
                    }
                }
            }
        }
    }

    if (editorOpen) {
        CategoryEditorSheet(
            existing = categories.firstOrNull { it.id == editingId },
            defaultIsIncome = showIncome,
            onSave = { name, isIncome, colorArgb ->
                viewModel.save(
                    existing = categories.firstOrNull { it.id == editingId },
                    name = name,
                    isIncome = isIncome,
                    colorArgb = colorArgb
                )
                editorOpen = false
            },
            onDismiss = { editorOpen = false }
        )
    }
}

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    color: Color = PrimaryBlue,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (selected) color else MaterialTheme.colorScheme.outline,
        animationSpec = tween(Motion.Fast),
        label = "filterPillBorder"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(Motion.Fast),
        label = "filterPillText"
    )
    Box(
        modifier = Modifier
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = textColor)
    }
}

/**
 * Altura fija del contenido de la card en AMBOS estados (normal y
 * confirmar eliminación): así el cambio de estado no produce layout
 * shifting — la card nunca cambia de tamaño.
 */
private val categoryCardContentHeight = 62.dp

/**
 * Card de categoría con dos estados in-place animados:
 *  - normal: nombre + chip de tipo | dot de color, editar, eliminar
 *  - eliminando: "¿Eliminar X?" + Cancelar/Eliminar compactos, borde
 *    rojo (ExpenseRed al 40%, verificado contra el inspector del mock)
 */
@Composable
private fun CategoryCard(
    category: Category,
    deleting: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (deleting) ExpenseRed.copy(alpha = 0.4f)
                      else MaterialTheme.colorScheme.outline,
        animationSpec = tween(Motion.Fast),
        label = "categoryCardBorder"
    )

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, MaterialTheme.shapes.large)
    ) {
        AnimatedContent(
            targetState = deleting,
            transitionSpec = {
                fadeIn(tween(Motion.Fast)) togetherWith fadeOut(tween(Motion.Fast))
            },
            label = "categoryCardContent"
        ) { isDeleting ->
            if (isDeleting) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(categoryCardContentHeight)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.delete_named_title, category.name),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    CompactButton(
                        text = stringResource(R.string.cancel),
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onCancelDelete
                    )
                    CompactButton(
                        text = stringResource(R.string.delete),
                        container = ExpenseRed,
                        content = Color.White,
                        onClick = onConfirmDelete
                    )
                }
            } else {
                val colors = chipColorsFor(category.id, category.colorArgb)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(categoryCardContentHeight)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(category.name, style = MaterialTheme.typography.bodyMedium)
                        Chip(
                            text = stringResource(
                                if (category.isIncome) R.string.income_type
                                else R.string.expense_type
                            ),
                            colors = colors
                        )
                    }
                    // Dot del color de la categoría
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(colors.content, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    PressableIconButton(
                        icon = Lucide.Pencil,
                        contentDescription = stringResource(R.string.edit),
                        pressedTint = MaterialTheme.colorScheme.onSurface,
                        pressedBackground = MaterialTheme.colorScheme.surfaceVariant,
                        onClick = onEdit
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    PressableIconButton(
                        icon = Lucide.Trash2,
                        contentDescription = stringResource(R.string.delete),
                        pressedTint = ExpenseRed,
                        pressedBackground = ExpenseRed.copy(alpha = 0.2f),
                        onClick = onDelete
                    )
                }
            }
        }
    }
}

/**
 * Botón compacto del estado de eliminación (inspector del mock:
 * texto 11px bold, padding 6/12, pill, sin altura mínima de M3).
 */
@Composable
private fun CompactButton(
    text: String,
    container: Color,
    content: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .background(container, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = content
        )
    }
}

/**
 * Botón de ícono con estado "pressed" del mock: al mantenerlo
 * presionado aparece un círculo de fondo y el ícono toma el tinte
 * de la acción (rojo para eliminar).
 */
@Composable
private fun PressableIconButton(
    icon: ImageVector,
    contentDescription: String,
    pressedTint: Color,
    pressedBackground: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = Modifier
            .size(30.dp)
            .background(
                if (pressed) pressedBackground else Color.Transparent,
                CircleShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (pressed) pressedTint else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryEditorSheet(
    existing: Category?,
    defaultIsIncome: Boolean,
    onSave: (name: String, isIncome: Boolean, colorArgb: Int?) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val animatedDismiss: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    // Fade del contenido sincronizado con el desplazamiento del sheet
    // (la misma combinación slide+fade de las transiciones de pantalla):
    // targetValue pasa a Hidden apenas inicia el cierre, por cualquier
    // vía (backdrop, X, guardar), así el fade acompaña todo el recorrido.
    val contentAlpha by animateFloatAsState(
        targetValue = if (sheetState.targetValue == SheetValue.Hidden) 0f else 1f,
        animationSpec = tween(Motion.Fast),
        label = "sheetContentFade"
    )

    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var isIncome by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.isIncome ?: defaultIsIncome)
    }
    var colorArgb by rememberSaveable(existing?.id) { mutableStateOf(existing?.colorArgb) }

    val previewColors = chipColorsFor(existing?.id, colorArgb)

    ModalBottomSheet(
        onDismissRequest = animatedDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = { WindowInsets(0) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = contentAlpha }
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = animatedDismiss) {
                    Icon(
                        Lucide.X,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    if (existing == null) stringResource(R.string.new_category)
                    else stringResource(R.string.edit_category),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = { onSave(name, isIncome, colorArgb) },
                    enabled = name.isNotBlank(),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryBlue,
                        contentColor = Color.White
                    )
                ) { Text(stringResource(R.string.save), style = MaterialTheme.typography.titleSmall) }
            }

            // Preview de la categoría con su color
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .background(previewColors.container, RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        name.ifBlank { stringResource(R.string.category_preview_placeholder) },
                        style = MaterialTheme.typography.titleSmall,
                        color = previewColors.content
                    )
                }
            }

            SectionLabel(stringResource(R.string.name).uppercase())
            AppTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth()
            )

            SectionLabel(stringResource(R.string.type_label).uppercase())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterPill(stringResource(R.string.expense_type), selected = !isIncome, color = ExpenseRed) {
                    isIncome = false
                }
                FilterPill(stringResource(R.string.income_type), selected = isIncome, color = IncomeGreen) {
                    isIncome = true
                }
            }

            SectionLabel(stringResource(R.string.color_label).uppercase())
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                colorPalette.chunked(8).forEach { rowColors ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        rowColors.forEach { color ->
                            val selected = colorArgb == color.toArgb()
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(color, CircleShape)
                                    .clickable { colorArgb = color.toArgb() },
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    Icon(
                                        Lucide.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
