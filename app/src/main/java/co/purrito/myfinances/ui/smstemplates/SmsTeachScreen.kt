package co.purrito.myfinances.ui.smstemplates

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import co.purrito.myfinances.R
import co.purrito.myfinances.data.model.Account
import co.purrito.myfinances.data.model.AccountType
import co.purrito.myfinances.data.model.SmsTemplate
import co.purrito.myfinances.data.model.TransactionType
import co.purrito.myfinances.domain.SmsTemplateGenerator
import co.purrito.myfinances.service.DeviceSms
import co.purrito.myfinances.service.SmsParseResult
import co.purrito.myfinances.service.SmsParser
import co.purrito.myfinances.ui.components.AppCard
import co.purrito.myfinances.ui.components.AppTextField
import co.purrito.myfinances.ui.components.Chip
import co.purrito.myfinances.ui.components.DropdownField
import co.purrito.myfinances.ui.components.FieldLabel
import co.purrito.myfinances.ui.components.PillTabs
import co.purrito.myfinances.ui.formatCop
import co.purrito.myfinances.ui.formatShortDateTime
import co.purrito.myfinances.ui.theme.AccentPink
import co.purrito.myfinances.ui.theme.ChipColors
import co.purrito.myfinances.ui.theme.ExpenseRed
import co.purrito.myfinances.ui.theme.IncomeGreen
import co.purrito.myfinances.ui.theme.PrimaryBlue
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.Lucide

/* =====================================================================
 * Enseñar un SMS (Hito 7):
 *
 *  1. Elegir el SMS (bandeja reciente; los no reconocidos, marcados).
 *     Si se llegó desde la notificación, se salta este paso.
 *  2. Marcar el MONTO (chips con los candidatos) y, opcional, el
 *     COMERCIO (seleccionar el texto y "Marcar como comercio"). Tipo,
 *     cuenta y destino. Vista previa: qué extrae y cuántos SMS recientes
 *     del mismo remitente reconocería.
 *  3. Guardar → ofrece llevar al inbox los SMS que se habían perdido.
 * ===================================================================== */

private val AmountHighlight = PrimaryBlue
private val MerchantHighlight = AccentPink

@Composable
fun SmsTeachScreen(
    initial: DeviceSms?,
    onBack: () -> Unit,
    viewModel: SmsTeachViewModel = viewModel(factory = SmsTeachViewModel.factory(initial))
) {
    val step by viewModel.step.collectAsState()
    val inbox by viewModel.inbox.collectAsState()
    val accounts by viewModel.accounts.collectAsState()

    // Desde la edición, "atrás" vuelve a la lista (si se llegó por ella)
    BackHandler(enabled = step is TeachStep.Edit && initial == null) { viewModel.backToPick() }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        // Desde la edición se vuelve a la lista (si se llegó por ella)
                        if (step is TeachStep.Edit && initial == null) viewModel.backToPick() else onBack()
                    }
                ) {
                    Icon(
                        Lucide.ChevronLeft,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(stringResource(R.string.sms_teach_title), style = MaterialTheme.typography.titleLarge)
            }

            when (val s = step) {
                TeachStep.Pick -> PickStep(inbox, onPick = viewModel::pick)
                is TeachStep.Edit -> EditStep(
                    sms = s.sms,
                    accounts = accounts,
                    sameSender = inbox.orEmpty().map { it.sms }
                        .filter { it.sender == s.sms.sender && it.body != s.sms.body },
                    onSave = viewModel::save
                )
                is TeachStep.Saved -> SavedStep(
                    state = s,
                    onRecover = viewModel::recover,
                    onDone = onBack
                )
            }
        }
    }
}

// --- 1. Elegir el SMS ------------------------------------------------------

