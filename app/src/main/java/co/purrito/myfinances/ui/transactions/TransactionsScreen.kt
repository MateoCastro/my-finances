package co.purrito.myfinances.ui.transactions

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import co.purrito.myfinances.ui.theme.Motion
import co.purrito.myfinances.ui.components.AppTextField
import co.purrito.myfinances.ui.components.MonthSlide
import co.purrito.myfinances.R
import co.purrito.myfinances.data.model.Transaction
import co.purrito.myfinances.data.model.TransactionType
import co.purrito.myfinances.ui.components.MonthSelector
import co.purrito.myfinances.ui.components.TransactionDayCard
import co.purrito.myfinances.ui.components.TransactionDayHeader
import co.purrito.myfinances.ui.components.groupTransactionsByDay
import co.purrito.myfinances.ui.formatCop
import co.purrito.myfinances.ui.formatMonthShort
import co.purrito.myfinances.ui.theme.ExpenseRed
import co.purrito.myfinances.ui.theme.FabContainer
import co.purrito.myfinances.ui.theme.FabContent
import co.purrito.myfinances.ui.theme.IncomeGreen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X

/* =====================================================================
 * Registro mensual — pantalla principal:
 *
 *  ┌──────────────────────────────────┐
 *  │ ← Junio 2026 →               🔍  │  mes a la izquierda, búsqueda
 *  │  Ingresos │ Gastos │ Total       │  resumen con divisores
 *  ├──────────────────────────────────┤
 *  │ (secciones de día compartidas:   │
 *  │  ui/components/TransactionDay*)  │
 *  └──────────────────────────────────┘
 *                                  (+)  FAB: nueva transacción
 *
 * Las TRANSFER se muestran (en neutro) pero NO suman en los totales:
 * mover plata entre cuentas no es ni ingreso ni gasto.
 * ===================================================================== */

