package co.purrito.myfinances.ui.transactions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.purrito.myfinances.data.AppDatabase
import co.purrito.myfinances.data.dao.TransactionWithLabels
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId

/* =====================================================================
 * ViewModel del registro mensual (pantalla principal).
 *
 * El mes seleccionado es un MutableStateFlow; cada cambio re-suscribe
 * la query del DAO con el nuevo rango (flatMapLatest ≈ switchMap de
 * RxJS: cancela la suscripción anterior y abre la nueva).
 * ===================================================================== */

class TransactionsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /**
     * Con query vacía: el registro del mes seleccionado.
     * Con query: búsqueda global en todas las fechas (el mes se ignora,
     * como en Money Manager).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val transactions: StateFlow<List<TransactionWithLabels>> =
        combine(_month, _query) { month, query -> month to query }
            .flatMapLatest { (month, query) ->
                if (query.isBlank()) {
                    val zone = ZoneId.systemDefault()
                    val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                    db.transactionDao().observeByRangeWithLabels(from, to)
                } else {
                    db.transactionDao().searchWithLabels(query.trim())
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(query: String) {
        _query.value = query
    }

    fun deleteTransactions(ids: Set<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            db.transactionDao().deleteByIds(ids.toList())
            // Limpia planes diferidos que hayan quedado sin transacción
            // ancla tras el borrado en lote.
            db.deferredPurchaseDao().deleteOrphans()
        }
    }

    fun previousMonth() {
        _month.value = _month.value.minusMonths(1)
    }

    fun nextMonth() {
        _month.value = _month.value.plusMonths(1)
    }
}
