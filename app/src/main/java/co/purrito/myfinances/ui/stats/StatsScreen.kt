package co.purrito.myfinances.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import co.purrito.myfinances.ui.theme.Motion
import co.purrito.myfinances.ui.components.MonthSlide
import co.purrito.myfinances.R
import co.purrito.myfinances.ui.components.AppCard
import co.purrito.myfinances.data.dao.CategoryTotal
import co.purrito.myfinances.data.model.TransactionType
import co.purrito.myfinances.ui.components.Chip
import co.purrito.myfinances.ui.components.MonthSelector
import co.purrito.myfinances.ui.formatCop
import co.purrito.myfinances.ui.formatCopCompact
import co.purrito.myfinances.ui.formatMonthShort
import co.purrito.myfinances.ui.theme.ChipColors
import co.purrito.myfinances.ui.theme.ExpenseRed
import co.purrito.myfinances.ui.theme.IncomeGreen
import co.purrito.myfinances.ui.theme.chipColorsFor
import java.time.YearMonth
import kotlin.math.roundToInt
import androidx.compose.runtime.ReadOnlyComposable

/* =====================================================================
 * Estadísticas — implementación del mock de diseño:
 *
 *  ┌──────────────────────────────────┐
 *  │ ← Junio 2026 →                   │  mes a la izquierda
 *  │   Ingresos   │   GASTOS          │  tabs con total y subrayado
 *  │            ━━━━━━━                │  del color activo
 *  │            ⬤ dona                │  total compacto en el centro
 *  │  Total ─────────────  $1.308.110 │
 *  │ ╭──────────────────────────────╮ │
 *  │ │ [60%]  Tarjeta D...  $787.310│ │  card con chips de porcentaje
 *  │ ╰──────────────────────────────╯ │
 *  └──────────────────────────────────┘
 *
 * El color de cada categoría es el MISMO en el chip y en su segmento
 * de la dona: chipColorsFor(categoryId), o el colorArgb de la
 * categoría si está configurado.
 * ===================================================================== */

@Composable
@ReadOnlyComposable
private fun colorsFor(total: CategoryTotal): ChipColors =
    chipColorsFor(total.categoryId, total.colorArgb)

