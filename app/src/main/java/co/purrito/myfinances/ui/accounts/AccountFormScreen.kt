package co.purrito.myfinances.ui.accounts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import co.purrito.myfinances.R
import co.purrito.myfinances.data.model.Account
import co.purrito.myfinances.data.model.AccountType
import co.purrito.myfinances.ui.components.AppTextField
import co.purrito.myfinances.ui.components.DropdownField
import co.purrito.myfinances.ui.components.FieldLabel
import co.purrito.myfinances.ui.theme.AccentPink
import co.purrito.myfinances.ui.theme.ExpenseRed
import co.purrito.myfinances.ui.theme.Motion
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2

/* =====================================================================
 * Formulario crear/editar cuenta. Mismo patrón que el de transacción:
 * estado en rememberSaveable, validación derivada (no almacenada),
 * DropdownField para el tipo y campos POLIMÓRFICOS según el tipo (los
 * de tarjeta solo aparecen con CREDIT_CARD).
 * ===================================================================== */

@Composable
fun AccountFormScreen(
    accountId: Long,           // -1 = crear
    onBack: () -> Unit,
    viewModel: AccountFormViewModel = viewModel(
        factory = AccountFormViewModel.factory(accountId)
    )
) {
    val account by viewModel.account.collectAsState()
    val isEdit = accountId != -1L

    // En edición esperamos a que la cuenta cargue para sembrar el form
    if (isEdit && account == null) return

    AccountFormContent(
        existing = account,
        onSave = { name, type, balance, statementDay, paymentDueDay, lastFour ->
            viewModel.save(
                existing = account,
                name = name,
                type = type,
                initialBalanceMinor = balance,
                statementDay = statementDay,
                paymentDueDay = paymentDueDay,
                lastFourDigits = lastFour,
                onSaved = onBack
            )
        },
        onArchive = { account?.let { viewModel.archive(it.id, onBack) } },
        onBack = onBack
    )
}

@Composable
private fun AccountFormContent(
    existing: Account?,
    onSave: (
        name: String, type: AccountType, balanceMinor: Long,
        statementDay: Int?, paymentDueDay: Int?, lastFour: String?
    ) -> Unit,
    onArchive: () -> Unit,
    onBack: () -> Unit
) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var type by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.type ?: AccountType.CASH)
    }
    // El usuario escribe en PESOS enteros; internamente son centavos.
    var balanceText by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.let { if (it.initialBalanceMinor != 0L) (it.initialBalanceMinor / 100).toString() else "" } ?: "")
    }
    var statementDayText by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.statementDay?.toString() ?: "")
    }
    var paymentDueDayText by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.paymentDueDay?.toString() ?: "")
    }
    var lastFour by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.lastFourDigits ?: "")
    }
    var confirmingArchive by rememberSaveable { mutableStateOf(false) }

    val isCard = type == AccountType.CREDIT_CARD

    // Saldo: vacío = 0; admite negativo (deuda inicial de una TC)
    val balanceMinor: Long? = if (balanceText.isBlank() || balanceText == "-") 0L
                              else balanceText.toLongOrNull()?.times(100)
    val statementDay = statementDayText.toIntOrNull()
    val paymentDueDay = paymentDueDayText.toIntOrNull()

    fun dayOk(text: String, day: Int?) = text.isBlank() || (day != null && day in 1..28)

    val isValid = name.isNotBlank() && balanceMinor != null &&
        (!isCard || (dayOk(statementDayText, statementDay) && dayOk(paymentDueDayText, paymentDueDay)))

    // Etiquetas del tipo (hoisted: optionLabel no es composable)
    val typeLabels = AccountType.entries.associateWith { accountTypeLabel(it) }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Top bar: volver + título + archivar (solo en edición)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Lucide.ChevronLeft,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    if (existing == null) stringResource(R.string.account_form_new)
                    else stringResource(R.string.account_form_edit),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                if (existing != null) {
                    IconButton(onClick = { confirmingArchive = true }) {
                        Icon(
                            Lucide.Trash2,
                            contentDescription = stringResource(R.string.archive_account),
                            tint = ExpenseRed,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column {
                    FieldLabel(stringResource(R.string.name))
                    AppTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                DropdownField(
                    label = stringResource(R.string.type_label),
                    options = AccountType.entries,
                    selectedLabel = typeLabels[type] ?: "",
                    optionLabel = { typeLabels[it] ?: "" },
                    onSelect = { type = it }
                )

                Column {
                    FieldLabel(stringResource(R.string.initial_balance))
                    AppTextField(
                        value = balanceText,
                        onValueChange = { input ->
                            // dígitos, con un '-' inicial opcional (deuda)
                            val cleaned = input.filterIndexed { i, c ->
                                c.isDigit() || (c == '-' && i == 0)
                            }.take(13)
                            balanceText = cleaned
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        leading = {
                            Text(
                                "$",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Campos solo de tarjeta de crédito (polimórfico)
                AnimatedVisibility(
                    visible = isCard,
                    enter = expandVertically(tween(Motion.Fast)) + fadeIn(tween(Motion.Fast)),
                    exit = shrinkVertically(tween(Motion.Fast)) + fadeOut(tween(Motion.Fast))
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(modifier = Modifier.weight(1f)) {
                                Column {
                                    FieldLabel(stringResource(R.string.statement_day))
                                    AppTextField(
                                        value = statementDayText,
                                        onValueChange = {
                                            statementDayText = it.filter { c -> c.isDigit() }.take(2)
                                        },
                                        placeholder = stringResource(R.string.day_range_hint),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                Column {
                                    FieldLabel(stringResource(R.string.payment_due_day))
                                    AppTextField(
                                        value = paymentDueDayText,
                                        onValueChange = {
                                            paymentDueDayText = it.filter { c -> c.isDigit() }.take(2)
                                        },
                                        placeholder = stringResource(R.string.day_range_hint),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                        Column {
                            FieldLabel(stringResource(R.string.last_four_digits))
                            AppTextField(
                                value = lastFour,
                                onValueChange = {
                                    lastFour = it.filter { c -> c.isDigit() }.take(4)
                                },
                                placeholder = "1234",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        onSave(
                            name, type, balanceMinor!!,
                            if (isCard) statementDay else null,
                            if (isCard) paymentDueDay else null,
                            if (isCard) lastFour else null
                        )
                    },
                    enabled = isValid,
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentPink,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(stringResource(R.string.save), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    if (confirmingArchive && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmingArchive = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.archive_account_title, existing.name)) },
            text = { Text(stringResource(R.string.archive_account_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingArchive = false
                    onArchive()
                }) { Text(stringResource(R.string.archive_account), color = ExpenseRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingArchive = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun accountTypeLabel(type: AccountType): String = stringResource(
    when (type) {
        AccountType.CASH -> R.string.acc_type_cash
        AccountType.BANK -> R.string.acc_type_bank
        AccountType.SAVINGS -> R.string.acc_type_savings
        AccountType.CREDIT_CARD -> R.string.acc_type_credit_card
    }
)