@Composable
fun TransactionsScreen(
    onEditTransaction: (Transaction) -> Unit,
    onAddTransaction: () -> Unit,
    viewModel: TransactionsViewModel = viewModel()
) {
    val month by viewModel.month.collectAsState()
    val transactions by viewModel.transactions.collectAsState()
    val query by viewModel.query.collectAsState()

    var searching by rememberSaveable { mutableStateOf(false) }

    // Selección múltiple (estilo MM): long-press entra al modo, tap
    // agrega/quita. Con selección activa el header muestra la papelera.
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    val selectionMode = selectedIds.isNotEmpty()
    var confirmingDelete by remember { mutableStateOf(false) }

    // Permisos SMS al primer arranque: es la pantalla inicial, así el
    // receiver queda habilitado desde el principio sin pasos extra.
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* el receiver funciona apenas se concede; la UI no cambia */ }

    LaunchedEffect(Unit) {
        // + notificaciones (Android 13+): el aviso de SMS bancario no
        // reconocido (Hito 7) las necesita
        val smsPermissions = buildList {
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = smsPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    val byDay = remember(transactions) { groupTransactionsByDay(transactions) }

    val monthIncome = transactions
        .filter { it.transaction.type == TransactionType.INCOME }
        .sumOf { it.transaction.amountMinor }
    val monthExpense = transactions
        .filter { it.transaction.type == TransactionType.EXPENSE }
        .sumOf { it.transaction.amountMinor }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddTransaction,
                containerColor = FabContainer,
                contentColor = FabContent
            ) {
                Icon(Lucide.Plus, contentDescription = stringResource(R.string.new_transaction))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Los tres modos del header transicionan con fade + altura
            // animada (AnimatedContent): habilitar/cerrar la búsqueda no
            // produce un salto en el contenido. El resumen vive DENTRO de
            // los modos que lo muestran para que su altura participe de la
            // transición. En búsqueda se oculta: los resultados cruzan
            // meses y el resumen mensual sería engañoso.
            val headerMode = when {
                selectionMode && !searching -> HeaderMode.Selection
                searching && !selectionMode -> HeaderMode.Search
                searching && selectionMode -> HeaderMode.SearchSelection
                else -> HeaderMode.Month
            }
            AnimatedContent(
                targetState = headerMode,
                transitionSpec = {
                    fadeIn(tween(Motion.Fast)) togetherWith fadeOut(tween(Motion.Fast))
                },
                label = "transHeader"
            ) { mode ->
                Column {
                    when (mode) {
                        HeaderMode.Selection, HeaderMode.SearchSelection -> {
                            SelectionHeader(
                                count = selectedIds.size,
                                onCancel = { selectedIds = emptySet() },
                                onDelete = { confirmingDelete = true }
                            )
                            if (mode == HeaderMode.Selection) {
                                MonthSummary(
                                    incomeMinor = monthIncome,
                                    expenseMinor = monthExpense
                                )
                            }
                        }
                        HeaderMode.Search -> {
                            SearchHeader(
                                query = query,
                                onQueryChange = viewModel::setQuery,
                                onClose = {
                                    viewModel.setQuery("")
                                    searching = false
                                }
                            )
                        }
                        HeaderMode.Month -> {
                            MonthSelector(
                                label = formatMonthShort(month).replaceFirstChar { it.uppercase() },
                                onPrevious = viewModel::previousMonth,
                                onNext = viewModel::nextMonth
                            ) {
                                IconButton(onClick = { searching = true }) {
                                    Icon(
                                        Lucide.Search,
                                        contentDescription = stringResource(R.string.search_transactions),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            MonthSummary(
                                incomeMinor = monthIncome,
                                expenseMinor = monthExpense
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            MonthSlide(month = month, modifier = Modifier.fillMaxSize()) { _ ->
            if (transactions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (searching) stringResource(R.string.no_results)
                        else stringResource(R.string.no_movements_this_month),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Sin padding lateral: los headers de día son full-bleed
                // y las cards traen su propio margen de 8 (mock)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
                ) {
                    byDay.forEach { (day, dayTransactions) ->
                        item(key = "header-$day") {
                            TransactionDayHeader(day = day, transactions = dayTransactions)
                        }
                        item(key = "card-$day") {
                            TransactionDayCard(
                                transactions = dayTransactions,
                                selectedIds = selectedIds,
                                onTransactionClick = { row ->
                                    if (selectionMode) {
                                        // En modo selección, tap agrega/quita
                                        val id = row.transaction.id
                                        selectedIds = if (id in selectedIds) selectedIds - id
                                                      else selectedIds + id
                                    } else {
                                        // Tap normal: editar la transacción
                                        onEditTransaction(row.transaction)
                                    }
                                },
                                onTransactionLongClick = { row ->
                                    selectedIds = selectedIds + row.transaction.id
                                }
                            )
                        }
                    }
                }
            }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = {
                Text(
                    pluralStringResource(
                        R.plurals.delete_transactions_title,
                        selectedIds.size, selectedIds.size
                    )
                )
            },
            text = { Text(stringResource(R.string.delete_transactions_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTransactions(selectedIds)
                    selectedIds = emptySet()
                    confirmingDelete = false
                }) { Text(stringResource(R.string.delete), color = ExpenseRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun SelectionHeader(
    count: Int,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onCancel) {
            Icon(
                Lucide.X,
                contentDescription = stringResource(R.string.cancel_selection),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            pluralStringResource(R.plurals.selected_count, count, count),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onDelete) {
            Icon(
                Lucide.Trash2,
                contentDescription = stringResource(R.string.delete_selected),
                tint = ExpenseRed
            )
        }
    }
}

private enum class HeaderMode { Month, Search, Selection, SearchSelection }

@Composable
private fun SearchHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.search_placeholder),
            focusRequester = focusRequester,
            leading = {
                Icon(
                    Lucide.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            },
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onClose) {
            Icon(
                Lucide.X,
                contentDescription = stringResource(R.string.close_search),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@Composable
private fun MonthSummary(
    incomeMinor: Long,
    expenseMinor: Long
) {
    val totalMinor = incomeMinor - expenseMinor
    // Mock: fondo card al 40%, py-3 — el resumen se distingue
    // sutilmente del fondo de la pantalla
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            .height(IntrinsicSize.Min)
            .padding(vertical = 12.dp)
    ) {
        SummaryColumn(stringResource(R.string.income), formatCop(incomeMinor), IncomeGreen, Modifier.weight(1f))
        VerticalDivider(color = MaterialTheme.colorScheme.outline)
        SummaryColumn(stringResource(R.string.expenses), formatCop(expenseMinor), ExpenseRed, Modifier.weight(1f))
        VerticalDivider(color = MaterialTheme.colorScheme.outline)
        SummaryColumn(
            stringResource(R.string.total),
            formatCop(totalMinor),
            if (totalMinor < 0) ExpenseRed else IncomeGreen,
            Modifier.weight(1f)
        )
    }
}

@Composable
private fun SummaryColumn(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
