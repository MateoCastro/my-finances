package co.purrito.myfinances.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import co.purrito.myfinances.MainActivity

/* =====================================================================
 * Tile de Ajustes Rápidos (Hito 6): micrófono a un swipe + tap desde
 * cualquier lugar del sistema, sin abrir la app manualmente.
 *
 * Al tocar, abre MainActivity con la acción VOICE_CAPTURE, que dispara
 * la hoja de captura por voz. En API 34+ startActivityAndCollapse exige
 * un PendingIntent (la sobrecarga con Intent lanza excepción).
 * ===================================================================== */

class VoiceTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_VOICE_CAPTURE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
