package co.purrito.myfinances.service

import co.purrito.myfinances.data.AppDatabase
import co.purrito.myfinances.data.model.Transaction
import co.purrito.myfinances.data.model.TransactionSource
import co.purrito.myfinances.data.model.TransactionStatus
import co.purrito.myfinances.data.model.TransactionType

/* =====================================================================
 * Captura de un SMS reconocido → transacción PENDING en el inbox.
 *
 * Compartida por el receptor (SMS que llega en vivo) y la recuperación
 * de SMS perdidos (Hito 7: al enseñar una plantilla se revisa la bandeja
 * de SMS), para que ambos caminos creen exactamente lo mismo.
 * ===================================================================== */

object SmsCapture {

    /** Monto con "$"/"COP" delante o con separador de miles ("25,300"). */
    private val moneyLike = Regex("""(?:\$|COP)\s?\d|\d{1,3}(?:[.,]\d{3})+""", RegexOption.IGNORE_CASE)

    /**
     * Inserta la PENDING del SMS. Devuelve el id, o null si ese SMS ya
     * estaba en la app.
     *
     * @param fromInbox true al recuperar de la bandeja: además de la huella
     *   se descarta por texto idéntico (la marca de tiempo del proveedor
     *   puede diferir de la que vio el receptor). En vivo NO: dos compras
     *   iguales en el mismo minuto producen el mismo texto y son dos.
     */
    suspend fun capture(
        db: AppDatabase,
        body: String,
        timestampMillis: Long,
        result: SmsParseResult,
        fromInbox: Boolean = false
    ): Long? {
        if (db.transactionDao().existsByExternalRef(result.externalRef)) return null
        if (fromInbox && db.transactionDao().existsByRawText(body)) return null

        // Diccionario de alias: si el comercio ya es conocido, la
        // transacción llega al inbox con nombre legible y categoría
        // sugerida (el usuario solo confirma).
        val alias = result.merchantRaw?.let { db.merchantAliasDao().findMatch(it) }
        val isTransfer = result.type == TransactionType.TRANSFER

        val id = db.transactionDao().insert(
            Transaction(
                accountId = result.accountId,
                counterAccountId = if (isTransfer) result.counterAccountId else null,
                type = result.type,
                amountMinor = result.amountMinor,
                categoryId = if (isTransfer) null else alias?.defaultCategoryId,
                dateMillis = timestampMillis,
                description = alias?.displayName,
                merchantRaw = result.merchantRaw,
                source = TransactionSource.SMS,
                status = TransactionStatus.PENDING,
                externalRef = result.externalRef,
                rawText = body
            )
        )
        db.smsTemplateDao().markMatched(result.templateId, timestampMillis)
        return id
    }

    /**
     * ¿Parece un SMS de movimiento bancario? Remitente de código corto
     * (los bancos no escriben desde celulares) y un monto en el texto.
     * Si además ninguna plantilla lo reconoció, vale la pena avisar: lo
     * más probable es que el banco haya cambiado el formato.
     */
    fun looksLikeBankSms(sender: String, body: String): Boolean =
        sender.all { it.isDigit() } && sender.length in 4..6 && moneyLike.containsMatchIn(body)
}
