package co.purrito.myfinances.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/* =====================================================================
 * MODELO DE DATOS — App de finanzas personales
 *
 * Convenciones globales:
 *  - Dinero: SIEMPRE Long en centavos (ej: $25.000 COP = 2_500_000).
 *    Nunca Double/Float. Se formatea solo en la capa de UI.
 *  - Fechas: Long epoch millis (Instant.toEpochMilli()).
 *  - Montos siempre POSITIVOS; el signo lo da TransactionType.
 *  - Room convierte los enums a String automáticamente (por nombre).
 * ===================================================================== */

// ---------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------

enum class AccountType {
    CASH,           // Efectivo
    BANK,           // Cuenta corriente / ahorros transaccional
    SAVINGS,        // Ahorro / bolsillos
    CREDIT_CARD     // Tarjeta de crédito: su saldo es DEUDA (negativo)
}

enum class TransactionType {
    EXPENSE,        // Gasto: resta al saldo de accountId
    INCOME,         // Ingreso: suma al saldo de accountId
    TRANSFER        // Movimiento accountId -> counterAccountId.
                    // NO es gasto ni ingreso: no aparece en reportes
                    // de categorías. El pago de la TC es de este tipo.
}

enum class TransactionSource {
    MANUAL,         // Ingresada a mano
    SMS,            // Capturada del SMS bancario
    VOICE,          // Dictada por voz
    OCR,            // Foto de recibo
    STATEMENT,      // Importada de un extracto
    RECONCILIATION  // Generada por el modo salida (diferencia de efectivo)
}

enum class TransactionStatus {
    PENDING,        // Capturada automáticamente, espera confirmación en el inbox
    CONFIRMED       // Validada por el usuario (o creada manualmente)
}

// ---------------------------------------------------------------------
// Cuentas
// ---------------------------------------------------------------------

@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,                  // "Bancolombia", "Efectivo", "TC Visa"
    val type: AccountType,
    val currency: String = "COP",
    val initialBalanceMinor: Long = 0, // En centavos. Para TC suele ser 0 o negativo (deuda inicial)
    /** Solo tarjetas de crédito: día del mes de corte (1..28). */
    val statementDay: Int? = null,
    /** Solo tarjetas de crédito: día límite de pago (1..28). */
    val paymentDueDay: Int? = null,
    /** Últimos 4 dígitos, para matchear contra SMS/extractos. */
    val lastFourDigits: String? = null,
    val archived: Boolean = false
)

// ---------------------------------------------------------------------
// Categorías (jerárquicas: parentId permite subcategorías)
// ---------------------------------------------------------------------

