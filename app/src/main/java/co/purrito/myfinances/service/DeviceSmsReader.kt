package co.purrito.myfinances.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat

/* =====================================================================
 * Lectura de la bandeja de SMS del teléfono (permiso READ_SMS, que la
 * app ya pide desde el Hito 2). Para enseñar plantillas a partir de un
 * SMS real y recuperar los que no se reconocieron a tiempo (Hito 7).
 * Solo lectura y 100% local.
 * ===================================================================== */

data class DeviceSms(val sender: String, val body: String, val timestampMillis: Long)

object DeviceSmsReader {

    private const val DAY = 24L * 60 * 60 * 1000

    /** SMS recibidos en los últimos `days` días, del más reciente al más viejo. */
    fun recent(context: Context, days: Int = 30, limit: Int = 500): List<DeviceSms> {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return emptyList()

        val since = System.currentTimeMillis() - days * DAY
        val out = mutableListOf<DeviceSms>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT),
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(since.toString()),
            "${Telephony.Sms.DATE} DESC"
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val sender = c.getString(0) ?: continue
                val body = c.getString(1) ?: continue
                // DATE_SENT es el timestamp del centro de mensajes: el mismo
                // que ve el receptor (SmsMessage.timestampMillis). Si falta,
                // la fecha de recepción.
                val sent = c.getLong(3)
                out += DeviceSms(sender, body, if (sent > 0) sent else c.getLong(2))
            }
        }
        return out
    }
}