@Composable
private fun PickStep(inbox: List<InboxSms>?, onPick: (DeviceSms) -> Unit) {
    when {
        inbox == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.tertiary)
        }
        inbox.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.sms_teach_no_sms),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        else -> LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.sms_teach_pick_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
            items(inbox) { row ->
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .clickable { onPick(row.sms) }
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.sms.sender, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                formatShortDateTime(row.sms.timestampMillis),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            val (label, color) = if (row.recognized) {
                                stringResource(R.string.sms_recognized) to IncomeGreen
                            } else {
                                stringResource(R.string.sms_not_recognized) to WarningAmber
                            }
                            Chip(label, ChipColors(color.copy(alpha = 0.15f), color))
                        }
                        Text(
                            row.sms.body,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

// --- 2. Marcar monto y comercio -----------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditStep(
    sms: DeviceSms,
    accounts: List<Account>,
    sameSender: List<DeviceSms>,
    onSave: (SmsTemplate) -> Unit
) {
    val body = sms.body
    val candidates = remember(body) { SmsTemplateGenerator.amountCandidates(body) }

    // Rangos marcados como [inicio, fin) — saveable como Ints (-1 = sin marcar)
    var amountStart by rememberSaveable(body) { mutableStateOf(candidates.firstOrNull()?.first ?: -1) }
    var amountEnd by rememberSaveable(body) {
        mutableStateOf(candidates.firstOrNull()?.let { it.last + 1 } ?: -1)
    }
    var merchantStart by rememberSaveable(body) { mutableStateOf(-1) }
    var merchantEnd by rememberSaveable(body) { mutableStateOf(-1) }
    val amountRange = if (amountStart >= 0) amountStart until amountEnd else null
    val merchantRange = if (merchantStart >= 0) merchantStart until merchantEnd else null

    var textValue by remember(body) { mutableStateOf(TextFieldValue(body)) }
    val selection = textValue.selection
    val hasSelection = !selection.collapsed

    var bankName by rememberSaveable(body) {
        mutableStateOf(SmsTemplateGenerator.guessBankName(body) ?: sms.sender)
    }
    var type by rememberSaveable(body) { mutableStateOf(guessType(body)) }
    var accountId by rememberSaveable(body) { mutableStateOf(-1L) }
    var counterAccountId by rememberSaveable(body) { mutableStateOf<Long?>(null) }
    LaunchedEffect(accounts) {
        if (accountId == -1L) guessAccount(body, bankName, accounts)?.let { accountId = it.id }
    }

    // Plantilla candidata y vista previa: se recalculan en vivo. La
    // validación es que la plantilla reconozca su propio SMS con el
    // mismo monto marcado (y el comercio, si se marcó).
    val draft: SmsTemplate? = remember(body, amountRange, merchantRange, bankName, type, accountId, counterAccountId) {
        amountRange ?: return@remember null
        runCatching {
            SmsTemplate(
                bankName = bankName.trim().ifBlank { sms.sender },
                accountId = accountId,
                senderPattern = SmsTemplateGenerator.senderPattern(sms.sender),
                bodyPattern = SmsTemplateGenerator.bodyPattern(body, amountRange, merchantRange),
                resultingType = type,
                counterAccountId = if (type == TransactionType.TRANSFER) counterAccountId else null,
                exampleBody = body
            )
        }.getOrNull()
    }
    val selfResult: SmsParseResult? = draft?.let {
        SmsParser.parse(sms.sender, body, sms.timestampMillis, listOf(it))
    }
    val expectedAmount = amountRange?.let { SmsParser.parseAmountToMinor(body.substring(it.first, it.last + 1)) }
    val valid = selfResult != null && selfResult.amountMinor == expectedAmount && accountId != -1L
    val othersMatched = remember(draft, sameSender) {
        draft?.let { t -> sameSender.count { SmsParser.parse(it.sender, it.body, 0L, listOf(t)) != null } } ?: 0
    }

    val typeColor = when (type) {
        TransactionType.EXPENSE -> ExpenseRed
        TransactionType.INCOME -> IncomeGreen
        TransactionType.TRANSFER -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.sms_teach_edit_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // El SMS: seleccionable (para marcar el comercio) y con los campos resaltados
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "${sms.sender} · ${formatShortDateTime(sms.timestampMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                BasicTextField(
                    value = textValue,
                    // Solo cambia la selección: el texto del SMS es fijo
                    onValueChange = { textValue = it.copy(text = body) },
                    readOnly = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(Color.Transparent),
                    visualTransformation = HighlightTransformation(
                        listOfNotNull(
                            amountRange?.let { it to AmountHighlight },
                            merchantRange?.let { it to MerchantHighlight }
                        )
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
            }
        }

        // Monto: candidatos detectados como chips
        Column {
            FieldLabel(stringResource(R.string.sms_teach_amount))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                candidates.forEach { r ->
                    val active = amountStart == r.first && amountEnd == r.last + 1
                    Box(
                        modifier = Modifier
                            .border(
                                1.dp,
                                if (active) AmountHighlight else MaterialTheme.colorScheme.outline,
                                MaterialTheme.shapes.large
                            )
                            .clickable { amountStart = r.first; amountEnd = r.last + 1 }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "$" + body.substring(r.first, r.last + 1),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (active) AmountHighlight else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Marcar con la selección del texto
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { merchantStart = selection.min; merchantEnd = selection.max },
                enabled = hasSelection,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.sms_teach_mark_merchant), fontSize = 12.sp, color = MerchantHighlight) }
            OutlinedButton(
                onClick = { amountStart = selection.min; amountEnd = selection.max },
                enabled = hasSelection,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.sms_teach_mark_amount), fontSize = 12.sp, color = AmountHighlight) }
        }
        if (merchantRange != null) {
            Text(
                stringResource(R.string.sms_teach_clear_merchant),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { merchantStart = -1; merchantEnd = -1 }
            )
        }

        Column {
            FieldLabel(stringResource(R.string.sms_teach_bank))
            AppTextField(value = bankName, onValueChange = { bankName = it }, modifier = Modifier.fillMaxWidth())
        }

        PillTabs(
            options = listOf(
                stringResource(R.string.expense_type),
                stringResource(R.string.income_type),
                stringResource(R.string.transfer_short)
            ),
            selectedIndex = type.ordinal,
            onSelect = { type = TransactionType.entries[it] },
            activeColor = typeColor,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                DropdownField(
                    label = stringResource(
                        if (type == TransactionType.TRANSFER) R.string.account_from else R.string.account
                    ),
                    options = accounts,
                    selectedLabel = accounts.firstOrNull { it.id == accountId }?.name ?: "",
                    optionLabel = { it.name },
                    onSelect = { accountId = it.id }
                )
            }
            if (type == TransactionType.TRANSFER) {
                val unknown = stringResource(R.string.sms_teach_counter_unknown)
                Box(modifier = Modifier.weight(1f)) {
                    DropdownField(
                        label = stringResource(R.string.account_to),
                        options = listOf<Account?>(null) + accounts,
                        selectedLabel = accounts.firstOrNull { it.id == counterAccountId }?.name ?: unknown,
                        optionLabel = { it?.name ?: unknown },
                        onSelect = { counterAccountId = it?.id }
                    )
                }
            }
        }

        // Vista previa
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel(stringResource(R.string.sms_teach_preview))
                if (selfResult != null && selfResult.amountMinor == expectedAmount) {
                    Text(
                        formatCop(selfResult.amountMinor) +
                            (selfResult.merchantRaw?.let { " · $it" } ?: ""),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = typeColor
                    )
                    if (sameSender.isNotEmpty()) {
                        Text(
                            pluralStringResource(
                                R.plurals.sms_teach_matches_others, sameSender.size,
                                othersMatched, sameSender.size, sms.sender
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        stringResource(
                            if (amountRange == null) R.string.sms_teach_need_amount
                            else R.string.sms_teach_invalid
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = ExpenseRed
                    )
                }
            }
        }

        Button(
            onClick = { draft?.let(onSave) },
            enabled = valid,
            shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.buttonColors(containerColor = AccentPink),
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.sms_teach_save), fontWeight = FontWeight.SemiBold) }
    }
}

