package co.purrito.myfinances.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import co.purrito.myfinances.MainActivity
import co.purrito.myfinances.R

/* =====================================================================
 * Aviso temprano (Hito 7): un SMS con pinta de movimiento bancario que
 * NINGUNA plantilla reconoció. Casi siempre significa que el banco cambió
 * el formato o el remitente — mejor enterarse ese mismo día. Tocar la
 * notificación abre la pantalla de enseñar SMS con ese mensaje cargado.
 * ===================================================================== */

object SmsNotifier {

    private const val CHANNEL_ID = "sms_unrecognized"

    fun notifyUnrecognized(context: Context, sender: String, body: String, timestampMillis: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_TEACH_SMS
            putExtra(MainActivity.EXTRA_SMS_SENDER, sender)
            putExtra(MainActivity.EXTRA_SMS_BODY, body)
            putExtra(MainActivity.EXTRA_SMS_TIMESTAMP, timestampMillis)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        // Un requestCode por SMS: cada notificación abre SU mensaje
        val requestCode = (sender + body).hashCode()
        val pending = PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sms_notification)
            .setContentTitle(context.getString(R.string.sms_unrecognized_title, sender))
            .setContentText(context.getString(R.string.sms_unrecognized_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(requestCode, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.sms_unrecognized_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }
}
