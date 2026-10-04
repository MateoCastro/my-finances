package co.purrito.myfinances.ui.addtransaction

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import co.purrito.myfinances.ui.components.FieldLabel
import co.purrito.myfinances.ui.components.ClickableField
import co.purrito.myfinances.ui.components.AppTextField
import co.purrito.myfinances.ui.theme.Motion
import co.purrito.myfinances.R
import co.purrito.myfinances.data.model.AccountType
import co.purrito.myfinances.data.model.Transaction
import co.purrito.myfinances.data.model.TransactionType
import co.purrito.myfinances.ui.components.DropdownField
import co.purrito.myfinances.ui.components.PillTabs
import co.purrito.myfinances.ui.components.ThousandsSeparatorTransformation
import co.purrito.myfinances.ui.formatCop
import co.purrito.myfinances.ui.formatDate
import co.purrito.myfinances.ui.theme.AccentPink
import co.purrito.myfinances.ui.theme.ExpenseRed
import co.purrito.myfinances.ui.theme.IncomeGreen
import co.purrito.myfinances.domain.VoiceParser
import co.purrito.myfinances.ui.voice.rememberVoiceInput
import com.composables.icons.lucide.Calendar
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/* =====================================================================
 * Formulario de transacción como MODAL BOTTOM SHEET (mock):
 *
 *  ╭──────────────────────────────────╮
 *  │ [Gasto|Ingreso|Transf.]       ✕  │  pills + cerrar
 *  │            Monto                 │
 *  │            $ 85000               │  grande, color según tipo
 *  │ [Categoría ▾]  [Cuenta ▾]        │
 *  │ [Fecha 📅]    ✎ Editar categorías│
 *  │ [Agregar una nota...]            │
 *  │ ████████ Guardar ████████        │
 *  ╰──────────────────────────────────╯
 *
 * Mantiene los campos propios que el mock no muestra: fecha editable
 * (registro retroactivo) y el tipo Transferencia (pago de TC).
 * El monto usa el teclado numérico del sistema.
 * ===================================================================== */

