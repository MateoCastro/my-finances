package co.purrito.myfinances.service

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/* =====================================================================
 * Extracción de texto de PDFs con PdfBox-Android (Hito 4).
 *
 * Los extractos de Davivienda/Bancolombia vienen cifrados solo con
 * contraseña de PROPIETARIO (restricciones), pero con contraseña de
 * USUARIO vacía → abren con "" sin pedir nada. Por eso no hay UI de
 * contraseña: se carga con clave vacía.
 *
 * PdfBox-Android necesita inicializar su cargador de recursos (fuentes)
 * con un Context antes del primer uso.
 * ===================================================================== */

object PdfTextExtractor {

    @Volatile private var initialized = false

    private fun ensureInit(context: Context) {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
    }

    /** Texto plano del PDF (todas las páginas) o null si no se pudo abrir. */
    fun extract(context: Context, bytes: ByteArray): String? {
        ensureInit(context)
        return runCatching {
            PDDocument.load(bytes, "").use { doc ->
                PDFTextStripper().getText(doc)
            }
        }.getOrNull()
    }
}
