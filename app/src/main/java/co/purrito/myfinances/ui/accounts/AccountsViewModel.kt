package co.purrito.myfinances.ui.accounts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.purrito.myfinances.data.AppDatabase
import co.purrito.myfinances.data.dao.AccountWithBalance
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/* =====================================================================
 * ViewModel — aquí vive el estado y la lógica de la pantalla.
 *
 * Analogía frontend: es tu custom hook / servicio de Angular. La UI
 * solo observa `accounts` y llama métodos; nunca toca la BD directo.
 *
 * Nota: usamos AndroidViewModel (que recibe el Application) como
 * atajo para obtener la BD sin montar inyección de dependencias.
 * Cuando el proyecto crezca, esto se reemplaza por un Repository +
 * DI (Hilt), pero para el Hito 0 esto es suficiente y honesto.
 * ===================================================================== */

class AccountsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    /**
     * El Flow del DAO convertido a StateFlow: un observable "caliente"
     * con valor actual, que la UI puede coleccionar.
     *
     * - stateIn ≈ shareReplay(1) de RxJS con valor inicial.
     * - WhileSubscribed(5000): deja de observar la BD 5s después de
     *   que la UI desaparece (ahorra trabajo en rotaciones).
     * - Cualquier escritura en las tablas involucradas re-emite la
     *   lista automáticamente: no hay que "refrescar" nada a mano.
     */
    val accounts: StateFlow<List<AccountWithBalance>> =
        db.accountDao().observeAllWithBalance()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )
}