@Composable
fun StatsScreen(
    // Tocar una categoría abre sus movimientos del mes (null = sin categoría)
    onCategoryClick: (categoryId: Long?, type: TransactionType, month: YearMonth) -> Unit,
    viewModel: StatsViewModel = viewModel()
) {
    val month by viewModel.month.collectAsState()
    val type by viewModel.type.collectAsState()
    val totals by viewModel.totals.collectAsState()
    val incomeTotal by viewModel.incomeTotalMinor.collectAsState()
    val expenseTotal by viewModel.expenseTotalMinor.collectAsState()

    val grandTotal = totals.sumOf { it.totalMinor }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            MonthSelector(
                label = formatMonthShort(month).replaceFirstChar { it.uppercase() },
                onPrevious = viewModel::previousMonth,
                onNext = viewModel::nextMonth
            )

            TypeTabs(
                selected = type,
                incomeTotalMinor = incomeTotal,
                expenseTotalMinor = expenseTotal,
                onSelect = viewModel::setType
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            MonthSlide(month = month, modifier = Modifier.fillMaxSize()) { _ ->
            if (totals.isEmpty() || grandTotal == 0L) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (type == TransactionType.EXPENSE) stringResource(R.string.no_expenses_this_month)
                        else stringResource(R.string.no_income_this_month),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp, end = 12.dp, bottom = 24.dp
                    )
                ) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            DonutChart(
                                totals = totals,
                                grandTotal = grandTotal,
                                modifier = Modifier.size(190.dp)
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    stringResource(R.string.total),
                                    fontSize = 9.sp,
                                    lineHeight = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    formatCopCompact(grandTotal),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.total),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            HorizontalDivider(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                formatCop(grandTotal),
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    }

                    item {
                        CategoryCard(
                            totals = totals,
                            grandTotal = grandTotal,
                            onCategoryClick = { onCategoryClick(it, type, month) }
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun TypeTabs(
    selected: TransactionType,
    incomeTotalMinor: Long,
    expenseTotalMinor: Long,
    onSelect: (TransactionType) -> Unit
) {
    // Subrayado ÚNICO que se desliza entre tabs (y transiciona de
    // color verde↔rojo). Sin ripple: el movimiento es el feedback.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val tabWidth = maxWidth / 2
        val selectedIndex = if (selected == TransactionType.INCOME) 0 else 1
        val lineOffset by animateDpAsState(
            targetValue = tabWidth * selectedIndex + 8.dp,
            animationSpec = tween(Motion.Fast),
            label = "statsLineOffset"
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            TypeTab(
                label = stringResource(R.string.income),
                color = IncomeGreen,
                active = selected == TransactionType.INCOME,
                totalMinor = incomeTotalMinor,
                onClick = { onSelect(TransactionType.INCOME) },
                modifier = Modifier.weight(1f)
            )
            TypeTab(
                label = stringResource(R.string.expenses),
                color = ExpenseRed,
                active = selected == TransactionType.EXPENSE,
                totalMinor = expenseTotalMinor,
                onClick = { onSelect(TransactionType.EXPENSE) },
                modifier = Modifier.weight(1f)
            )
        }

        // Subrayado verde/rojo según el tab (preferencia del usuario
        // sobre el rojo fijo del mock), transicionando de color al
        // deslizarse
        val lineColor by animateColorAsState(
            targetValue = if (selected == TransactionType.INCOME) IncomeGreen else ExpenseRed,
            animationSpec = tween(Motion.Fast),
            label = "statsLineColor"
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = lineOffset)
                .width(tabWidth - 16.dp)
                .height(2.dp)
                .background(lineColor, RoundedCornerShape(1.dp))
        )
    }
}

@Composable
private fun TypeTab(
    label: String,
    color: Color,
    active: Boolean,
    totalMinor: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val amountColor by animateColorAsState(
        targetValue = if (active) color else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(Motion.Fast),
        label = "statsTabAmount"
    )
    Column(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // py-2.5 del mock: 10dp arriba y abajo del contenido del tab
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            formatCop(totalMinor),
            fontSize = 14.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Bold,
            // Como en el mock: el tab activo en su color semántico,
            // el inactivo apagado en gris.
            color = amountColor,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
        )
    }
}

@Composable
private fun DonutChart(
    totals: List<CategoryTotal>,
    grandTotal: Long,
    modifier: Modifier = Modifier
) {
    val colors = totals.map { colorsFor(it).content }

    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.24f
        val diameter = size.minDimension - strokeWidth
        val topLeft = Offset(
            (size.width - diameter) / 2f,
            (size.height - diameter) / 2f
        )
        val arcSize = Size(diameter, diameter)

        var startAngle = -90f
        totals.forEachIndexed { index, total ->
            val sweep = total.totalMinor.toFloat() / grandTotal * 360f
            drawArc(
                color = colors[index],
                startAngle = startAngle,
                // Pequeño gap visual entre segmentos, solo si hay más de uno
                sweepAngle = if (totals.size > 1) (sweep - 2f).coerceAtLeast(0.5f) else sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth)
            )
            startAngle += sweep
        }
    }
}

@Composable
private fun CategoryCard(
    totals: List<CategoryTotal>,
    grandTotal: Long,
    onCategoryClick: (categoryId: Long?) -> Unit
) {
    AppCard(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            totals.forEachIndexed { index, total ->
                CategoryRow(
                    total = total,
                    colors = colorsFor(total),
                    percent = total.totalMinor * 100f / grandTotal,
                    onClick = { onCategoryClick(total.categoryId) }
                )
                if (index < totals.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    total: CategoryTotal,
    colors: ChipColors,
    percent: Float,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(28.dp)
                    .background(colors.container, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${percent.roundToInt()}%",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.content
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                total.categoryName ?: stringResource(R.string.no_category),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(
            formatCop(total.totalMinor),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
    }
}
