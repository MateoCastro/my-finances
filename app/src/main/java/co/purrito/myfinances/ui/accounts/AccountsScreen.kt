package co.purrito.myfinances.ui.accounts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import co.purrito.myfinances.R
import co.purrito.myfinances.ui.components.AppCard
import co.purrito.myfinances.data.dao.AccountWithBalance
import co.purrito.myfinances.data.model.AccountType
import co.purrito.myfinances.ui.components.AccountTypeStyle
import co.purrito.myfinances.ui.components.accountTypeStyle
import co.purrito.myfinances.ui.formatCop
import co.purrito.myfinances.ui.theme.ExpenseRed
import co.purrito.myfinances.ui.theme.FabContainer
import co.purrito.myfinances.ui.theme.FabContent
import co.purrito.myfinances.ui.theme.IncomeGreen

/* =====================================================================
 * Cuentas — implementación del mock de diseño:
 *
 *  ┌──────────────────────────────────┐
 *  │ Cuentas                       ✉︎ │
 *  │ ╭──────────────────────────────╮ │
 *  │ │ ACTIVOS │ PASIVOS │  TOTAL   │ │  resumen con divisores
 *  │ │ ▓▓▓▓▓▓▓▓▓▓▓▓░░░░░ (barra)    │ │  proporción activos/pasivos
 *  │ ╰──────────────────────────────╯ │
 *  │ ╭──────────────────────────────╮ │
 *  │ │ [🏦] Cuentas      $20.358.972│ │  header tintado por tipo
 *  │ │ Bancolombia       $20.358.972│ │  filas de cuentas del grupo
 *  │ ╰──────────────────────────────╯ │
 *  └──────────────────────────────────┘
 *
 * A diferencia del registro, aquí los montos SÍ llevan signo: son
 * saldos, y la deuda de una TC es negativa.
 * ===================================================================== */

private val groupOrder = listOf(
    AccountType.CASH, AccountType.BANK, AccountType.SAVINGS, AccountType.CREDIT_CARD
)

private fun balanceColor(balanceMinor: Long): Color =
    if (balanceMinor < 0) ExpenseRed else IncomeGreen

@Composable
fun AccountsScreen(
    onAccountClick: (accountId: Long) -> Unit,
    onAddAccount: () -> Unit,
    onEditAccount: (accountId: Long) -> Unit,
    viewModel: AccountsViewModel = viewModel()
) {
    val accounts by viewModel.accounts.collectAsState()

    val assetsMinor = accounts.filter { it.balanceMinor > 0 }.sumOf { it.balanceMinor }
    val liabilitiesMinor = -accounts.filter { it.balanceMinor < 0 }.sumOf { it.balanceMinor }

    val groups = groupOrder.mapNotNull { type ->
        accounts.filter { it.type == type }
            .takeIf { it.isNotEmpty() }
            ?.let { type to it }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddAccount,
                containerColor = FabContainer,
                contentColor = FabContent
            ) {
                Icon(Lucide.Plus, contentDescription = stringResource(R.string.account_form_new))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Text(
                stringResource(R.string.accounts_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
            )

            if (accounts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.no_accounts_yet),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "summary") {
                        SummaryCard(
                            assetsMinor = assetsMinor,
                            liabilitiesMinor = liabilitiesMinor
                        )
                    }

                    items(groups, key = { it.first }) { (type, groupAccounts) ->
                        AccountGroupCard(
                            style = accountTypeStyle(type),
                            accounts = groupAccounts,
                            onAccountClick = onAccountClick,
                            onAccountLongClick = onEditAccount
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    assetsMinor: Long,
    liabilitiesMinor: Long
) {
    AppCard(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(vertical = 16.dp)
            ) {
                SummaryColumn(stringResource(R.string.assets).uppercase(), assetsMinor, IncomeGreen, Modifier.weight(1f))
                VerticalDivider(color = MaterialTheme.colorScheme.outline)
                SummaryColumn(stringResource(R.string.liabilities).uppercase(), liabilitiesMinor, ExpenseRed, Modifier.weight(1f))
                VerticalDivider(color = MaterialTheme.colorScheme.outline)
                SummaryColumn(
                    stringResource(R.string.total).uppercase(),
                    assetsMinor - liabilitiesMinor,
                    balanceColor(assetsMinor - liabilitiesMinor),
                    Modifier.weight(1f)
                )
            }

            // Barra de proporción activos (verde) vs pasivos (rojo)
            val total = assetsMinor + liabilitiesMinor
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            ) {
                if (total <= 0L) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(3.dp)
                            )
                    )
                } else {
                    if (assetsMinor > 0) {
                        Box(
                            modifier = Modifier
                                .weight(assetsMinor.toFloat())
                                .fillMaxSize()
                                .background(IncomeGreen)
                        )
                    }
                    if (liabilitiesMinor > 0) {
                        Box(
                            modifier = Modifier
                                .weight(liabilitiesMinor.toFloat())
                                .fillMaxSize()
                                .background(ExpenseRed)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryColumn(
    label: String,
    amountMinor: Long,
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
            formatCop(amountMinor),
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountGroupCard(
    style: AccountTypeStyle,
    accounts: List<AccountWithBalance>,
    onAccountClick: (accountId: Long) -> Unit,
    onAccountLongClick: (accountId: Long) -> Unit
) {
    val groupTotal = accounts.sumOf { it.balanceMinor }

    AppCard(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Header tintado del grupo (mock: px-4 py-2.5)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(style.colors.container)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(style.colors.container, RoundedCornerShape(12.dp))
                        .border(
                            1.dp,
                            style.colors.content.copy(alpha = 0.2f),
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        style.icon,
                        contentDescription = null,
                        tint = style.colors.content,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    stringResource(style.labelRes),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = style.colors.content
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    formatCop(groupTotal),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = balanceColor(groupTotal)
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            accounts.forEachIndexed { index, account ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // tap = detalle; long-press = editar/archivar
                        .combinedClickable(
                            onClick = { onAccountClick(account.id) },
                            onLongClick = { onAccountLongClick(account.id) }
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        account.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        formatCop(account.balanceMinor),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = balanceColor(account.balanceMinor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        Lucide.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(14.dp)
                    )
                }
                if (index < accounts.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    }
}
