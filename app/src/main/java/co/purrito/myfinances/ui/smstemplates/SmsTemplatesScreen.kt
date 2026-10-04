package co.purrito.myfinances.ui.smstemplates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import co.purrito.myfinances.R
import co.purrito.myfinances.data.model.Account
import co.purrito.myfinances.data.model.SmsTemplate
import co.purrito.myfinances.data.model.TransactionType
import co.purrito.myfinances.service.MissedSms
import co.purrito.myfinances.ui.components.AppCard
import co.purrito.myfinances.ui.components.Chip
import co.purrito.myfinances.ui.formatCop
import co.purrito.myfinances.ui.formatDate
import co.purrito.myfinances.ui.formatShortDateTime
import co.purrito.myfinances.ui.theme.AccentPink
import co.purrito.myfinances.ui.theme.ChipColors
import co.purrito.myfinances.ui.theme.ExpenseRed
import co.purrito.myfinances.ui.theme.IncomeGreen
import co.purrito.myfinances.ui.theme.NeutralChip
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import androidx.compose.runtime.ReadOnlyComposable
import co.purrito.myfinances.ui.theme.AppTheme

/* =====================================================================
 * Plantillas SMS (Hito 7): las reglas con que la app reconoce los SMS
 * del banco. Las de fábrica vienen del seed; las "enseñadas" las crea el
 * usuario a partir de un SMS real. Cada card muestra cuándo reconoció un
 * SMS por última vez — una plantilla que lleva semanas sin reconocer
 * nada es la pista de que el banco cambió el formato.
 * ===================================================================== */

@Composable
fun SmsTemplatesScreen(
    onBack: () -> Unit,
    onTeach: () -> Unit,
    viewModel: SmsTemplatesViewModel = viewModel()
) {
    val templates by viewModel.templates.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val recovery by viewModel.recovery.collectAsState()
    var deleting by remember { mutableStateOf<SmsTemplate?>(null) }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Lucide.ChevronLeft,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(stringResource(R.string.sms_templates_title), style = MaterialTheme.typography.titleLarge)
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "teach") {
                    Button(
                        onClick = onTeach,
                        shape = MaterialTheme.shapes.large,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPink),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Lucide.Plus, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.sms_teach_action), fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        stringResource(R.string.sms_templates_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)
                    )
                }
                items(templates, key = { it.id }) { template ->
                    TemplateCard(
                        template = template,
                        accounts = accounts,
                        onToggle = { viewModel.setEnabled(template, it) },
                        onFindMissed = { viewModel.findMissed(template) },
                        onDelete = { deleting = template }
                    )
                }
            }
        }
    }

    deleting?.let { template ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.sms_template_delete_title)) },
            text = { Text(stringResource(R.string.sms_template_delete_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(template); deleting = null }) {
                    Text(stringResource(R.string.delete), color = ExpenseRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    recovery?.let { state ->
        MissedSmsDialog(
            missed = state.missed,
            recovered = state.recovered,
            onRecover = viewModel::recover,
            onDismiss = viewModel::closeRecovery
        )
    }
}

@Composable
private fun TemplateCard(
    template: SmsTemplate,
    accounts: List<Account>,
    onToggle: (Boolean) -> Unit,
    onFindMissed: () -> Unit,
    onDelete: () -> Unit
) {
    fun name(id: Long?) = accounts.firstOrNull { it.id == id }?.name
    val taught = template.exampleBody != null

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    template.bankName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(8.dp))
                TypeChip(template.resultingType)
                if (taught) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Chip(stringResource(R.string.sms_template_taught), NeutralChip)
                }
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = template.enabled,
                    onCheckedChange = onToggle,
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

            // Cuenta (→ destino) a la que enruta lo que reconoce
            val route = buildString {
                append(name(template.accountId) ?: "?")
                if (template.resultingType == TransactionType.TRANSFER) {
                    append(" → ").append(name(template.counterAccountId) ?: "…")
                }
            }
            Text(route, style = MaterialTheme.typography.bodySmall)

            Text(
                template.lastMatchedMillis?.let {
                    stringResource(R.string.sms_template_last_match, formatDate(it))
                } ?: stringResource(R.string.sms_template_never_matched),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )

            template.exampleBody?.let {
                Text(
                    it,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp, end = 8.dp)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onFindMissed, enabled = template.enabled) {
                    Text(stringResource(R.string.sms_find_missed), fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                // Solo las enseñadas se borran; las de fábrica se desactivan
                if (taught) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Lucide.Trash2,
                            contentDescription = stringResource(R.string.delete),
                            tint = ExpenseRed,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TypeChip(type: TransactionType) {
    val (label, color) = when (type) {
        TransactionType.EXPENSE -> stringResource(R.string.expense_type) to ExpenseRed
        TransactionType.INCOME -> stringResource(R.string.income_type) to IncomeGreen
        TransactionType.TRANSFER -> stringResource(R.string.transfer_short) to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Chip(label, ChipColors(container = color.copy(alpha = 0.15f), content = color))
}

/**
 * SMS que una plantilla reconoce pero no están en la app. Todos marcados
 * por defecto; el usuario desmarca los que no quiere (ej. uno que ya
 * había rechazado a propósito).
 */
@Composable
internal fun MissedSmsDialog(
    missed: List<MissedSms>?,
    recovered: Int?,
    onRecover: (List<MissedSms>) -> Unit,
    onDismiss: () -> Unit
) {
    var unchecked by remember(missed) { mutableStateOf(setOf<Int>()) }
    val selected = missed.orEmpty().filterIndexed { i, _ -> i !in unchecked }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.sms_missed_title)) },
        text = {
            when {
                missed == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.tertiary)
                }
                recovered != null -> Text(
                    pluralStringResource(R.plurals.sms_recovered, recovered, recovered)
                )
                missed.isEmpty() -> Text(stringResource(R.string.sms_missed_none))
                else -> MissedSmsList(missed, unchecked) { i ->
                    unchecked = if (i in unchecked) unchecked - i else unchecked + i
                }
            }
        },
        confirmButton = {
            if (missed.isNullOrEmpty() || recovered != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.accept)) }
            } else {
                TextButton(onClick = { onRecover(selected) }, enabled = selected.isNotEmpty()) {
                    Text(pluralStringResource(R.plurals.sms_recover_action, selected.size, selected.size))
                }
            }
        },
        dismissButton = {
            if (!missed.isNullOrEmpty() && recovered == null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}

@Composable
internal fun MissedSmsList(
    missed: List<MissedSms>,
    unchecked: Set<Int>,
    onToggle: (Int) -> Unit
) {
    Column {
        Text(
            stringResource(R.string.sms_missed_message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyColumn(modifier = Modifier.heightIn(max = 320.dp).padding(top = 8.dp)) {
            items(missed.size) { i ->
                val m = missed[i]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(i) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = i !in unchecked,
                        onCheckedChange = { onToggle(i) },
                        colors = CheckboxDefaults.colors(checkedColor = AccentPink)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            formatCop(m.result.amountMinor) +
                                (m.result.merchantRaw?.let { " · $it" } ?: ""),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            formatShortDateTime(m.sms.timestampMillis),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** Ámbar de "atención" (mismo tono que el badge del inbox). */
internal val WarningAmber: Color
    @Composable @ReadOnlyComposable
    get() = if (AppTheme.isLight) AppTheme.warningText else AppTheme.warning
