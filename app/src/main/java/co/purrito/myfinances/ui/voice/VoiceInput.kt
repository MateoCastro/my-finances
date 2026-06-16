package co.purrito.myfinances.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import co.purrito.myfinances.service.VoiceCapture

/* =====================================================================
 * Helper de Compose para el dictado por voz (Hito 6).
 *
 * Une el permiso RECORD_AUDIO (se pide al primer uso), el VoiceCapture
 * (SpeechRecognizer) y su ciclo de vida (se destruye con la composición)
 * tras un controller con estado observable. Lo usan tanto la hoja de
 * captura como el botón de micrófono del formulario.
 * ===================================================================== */

class VoiceInputController internal constructor(
    private val capture: VoiceCapture,
    private val languageTag: String,
    private val onResult: (String) -> Unit,
    private val onError: (Int) -> Unit
) {
    var listening by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
        private set

    val available: Boolean get() = capture.isAvailable

    private var hasPermission: () -> Boolean = { false }
    private var requestPermission: () -> Unit = {}

    internal fun bindPermission(hasPermission: () -> Boolean, requestPermission: () -> Unit) {
        this.hasPermission = hasPermission
        this.requestPermission = requestPermission
    }

    /** Punto de entrada desde la UI: pide permiso si hace falta, o empieza. */
    fun launch() {
        if (listening) {
            capture.stop()
            return
        }
        if (hasPermission()) startNow() else requestPermission()
    }

    internal fun startNow() {
        if (!capture.isAvailable) {
            onError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        // Primero on-device (privacidad); si falla por idioma/red/no-match,
        // se reintenta una vez online — así funciona aunque falte el modelo
        // de voz español descargado.
        startInternal(preferOffline = true)
    }

    private fun startInternal(preferOffline: Boolean) {
        partial = ""
        listening = true
        capture.start(
            languageTag = languageTag,
            preferOffline = preferOffline,
            onPartial = { partial = it },
            onResult = { listening = false; partial = ""; onResult(it) },
            onError = { code ->
                if (preferOffline && code in RETRYABLE_OFFLINE) {
                    startInternal(preferOffline = false)
                } else {
                    listening = false; partial = ""; onError(code)
                }
            }
        )
    }

    internal fun fail(code: Int) {
        listening = false
        onError(code)
    }

    fun cancel() {
        capture.stop()
        listening = false
        partial = ""
    }

    companion object {
        // Errores del intento OFFLINE que justifican reintentar online:
        // típicamente el modelo del idioma no está descargado on-device.
        private val RETRYABLE_OFFLINE = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_CLIENT
        )
    }
}

@Composable
fun rememberVoiceInput(
    onResult: (String) -> Unit,
    onError: (Int) -> Unit = {}
): VoiceInputController {
    val context = LocalContext.current
    val capture = remember { VoiceCapture(context) }
    DisposableEffect(Unit) { onDispose { capture.destroy() } }

    val controller = remember {
        VoiceInputController(capture, VOICE_LANGUAGE, onResult, onError)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) controller.startNow()
        else controller.fail(PERMISSION_DENIED)
    }

    controller.bindPermission(
        hasPermission = {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        },
        requestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    )

    return controller
}

/** Código sintético (fuera del rango de SpeechRecognizer) para "permiso denegado". */
const val PERMISSION_DENIED = -1

/**
 * El dictado SIEMPRE escucha en español, independiente del idioma de la
 * app: el VoiceParser es español (números en palabras y keywords) y el
 * usuario dicta y registra descripciones en español aunque la UI esté en
 * inglés.
 *
 * Se usa el código GENÉRICO "es" (no "es-CO"): exigir una variante de país
 * concreta falla offline si el modelo descargado es otra variante (ej. el
 * usuario tiene "es-US"). El motor de Google resuelve "es" al español que
 * haya on-device. El parser es español latino → cualquier variante sirve.
 */
private const val VOICE_LANGUAGE = "es"
