package co.purrito.myfinances.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.purrito.myfinances.data.AppDatabase
import co.purrito.myfinances.data.dao.CategoryTotal
import co.purrito.myfinances.data.model.TransactionType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import java.time.ZoneId

/* =====================================================================
 * ViewModel de estadísticas: composición de gastos (o ingresos) por
 * categoría en el mes seleccionado.
 *
 * combine(mes, tipo) + flatMapLatest: cualquier cambio en el mes o en
 * el toggle re-suscribe la query con los nuevos parámetros.
 * ===================================================================== */

class StatsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month

    private val _type = MutableStateFlow(TransactionType.EXPENSE)
    val type: StateFlow<TransactionType> = _type

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun totalsOf(type: TransactionType): Flow<List<CategoryTotal>> =
        _month.flatMapLatest { month ->
            val zone = ZoneId.systemDefault()
            val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            db.transactionDao().observeTotalsByCategory(type.name, from, to)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val totals: StateFlow<List<CategoryTotal>> =
        _type.flatMapLatest { totalsOf(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Totales del mes de AMBOS tipos, para que los tabs muestren su
     * monto aunque no estén seleccionados (como en el diseño).
     */
    val incomeTotalMinor: StateFlow<Long> =
        totalsOf(TransactionType.INCOME)
            .map { list -> list.sumOf { it.totalMinor } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val expenseTotalMinor: StateFlow<Long> =
        totalsOf(TransactionType.EXPENSE)
            .map { list -> list.sumOf { it.totalMinor } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun previousMonth() {
        _month.value = _month.value.minusMonths(1)
    }

    fun nextMonth() {
        _month.value = _month.value.plusMonths(1)
    }

    fun setType(type: TransactionType) {
        _type.value = type
    }
}