@Entity(
    tableName = "categories",
    foreignKeys = [ForeignKey(
        entity = Category::class,
        parentColumns = ["id"],
        childColumns = ["parentId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("parentId")]
)
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,                  // "Restaurantes", "Mercado", "Tecnología"
    val parentId: Long? = null,        // null = categoría raíz
    val isIncome: Boolean = false,     // Separa categorías de ingreso ("Salario")
    val icon: String? = null,          // Nombre de ícono Material, para la UI
    val colorArgb: Int? = null,
    /**
     * Soft-delete. "Eliminar" una categoría la archiva en lugar de
     * borrar la fila: las transacciones históricas conservan su
     * categoría (los JOIN siguen resolviendo el nombre), pero la
     * categoría deja de ofrecerse en listas y formularios. Un DELETE
     * real pondría categoryId = NULL en las transacciones (FK SET_NULL)
     * y se perdería el historial.
     */
    val archived: Boolean = false
)

// ---------------------------------------------------------------------
// Compras diferidas (cuotas de tarjeta de crédito)
//
// La compra ORIGINAL se registra como Transaction(EXPENSE, monto total,
// cuenta = TC) en su fecha y categoría: ahí vive la "verdad de
// categorías". Esta entidad guarda el plan de cuotas para la "verdad de
// flujo de caja". Las cuotas proyectadas se CALCULAN (no se almacenan
// como transacciones) a partir de estos campos; el extracto mensual
// luego confirma cuáles ya fueron facturadas (billedInstallments).
//
// Nota: modela el plan de facturación de una DEUDA, no una compra — el
// nombre es histórico. También un avance (TRANSFER con origen TC) puede
// tener plan de cuotas.
// ---------------------------------------------------------------------

@Entity(
    tableName = "deferred_purchases",
    foreignKeys = [ForeignKey(
        entity = Account::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("accountId")]
)
data class DeferredPurchase(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,               // La tarjeta de crédito
    val merchant: String,              // "Falabella"
    val purchaseDateMillis: Long,
    val totalAmountMinor: Long,        // Capital total de la compra
    val totalInstallments: Int,        // 12
    /**
     * Valor de la cuota según el extracto. Puede incluir intereses,
     * por eso no siempre es total/cuotas. Si aún no se conoce
     * (la compra acaba de capturarse por SMS), null y se estima.
     */
    val installmentAmountMinor: Long? = null,
    /** Cuotas ya facturadas en extractos importados. */
    val billedInstallments: Int = 0,
    val closed: Boolean = false        // true cuando se factura la última cuota
)

// ---------------------------------------------------------------------
// Transacciones
// ---------------------------------------------------------------------

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["counterAccountId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = DeferredPurchase::class,
            parentColumns = ["id"],
            childColumns = ["deferredPurchaseId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("accountId"),
        Index("counterAccountId"),
        Index("categoryId"),
        Index("deferredPurchaseId"),
        Index("dateMillis"),
        // Clave de deduplicación: evita registrar dos veces el mismo
        // SMS o la misma línea de extracto.
        Index(value = ["externalRef"], unique = true)
    ]
)
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,               // Cuenta afectada (origen si es TRANSFER)
    val counterAccountId: Long? = null,// Destino. Solo para TRANSFER
    val type: TransactionType,
    val amountMinor: Long,             // Siempre > 0; el signo lo da `type`
    val categoryId: Long? = null,      // null en TRANSFER y en pendientes sin clasificar
    val dateMillis: Long,
    val description: String? = null,   // Editable por el usuario: "Almuerzo con Lau"
    val merchantRaw: String? = null,   // Texto crudo del SMS/extracto: "PAYU*RAPPI BOG"
    val source: TransactionSource,
    val status: TransactionStatus,
    /** Si es la compra original de algo diferido, apunta a su plan de cuotas. */
    val deferredPurchaseId: Long? = null,
    /**
     * Huella única del origen para deduplicar:
     *  - SMS: hash(remitente + cuerpo + timestamp)
     *  - Extracto: hash(cuenta + fecha + monto + descripción + nro línea)
     * null para transacciones manuales/voz.
     */
    val externalRef: String? = null,
    val notes: String? = null,
    /**
     * Cuerpo original del SMS que generó esta transacción, para poder
     * mostrarlo como preview en el inbox ("Ver SMS original").
     * null para transacciones de otras fuentes.
     */
    val rawText: String? = null,
    /**
     * true cuando un extracto ya CONFIRMÓ este movimiento (o nació de
     * uno). La verificación de saldo del extracto solo cuenta estos: lo
     * no confirmado (compras del próximo corte, movimientos en dólares que
     * el "Pago total" en pesos no incluye) no genera ajustes. Solo tiene
     * sentido en tarjetas de crédito. (DB v5)
     */
    @ColumnInfo(defaultValue = "0")
    val reconciled: Boolean = false
)

// ---------------------------------------------------------------------
// Diccionario de alias de comercios (aprende de tus correcciones)
// "PAYU*RAPPI BOG" -> "Rappi", categoría sugerida: Domicilios
// ---------------------------------------------------------------------

@Entity(
    tableName = "merchant_aliases",
    foreignKeys = [ForeignKey(
        entity = Category::class,
        parentColumns = ["id"],
        childColumns = ["defaultCategoryId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index(value = ["rawPattern"], unique = true), Index("defaultCategoryId")]
)
data class MerchantAlias(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Substring (en mayúsculas) a buscar en merchantRaw. */
    val rawPattern: String,
    val displayName: String,
    val defaultCategoryId: Long? = null
)

// ---------------------------------------------------------------------
// Plantillas de parsing de SMS por banco
//
// Un SMS entrante se prueba contra cada plantilla activa. La primera
// que matchea (remitente + regex) produce una Transaction PENDING.
// Mantenerlas como datos (no hardcodeadas) permite ajustarlas desde la
// app cuando el banco cambie el formato, sin recompilar.
// ---------------------------------------------------------------------

@Entity(
    tableName = "sms_templates",
    foreignKeys = [
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["counterAccountId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("accountId"), Index("counterAccountId")]
)
data class SmsTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bankName: String,              // "Bancolombia"
    val accountId: Long,               // Cuenta a la que aplican los matches
    /** Regex sobre el remitente del SMS (ej: "^8915[0-9]*$" o "BANCOLOMBIA"). */
    val senderPattern: String,
    /**
     * Regex sobre el cuerpo, con GRUPOS NOMBRADOS:
     *   (?<amount>...) obligatorio
     *   (?<merchant>...) opcional
     *   (?<lastFour>...) opcional, para enrutar a la cuenta correcta
     * Ej. Bancolombia compra:
     *   "Compra por \\$(?<amount>[\\d.,]+) en (?<merchant>.+?) con tu tarjeta \\*(?<lastFour>\\d{4})"
     */
    val bodyPattern: String,
    val resultingType: TransactionType, // EXPENSE para compras, INCOME para abonos
    /**
     * Solo para resultingType = TRANSFER con destino conocido de antemano:
     * el pago de TC va a la tarjeta, el retiro de cajero va a Efectivo.
     * null cuando el destino no se conoce (ej: avance de TC).
     */
    val counterAccountId: Long? = null,
    val enabled: Boolean = true,
    /**
     * SMS de ejemplo con que el usuario ENSEÑÓ esta plantilla (Hito 7).
     * null = plantilla por defecto del seed. (DB v6)
     */
    val exampleBody: String? = null,
    /** Última vez que reconoció un SMS: diagnóstico en la pantalla de plantillas. */
    val lastMatchedMillis: Long? = null
)
