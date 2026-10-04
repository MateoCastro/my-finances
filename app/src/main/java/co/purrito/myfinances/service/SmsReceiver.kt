package co.purrito.myfinances.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import co.purrito.myfinances.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/* =====================================================================
 * Receptor de SMS en segundo plano.
 *
 * goAsync() es el contrato correcto para hacer trabajo asíncrono en un
 * BroadcastReceiver: le dice al sistema "no cierres este proceso todavía,
 * tengo trabajo pendiente", y llama a finish() al terminar.
 * Límite: 10 segundos (más que suficiente para un insert en Room).
 *
 * Diagnóstico: adb logcat -s SmsReceiver
 * ===================================================================== */

private const val TAG = "SmsReceiver"

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            ?.takeIf { it.isNotEmpty() } ?: return

        val db = AppDatabase.get(context)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val templates = db.smsTemplateDao().getEnabled()
                if (templates.isEmpty()) {
                    Log.w(TAG, "SMS recibido pero no hay plantillas activas en la BD")
                    return@launch
                }

                // Agrupa partes de SMS multiparte por número de origen
                messages
                    .groupBy { it.originatingAddress }
                    .forEach { (sender, parts) ->
                        if (sender == null) return@forEach
                        val body = parts.joinToString("") { it.messageBody }
                        val timestampMillis = parts.first().timestampMillis

                        val result = SmsParser.parse(sender, body, timestampMillis, templates)
                        if (result == null) {
                            Log.d(TAG, "Sin match para SMS de '$sender' (${templates.size} plantillas probadas)")
                            // Aviso temprano: con pinta de movimiento bancario
                            // pero sin plantilla → probablemente cambió el formato
                            if (SmsCapture.looksLikeBankSms(sender, body)) {
                                SmsNotifier.notifyUnrecognized(context, sender, body, timestampMillis)
                            }
                            return@forEach
                        }

                        val id = SmsCapture.capture(db, body, timestampMillis, result)
                        if (id == null) {
                            Log.d(TAG, "SMS duplicado ignorado (ya estaba en la app)")
                        } else {
                            Log.d(
                                TAG,
                                "Transacción PENDING #$id creada: ${result.type} " +
                                    "${result.amountMinor} centavos, comercio=${result.merchantRaw}"
                            )
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando SMS", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