// --- 3. Guardada: recuperar SMS perdidos -----------------------------------

@Composable
private fun SavedStep(
    state: TeachStep.Saved,
    onRecover: (List<co.purrito.myfinances.service.MissedSms>) -> Unit,
    onDone: () -> Unit
) {
    var unchecked by remember(state.missed) { mutableStateOf(setOf<Int>()) }
    val missed = state.missed
    val selected = missed.orEmpty().filterIndexed { i, _ -> i !in unchecked }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.sms_teach_saved),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = IncomeGreen
        )
        when {
            missed == null -> CircularProgressIndicator(color = MaterialTheme.colorScheme.tertiary)
            state.recovered != null -> Text(
                pluralStringResource(R.plurals.sms_recovered, state.recovered, state.recovered)
            )
            missed.isEmpty() -> Text(stringResource(R.string.sms_missed_none))
            else -> Box(modifier = Modifier.weight(1f, fill = false)) {
                MissedSmsList(missed, unchecked) { i ->
                    unchecked = if (i in unchecked) unchecked - i else unchecked + i
                }
            }
        }
        if (!missed.isNullOrEmpty() && state.recovered == null) {
            Button(
                onClick = { onRecover(selected) },
                enabled = selected.isNotEmpty(),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(containerColor = AccentPink),
                modifier = Modifier.fillMaxWidth()
            ) { Text(pluralStringResource(R.plurals.sms_recover_action, selected.size, selected.size)) }
        }
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sms_teach_done))
        }
    }
}

