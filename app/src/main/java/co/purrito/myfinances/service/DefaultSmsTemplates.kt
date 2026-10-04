package co.purrito.myfinances.service

import co.purrito.myfinances.data.model.SmsTemplate
import co.purrito.myfinances.data.model.TransactionType

/* =====================================================================
 * Plantillas por defecto, derivadas de SMS reales (jun 2026).
 *
 * Función pura para poder testearlas con JUnit sin Android. El seed
 * las inserta en la BD; desde ahí son editables si el banco cambia
 * el formato.
 *
 * Remitentes observados:
 *   Bancolombia: 85540, 85784, 87400  →  ^8[57]\d{3}$
 *   Davivienda:  890077, 891000, 87188 →  ^8\d{4,5}$
 *     (2026-10: Davivienda pasó a 87188, que también encaja en el patrón
 *     de Bancolombia; no importa: el CUERPO distingue al banco.)
 *
 * Nota sobre montos: Bancolombia mezcla formato colombiano
 * ("44.444,44") y americano ("10,000.00") según el canal; la
 * normalización vive en SmsParser.parseAmountToMinor.
 * ===================================================================== */

private const val SENDER_BANCOLOMBIA = """^8[57]\d{3}$"""
private const val SENDER_DAVIVIENDA = """^8\d{4,5}$"""

object DefaultSmsTemplates {

    /**
     * @param cuentaId cuenta bancaria Bancolombia (débito/ahorros)
     * @param tarjetaCreditoId TC Bancolombia
     * @param efectivoId cuenta Efectivo (destino de retiros de cajero)
     */
    fun bancolombia(
        cuentaId: Long,
        tarjetaCreditoId: Long,
        efectivoId: Long
    ): List<SmsTemplate> = listOf(
        // Compra con TC, variante "asociada a T.Cred" (remitente 85540)
        SmsTemplate(
            bankName = "Bancolombia",
            accountId = tarjetaCreditoId,
            senderPattern = SENDER_BANCOLOMBIA,
            bodyPattern = """Compraste (?:COP|\$)?(?<amount>[\d.,]+) en (?<merchant>.+?), el .*asociada a T\.Cred \*(?<lastFour>\d{4})""",
            resultingType = TransactionType.EXPENSE
        ),
        // Compra con TC, variante "con tu T.Cred" (remitente 85784)
        SmsTemplate(
            bankName = "Bancolombia",
            accountId = tarjetaCreditoId,
            senderPattern = SENDER_BANCOLOMBIA,
            bodyPattern = """Compraste (?:COP|\$)?(?<amount>[\d.,]+) en (?<merchant>.+?) con tu T\.Cred \*(?<lastFour>\d{4})""",
            resultingType = TransactionType.EXPENSE
        ),
        // Compra con tarjeta débito
        SmsTemplate(
            bankName = "Bancolombia",
            accountId = cuentaId,
            senderPattern = SENDER_BANCOLOMBIA,
            bodyPattern = """Compraste (?:COP|\$)?(?<amount>[\d.,]+) en (?<merchant>.+?) con tu T\.Deb \*+(?<lastFour>\d{4})""",
            resultingType = TransactionType.EXPENSE
        ),
        // Avance de TC: la deuda crece en la TC y la plata cae a la cuenta.
        // (El banco también envía el SMS de "Recibiste una transferencia";
        // ese queda en el inbox para descartar, o se vuelve doble registro.)
        SmsTemplate(
            bankName = "Bancolombia",
            accountId = tarjetaCreditoId,
            senderPattern = SENDER_BANCOLOMBIA,
            bodyPattern = """Hiciste un avance de \$?(?<amount>[\d.,]+) en tu (?<merchant>.+?) el .*desde tu T\.Credito \*(?<lastFour>\d{4})""",
            resultingType = TransactionType.TRANSFER,
            counterAccountId = cuentaId
        ),
        // Pago de la TC desde la cuenta: TRANSFER cuenta -> tarjeta
        SmsTemplate(
            bankName = "Bancolombia",
            accountId = cuentaId,
            senderPattern = SENDER_BANCOLOMBIA,
            bodyPattern = """Pagaste \$?(?<amount>[\d.,]+) en la tarjeta de credito \*(?<lastFour>\d{4}) desde la cuenta""",
            resultingType = TransactionType.TRANSFER,
            counterAccountId = tarjetaCreditoId
        ),
        // Pago por PSE a un tercero (recaudo/servicios): gasto desde la
        // cuenta. Va DESPUÉS del pago de TC porque ambos empiezan por
        // "Pagaste" (el de TC es más específico: "en la tarjeta de
        // credito").
        SmsTemplate(
            bankName = "Bancolombia",
            accountId = cuentaId,
            senderPattern = SENDER_BANCOLOMBIA,
            bodyPattern = """Pagaste \$?(?<amount>[\d.,]+) a (?<merchant>.+?) desde tu producto \*?(?<lastFour>\d{4})""",
            resultingType = TransactionType.EXPENSE
        ),
        // Retiro de cajero: TRANSFER cuenta -> efectivo
        SmsTemplate(
            bankName = "Bancolombia",
            accountId = cuentaId,
            senderPattern = SENDER_BANCOLOMBIA,
            bodyPattern = """Retiraste \$?(?<amount>[\d.,]+) en (?<merchant>.+?) de tu T\.Deb \*+(?<lastFour>\d{4})""",
            resultingType = TransactionType.TRANSFER,
            counterAccountId = efectivoId
        ),
        // Pago online por Botón Bancolombia
        SmsTemplate(
            bankName = "Bancolombia",
            accountId = cuentaId,
            senderPattern = SENDER_BANCOLOMBIA,
            bodyPattern = """Transferiste \$?(?<amount>[\d.,]+) por Boton Bancolombia a (?<merchant>.+?) desde producto""",
            resultingType = TransactionType.EXPENSE
        ),
        // Pago por código QR
        SmsTemplate(
            bankName = "Bancolombia",
            accountId = cuentaId,
            senderPattern = SENDER_BANCOLOMBIA,
            bodyPattern = """pagaste \$?(?<amount>[\d.,]+) por codigo QR desde tu cuenta \*?(?<lastFour>\d{4}) a la llave (?<merchant>\S+)""",
            resultingType = TransactionType.EXPENSE
        ),
        // Transferencia enviada por llave (Bre-b): el destinatario es persona
        SmsTemplate(
            bankName = "Bancolombia",
            accountId = cuentaId,
            senderPattern = SENDER_BANCOLOMBIA,
            bodyPattern = """transferiste \$?(?<amount>[\d.,]+) a la llave \S+ desde tu cuenta \*?(?<lastFour>\d{4}) a (?<merchant>.+?) el """,
            resultingType = TransactionType.EXPENSE
        ),
        // Transferencia enviada genérica a una cuenta de tercero.
        // Criterio: por defecto es gasto; si era entre cuentas propias,
        // se corrige en el inbox.
        SmsTemplate(
            bankName = "Bancolombia",
            accountId = cuentaId,
            senderPattern = SENDER_BANCOLOMBIA,
            bodyPattern = """Transferiste \$?(?<amount>[\d.,]+) desde tu cuenta \*?(?<lastFour>\d{4}) a la cuenta""",
            resultingType = TransactionType.EXPENSE
        ),
        // Transferencia recibida por llave
        SmsTemplate(
            bankName = "Bancolombia",
            accountId = cuentaId,
            senderPattern = SENDER_BANCOLOMBIA,
            bodyPattern = """recibiste una transferencia de (?<merchant>.+?) por \$?(?<amount>[\d.,]+) en tu cuenta""",
            resultingType = TransactionType.INCOME
        ),
        // Transferencia recibida genérica
        SmsTemplate(
            bankName = "Bancolombia",
            accountId = cuentaId,
            senderPattern = SENDER_BANCOLOMBIA,
            bodyPattern = """Recibiste una transferencia por \$?(?<amount>[\d.,]+) de (?<merchant>.+?) en tu cuenta""",
            resultingType = TransactionType.INCOME
        ),
        // Pago de nómina
        SmsTemplate(
            bankName = "Bancolombia",
            accountId = cuentaId,
            senderPattern = SENDER_BANCOLOMBIA,
            bodyPattern = """Recibiste un pago de Nomina de (?<merchant>.+?) por \$?(?<amount>[\d.,]+) en tu cuenta""",
            resultingType = TransactionType.INCOME
        )
    )

