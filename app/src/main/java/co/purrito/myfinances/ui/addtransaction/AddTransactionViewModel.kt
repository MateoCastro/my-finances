package co.purrito.myfinances.ui.addtransaction

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.purrito.myfinances.R
import co.purrito.myfinances.data.AppDatabase
import co.purrito.myfinances.data.model.Account
import co.purrito.myfinances.data.model.DeferredPurchase
import co.purrito.myfinances.data.model.Category
import co.purrito.myfinances.data.model.Transaction
import co.purrito.myfinances.data.model.TransactionSource
import co.purrito.myfinances.data.model.TransactionStatus
import co.purrito.myfinances.data.model.TransactionType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/* =====================================================================
 * ViewModel del formulario. Expone los catálogos (cuentas, categorías)
 * y la operación de guardado. El estado del formulario en sí vive en
 * la pantalla — es estado efímero de UI, como el useState de un form
 * en React; al ViewModel solo llega el resultado final validado.
 * ===================================================================== */

class AddTransactionViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    val accounts: StateFlow<List<Account>> =
        db.accountDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<Category>> =
        db.categoryDao().observeActive()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Autocompletado de la descripción: lo escrito alimenta la query y
    // las sugerencias vuelven reactivas (también se refrescan si entra
    // una transacción nueva mientras el formulario está abierto)
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
     * Crea ([existing] = null) o actualiza una transacción, y avisa al
     * terminar. El callback se invoca DENTRO de la corrutina, después
     * de la escritura: al volver, el dato ya está en la BD.
     *
     * Al editar, copy() preserva lo que el formulario no toca: fuente,
     * estado, merchantRaw, externalRef y rawText (huella e historia
     * del SMS original).
     */
    fun save(
        existing: Transaction?,
        type: TransactionType,
        amountMinor: Long,
        accountId: Long,
        counterAccountId: Long?,
        categoryId: Long?,
        dateMillis: Long,
        description: String,
        installments: Int? = null,
        onSaved: () -> Unit
    ) {
        viewModelScope.launch {
            val counter = if (type == TransactionType.TRANSFER) counterAccountId else null
            val category = if (type == TransactionType.TRANSFER) null else categoryId
            val desc = description.trim().ifBlank { null }

            if (existing == null) {
                // Compra diferida (Hito 3): primero el plan de cuotas,
                // luego la transacción original ligada a él. La cuota
                // real (con intereses) llegará con el extracto (Hito 4);
                // mientras tanto se estima como total/cuotas.
                val deferredPurchaseId = installments?.let { n ->
                    db.deferredPurchaseDao().insert(
                        DeferredPurchase(
                            accountId = accountId,
                            merchant = desc
                                ?: getApplication<Application>()
                                    .getString(R.string.deferred_purchase),
                            purchaseDateMillis = dateMillis,
                            totalAmountMinor = amountMinor,
                            totalInstallments = n
                        )
                    )
                }

                db.transactionDao().insert(
                    Transaction(
                        accountId = accountId,
                        counterAccountId = counter,
                        type = type,
                        amountMinor = amountMinor,
                        categoryId = category,
                        dateMillis = dateMillis,
                        description = desc,
                        source = TransactionSource.MANUAL,
                        status = TransactionStatus.CONFIRMED,
                        deferredPurchaseId = deferredPurchaseId
                    )
                )
            } else {
                // Agregar plan de cuotas a una transacción que no lo
                // tenía (ej: un avance guardado sin diferido). Si ya
                // tiene plan, la UI no ofrece el toggle (editar el plan
                // llega con el extracto, Hito 4).
                val deferredPurchaseId =
                    if (existing.deferredPurchaseId == null) {
                        installments?.let { n ->
                            db.deferredPurchaseDao().insert(
                                DeferredPurchase(
                                    accountId = accountId,
                                    merchant = desc ?: existing.merchantRaw
                                        ?: getApplication<Application>()
                                            .getString(R.string.deferred_purchase),
                                    purchaseDateMillis = dateMillis,
                                    totalAmountMinor = amountMinor,
                                    totalInstallments = n
                                )
                            )
                        }
                    } else null

                db.transactionDao().update(
                    existing.copy(
                        accountId = accountId,
                        counterAccountId = counter,
                        type = type,
                        amountMinor = amountMinor,
                        categoryId = category,
                        dateMillis = dateMillis,
                        description = desc,
                        deferredPurchaseId = deferredPurchaseId
                            ?: existing.deferredPurchaseId
                    )
                )
            }
            onSaved()
        }
    }

    fun delete(transaction: Transaction, onDeleted: () -> Unit) {
        viewModelScope.launch {
            db.transactionDao().delete(transaction)
            // Borrar también su plan de cuotas (es entidad aparte, sin
            // cascada): si no, quedaría huérfano y se duplicaría al
            // recrear. El tab Diferidos ya filtra huérfanos por si el
            // borrado llega por otra vía (selección múltiple).
            transaction.deferredPurchaseId?.let {
                db.deferredPurchaseDao().deleteById(it)
            }
            onDeleted()
        }
    }
}
