package co.purrito.myfinances.ui.accountdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import co.purrito.myfinances.ui.components.MonthSlide
import co.purrito.myfinances.R
import co.purrito.myfinances.ui.components.AppCard
import co.purrito.myfinances.data.model.Account
import co.purrito.myfinances.data.model.AccountType
import co.purrito.myfinances.data.dao.DeferredPurchaseWithAnchor
import co.purrito.myfinances.data.model.DeferredPurchase
import co.purrito.myfinances.data.model.Transaction
import co.purrito.myfinances.data.model.TransactionType
import co.purrito.myfinances.ui.components.PillTabs
import co.purrito.myfinances.ui.components.TransactionDayCard
import co.purrito.myfinances.ui.components.TransactionDayHeader
import co.purrito.myfinances.ui.components.accountTypeStyle
import co.purrito.myfinances.ui.components.groupTransactionsByDay
import co.purrito.myfinances.ui.formatCop
import co.purrito.myfinances.ui.formatDate
import co.purrito.myfinances.ui.formatMonth
import co.purrito.myfinances.ui.formatMonthShort
import co.purrito.myfinances.ui.theme.ExpenseRed
import co.purrito.myfinances.ui.theme.FabContainer
import co.purrito.myfinances.ui.theme.FabContent
import co.purrito.myfinances.ui.theme.IncomeGreen
import co.purrito.myfinances.ui.theme.PrimaryBlue
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus

/* =====================================================================
 * Detalle de cuenta — implementación del mock de diseño:
 *
 *  ┌──────────────────────────────────┐
 *  │ ←        ‹ Jun 2026 ›            │  volver + mes compacto centrado
 *  │ ╭──────────────────────────────╮ │
 *  │ │ [💵] Efectivo        Balance │ │  card tintada por tipo
 *  │ │      Efectivo   -$12.009.800 │ │
 *  │ ╰──────────────────────────────╯ │
 *  │ │ INGRESOS │ GASTOS │  NETO    │ │  resumen del mes
 *  │ 9 transacciones en junio 2026    │
 *  │ (secciones de día compartidas)   │
 *  └──────────────────────────────────┘
 *
 * El selector de mes va con las flechas PEGADAS al mes, centrado:
 * así la flecha izquierda no se confunde con el botón de volver.
 * Saldo y deuda llevan signo; los movimientos van sin signo (color).
 * ===================================================================== */