    /** @param tarjetaCreditoId TC Davivienda */
    fun davivienda(tarjetaCreditoId: Long): List<SmsTemplate> = listOf(
        // Compra: "DAVIVIENDA: Compra . Aprobado(a), $8,888, Tarjeta *1111, Hora 19:22,Lugar UBER RIDES ."
        SmsTemplate(
            bankName = "Davivienda",
            accountId = tarjetaCreditoId,
            senderPattern = SENDER_DAVIVIENDA,
            bodyPattern = """Compra\s*\.\s*Aprobado\(a\),\s*\$?(?<amount>[\d.,]+),\s*Tarjeta\s+\*(?<lastFour>\d{4}),\s*Hora\s+[\d:]+,\s*Lugar\s+(?<merchant>.+)\.""",
            resultingType = TransactionType.EXPENSE
        ),
        // Avance: deuda crece en la TC; el destino del dinero llega por
        // su propio canal (ej: SMS de recepción de Bancolombia).
        SmsTemplate(
            bankName = "Davivienda",
            accountId = tarjetaCreditoId,
            senderPattern = SENDER_DAVIVIENDA,
            bodyPattern = """Avance\s*\.\s*Aprobado\(a\),\s*\$?(?<amount>[\d.,]+),\s*Tarjeta\s+\*(?<lastFour>\d{4}),\s*Hora\s+[\d:]+,\s*Lugar\s+(?<merchant>.+)\.""",
            resultingType = TransactionType.TRANSFER,
            counterAccountId = null
        )
    )
}