// --- Utilidades ----------------------------------------------------------

/** Resalta rangos del texto sin cambiarlo (el mapeo de offsets es identidad). */
private class HighlightTransformation(
    private val ranges: List<Pair<IntRange, Color>>
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val styled = buildAnnotatedString {
            append(text.text)
            ranges.forEach { (r, color) ->
                if (r.first >= 0 && r.last < text.length && r.first <= r.last) {
                    addStyle(
                        SpanStyle(background = color.copy(alpha = 0.25f), color = color, fontWeight = FontWeight.Bold),
                        r.first, r.last + 1
                    )
                }
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is HighlightTransformation && other.ranges == ranges
    override fun hashCode() = ranges.hashCode()
}

/** Tipo sugerido por las palabras del SMS (el usuario lo confirma). */
private fun guessType(body: String): TransactionType {
    val b = body.lowercase()
    return when {
        listOf("recibiste", "abono", "consignaci", "nomina", "nómina").any { it in b } -> TransactionType.INCOME
        listOf("avance", "retiro", "retiraste", "pagaste en la tarjeta").any { it in b } ->
            TransactionType.TRANSFER
        else -> TransactionType.EXPENSE
    }
}

/**
 * Cuenta sugerida: la que tenga los últimos 4 dígitos que trae el SMS
 * ("Tarjeta *9999"); si no, una cuya nombre contenga el banco (tarjeta de
 * crédito si el SMS habla de tarjeta).
 */
private fun guessAccount(body: String, bankName: String, accounts: List<Account>): Account? {
    val lastFours = Regex("""\*+(\d{4})""").findAll(body).map { it.groupValues[1] }.toSet()
    accounts.firstOrNull { it.lastFourDigits != null && it.lastFourDigits in lastFours }?.let { return it }
    val byBank = accounts.filter { it.name.contains(bankName, ignoreCase = true) }
    val mentionsCard = Regex("""tarjeta|t\.cred""", RegexOption.IGNORE_CASE).containsMatchIn(body)
    return byBank.firstOrNull { (it.type == AccountType.CREDIT_CARD) == mentionsCard } ?: byBank.firstOrNull()
}
