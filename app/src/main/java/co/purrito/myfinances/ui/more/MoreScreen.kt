package co.purrito.myfinances.ui.more

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import co.purrito.myfinances.R
import co.purrito.myfinances.ui.categories.CategoriesSheet
import co.purrito.myfinances.ui.components.AppCard
import co.purrito.myfinances.ui.components.PillTabs
import co.purrito.myfinances.service.ImportErrorKind
import co.purrito.myfinances.ui.formatCop
import co.purrito.myfinances.ui.formatUsd
import co.purrito.myfinances.ui.statementimport.ImportState
import co.purrito.myfinances.ui.statementimport.StatementImportViewModel
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FileUp
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Tag
import java.util.Locale

/* =====================================================================
 * Tab "Más" — entradas: Categorías (abre el modal) e Idioma (es/en).
 * El resto de opciones del mock (ajustes, seguridad, backup, etc.)
 * llegarán con sus respectivas features.
 *
 * Idioma: AppCompatDelegate.setApplicationLocales recrea la activity
 * con el locale elegido y lo persiste solo (autoStoreLocales en el
 * manifest para API < 33; localeConfig para 33+).
 * ===================================================================== */

private val entryIconTint = Color(0xFF7FB1F2)
private val entryIconBg = Color(0xFF1C2C45)

// Formatos de extracto aceptados: .xlsx (Bancolombia) y .txt (Davivienda).
// Algunos proveedores reportan el xlsx como octet-stream.
private val statementMimeTypes = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel",
    "application/pdf",
    "text/plain",
    "application/octet-stream"
)

@Composable
fun MoreScreen(
    importViewModel: StatementImportViewModel = viewModel()
) {
    var showCategories by rememberSaveable { mutableStateOf(false) }
    val importState by importViewModel.state.collectAsState()

    val pickStatement = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importViewModel.import(it) } }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                stringResource(R.string.more_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp)
            )

            MoreEntry(
                icon = Lucide.Tag,
                title = stringResource(R.string.categories_title),
                subtitle = stringResource(R.string.categories_subtitle),
                onClick = { showCategories = true },
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            MoreEntry(
                icon = Lucide.FileUp,
                title = stringResource(R.string.import_statement_title),
                subtitle = stringResource(R.string.import_statement_subtitle),
                onClick = { pickStatement.launch(statementMimeTypes) },
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            LanguageEntry(modifier = Modifier.padding(horizontal = 12.dp))
        }
    }

    if (showCategories) {
        CategoriesSheet(onDismiss = { showCategories = false })
    }

    ImportFeedback(state = importState, onDismiss = importViewModel::reset)
}

@Composable
private fun ImportFeedback(state: ImportState, onDismiss: () -> Unit) {
    when (state) {
        is ImportState.Loading -> AlertDialog(
            onDismissRequest = {},
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            confirmButton = {},
            title = { Text(stringResource(R.string.importing)) },
            text = {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.tertiary)
                }
            }
        )
        is ImportState.Success -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.import_done_title)) },
            text = {
                val r = state.result
                // Resumen + explicación de la verificación de saldo: el
                // ajuste (si lo hay) y lo que quedó fuera de ella, para que
                // una diferencia nunca sea una caja negra.
                val parts = buildList {
                    add(r.accountName)
                    add(
                        stringResource(
                            R.string.import_done_message,
                            r.newCount, r.updatedCount, r.duplicateCount
                        )
                    )
                    r.adjustmentMinor?.let {
                        add(stringResource(R.string.import_adjustment_line, formatCop(it)))
                    }
                    if (r.unreconciledCount > 0) add(
                        pluralStringResource(
                            R.plurals.import_unreconciled, r.unreconciledCount,
                            r.unreconciledCount, formatCop(r.unreconciledMinor)
                        )
                    )
                    if (r.foreignPurchaseCount > 0) add(
                        pluralStringResource(
                            R.plurals.import_foreign_purchases, r.foreignPurchaseCount,
                            r.foreignPurchaseCount, formatUsd(r.foreignPurchasesMinor)
                        )
                    )
                }
                Text(
                    parts.joinToString("\n\n"),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.accept)) }
            }
        )
        is ImportState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.import_error_title)) },
            text = {
                Text(
                    when (state.kind) {
                        ImportErrorKind.INVALID_FILE -> stringResource(R.string.import_error_invalid)
                        ImportErrorKind.ACCOUNT_NOT_FOUND ->
                            stringResource(R.string.import_error_account, state.arg ?: "")
                        ImportErrorKind.READ_FAILED -> stringResource(R.string.import_error_read)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.accept)) }
            }
        )
        ImportState.Idle -> Unit
    }
}

@Composable
private fun LanguageEntry(modifier: Modifier = Modifier) {
    // Idioma efectivo: el per-app si está configurado; si no, el del
    // sistema (Locale.getDefault ya lo refleja).
    val appLocales = AppCompatDelegate.getApplicationLocales()
    val currentLanguage = if (!appLocales.isEmpty) appLocales[0]?.language
                          else Locale.getDefault().language
    val selectedIndex = if (currentLanguage == "en") 1 else 0

    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EntryIcon(Lucide.Globe)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        stringResource(R.string.language),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.language_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            PillTabs(
                options = listOf(
                    stringResource(R.string.language_es),
                    stringResource(R.string.language_en)
                ),
                selectedIndex = selectedIndex,
                onSelect = { index ->
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(if (index == 1) "en" else "es")
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun MoreEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EntryIcon(icon)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Lucide.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun EntryIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(entryIconBg, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = entryIconTint,
            modifier = Modifier.size(16.dp)
        )
    }
}
