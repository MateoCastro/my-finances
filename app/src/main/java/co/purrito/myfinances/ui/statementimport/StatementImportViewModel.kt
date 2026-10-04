package co.purrito.myfinances.ui.statementimport

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.purrito.myfinances.data.AppDatabase
import co.purrito.myfinances.service.ImportErrorKind
import co.purrito.myfinances.service.ImportResult
import co.purrito.myfinances.service.StatementImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* =====================================================================
 * Importación de extractos (Hito 4) — estado de la UI.
 *
 * Lee el archivo elegido (SAF) y delega todo el trabajo en
 * StatementImporter (parseo, reconciliación y persistencia).
 * ===================================================================== */

sealed interface ImportState {
    data object Idle : ImportState
    data object Loading : ImportState
    data class Success(val result: ImportResult.Success) : ImportState
    /** Errores con string de recurso para i18n; arg opcional (ej: últimos 4). */
    data class Error(val kind: ImportErrorKind, val arg: String? = null) : ImportState
}

class StatementImportViewModel(app: Application) : AndroidViewModel(app) {

    private val importer = StatementImporter(app, AppDatabase.get(app))

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state

    fun reset() { _state.value = ImportState.Idle }

    fun import(uri: Uri) {
        _state.value = ImportState.Loading
        viewModelScope.launch {
            val result = runCatching { runImport(uri) }
            _state.value = result.getOrElse { ImportState.Error(ImportErrorKind.READ_FAILED) }
        }
    }

    private suspend fun runImport(uri: Uri): ImportState = withContext(Dispatchers.IO) {
        val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use {
            it.readBytes()
        } ?: return@withContext ImportState.Error(ImportErrorKind.READ_FAILED)

        when (val result = importer.import(bytes)) {
            is ImportResult.Success -> ImportState.Success(result)
            is ImportResult.Error -> ImportState.Error(result.kind, result.arg)
        }
    }
}
