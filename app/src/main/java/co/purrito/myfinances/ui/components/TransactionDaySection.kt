package co.purrito.myfinances.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.purrito.myfinances.R
import co.purrito.myfinances.data.dao.TransactionWithLabels
import co.purrito.myfinances.data.model.TransactionType
import co.purrito.myfinances.ui.formatCop
import co.purrito.myfinances.ui.theme.Accent
import co.purrito.myfinances.ui.theme.AccentPink
import co.purrito.myfinances.ui.theme.ChipColors
import co.purrito.myfinances.ui.theme.DayBadgeFriday
import co.purrito.myfinances.ui.theme.DayBadgeSaturday
import co.purrito.myfinances.ui.theme.DayBadgeSunday
import co.purrito.myfinances.ui.theme.DayBadgeThursday
import co.purrito.myfinances.ui.theme.DayBadgeWeekday
import co.purrito.myfinances.ui.theme.ExpenseRed
import co.purrito.myfinances.ui.theme.IncomeGreen
import co.purrito.myfinances.ui.theme.NeutralChip
import co.purrito.myfinances.ui.theme.PrimaryBlue
import co.purrito.myfinances.ui.theme.chipColorsFor
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.SortedMap
import androidx.compose.runtime.ReadOnlyComposable

/* =====================================================================
 * Sección de "día" del registro (registro general y detalle de
 * cuenta), maquetada 1:1 contra el mock React (TransactionsScreen.tsx):
 *
 *  - Header de día FULL-BLEED con fondo accent al 30% (un poco más
 *    claro que el fondo de la pantalla), padding 16/10.
 *  - Badge del día con color POR día (lun-mié slate, jue índigo,
 *    vie sky, sáb cian, dom rosa), radio 6.
 *  - Card de transacciones con margen lateral de 8 (mx-2).
 *  - Montos SIN signo — el color comunica.
 * ===================================================================== */

/**
 * Ancho fijo de la columna del chip: el usuario prefiere los títulos
 * ALINEADOS (revirtió el chip inline de ancho variable del mock).
 */
private val categoryColumnWidth = 84.dp

/** Agrupa por día local, del más reciente al más antiguo. */
fun groupTransactionsByDay(
    transactions: List<TransactionWithLabels>,
    zone: ZoneId = ZoneId.systemDefault()
): SortedMap<LocalDate, List<TransactionWithLabels>> =
    transactions.groupBy {
        Instant.ofEpochMilli(it.transaction.dateMillis).atZone(zone).toLocalDate()
    }.toSortedMap(compareByDescending { it })

@Composable
@ReadOnlyComposable
private fun dayBadgeColors(day: DayOfWeek): ChipColors = when (day) {
    DayOfWeek.SUNDAY -> DayBadgeSunday
    DayOfWeek.SATURDAY -> DayBadgeSaturday
    DayOfWeek.FRIDAY -> DayBadgeFriday
    DayOfWeek.THURSDAY -> DayBadgeThursday
    else -> DayBadgeWeekday
}

@Composable
fun TransactionDayHeader(
    day: LocalDate,
    transactions: List<TransactionWithLabels>
) {
    val dayIncome = transactions
        .filter { it.transaction.type == TransactionType.INCOME }
        .sumOf { it.transaction.amountMinor }
    val dayExpense = transactions
        .filter { it.transaction.type == TransactionType.EXPENSE }
        .sumOf { it.transaction.amountMinor }

    val badge = dayBadgeColors(day.dayOfWeek)

    // "jue." -> "Jue"
    val weekdayLabel = day.dayOfWeek
        .getDisplayName(TextStyle.SHORT, Locale.getDefault())
        .replace(".", "")
        .replaceFirstChar { it.uppercase() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Accent.copy(alpha = 0.3f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "%02d".format(day.dayOfMonth),
                fontSize = 22.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Chip(
                text = weekdayLabel,
                colors = badge,
                shape = RoundedCornerShape(6.dp),
                fontWeight = FontWeight.Bold
            )
            Text(
                "%02d.%d".format(day.monthValue, day.year),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                formatCop(dayIncome),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                // Como el mock: el $0 de ingresos del día va en azul
                // apagado, no en verde
                color = if (dayIncome > 0) IncomeGreen
                        else PrimaryBlue.copy(alpha = 0.6f)
            )
            Text(
                formatCop(dayExpense),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = ExpenseRed
            )
        }
    }
}

/**
 * Card con las transacciones de un día (margen lateral de 8, como el
 * mx-2 del mock — el header de día es full-bleed).
 *
 * @param showAccountSubtitle en el registro general el subtítulo es la
 *   cuenta; en el detalle de una cuenta sería redundante (false). Las
 *   transferencias siempre muestran "origen → destino".
 * @param selectedIds ids resaltados por la selección múltiple (estilo
 *   MM: long-press selecciona; tap agrega/quita mientras haya selección).
 */
@Composable
fun TransactionDayCard(
    transactions: List<TransactionWithLabels>,
    showAccountSubtitle: Boolean = true,
    selectedIds: Set<Long> = emptySet(),
    onTransactionClick: (TransactionWithLabels) -> Unit = {},
    onTransactionLongClick: (TransactionWithLabels) -> Unit = {}
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            // Gap entre grupos de día (mock: card mb-2 + wrapper mb-1)
            .padding(horizontal = 8.dp)
            .padding(bottom = 12.dp)
    ) {
        Column {
            transactions.forEachIndexed { index, row ->
                TransactionRow(
                    row = row,
                    showAccountSubtitle = showAccountSubtitle,
                    selected = row.transaction.id in selectedIds,
                    onClick = { onTransactionClick(row) },
                    onLongClick = { onTransactionLongClick(row) }
                )
                if (index < transactions.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransactionRow(
    row: TransactionWithLabels,
    showAccountSubtitle: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val t = row.transaction

    val chipLabel: String
    val chipColors: ChipColors
    val title: String
    val subtitle: String?
    when (t.type) {
        TransactionType.TRANSFER -> {
            chipLabel = row.counterAccountName ?: stringResource(R.string.transfer_short)
            chipColors = NeutralChip
            title = t.description ?: stringResource(R.string.transfer)
            subtitle = "${row.accountName ?: "?"} → ${row.counterAccountName ?: "?"}"
        }
        else -> {
            chipLabel = row.categoryName ?: stringResource(R.string.no_category)
            chipColors = chipColorsFor(t.categoryId, row.categoryColorArgb)
            title = t.description ?: t.merchantRaw ?: row.categoryName ?: stringResource(R.string.no_description)
            subtitle = if (showAccountSubtitle) row.accountName else null
        }
    }

    val amountColor = when (t.type) {
        TransactionType.INCOME -> IncomeGreen
        TransactionType.EXPENSE -> ExpenseRed
        TransactionType.TRANSFER -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) AccentPink.copy(alpha = 0.12f) else Color.Transparent
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Columna fija: los títulos de todas las filas quedan alineados
        Box(modifier = Modifier.width(categoryColumnWidth)) {
            Chip(
                text = chipLabel,
                colors = chipColors,
                modifier = Modifier.widthIn(max = categoryColumnWidth)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Text(
            // Sin signo: el color comunica la dirección del movimiento
            formatCop(t.amountMinor),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = amountColor,
            textAlign = TextAlign.End
        )
    }
}
