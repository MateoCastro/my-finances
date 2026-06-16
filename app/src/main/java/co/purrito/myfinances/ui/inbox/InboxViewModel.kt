package co.purrito.myfinances.ui.inbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.purrito.myfinances.R
import co.purrito.myfinances.data.AppDatabase
import co.purrito.myfinances.data.model.Account
import co.purrito.myfinances.data.model.Category
import co.purrito.myfinances.data.model.DeferredPurchase
import co.purrito.myfinances.data.model.MerchantAlias
import co.purrito.myfinances.data.model.Transaction
import co.purrito.myfinances.data.model.TransactionStatus
import co.purrito.myfinances.data.model.TransactionType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InboxViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    val pending: StateFlow<List<Transaction>> =
        db.transactionDao().observePending()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<Category>> =
        db.categoryDao().observeActive()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accounts: StateFlow<List<Account>> =
        db.accountDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Autocompletado de la descripción al editar en el inbox: mismas
    // sugerencias que el formulario de transacción (descripciones ya
    // usadas que contienen lo escrito). Una sola query compartida: en el
    // inbox normalmente se edita una card a la vez.
    private val descriptionQuery = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val descriptionSuggestions: StateFlow<List<String>> =
        descriptionQuery.flatMapLatest { query ->
            if (query.isBlank()) flowOf(emptyList())
            else db.transactionDao().observeDescriptionSuggestions(query.trim())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setDescriptionQuery(query: String) {
        descriptionQuery.value = query
    }

    /**
     * Aprueba la transacción, aplicando lo que el usuario haya editado
     * (tipo, monto, cuenta, categoría, descripción), y APRENDE: si
     * corrigió el nombre del comercio o eligió categoría, se
     * crea/actualiza el MerchantAlias para que el próximo SMS del
     * mismo comercio llegue al inbox ya pre-llenado.
     *
     * El tipo solo alterna entre EXPENSE/INCOME desde la UI; una
     * TRANSFER conserva su tipo y su cuenta destino.
     */
    fun approve(
        transaction: Transaction,
        type: TransactionType,
        amountMinor: Long,
        accountId: Long,
        counterAccountId: Long?,
        categoryId: Long?,
        displayName: String,
        installments: Int? = null
    ) {
        viewModelScope.launch {
            val name = displayName.trim().ifBlank { null }

            // Compra diferida marcada al confirmar (el SMS no trae el
            // número de cuotas): mismo flujo que el formulario manual
            val deferredPurchaseId = installments?.let { n ->
                db.deferredPurchaseDao().insert(
                    DeferredPurchase(
                        accountId = accountId,
                        merchant = name ?: transaction.merchantRaw
                            ?: getApplication<Application>()
                                .getString(R.string.deferred_purchase),
                        purchaseDateMillis = transaction.dateMillis,
                        totalAmountMinor = amountMinor,
                        totalInstallments = n
                    )
                )
            }

            db.transactionDao().update(
                transaction.copy(
                    status = TransactionStatus.CONFIRMED,
                    type = type,
                    amountMinor = amountMinor,
                    accountId = accountId,
                    counterAccountId = if (type == TransactionType.TRANSFER)
                        counterAccountId else null,
                    categoryId = if (type == TransactionType.TRANSFER) null else categoryId,
                    description = name,
                    deferredPurchaseId = deferredPurchaseId ?: transaction.deferredPurchaseId
                )
            )

            val raw = transaction.merchantRaw ?: return@launch
            if (name == null && categoryId == null) return@launch

            val existing = db.merchantAliasDao().findMatch(raw)
            when {
                existing != null -> db.merchantAliasDao().update(
                    existing.copy(
                        displayName = name ?: existing.displayName,
                        defaultCategoryId = categoryId ?: existing.defaultCategoryId
                    )
                )
                name != null -> db.merchantAliasDao().insert(
                    MerchantAlias(
                        rawPattern = raw.uppercase().trim(),
                        displayName = name,
                        defaultCategoryId = categoryId
                    )
                )
            }
        }
    }

    fun reject(transactionId: Long) {
        viewModelScope.launch {
            val tx = db.transactionDao().getById(transactionId) ?: return@launch
            db.transactionDao().delete(tx)
            // Si traía un plan diferido precargado del extracto, borrarlo
            // también para no dejar un plan huérfano.
            tx.deferredPurchaseId?.let { db.deferredPurchaseDao().deleteById(it) }
        }
    }

    /** Rechaza TODO lo pendiente (útil tras una importación equivocada). */
    fun rejectAll() {
        viewModelScope.launch {
            pending.value.forEach { tx ->
                db.transactionDao().delete(tx)
                tx.deferredPurchaseId?.let { db.deferredPurchaseDao().deleteById(it) }
            }
        }
    }
}
