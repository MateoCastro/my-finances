package co.purrito.myfinances.ui.inbox

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.unit.sp
import co.purrito.myfinances.ui.components.FieldLabel
import co.purrito.myfinances.ui.components.AppTextField
import co.purrito.myfinances.ui.theme.Motion
import co.purrito.myfinances.R
import co.purrito.myfinances.ui.components.AppCard
import co.purrito.myfinances.data.model.Account
import co.purrito.myfinances.data.model.AccountType
import co.purrito.myfinances.data.model.Category
import co.purrito.myfinances.data.model.Transaction
import co.purrito.myfinances.data.model.TransactionSource
import co.purrito.myfinances.data.model.TransactionType
import co.purrito.myfinances.ui.components.Chip
import co.purrito.myfinances.ui.components.DropdownField
import co.purrito.myfinances.ui.components.PillTabs
import co.purrito.myfinances.ui.components.ThousandsSeparatorTransformation
import co.purrito.myfinances.ui.formatCop
import co.purrito.myfinances.ui.formatShortDateTime
import co.purrito.myfinances.ui.theme.AccentPink
import co.purrito.myfinances.ui.theme.ChipColors
import co.purrito.myfinances.ui.theme.ExpenseRed
import co.purrito.myfinances.ui.theme.IncomeGreen
import co.purrito.myfinances.ui.theme.PrimaryBlue
import co.purrito.myfinances.ui.theme.chipColorsFor
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import androidx.compose.runtime.ReadOnlyComposable
import co.purrito.myfinances.ui.theme.AppTheme

/* =====================================================================
 * Inbox SMS — implementación del mock de diseño:
 *
 *  ┌──────────────────────────────────┐
 *  │ Inbox SMS          [4 pendientes]│
 *  │ Transacciones detectadas por SMS │
 *  │ ╭──────────────────────────────╮ │
 *  │ │ 💬 SMS · 7 jun 13:42 [pend.] │ │
 *  │ │ - $ 45.900       (rojo/verde)│ │
 *  │ │ RAPPI *FOOD                  │ │
 *  │ │ [Food] · Tarjeta             │ │
 *  │ │ ⌄ Ver SMS original           │ │  expandible
 *  │ │ ✕ Rechazar │ ✎ Editar │ ✓ OK │ │
 *  │ ╰──────────────────────────────╯ │
 *  └──────────────────────────────────┘
 *
 * En modo edición la card despliega: pills Gasto/Ingreso, monto,
 * descripción, categoría + cuenta, y Cancelar / Aprobar (verde).
 * Solo se muestran PENDING (no hay filtros de estado: lo aprobado
 * va al registro y lo rechazado se elimina).
 * ===================================================================== */

// Pill "pending" del mock: ámbar vivo con borde (amber-500/amber-400),
// no el marrón apagado anterior.
private val pendingChip: ChipColors
    @Composable @ReadOnlyComposable
    get() = ChipColors(
        container = AppTheme.warning.copy(alpha = 0.15f), content = AppTheme.warningText
    )
private val pendingBorder: BorderStroke
    @Composable @ReadOnlyComposable
    get() = BorderStroke(1.dp, AppTheme.warning.copy(alpha = 0.30f))
// Mock: bg-primary/15 + ícono primary (azul del tema)
private val smsBubble: ChipColors
    @Composable @ReadOnlyComposable
    get() = ChipColors(container = PrimaryBlue.copy(alpha = 0.15f), content = PrimaryBlue)