@Composable
fun AccountDetailScreen(
    accountId: Long,
    onBack: () -> Unit,
    onAddTransaction: () -> Unit,
    onEditTransaction: (Transaction) -> Unit,
    viewModel: AccountDetailViewModel = viewModel(
        factory = AccountDetailViewModel.factory(accountId)
    )
) {
    val account by viewModel.account.collectAsState()
    val month by viewModel.month.collectAsState()
    val transactions by viewModel.transactions.collectAsState()
    val deferredPurchases by viewModel.deferredPurchases.collectAsState()

    val byDay = remember(transactions) { groupTransactionsByDay(transactions) }

    // Solo las TC tienen la doble vista: movimientos (flujo de caja del
    // mes) vs. diferidos (composición de la deuda, cruza meses)
    val isCreditCard = account?.type == AccountType.CREDIT_CARD
    var detailTab by rememberSaveable { mutableStateOf(0) }
    val showDeferred = isCreditCard && detailTab == 1

    val monthIncome = transactions
        .filter { it.transaction.type == TransactionType.INCOME }
        .sumOf { it.transaction.amountMinor }
    val monthExpense = transactions
        .filter { it.transaction.type == TransactionType.EXPENSE }
        .sumOf { it.transaction.amountMinor }

    val monthLabel = formatMonthShort(month).replaceFirstChar { it.uppercase() }

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
            // Volver a la izquierda; selector de mes compacto centrado
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
                        monthLabel,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        // Ancho fijo: los chevrons no se mueven con el
                        // largo del nombre del mes
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
                contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp)
            ) {
                account?.let { acc ->
                    item(key = "header") {
                        AccountSummaryCard(
                            account = acc,
                            incomeMinor = monthIncome,
                            expenseMinor = monthExpense
                        )
                    }
                }

                if (isCreditCard) {
                    item(key = "tabs") {
                        PillTabs(
                            options = listOf(
                                stringResource(R.string.tab_movements),
                                stringResource(R.string.tab_deferred)
                            ),
                            selectedIndex = detailTab,
                            onSelect = { detailTab = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(top = 10.dp)
                        )
                    }
                }

                if (showDeferred) {
                    deferredSection(deferredPurchases) { anchorId ->
                        viewModel.openAnchor(anchorId, onEditTransaction)
                    }
                    return@LazyColumn
                }

                item(key = "count") {
                    Text(
                        pluralStringResource(
                            R.plurals.transactions_in_month,
                            transactions.size, transactions.size, formatMonth(month)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp)
                    )
                }

                if (transactions.isEmpty()) {
                    item {
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
                    byDay.forEach { (day, dayTransactions) ->
                        item(key = "header-$day") {
                            TransactionDayHeader(day = day, transactions = dayTransactions)
                        }
                        item(key = "card-$day") {
                            // Sin subtítulo de cuenta: ya estamos en ella
                            TransactionDayCard(
                                transactions = dayTransactions,
                                showAccountSubtitle = false,
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

@Composable
private fun AccountSummaryCard(
    account: Account,
    incomeMinor: Long,
    expenseMinor: Long
) {
    val style = accountTypeStyle(account.type)
    val netMinor = incomeMinor - expenseMinor

    // Mock: UNA card (rounded-2xl) con el header tintado arriba y el
    // resumen del mes (3 columnas) debajo, separados por el border
    AppCard(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(style.colors.container)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(style.colors.container, MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        style.icon,
                        contentDescription = null,
                        tint = style.colors.content,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        account.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(style.labelRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                BalanceColumn(accountId = account.id)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // Resumen del mes (dentro de la misma card, como el mock)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(vertical = 12.dp)
            ) {
                SummaryColumn(
                    stringResource(R.string.income).uppercase(),
                    formatCop(incomeMinor), IncomeGreen, Modifier.weight(1f)
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outline)
                SummaryColumn(
                    stringResource(R.string.expenses).uppercase(),
                    formatCop(expenseMinor), ExpenseRed, Modifier.weight(1f)
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outline)
                SummaryColumn(
                    stringResource(R.string.net).uppercase(),
                    formatCop(netMinor),
                    if (netMinor < 0) ExpenseRed else IncomeGreen,
                    Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * El saldo es CALCULADO (proyección de la lista de cuentas); para no
 * duplicar esa query SQL, se observa aquí el mismo catálogo reactivo
 * de saldos que usa la pantalla de Cuentas.
 */
@Composable
private fun BalanceColumn(accountId: Long) {
    val balancesViewModel: co.purrito.myfinances.ui.accounts.AccountsViewModel = viewModel()
    val accounts by balancesViewModel.accounts.collectAsState()
    val balanceMinor = accounts.firstOrNull { it.id == accountId }?.balanceMinor ?: 0L

    Column(horizontalAlignment = Alignment.End) {
        Text(
            stringResource(R.string.balance),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            formatCop(balanceMinor),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (balanceMinor < 0) ExpenseRed else IncomeGreen,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}



/**
 * Tab "Diferidos" de una TC: las compras a cuotas abiertas. No depende
 * del mes seleccionado — un plan de cuotas vive a través de varios
 * extractos, así que siempre se listan todas las abiertas.
 */
private fun LazyListScope.deferredSection(
    purchases: List<DeferredPurchaseWithAnchor>,
    onPurchaseClick: (anchorTransactionId: Long) -> Unit
) {
    item(key = "deferred-count") {
        Text(
            pluralStringResource(
                R.plurals.deferred_open_count, purchases.size, purchases.size
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 8.dp)
        )
    }

    if (purchases.isEmpty()) {
        item(key = "deferred-empty") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.no_deferred_purchases),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        items(
            purchases.sortedByDescending { it.purchase.purchaseDateMillis },
            key = { "deferred-${it.purchase.id}" }
        ) { item ->
            DeferredPurchaseCard(
                purchase = item.purchase,
                // El nombre vive en la transacción (editable); `merchant`
                // solo como respaldo si la transacción no tiene descripción
                title = item.anchorDescription ?: item.anchorMerchantRaw ?: item.purchase.merchant,
                onClick = { onPurchaseClick(item.anchorTransactionId) },
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun DeferredPurchaseCard(
    purchase: DeferredPurchase,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Cuota: la del extracto si ya se conoce; si no, estimada total/n
    // (referencial — con intereses las cuotas reales no son iguales)
    val perInstallment = purchase.installmentAmountMinor
        ?: (purchase.totalAmountMinor / purchase.totalInstallments)
    val remainingMinor =
        (purchase.totalInstallments - purchase.billedInstallments) * perInstallment
    val progress =
        purchase.billedInstallments.toFloat() / purchase.totalInstallments

    AppCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        formatDate(purchase.purchaseDateMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatCop(purchase.totalAmountMinor),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.per_installment, formatCop(perInstallment)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            // Barra de progreso de cuotas facturadas (misma receta que
            // la barra de proporción de Cuentas: h-1.5, rounded-full)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (progress > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(PrimaryBlue, RoundedCornerShape(50))
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(
                        R.string.installments_progress,
                        purchase.billedInstallments,
                        purchase.totalInstallments
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.pending_label, formatCop(remainingMinor)),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ExpenseRed
                )
            }
        }
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
            lineHeight = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
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
