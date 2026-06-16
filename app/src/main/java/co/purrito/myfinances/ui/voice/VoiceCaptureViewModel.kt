package co.purrito.myfinances.ui.voice

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.purrito.myfinances.data.AppDatabase
import co.purrito.myfinances.data.model.AccountType
import co.purrito.myfinances.data.model.Transaction
import co.purrito.myfinances.data.model.TransactionSource
import co.purrito.myfinances.data.model.TransactionStatus
import co.purrito.myfinances.data.model.TransactionType
import co.purrito.myfinances.domain.ParsedVoice
import co.purrito.myfinances.domain.VoiceParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/* =====================================================================
 * ViewModel de la captura por voz (Hito 6).
 *
 * Recibe el texto transcrito, lo parsea (VoiceParser, puro), resuelve
 * contra la BD la cuenta (por su TIPO) y la categoría sugerida (alias o
 * nombre), y guarda una Transaction con source=VOICE y status=PENDING:
 * cae al MISMO inbox que SMS/extractos para confirmación. Nunca pierde
 * la captura: si no se entendió el monto, guarda la transcripción cruda
 * en notes con monto 0 para completar a mano.
 * ===================================================================== */

class VoiceCaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    /**
     * @return true si se guardó algo (cayó al inbox); false si la frase
     * venía vacía o no hay cuentas donde registrar.
     */
    suspend fun save(transcript: String): Boolean {
        val parsed = VoiceParser.parse(transcript) ?: return false

        val accounts = db.accountDao().getAll()
        val account = accounts.firstOrNull { it.type == parsed.accountTypeHint }
            ?: accounts.firstOrNull { it.type == AccountType.CASH }
            ?: accounts.firstOrNull()
            ?: return false

        db.transactionDao().insert(
            Transaction(
                accountId = account.id,
                type = parsed.type,
                amountMinor = parsed.amountMinor ?: 0L,
                categoryId = resolveCategory(parsed),
                dateMillis = System.currentTimeMillis(),
                description = parsed.description,
                // merchantRaw = lo dictado: alimenta el aprendizaje de
                // MerchantAlias al confirmar en el inbox, de modo que la
                // categoría se autocomplete la próxima vez aunque los
                // nombres de categoría estén en otro idioma que el dictado.
                merchantRaw = parsed.description,
                source = TransactionSource.VOICE,
                status = TransactionStatus.PENDING,
                // Sin monto entendido: guarda el dictado crudo para completar
                notes = if (parsed.amountMinor == null) parsed.rawText else null,
                rawText = parsed.rawText
            )
        )
        return true
    }

    fun saveAsync(transcript: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(save(transcript)) }
    }

    /**
     * Categoría sugerida: primero el diccionario de alias (mismo que
     * SMS/extracto), luego coincidencia simple por nombre de categoría
     * activa del tipo correcto (gasto/ingreso). null si nada matchea.
     */
    private suspend fun resolveCategory(parsed: ParsedVoice): Long? {
        val desc = parsed.description ?: return null

        db.merchantAliasDao().findMatch(desc)?.defaultCategoryId?.let { return it }

        val income = parsed.type == TransactionType.INCOME
        val descLower = desc.lowercase()
        return db.categoryDao().observeActive().first()
            .firstOrNull { it.isIncome == income && descLower.contains(it.name.lowercase()) }
            ?.id
    }
}
