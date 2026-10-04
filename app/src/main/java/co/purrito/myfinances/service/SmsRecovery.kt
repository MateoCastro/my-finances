package co.purrito.myfinances.service

import co.purrito.myfinances.data.AppDatabase
import co.purrito.myfinances.data.model.Transaction
import co.purrito.myfinances.data.model.TransactionType
import kotlin.math.abs
import kotlin.math.sign

/* =====================================================================
 * Recuperación de SMS perdidos (Hito 7): SMS de la bandeja que hoy SÍ
 * reconoce una plantilla pero que no están en la app (llegaron cuando la
 * plantilla no existía o no servía). No se agregan solos: el usuario los
 * revisa y elige — así un SMS que él ya había rechazado en el inbox no
 * "resucita" sin que lo decida.
 *
 * "Ya está en la app" no es solo el mismo SMS (huella / texto crudo): el
 * movimiento puede haber entrado por OTRO camino — registrado a mano
 * mientras el SMS no se reconocía, o importado de un extracto. Por eso
 * también se descarta si en la misma cuenta hay un movimiento del mismo
 * monto (±1 peso), mismo sentido y fecha ±1 día, igual criterio que la
 * reconciliación de extractos.
 * ===================================================================== */

data class MissedSms(val sms: DeviceSms, val result: SmsParseResult)

object SmsRecovery {

    private const val DAY = 24L * 60 * 60 * 1000

    /** Menos de 1 peso: el formulario e inbox trabajan en pesos enteros. */
    private const val AMOUNT_TOLERANCE_MINOR = 100L

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
        // Un movimiento existente "cubre" un solo SMS: dos viajes iguales
        // el mismo día son dos.
        val consumed = mutableSetOf<Long>()
        return inbox.mapNotNull { sms ->
            val result = SmsParser.parse(sms.sender, sms.body, sms.timestampMillis, templates)
                ?: return@mapNotNull null
            if (onlyTemplateId != null && result.templateId != onlyTemplateId) return@mapNotNull null
            val sameSms = db.transactionDao().existsByExternalRef(result.externalRef) ||
                db.transactionDao().existsByRawText(sms.body)
            if (sameSms) return@mapNotNull null

            val nearby = db.transactionDao().getForReconciliation(
                result.accountId, sms.timestampMillis - DAY, sms.timestampMillis + DAY
            )
            val recorded = findRecorded(result, sms.timestampMillis, nearby, consumed)
            if (recorded != null) {
                consumed += recorded.id
                null
            } else {
                MissedSms(sms, result)
            }
        }
    }

    /**
     * Movimiento de la app que ya representa este SMS: misma cuenta, mismo
     * sentido, monto ±1 peso y fecha ±1 día (cualquier status: también
     * cuenta lo que espera en el inbox). Si hay varios, el más parecido.
     */
    internal fun findRecorded(
        result: SmsParseResult,
        timestampMillis: Long,
        candidates: List<Transaction>,
        consumed: Set<Long> = emptySet()
    ): Transaction? {
        val smsEffect = when (result.type) {
            TransactionType.INCOME -> result.amountMinor
            // EXPENSE y TRANSFER salen de la cuenta del SMS
            TransactionType.EXPENSE, TransactionType.TRANSFER -> -result.amountMinor
        }
        return candidates
            .filter { t ->
                t.id !in consumed &&
                    abs(t.amountMinor - result.amountMinor) < AMOUNT_TOLERANCE_MINOR &&
                    abs(t.dateMillis - timestampMillis) <= DAY &&
                    t.effectOn(result.accountId).sign == smsEffect.sign
            }
            .minWithOrNull(
                compareBy<Transaction> { abs(it.amountMinor - result.amountMinor) }
                    .thenBy { abs(it.dateMillis - timestampMillis) }
            )
    }

    /** Lleva al inbox (PENDING) los elegidos. Devuelve cuántos se agregaron. */
    suspend fun recover(db: AppDatabase, missed: List<MissedSms>): Int =
        missed.count { m ->
            SmsCapture.capture(db, m.sms.body, m.sms.timestampMillis, m.result, fromInbox = true) != null
        }
}
