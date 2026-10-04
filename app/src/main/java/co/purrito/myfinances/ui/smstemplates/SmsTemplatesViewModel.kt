package co.purrito.myfinances.ui.smstemplates

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.purrito.myfinances.data.AppDatabase
import co.purrito.myfinances.data.model.Account
import co.purrito.myfinances.data.model.SmsTemplate
import co.purrito.myfinances.service.DeviceSmsReader
import co.purrito.myfinances.service.MissedSms
import co.purrito.myfinances.service.SmsRecovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* =====================================================================
 * Plantillas SMS (Hito 7): listar, activar/desactivar, borrar las
 * enseñadas y buscar SMS perdidos de una plantilla.
 * ===================================================================== */

/** Búsqueda de SMS perdidos: null = cerrada; `missed` null = buscando. */
data class RecoveryState(
    val missed: List<MissedSms>? = null,
    /** Cuántos se agregaron al inbox; no-null = terminado. */
    val recovered: Int? = null
)

class SmsTemplatesViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    val templates: StateFlow<List<SmsTemplate>> =
        db.smsTemplateDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accounts: StateFlow<List<Account>> =
        db.accountDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _recovery = MutableStateFlow<RecoveryState?>(null)
    val recovery: StateFlow<RecoveryState?> = _recovery

    fun setEnabled(template: SmsTemplate, enabled: Boolean) {
        viewModelScope.launch { db.smsTemplateDao().setEnabled(template.id, enabled) }
    }

    fun delete(template: SmsTemplate) {
        viewModelScope.launch { db.smsTemplateDao().delete(template) }
    }

    /** SMS de los últimos 30 días que hoy reconoce la plantilla y no están en la app. */
    fun findMissed(template: SmsTemplate) {
        _recovery.value = RecoveryState()
        viewModelScope.launch {
            val missed = withContext(Dispatchers.IO) {
                SmsRecovery.findMissed(db, DeviceSmsReader.recent(getApplication()), template.id)
            }
            _recovery.value = RecoveryState(missed = missed)
        }
    }

    fun recover(selected: List<MissedSms>) {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { SmsRecovery.recover(db, selected) }
            _recovery.value = _recovery.value?.copy(recovered = n)
        }
    }

    fun closeRecovery() {
        _recovery.value = null
    }
}
