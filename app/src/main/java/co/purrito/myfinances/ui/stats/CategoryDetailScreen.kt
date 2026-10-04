package co.purrito.myfinances.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import co.purrito.myfinances.R
import co.purrito.myfinances.data.model.Transaction
import co.purrito.myfinances.data.model.TransactionType
import co.purrito.myfinances.ui.components.AppCard
import co.purrito.myfinances.ui.components.MonthSlide
import co.purrito.myfinances.ui.components.TransactionDayCard
import co.purrito.myfinances.ui.components.TransactionDayHeader
import co.purrito.myfinances.ui.components.groupTransactionsByDay
import co.purrito.myfinances.ui.formatCop
import co.purrito.myfinances.ui.formatMonth
import co.purrito.myfinances.ui.formatMonthShort
import co.purrito.myfinances.ui.theme.ExpenseRed
import co.purrito.myfinances.ui.theme.IncomeGreen
import co.purrito.myfinances.ui.theme.chipColorsFor
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import java.time.YearMonth

/* =====================================================================
 * Detalle de categoría (al tocar una fila en Estadísticas):
 *
 *  ┌──────────────────────────────────┐
 *  │ ←        ‹ Sep 2026 ›            │  volver + mes compacto centrado
 *  │ ╭──────────────────────────────╮ │
 *  │ │ ● Comida           $ 540.300 │ │  card con el color de la categoría
 *  │ │   12 transacciones en sep... │ │
 *  │ ╰──────────────────────────────╯ │
 *  │ (secciones de día compartidas)   │
 *  └──────────────────────────────────┘
 *
 * Mismo patrón que el detalle de cuenta: el mes es navegable aquí y
 * tocar un movimiento abre el formulario de edición.
 * ===================================================================== */

@Composable
fun CategoryDetailScreen(
    categoryId: Long?,
    type: TransactionType,
    initialMonth: YearMonth,
    onBack: () -> Unit,
    onEditTransaction: (Transaction) -> Unit,
    viewModel: CategoryDetailViewModel = viewModel(
        factory = CategoryDetailViewModel.factory(categoryId, type, initialMonth)
    )
) {
    val category by viewModel.category.collectAsState()
    val month by viewModel.month.collectAsState()
    val transactions by viewModel.transactions.collectAsState()

    val byDay = remember(transactions) { groupTransactionsByDay(transactions) }
    val totalMinor = transactions.sumOf { it.transaction.amountMinor }
    val colors = chipColorsFor(categoryId, category?.colorArgb)
    val name = category?.name ?: stringResource(R.string.no_category)

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Volver a la izquierda; selector de mes compacto centrado
            // (igual que el detalle de cuenta)
            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(
                        Lucide.ChevronLeft,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = viewModel::previousMonth) {
                        Icon(
                            Lucide.ChevronLeft,
                            contentDescription = stringResource(R.string.previous_month),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        formatMonthShort(month).replaceFirstChar { it.uppercase() },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.width(120.dp)
                    )
                    IconButton(onClick = viewModel::nextMonth) {
                        Icon(
                            Lucide.ChevronRight,
                            contentDescription = stringResource(R.string.next_month),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            MonthSlide(month = month, modifier = Modifier.fillMaxSize()) { _ ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
                ) {
                    item(key = "header") {
                        AppCard(
                            shape = MaterialTheme.shapes.large,
                            color = colors.container,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(colors.content, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        pluralStringResource(
                                            R.plurals.transactions_in_month,
                                            transactions.size, transactions.size,
                                            formatMonth(month)
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    formatCop(totalMinor),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (type == TransactionType.INCOME) IncomeGreen
                                            else ExpenseRed
                                )
                            }
                        }
                    }

                    if (transactions.isEmpty()) {
                        item(key = "empty") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.no_movements_this_month),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        item(key = "spacer") { Spacer(modifier = Modifier.padding(top = 10.dp)) }
                        byDay.forEach { (day, dayTransactions) ->
                            item(key = "header-$day") {
                                TransactionDayHeader(day = day, transactions = dayTransactions)
                            }
                            item(key = "card-$day") {
                                TransactionDayCard(
                                    transactions = dayTransactions,
                                    onTransactionClick = { row ->
                                        onEditTransaction(row.transaction)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
