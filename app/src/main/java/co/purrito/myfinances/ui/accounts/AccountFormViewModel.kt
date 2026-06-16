package co.purrito.myfinances.ui.accounts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.purrito.myfinances.data.AppDatabase
import co.purrito.myfinances.data.model.Account
import co.purrito.myfinances.data.model.AccountType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/* =====================================================================
 * ViewModel del formulario de cuenta (crear/editar). Parametrizado por
 * accountId (-1 = crear) con factory, igual que AccountDetailViewModel.
 * El estado del formulario vive en la pantalla; aquí solo llega el
 * resultado validado.
 * ===================================================================== */

class AccountFormViewModel(
    app: Application,
    accountId: Long
) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    /** La cuenta a editar, o null si es creación (accountId = -1). */
    val account: StateFlow<Account?> =
        (if (accountId == -1L) flowOf(null) else db.accountDao().observeById(accountId))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Crea ([existing] = null) o actualiza una cuenta. Polimórfico: los
     * campos de tarjeta (corte, pago, últimos 4) solo se persisten si el
     * tipo es CREDIT_CARD; en otros tipos se fuerzan a null.
     */
    fun save(
        existing: Account?,
        name: String,
        type: AccountType,
        initialBalanceMinor: Long,
        statementDay: Int?,
        paymentDueDay: Int?,
        lastFourDigits: String?,
        onSaved: () -> Unit
    ) {
        viewModelScope.launch {
            val isCard = type == AccountType.CREDIT_CARD
            val account = Account(
                id = existing?.id ?: 0,
                name = name.trim(),
                type = type,
                currency = existing?.currency ?: "COP",
                initialBalanceMinor = initialBalanceMinor,
                statementDay = if (isCard) statementDay else null,
                paymentDueDay = if (isCard) paymentDueDay else null,
                lastFourDigits = if (isCard) lastFourDigits?.ifBlank { null } else null,
                archived = existing?.archived ?: false
            )
            if (existing == null) db.accountDao().insert(account)
            else db.accountDao().update(account)
            onSaved()
        }
    }

    fun archive(accountId: Long, onArchived: () -> Unit) {
        viewModelScope.launch {
            db.accountDao().archive(accountId)
            onArchived()
        }
    }

    companion object {
        fun factory(accountId: Long) = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as Application
                AccountFormViewModel(app, accountId)
            }
        }
    }
}
