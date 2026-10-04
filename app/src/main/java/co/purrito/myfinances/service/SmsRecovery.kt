package co.purrito.myfinances.service

import co.purrito.myfinances.data.AppDatabase

/* =====================================================================
 * Recuperación de SMS perdidos (Hito 7): SMS de la bandeja que hoy SÍ
 * reconoce una plantilla pero que no están en la app (llegaron cuando la
 * plantilla no existía o no servía). No se agregan solos: el usuario los
 * revisa y elige — así un SMS que él ya había rechazado en el inbox no
 * "resucita" sin que lo decida.
 * ===================================================================== */

data class MissedSms(val sms: DeviceSms, val result: SmsParseResult)

object SmsRecovery {

    /**
     * @param onlyTemplateId si se da, solo los que reconocería ESA
     *   plantilla (con el orden real: si otra anterior los toma, no cuentan).
     */
    suspend fun findMissed(
        db: AppDatabase,
        inbox: List<DeviceSms>,
        onlyTemplateId: Long? = null
    ): List<MissedSms> {
        val templates = db.smsTemplateDao().getEnabled()
        return inbox.mapNotNull { sms ->
            val result = SmsParser.parse(sms.sender, sms.body, sms.timestampMillis, templates)
                ?: return@mapNotNull null
            if (onlyTemplateId != null && result.templateId != onlyTemplateId) return@mapNotNull null
            val known = db.transactionDao().existsByExternalRef(result.externalRef) ||
                db.transactionDao().existsByRawText(sms.body)
            if (known) null else MissedSms(sms, result)
        }
    }

    /** Lleva al inbox (PENDING) los elegidos. Devuelve cuántos se agregaron. */
    suspend fun recover(db: AppDatabase, missed: List<MissedSms>): Int =
        missed.count { m ->
            SmsCapture.capture(db, m.sms.body, m.sms.timestampMillis, m.result, fromInbox = true) != null
        }
}
