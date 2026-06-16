package co.purrito.myfinances.ui.accountdetail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.purrito.myfinances.data.AppDatabase
import co.purrito.myfinances.data.dao.TransactionWithLabels
import co.purrito.myfinances.data.model.Account
import co.purrito.myfinances.data.model.DeferredPurchase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import java.time.ZoneId

/* =====================================================================
 * ViewModel del detalle de cuenta: la cuenta + sus movimientos del
 * mes seleccionado (mismo patrón de mes navegable que el registro).
 *
 * Necesita un PARÁMETRO (accountId) además del Application, así que
 * Compose no puede crearlo solo — hay que darle una "factory".
 * ===================================================================== */

class AccountDetailViewModel(
    app: Application,
    accountId: Long
) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    /** La cuenta misma (header con nombre, tipo y saldo). */
    val account: StateFlow<Account?> =
        db.accountDao().observeById(accountId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Compras diferidas abiertas (solo relevante si la cuenta es TC). */
    val deferredPurchases: StateFlow<List<DeferredPurchase>> =
        db.deferredPurchaseDao().observeOpenByCard(accountId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month

    @OptIn(ExperimentalCoroutinesApi::class)
    val transactions: StateFlow<List<TransactionWithLabels>> =
        _month.flatMapLatest { month ->
            val zone = ZoneId.systemDefault()
            val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            db.transactionDao().observeByAccountRangeWithLabels(accountId, from, to)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun previousMonth() {
        _month.value = _month.value.minusMonths(1)
    }

    fun nextMonth() {
        _month.value = _month.value.plusMonths(1)
    }

    companion object {
        /** Factory: "para crear este ViewModel, toma el Application del
         *  sistema y el accountId que te paso desde la pantalla". */
        fun factory(accountId: Long) = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as Application
                AccountDetailViewModel(app, accountId)
            }
        }
    }
}
