package co.purrito.myfinances

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Display
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import co.purrito.myfinances.ui.AppNavigation
import co.purrito.myfinances.ui.theme.MyFinancesTheme

// AppCompatActivity (no ComponentActivity): necesario para que
// AppCompatDelegate.setApplicationLocales aplique y persista el idioma
// elegido por el usuario en todas las versiones de Android (minSdk 26).
class MainActivity : AppCompatActivity() {

    // Solicitud de captura por voz desde el Tile de Ajustes Rápidos.
    // Es Compose State: AppNavigation lo lee y abre la hoja al cambiar.
    private val voiceRequest = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestMaxRefreshRate()
        if (intent?.action == ACTION_VOICE_CAPTURE) voiceRequest.value = true
        setContent {
            MyFinancesTheme {
                AppNavigation(
                    voiceRequest = voiceRequest.value,
                    onVoiceRequestHandled = { voiceRequest.value = false }
                )
            }
        }
    }

    // La activity es singleTop por launchSingleTop/CLEAR_TOP del tile:
    // un re-lanzamiento entra por aquí en vez de recrearse.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_VOICE_CAPTURE) voiceRequest.value = true
    }

    /**
     * Pide explícitamente el modo de pantalla de MAYOR tasa de refresco
     * (a la resolución actual). Sin esto, varios fabricantes dejan la
     * app a 60Hz aunque el panel soporte 90/120Hz, y las animaciones se
     * perciben "saltadas" sin importar su duración.
     */
    private fun requestMaxRefreshRate() {
        val display: Display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display ?: return
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay ?: return
        }
        val current = display.mode ?: return
        val best = display.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight
            }
            .maxByOrNull { it.refreshRate } ?: return

        window.attributes = window.attributes.apply {
            preferredDisplayModeId = best.modeId
        }
    }

    companion object {
        const val ACTION_VOICE_CAPTURE = "co.purrito.myfinances.action.VOICE_CAPTURE"
    }
}
