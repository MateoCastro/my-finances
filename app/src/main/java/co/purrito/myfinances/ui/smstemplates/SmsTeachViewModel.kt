package co.purrito.myfinances.ui.smstemplates

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.purrito.myfinances.data.AppDatabase
import co.purrito.myfinances.data.model.Account
import co.purrito.myfinances.data.model.SmsTemplate
import co.purrito.myfinances.service.DeviceSms
import co.purrito.myfinances.service.DeviceSmsReader
import co.purrito.myfinances.service.MissedSms
import co.purrito.myfinances.service.SmsParser
import co.purrito.myfinances.service.SmsRecovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* =====================================================================
 * Enseñar un SMS (Hito 7): elegir un SMS de la bandeja (o el que llegó
 * por la notificación), marcar monto/comercio, guardar la plantilla y
 * ofrecer recuperar los SMS que esa plantilla habría reconocido.
 * ===================================================================== */

/** SMS de la bandeja + si alguna plantilla activa ya lo reconoce. */
data class InboxSms(val sms: DeviceSms, val recognized: Boolean)

sealed interface TeachStep {
    data object Pick : TeachStep
    data class Edit(val sms: DeviceSms) : TeachStep
    /** Guardada; `missed` null mientras busca, `recovered` al terminar. */
    data class Saved(val missed: List<MissedSms>? = null, val recovered: Int? = null) : TeachStep
}

class SmsTeachViewModel(app: Application, initial: DeviceSms?) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    val accounts: StateFlow<List<Account>> =
        db.accountDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Bandeja reciente; null mientras carga. */
    private val _inbox = MutableStateFlow<List<InboxSms>?>(null)
    val inbox: StateFlow<List<InboxSms>?> = _inbox

    private val _step = MutableStateFlow<TeachStep>(initial?.let { TeachStep.Edit(it) } ?: TeachStep.Pick)
    val step: StateFlow<TeachStep> = _step

    init {
        viewModelScope.launch {
            _inbox.value = withContext(Dispatchers.IO) {
                val templates = db.smsTemplateDao().getEnabled()
                DeviceSmsReader.recent(getApplication())
                    // Bancos: códigos cortos o remitentes con nombre; fuera los
                    // celulares (+57…/10 dígitos), que no son notificaciones bancarias
                    .filter { sms -> sms.sender.any { it.isLetter() } || sms.sender.length <= 6 }
                    .map { sms ->
                        InboxSms(
                            sms,
                            SmsParser.parse(sms.sender, sms.body, sms.timestampMillis, templates) != null
                        )
                    }
            }
        }
    }

    fun pick(sms: DeviceSms) { _step.value = TeachStep.Edit(sms) }

    fun backToPick() { _step.value = TeachStep.Pick }

    /** Guarda la plantilla y busca los SMS de la bandeja que ahora reconoce. */
    fun save(template: SmsTemplate) {
        _step.value = TeachStep.Saved()
        viewModelScope.launch {
            val missed = withContext(Dispatchers.IO) {
                val id = db.smsTemplateDao().insert(template)
                SmsRecovery.findMissed(db, DeviceSmsReader.recent(getApplication()), onlyTemplateId = id)
            }
            _step.value = TeachStep.Saved(missed = missed)
        }
    }

    fun recover(selected: List<MissedSms>) {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { SmsRecovery.recover(db, selected) }
            (_step.value as? TeachStep.Saved)?.let { _step.value = it.copy(recovered = n) }
        }
    }

    companion object {
        fun factory(initial: DeviceSms?) = viewModelFactory {
            initializer {
                SmsTeachViewModel(this[APPLICATION_KEY] as Application, initial)
            }
        }
    }
}
