package co.purrito.myfinances.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.purrito.myfinances.data.AppDatabase
import co.purrito.myfinances.data.dao.TransactionWithLabels
import co.purrito.myfinances.data.model.Category
import co.purrito.myfinances.data.model.TransactionType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import java.time.ZoneId

/* =====================================================================
 * Detalle de una categoría desde Estadísticas: los movimientos de esa
 * categoría (y tipo) en el mes, agrupados por día. Arranca en el mes
 * que estaba viendo Stats y se puede navegar de mes aquí mismo.
 *
 * categoryId null = "Sin categoría".
 * ===================================================================== */

class CategoryDetailViewModel(
    app: Application,
    private val categoryId: Long?,
    private val type: TransactionType,
    initialMonth: YearMonth
) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    val category: StateFlow<Category?> =
        (if (categoryId != null) db.categoryDao().observeById(categoryId) else flowOf(null))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _month = MutableStateFlow(initialMonth)
    val month: StateFlow<YearMonth> = _month

    @OptIn(ExperimentalCoroutinesApi::class)
    val transactions: StateFlow<List<TransactionWithLabels>> =
        _month.flatMapLatest { month ->
            val zone = ZoneId.systemDefault()
            val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            db.transactionDao().observeByCategoryRangeWithLabels(type.name, categoryId, from, to)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun previousMonth() {
        _month.value = _month.value.minusMonths(1)
    }

    fun nextMonth() {
        _month.value = _month.value.plusMonths(1)
    }

    companion object {
        fun factory(categoryId: Long?, type: TransactionType, month: YearMonth) = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as Application
                CategoryDetailViewModel(app, categoryId, type, month)
            }
        }
    }
}