@Composable
fun InboxScreen(
    viewModel: InboxViewModel = viewModel(),
    onVoiceCapture: () -> Unit = {}
) {
    val pending by viewModel.pending.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val descriptionSuggestions by viewModel.descriptionSuggestions.collectAsState()
    var confirmingRejectAll by rememberSaveable { mutableStateOf(false) }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.inbox_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.inbox_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (pending.isNotEmpty()) {
                    // Pill del header (mock: px-2.5 py-1, 11sp bold)
                    Chip(
                        text = pluralStringResource(
                            R.plurals.pending_count, pending.size, pending.size
                        ),
                        colors = pendingChip,
                        fontWeight = FontWeight.Bold,
                        border = pendingBorder,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    )
                    // Rechazar todo (útil tras una importación equivocada)
                    IconButton(onClick = { confirmingRejectAll = true }) {
                        Icon(
                            Lucide.Trash2,
                            contentDescription = stringResource(R.string.reject_all),
                            tint = ExpenseRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                // Micrófono: dictar una transacción nueva (cae aquí mismo
                // como PENDING). Siempre visible, también con inbox vacío.
                IconButton(onClick = onVoiceCapture) {
                    Icon(
                        Lucide.Mic,
                        contentDescription = stringResource(R.string.voice_capture),
                        tint = AccentPink,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (pending.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.inbox_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(pending, key = { it.id }) { transaction ->
                        PendingCard(
                            // Aprobar/rechazar quita el ítem: animateItem
                            // hace fade-out de la card y desliza las demás
                            // hacia arriba (antes desaparecía de golpe).
                            modifier = Modifier.animateItem(),
                            transaction = transaction,
                            categories = categories,
                            accounts = accounts,
                            descriptionSuggestions = descriptionSuggestions,
                            onDescriptionQueryChange = viewModel::setDescriptionQuery,
                            onApprove = { type, amountMinor, accountId, counterAccountId, categoryId, name, installments ->
                                viewModel.approve(
                                    transaction, type, amountMinor, accountId,
                                    counterAccountId, categoryId, name, installments
                                )
                            },
                            onReject = { viewModel.reject(transaction.id) }
                        )
                    }
                }
            }
        }
    }

    if (confirmingRejectAll) {
        AlertDialog(
            onDismissRequest = { confirmingRejectAll = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = {
                Text(
                    pluralStringResource(
                        R.plurals.reject_all_title, pending.size, pending.size
                    )
                )
            },
            text = { Text(stringResource(R.string.reject_all_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRejectAll = false
                    viewModel.rejectAll()
                }) { Text(stringResource(R.string.reject_all), color = ExpenseRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRejectAll = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun PendingCard(
    transaction: Transaction,
    categories: List<Category>,
    accounts: List<Account>,
    descriptionSuggestions: List<String>,
    onDescriptionQueryChange: (String) -> Unit,
    onApprove: (
        type: TransactionType, amountMinor: Long,
        accountId: Long, counterAccountId: Long?, categoryId: Long?, displayName: String,
        installments: Int?
    ) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Pre-llenados con la sugerencia del diccionario de alias; lo que
    // el usuario corrija aquí re-entrena el alias al aprobar.
    var showSms by rememberSaveable(transaction.id) { mutableStateOf(false) }
    var editing by rememberSaveable(transaction.id) { mutableStateOf(false) }
    var type by rememberSaveable(transaction.id) { mutableStateOf(transaction.type) }
    val initialAmountText = (transaction.amountMinor / 100).toString()
    var amountText by rememberSaveable(transaction.id) { mutableStateOf(initialAmountText) }
    var displayName by rememberSaveable(transaction.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(transaction.description ?: ""))
    }
    var suggestionsOpen by rememberSaveable(transaction.id) { mutableStateOf(false) }
    var categoryId by rememberSaveable(transaction.id) { mutableStateOf(transaction.categoryId) }
    var accountId by rememberSaveable(transaction.id) { mutableStateOf(transaction.accountId) }
    var counterAccountId by rememberSaveable(transaction.id) { mutableStateOf(transaction.counterAccountId) }
    var deferred by rememberSaveable(transaction.id) { mutableStateOf(false) }
    var installmentsText by rememberSaveable(transaction.id) { mutableStateOf("") }

    // El campo es de pesos enteros: si no se toca, se conserva el monto
    // EXACTO (los extractos traen centavos, ej. intereses 45.678,42). Si
    // se truncaran, al reimportar el extracto la línea ya no se reconoce
    // como duplicada.
    val amountMinor: Long? =
        if (amountText == initialAmountText) transaction.amountMinor
        else amountText.toLongOrNull()?.let { it * 100 }

    // Diferido: el SMS no trae el número de cuotas (eso solo llega con
    // el extracto), así que se marca aquí al confirmar — el usuario sí
    // sabe cuántas cuotas eligió. Aplica cuando la TC es ORIGEN de la
    // deuda: gasto con TC (compra) o TRANSFER desde TC (avance).
    // El toggle de diferido SOLO si la TC es origen de la deuda y la
    // transacción no trae ya un plan (los diferidos del extracto llegan
    // con su plan precargado → se muestra como indicador, no se recrea).
    val deferrable = type != TransactionType.INCOME &&
        transaction.deferredPurchaseId == null &&
        accounts.firstOrNull { it.id == accountId }?.type == AccountType.CREDIT_CARD
    val installments = installmentsText.toIntOrNull()
    val deferredValid = !deferrable || !deferred ||
        (installments != null && installments >= 2)

    val isValid = amountMinor != null && amountMinor > 0 && deferredValid &&
        (type != TransactionType.TRANSFER ||
            (counterAccountId != null && counterAccountId != accountId))

    val accountName = accounts.firstOrNull { it.id == accountId }?.name ?: ""
    val visibleCategories = categories.filter { it.isIncome == (type == TransactionType.INCOME) }

    val (sign, amountColor) = when (type) {
        TransactionType.EXPENSE -> "- " to ExpenseRed
        TransactionType.INCOME -> "+ " to IncomeGreen
        TransactionType.TRANSFER -> "" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    fun approve() {
        if (isValid) onApprove(
            type, amountMinor!!, accountId, counterAccountId, categoryId, displayName.text,
            if (deferrable && deferred) installments else null
        )
    }

    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Burbuja según el ORIGEN (SMS / Extracto / Voz) + fecha + estado
            val sourceLabel = stringResource(
                when (transaction.source) {
                    TransactionSource.STATEMENT -> R.string.source_statement
                    TransactionSource.VOICE -> R.string.source_voice
                    else -> R.string.source_sms
                }
            )
            val sourceIcon = when (transaction.source) {
                TransactionSource.STATEMENT -> Lucide.FileText
                TransactionSource.VOICE -> Lucide.Mic
                else -> Lucide.MessageSquare
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(smsBubble.container, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        sourceIcon,
                        contentDescription = null,
                        tint = smsBubble.content,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "$sourceLabel · ${formatShortDateTime(transaction.dateMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                // Badge de la card (mock: px-2 py-0.5, 10sp bold)
                Chip(
                    text = stringResource(R.string.pending_chip),
                    colors = pendingChip,
                    fontWeight = FontWeight.Bold,
                    border = pendingBorder,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    sign + formatCop(amountMinor ?: transaction.amountMinor),
                    fontSize = 18.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = amountColor
                )
                Text(
                    displayName.text.ifBlank {
                        transaction.merchantRaw ?: stringResource(R.string.no_description)
                    },
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (type == TransactionType.TRANSFER) {
                        Chip(
                            text = stringResource(R.string.transfer_short),
                            colors = ChipColors(
                                container = MaterialTheme.colorScheme.surfaceVariant,
                                content = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    } else {
                        val selectedCategory = visibleCategories.firstOrNull { it.id == categoryId }
                        Chip(
                            text = selectedCategory?.name ?: stringResource(R.string.no_category),
                            colors = chipColorsFor(categoryId, selectedCategory?.colorArgb)
                        )
                    }
                    Text(
                        "  ·  $accountName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Preview del texto original (SMS o transcripción de voz)
            if (transaction.rawText != null) {
                val isVoice = transaction.source == TransactionSource.VOICE
                val showLabel = if (isVoice) R.string.show_transcription
                                else R.string.show_original_sms
                val hideLabel = if (isVoice) R.string.hide_transcription
                                else R.string.hide_original_sms
                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .clickable { showSms = !showSms },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (showSms) Lucide.ChevronUp else Lucide.ChevronDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        " " + if (showSms) stringResource(hideLabel)
                              else stringResource(showLabel),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AnimatedVisibility(
                    visible = showSms,
                    enter = expandVertically(tween(Motion.Normal)) + fadeIn(tween(Motion.Normal)),
                    exit = shrinkVertically(tween(Motion.Normal)) + fadeOut(tween(Motion.Fast))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.shapes.small
                            )
                            .padding(10.dp)
                    ) {
                        Text(
                            transaction.rawText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Modo edición inline
            AnimatedVisibility(
                visible = editing,
                enter = expandVertically(tween(Motion.Normal)) + fadeIn(tween(Motion.Normal)),
                exit = shrinkVertically(tween(Motion.Normal)) + fadeOut(tween(Motion.Fast))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    // 3 tipos como el formulario: avances/pagos del SMS o
                    // del extracto se confirman como Transferencia.
                    PillTabs(
                        options = listOf(
                            stringResource(R.string.expense_type),
                            stringResource(R.string.income_type),
                            stringResource(R.string.transfer_short)
                        ),
                        selectedIndex = type.ordinal,
                        onSelect = {
                            type = TransactionType.entries[it]
                            categoryId = null
                        },
                        activeColor = amountColor,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Column {
                        FieldLabel(stringResource(R.string.amount_cop))
                        AppTextField(
                            value = amountText,
                            onValueChange = { input ->
                                amountText = input.filter { it.isDigit() }.take(12)
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            visualTransformation = ThousandsSeparatorTransformation,
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

                    // Descripción con autocompletado INLINE (mismas
                    // sugerencias que el form de transacción)
                    Column {
                        FieldLabel(stringResource(R.string.description))
                        val visibleSuggestions = descriptionSuggestions
                            .filter { it != displayName.text.trim() }
                        AppTextField(
                            value = displayName,
                            onValueChange = {
                                displayName = it
                                suggestionsOpen = it.text.isNotBlank()
                                onDescriptionQueryChange(it.text)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        AnimatedVisibility(
                            visible = suggestionsOpen && visibleSuggestions.isNotEmpty(),
                            enter = expandVertically(tween(Motion.Fast)) + fadeIn(tween(Motion.Fast)),
                            exit = shrinkVertically(tween(Motion.Fast)) + fadeOut(tween(Motion.Fast))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        MaterialTheme.shapes.medium
                                    )
                            ) {
                                visibleSuggestions.forEach { suggestion ->
                                    Text(
                                        suggestion,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                // Cursor al final del texto aplicado
                                                displayName = TextFieldValue(
                                                    suggestion,
                                                    selection = TextRange(suggestion.length)
                                                )
                                                suggestionsOpen = false
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Hoisted: optionLabel es una lambda no-composable
                    val noCategoryLabel = stringResource(R.string.no_category)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (type == TransactionType.TRANSFER) {
                            // Transferencia (avance/pago): origen + destino
                            Box(modifier = Modifier.weight(1f)) {
                                DropdownField(
                                    label = stringResource(R.string.account_from),
                                    options = accounts,
                                    selectedLabel = accountName,
                                    optionLabel = { it.name },
                                    onSelect = { accountId = it.id }
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                DropdownField(
                                    label = stringResource(R.string.account_to),
                                    options = accounts.filter { it.id != accountId },
                                    selectedLabel = accounts
                                        .firstOrNull { it.id == counterAccountId }?.name ?: "",
                                    optionLabel = { it.name },
                                    onSelect = { counterAccountId = it.id }
                                )
                            }
                        } else {
                            Box(modifier = Modifier.weight(1f)) {
                                DropdownField(
                                    label = stringResource(R.string.category),
                                    options = listOf<Category?>(null) + visibleCategories,
                                    selectedLabel = visibleCategories
                                        .firstOrNull { it.id == categoryId }?.name
                                        ?: noCategoryLabel,
                                    optionLabel = { it?.name ?: noCategoryLabel },
                                    onSelect = { categoryId = it?.id }
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                DropdownField(
                                    label = stringResource(R.string.account),
                                    options = accounts,
                                    selectedLabel = accountName,
                                    optionLabel = { it.name },
                                    onSelect = { accountId = it.id }
                                )
                            }
                        }
                    }

                    // Diferido precargado del extracto (plan ya creado al
                    // importar): se muestra como info, no se recrea.
                    if (transaction.deferredPurchaseId != null) {
                        Text(
                            stringResource(R.string.deferred_purchase),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = AccentPink
                        )
                    }

                    // Diferido: mismo bloque del formulario manual
                    AnimatedVisibility(
                        visible = deferrable,
                        enter = expandVertically(tween(Motion.Fast)) + fadeIn(tween(Motion.Fast)),
                        exit = shrinkVertically(tween(Motion.Fast)) + fadeOut(tween(Motion.Fast))
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        stringResource(R.string.deferred_purchase),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        stringResource(R.string.deferred_purchase_subtitle),
                                        fontSize = 10.sp,
                                        lineHeight = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = deferred,
                                    onCheckedChange = { deferred = it },
                                    colors = SwitchDefaults.colors(
                                        checkedTrackColor = AccentPink,
                                        checkedThumbColor = Color.White,
                                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        uncheckedBorderColor = MaterialTheme.colorScheme.outline
                                    ),
                                    modifier = Modifier.scale(0.8f)
                                )
                            }
                            AnimatedVisibility(
                                visible = deferred,
                                enter = expandVertically(tween(Motion.Fast)) + fadeIn(tween(Motion.Fast)),
                                exit = shrinkVertically(tween(Motion.Fast)) + fadeOut(tween(Motion.Fast))
                            ) {
                                Column(modifier = Modifier.padding(top = 10.dp)) {
                                    FieldLabel(stringResource(R.string.installments_count))
                                    AppTextField(
                                        value = installmentsText,
                                        onValueChange = { input ->
                                            installmentsText = input.filter { it.isDigit() }.take(2)
                                        },
                                        placeholder = "12",
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (amountMinor != null && installments != null && installments >= 2) {
                                        Text(
                                            stringResource(
                                                R.string.estimated_installment,
                                                formatCop(amountMinor / installments)
                                            ),
                                            fontSize = 10.sp,
                                            lineHeight = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Divisor + acciones AGRUPADOS en su propia Column: la
            // Column exterior usa spacedBy(8.dp) y, sin agrupar, metía
            // 8dp entre el divisor horizontal y la fila — los separadores
            // verticales quedaban "flotando" sin tocar el horizontal.
            Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            if (editing) {
                // Cancelar / Aprobar (verde), como el mock de edición
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            // Volver a los valores capturados/sugeridos
                            editing = false
                            type = transaction.type
                            amountText = initialAmountText
                            displayName = TextFieldValue(transaction.description ?: "")
                            suggestionsOpen = false
                            categoryId = transaction.categoryId
                            accountId = transaction.accountId
                            counterAccountId = transaction.counterAccountId
                            deferred = false
                            installmentsText = ""
                        },
                        shape = MaterialTheme.shapes.large,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.cancel)) }
                    Button(
                        onClick = ::approve,
                        enabled = isValid,
                        shape = MaterialTheme.shapes.large,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = IncomeGreen,
                            contentColor = AppTheme.onIncome
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.approve)) }
                }
            } else {
                // Rechazar | Editar | Aprobar
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    ActionCell(
                        icon = Lucide.X,
                        label = stringResource(R.string.reject),
                        color = ExpenseRed,
                        onClick = onReject,
                        modifier = Modifier.weight(1f)
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outline)
                    ActionCell(
                        icon = Lucide.Pencil,
                        label = stringResource(R.string.edit),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { editing = true },
                        modifier = Modifier.weight(1f),
                        iconSize = 12.dp
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outline)
                    ActionCell(
                        icon = Lucide.Check,
                        label = stringResource(R.string.approve),
                        color = IncomeGreen,
                        onClick = ::approve,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun ActionCell(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 14.dp
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(iconSize)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}
