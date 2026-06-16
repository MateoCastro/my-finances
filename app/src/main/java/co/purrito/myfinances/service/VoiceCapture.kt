package co.purrito.myfinances.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi

private const val TAG = "VoiceCapture"

/* =====================================================================
 * Wrapper del SpeechRecognizer nativo (Hito 6).
 *
 * Aísla la API de Android tras una superficie mínima (start/stop/destroy
 * + callbacks). El parsing del texto lo hace VoiceParser (puro).
 *
 * On-device (privacidad): cuando preferOffline y la API lo permite (33+),
 * usa createOnDeviceSpeechRecognizer (NO el servicio por defecto, que en
 * Samsung ignora EXTRA_PREFER_OFFLINE y va a la red igual). Como el motor
 * on-device es estricto con el tag de idioma (pide "es-US" exacto, no "es"),
 * primero consulta checkRecognitionSupport para usar el español REALMENTE
 * instalado. Si no hay modelo, el error (LANGUAGE_*) hace que el controller
 * reintente online.
 *
 * El SpeechRecognizer debe crearse y manejarse en el hilo principal.
 * ===================================================================== */

class VoiceCapture(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(
        languageTag: String,
        preferOffline: Boolean,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (Int) -> Unit
    ) {
        if (!isAvailable) {
            onError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        destroy()

        val useOnDevice = preferOffline &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val sr = if (useOnDevice) {
            Log.d(TAG, "creando reconocedor ON-DEVICE")
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            Log.d(TAG, "creando reconocedor por defecto (red)")
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = sr
        sr.setRecognitionListener(listener(preferOffline, onPartial, onResult, onError))

        if (useOnDevice) {
            startOnDevice(sr, languageTag, onError)
        } else {
            Log.d(TAG, "startListening red (lang=$languageTag, preferOffline=$preferOffline)")
            sr.startListening(buildIntent(languageTag, preferOffline))
        }
    }

    /**
     * On-device: solo escucha si el español está REALMENTE instalado para
     * el motor on-device (no basta que esté "soportado" — el modelo de
     * Gboard es de otro componente). Si no está, dispara su descarga para
     * futuras veces y delega en el controller para que use la red ahora
     * (señalando LANGUAGE_NOT_SUPPORTED, que es reintentable online).
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun startOnDevice(
        sr: SpeechRecognizer,
        languageTag: String,
        onError: (Int) -> Unit
    ) {
        sr.checkRecognitionSupport(
            buildIntent(languageTag, preferOffline = true),
            context.mainExecutor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    val installedEs = support.installedOnDeviceLanguages
                        .firstOrNull { it.startsWith("es", ignoreCase = true) }
                    if (installedEs != null) {
                        Log.d(TAG, "on-device usando $installedEs (instalado)")
                        sr.startListening(buildIntent(installedEs, preferOffline = true))
                        return
                    }
                    // Español soportado pero NO instalado on-device: dispara
                    // la descarga (para uso offline futuro) y cae a la red.
                    val supportedEs = support.supportedOnDeviceLanguages
                        .firstOrNull { it.startsWith("es", ignoreCase = true) }
                    Log.w(
                        TAG,
                        "español no instalado on-device (installed=${support.installedOnDeviceLanguages}); " +
                            "descargando $supportedEs y usando red"
                    )
                    if (supportedEs != null) {
                        runCatching { sr.triggerModelDownload(buildIntent(supportedEs, preferOffline = true)) }
                            .onFailure { Log.w(TAG, "triggerModelDownload falló: $it") }
                    }
                    onError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "checkRecognitionSupport error=$error → red")
                    onError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
                }
            }
        )
    }

    private fun buildIntent(languageTag: String, preferOffline: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    private fun listener(
        preferOffline: Boolean,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (Int) -> Unit
    ) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            Log.w(TAG, "onError code=$error (preferOffline=$preferOffline)")
            onError(error)
        }
        override fun onResults(results: Bundle?) {
            val text = firstHypothesis(results)
            Log.d(TAG, "onResults: '$text'")
            onResult(text)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstHypothesis(partialResults)
            if (text.isNotBlank()) onPartial(text)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun stop() {
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun firstHypothesis(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
}