/** Tamaño del monto grande (con separador de miles cabe un poco menor). */
private val AMOUNT_FONT_SIZE = 28.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionSheet(
    preselectedAccountId: Long, // -1 = sin preselección (FAB global)
    onDismiss: () -> Unit,
    editing: Transaction? = null, // != null: editar en lugar de crear
    viewModel: AddTransactionViewModel = viewModel()
) {
    val accounts by viewModel.accounts.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Foco inicial en el monto al CREAR (no al editar): ahorra el primer
    // toque para abrir el teclado.
    val amountFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (editing == null) {
            // Esperar a que el sheet termine de abrir (Expanded) para que
            // el teclado salga sin pelear con la animación de entrada.
            snapshotFlow { sheetState.currentValue }.first { it == SheetValue.Expanded }
            amountFocus.requestFocus()
        }
    }

    // Cerrar SIEMPRE animando el hide() y desmontando al terminar: si
    // onDismiss remueve el sheet de la composición directo (backdrop,
    // X, guardar), la animación de salida nunca corre y el cierre se
    // percibe brusco.
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

    // Estados keyed por la transacción en edición: abrir otra resetea
    val initialAmountText = if (editing != null) (editing.amountMinor / 100).toString() else ""
    var type by rememberSaveable(editing?.id) {
        mutableStateOf(editing?.type ?: TransactionType.EXPENSE)
    }
    var amountText by rememberSaveable(editing?.id) { mutableStateOf(initialAmountText) }
    var accountId by rememberSaveable(editing?.id) {
        mutableStateOf(editing?.accountId ?: preselectedAccountId)
    }
    var counterAccountId by rememberSaveable(editing?.id) {
        mutableStateOf(editing?.counterAccountId)
    }
    var categoryId by rememberSaveable(editing?.id) { mutableStateOf(editing?.categoryId) }
    var dateMillis by rememberSaveable(editing?.id) {
        mutableStateOf(editing?.dateMillis ?: System.currentTimeMillis())
    }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var description by rememberSaveable(editing?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(editing?.description ?: ""))
    }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var deferred by rememberSaveable(editing?.id) { mutableStateOf(false) }
    var installmentsText by rememberSaveable(editing?.id) { mutableStateOf("") }

    // Si se llegó por el FAB global, usar la primera cuenta al cargar
    LaunchedEffect(accounts) {
        if (accountId == -1L && accounts.isNotEmpty()) accountId = accounts.first().id
    }

    // El usuario escribe en PESOS enteros; internamente son centavos.
    // Al editar sin tocar el monto se preserva el valor EXACTO original
    // (los SMS pueden traer centavos que el campo entero truncaría).
    val amountMinor: Long? =
        if (editing != null && amountText == initialAmountText) editing.amountMinor
        else amountText.toLongOrNull()?.let { it * 100 }

    val visibleCategories = categories.filter { it.isIncome == (type == TransactionType.INCOME) }

    // Diferido (Hito 3): cuando la TC participa como ORIGEN de la deuda
    // — gasto con TC (compra) o transferencia desde TC (avance). Aplica
    // al crear y al editar una transacción SIN plan (ej: un avance
    // guardado sin diferido); EDITAR un plan ya existente queda fuera
    // del alcance del formulario (el extracto lo actualizará en el Hito 4).
    val deferrable = (editing == null || editing.deferredPurchaseId == null) &&
        type != TransactionType.INCOME &&
        accounts.firstOrNull { it.id == accountId }?.type == AccountType.CREDIT_CARD
    val installments = installmentsText.toIntOrNull()
    val deferredValid = !deferrable || !deferred ||
        (installments != null && installments >= 2)

    val isValid = amountMinor != null && amountMinor > 0 && accountId != -1L && deferredValid && when (type) {
        TransactionType.TRANSFER ->
            counterAccountId != null && counterAccountId != accountId
        else -> true // la categoría es opcional: mejor capturar que bloquear
    }

    val amountColor = when (type) {
        TransactionType.EXPENSE -> ExpenseRed
        TransactionType.INCOME -> IncomeGreen
        TransactionType.TRANSFER -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Dictado por voz: rellena los campos en sitio (no crea PENDING; el
    // usuario está ya en el formulario y guarda con el botón). El monto,
    // tipo, descripción y cuenta se infieren del texto (VoiceParser).
    val voice = rememberVoiceInput(onResult = { text ->
        val parsed = VoiceParser.parse(text) ?: return@rememberVoiceInput
        parsed.amountMinor?.let { amountText = (it / 100).toString() }
        if (parsed.type != TransactionType.TRANSFER && parsed.type != type) {
            type = parsed.type
            categoryId = null
        }
        parsed.description?.let {
            description = TextFieldValue(it, TextRange(it.length))
        }
        accounts.firstOrNull { it.type == parsed.accountTypeHint }?.let { accountId = it.id }
    })

    ModalBottomSheet(
        onDismissRequest = animatedDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        // El sheet NO añade insets: el contenido los maneja con la unión
        // ime+navbar (máx por lado), evitando la banda del navbar
        contentWindowInsets = { WindowInsets(0) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = contentAlpha }
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Selector de tipo + cerrar
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillTabs(
                    options = listOf(
                        stringResource(R.string.expense_type),
                        stringResource(R.string.income_type),
                        stringResource(R.string.transfer_short)
                    ),
                    selectedIndex = type.ordinal,
                    onSelect = {
                        type = TransactionType.entries[it]
                        categoryId = null // las categorías válidas cambian con el tipo
                    },
                    activeColor = amountColor,
                    modifier = Modifier.weight(1f)
                )
                if (editing != null) {
                    IconButton(onClick = { confirmingDelete = true }) {
                        Icon(
                            Lucide.Trash2,
                            contentDescription = stringResource(R.string.delete),
                            tint = ExpenseRed,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                if (voice.available) {
                    IconButton(onClick = { voice.launch() }) {
                        Icon(
                            Lucide.Mic,
                            contentDescription = stringResource(R.string.voice_capture),
                            tint = if (voice.listening) AccentPink
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                IconButton(onClick = animatedDismiss) {
                    Icon(
                        Lucide.X,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Monto grande, teclado numérico del sistema
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.amount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$ ",
                        fontSize = AMOUNT_FONT_SIZE,
                        fontWeight = FontWeight.Bold,
                        color = amountColor
                    )
                    BasicTextField(
                        value = amountText,
                        onValueChange = { input ->
                            amountText = input.filter { it.isDigit() }.take(12)
                        },
                        textStyle = MaterialTheme.typography.headlineSmall.copy(
                            fontSize = AMOUNT_FONT_SIZE,
                            fontWeight = FontWeight.Bold,
                            color = amountColor,
                            textAlign = TextAlign.Start
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        // "1250000" se ve como "1.250.000"; el estado sigue en dígitos
                        visualTransformation = ThousandsSeparatorTransformation,
                        singleLine = true,
                        cursorBrush = SolidColor(amountColor),
                        modifier = Modifier.focusRequester(amountFocus),
                        decorationBox = { innerTextField ->
                            Box {
                                if (amountText.isEmpty()) {
                                    Text(
                                        "0",
                                        fontSize = AMOUNT_FONT_SIZE,
                                        fontWeight = FontWeight.Bold,
                                        color = amountColor.copy(alpha = 0.5f)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }

            // Categoría + cuenta (o origen + destino si es transferencia)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (type == TransactionType.TRANSFER) {
                    Box(modifier = Modifier.weight(1f)) {
                        DropdownField(
                            label = stringResource(R.string.account_from),
                            options = accounts,
                            selectedLabel = accounts.firstOrNull { it.id == accountId }?.name ?: "",
                            optionLabel = { it.name },
                            onSelect = { accountId = it.id }
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        DropdownField(
                            label = stringResource(R.string.account_to),
                            options = accounts.filter { it.id != accountId },
                            selectedLabel = accounts.firstOrNull { it.id == counterAccountId }?.name ?: "",
                            optionLabel = { it.name },
                            onSelect = { counterAccountId = it.id }
                        )
                    }
                } else {
                    Box(modifier = Modifier.weight(1f)) {
                        DropdownField(
                            label = stringResource(R.string.category),
                            options = visibleCategories,
                            selectedLabel = visibleCategories
                                .firstOrNull { it.id == categoryId }?.name ?: "",
                            optionLabel = { it.name },
                            onSelect = { categoryId = it.id }
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        DropdownField(
                            label = stringResource(R.string.account),
                            options = accounts,
                            selectedLabel = accounts.firstOrNull { it.id == accountId }?.name ?: "",
                            optionLabel = { it.name },
                            onSelect = { accountId = it.id }
                        )
                    }
                }
            }

            // Fecha (editable: el registro manual puede ser retroactivo)
            Column {
                FieldLabel(stringResource(R.string.date))
                ClickableField(
                    text = formatDate(dateMillis),
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    trailing = {
                        Icon(
                            Lucide.Calendar,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                )
            }

            // Diferido: aparece solo cuando aplica (gasto + TC, creando)
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

            // Nota con autocompletado INLINE (no Popup: los popups se
            // recolocan con cada cambio y parpadean al teclear). La
            // lista vive en el layout del sheet y solo cambian sus filas.
            val suggestions by viewModel.descriptionSuggestions.collectAsState()
            var suggestionsOpen by remember(editing?.id) { mutableStateOf(false) }
            val visibleSuggestions = suggestions.filter { it != description.text.trim() }

            Column {
                AppTextField(
                    value = description,
                    onValueChange = {
                        description = it
                        suggestionsOpen = it.text.isNotBlank()
                        viewModel.setDescriptionQuery(it.text)
                    },
                    placeholder = stringResource(R.string.add_note_placeholder),
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
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        // Cursor al FINAL del texto aplicado
                                        description = TextFieldValue(
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

            Button(
                onClick = {
                    viewModel.save(
                        existing = editing,
                        type = type,
                        amountMinor = amountMinor!!, // isValid ya garantiza no-null
                        accountId = accountId,
                        counterAccountId = counterAccountId,
                        categoryId = categoryId,
                        dateMillis = dateMillis,
                        description = description.text,
                        installments = if (deferrable && deferred) installments else null,
                        onSaved = animatedDismiss
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

        if (confirmingDelete && editing != null) {
            AlertDialog(
                onDismissRequest = { confirmingDelete = false },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                title = {
                    Text(pluralStringResource(R.plurals.delete_transactions_title, 1, 1))
                },
                text = { Text(stringResource(R.string.delete_transactions_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingDelete = false
                        viewModel.delete(editing, onDeleted = animatedDismiss)
                    }) { Text(stringResource(R.string.delete), color = ExpenseRed) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingDelete = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        if (showDatePicker) {
            // El DatePicker de Material3 trabaja en UTC-midnight; hay que
            // convertir de ida y de vuelta a la zona local del dispositivo.
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = Instant.ofEpochMilli(dateMillis)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        pickerState.selectedDateMillis?.let { utcMillis ->
                            val picked = Instant.ofEpochMilli(utcMillis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            // Si eligió hoy, conservar la hora actual para que
                            // el orden dentro del día quede natural.
                            dateMillis = if (picked == LocalDate.now()) {
                                System.currentTimeMillis()
                            } else {
                                picked.atStartOfDay(ZoneId.systemDefault())
                                    .toInstant()
                                    .toEpochMilli()
                            }
                        }
                        showDatePicker = false
                    }) { Text(stringResource(R.string.accept)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) }
                }
            ) {
                // Compacto estilo MM: solo la grilla del mes. Sin el
                // título "Selecciona fecha", sin el headline grande de la
                // fecha y sin el toggle calendario/texto.
                DatePicker(
                    state = pickerState,
                    title = null,
                    headline = null,
                    showModeToggle = false
                )
            }
        }
    }
}
