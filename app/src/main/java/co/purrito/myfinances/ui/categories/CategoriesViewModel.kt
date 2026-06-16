package co.purrito.myfinances.ui.categories

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.purrito.myfinances.data.AppDatabase
import co.purrito.myfinances.data.model.Category
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CategoriesViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    val categories: StateFlow<List<Category>> =
        db.categoryDao().observeActive()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Crea ([existing] = null) o actualiza una categoría con nombre,
     * tipo y color elegidos en el editor.
     */
    fun save(existing: Category?, name: String, isIncome: Boolean, colorArgb: Int?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            if (existing == null) {
                db.categoryDao().insert(
                    Category(name = trimmed, isIncome = isIncome, colorArgb = colorArgb)
                )
            } else {
                db.categoryDao().update(
                    existing.copy(name = trimmed, isIncome = isIncome, colorArgb = colorArgb)
                )
            }
        }
    }

    /**
     * "Eliminar" = archivar (estilo Money Manager): las transacciones
     * que ya la usan no cambian; la categoría solo deja de ofrecerse.
     */
    fun delete(category: Category) {
        viewModelScope.launch {
            db.categoryDao().archive(category.id)
        }
    }
}
