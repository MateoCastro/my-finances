package co.purrito.myfinances.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import co.purrito.myfinances.data.model.*
import kotlinx.coroutines.flow.Flow

/* =====================================================================
 * DAOs — Capa de acceso a datos
 *
 * Las queries que devuelven Flow<...> son REACTIVAS: la UI que las
 * observe se actualiza sola cuando cambia la tabla (≈ un observable
 * de RxJS que re-emite ante cada escritura). Las suspend fun son
 * operaciones puntuales (≈ async/await).
 * ===================================================================== */

// ---------------------------------------------------------------------
// Proyección auxiliar: cuenta + saldo calculado
// ---------------------------------------------------------------------

data class AccountWithBalance(
    val id: Long,
    val name: String,
    val type: AccountType,
    val currency: String,
    val balanceMinor: Long   // Para CREDIT_CARD será negativo = deuda
)

@Dao
interface AccountDao {

    @Insert
    suspend fun insert(account: Account): Long

    @Update
    suspend fun update(account: Account)

    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY name")
    fun observeAll(): Flow<List<Account>>

    /** Para el auto-seed: ¿la BD está recién creada/vacía? */
    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int

    /**
     * Saldo = inicial + ingresos − gastos − transferencias salientes
     *       + transferencias entrantes.
     * Solo cuentan transacciones CONFIRMED: las PENDING del inbox no
     * afectan saldos hasta que las valides.
     *
     * Nota: con CREDIT_CARD la misma fórmula funciona sola — las compras
     * llevan el saldo a negativo (deuda) y el pago (transferencia
     * entrante) lo acerca a cero.
     */
    @Query(
        """
        SELECT a.id, a.name, a.type, a.currency,
               a.initialBalanceMinor
             + COALESCE((SELECT SUM(CASE
                    WHEN t.type = 'INCOME'  AND t.accountId = a.id THEN  t.amountMinor
                    WHEN t.type = 'EXPENSE' AND t.accountId = a.id THEN -t.amountMinor
                    WHEN t.type = 'TRANSFER' AND t.accountId = a.id THEN -t.amountMinor
                    WHEN t.type = 'TRANSFER' AND t.counterAccountId = a.id THEN t.amountMinor
                    ELSE 0 END)
                 FROM transactions t
                 WHERE (t.accountId = a.id OR t.counterAccountId = a.id)
                   AND t.status = 'CONFIRMED'), 0) AS balanceMinor
        FROM accounts a
        WHERE a.archived = 0
        ORDER BY a.name
        """
    )
    fun observeAllWithBalance(): Flow<List<AccountWithBalance>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    fun observeById(id: Long): Flow<Account?>

    /** Para importar extractos: enrutar a la cuenta por sus últimos 4. */
    @Query("SELECT * FROM accounts WHERE lastFourDigits = :lastFour AND archived = 0 LIMIT 1")
    suspend fun findByLastFour(lastFour: String): Account?

    /** Cuentas activas (one-shot), para elegir el origen de un abono importado. */
    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY name")
    suspend fun getAll(): List<Account>

    /** Saldo CALCULADO de una cuenta (one-shot), misma fórmula reactiva. */
    @Query(
        """
        SELECT a.initialBalanceMinor
             + COALESCE((SELECT SUM(CASE
                    WHEN t.type = 'INCOME'  AND t.accountId = a.id THEN  t.amountMinor
                    WHEN t.type = 'EXPENSE' AND t.accountId = a.id THEN -t.amountMinor
                    WHEN t.type = 'TRANSFER' AND t.accountId = a.id THEN -t.amountMinor
                    WHEN t.type = 'TRANSFER' AND t.counterAccountId = a.id THEN t.amountMinor
                    ELSE 0 END)
                 FROM transactions t
                 WHERE (t.accountId = a.id OR t.counterAccountId = a.id)
                   AND t.status = 'CONFIRMED'), 0)
        FROM accounts a WHERE a.id = :id
        """
    )
    suspend fun balanceOf(id: Long): Long?

    /**
     * Saldo calculado considerando SOLO transacciones hasta `toMillis`
     * (inclusive). Para reconciliar un extracto contra la deuda a su
     * fecha de CORTE, sin que los movimientos posteriores (ej: un pago
     * hecho después del corte) sesguen el ajuste de saldo.
     */
    @Query(
        """
        SELECT a.initialBalanceMinor
             + COALESCE((SELECT SUM(CASE
                    WHEN t.type = 'INCOME'  AND t.accountId = a.id THEN  t.amountMinor
                    WHEN t.type = 'EXPENSE' AND t.accountId = a.id THEN -t.amountMinor
                    WHEN t.type = 'TRANSFER' AND t.accountId = a.id THEN -t.amountMinor
                    WHEN t.type = 'TRANSFER' AND t.counterAccountId = a.id THEN t.amountMinor
                    ELSE 0 END)
                 FROM transactions t
                 WHERE (t.accountId = a.id OR t.counterAccountId = a.id)
                   AND t.status = 'CONFIRMED'
                   AND t.dateMillis <= :toMillis), 0)
        FROM accounts a WHERE a.id = :id
        """
    )
    suspend fun balanceOfUpTo(id: Long, toMillis: Long): Long?

    /**
     * Soft-delete (mismo criterio que las categorías): "eliminar" una
     * cuenta la archiva. Las transacciones históricas la conservan vía
     * JOIN; solo deja de ofrecerse y de listarse. Un DELETE real
     * arrastraría sus transacciones por la FK CASCADE.
     */
    @Query("UPDATE accounts SET archived = 1 WHERE id = :id")
    suspend fun archive(id: Long)
}

@Dao
interface CategoryDao {

    @Insert
    suspend fun insert(category: Category): Long

    @Update
    suspend fun update(category: Category)

    /** Solo las vivas: las archivadas no se ofrecen en formularios ni listas. */
    @Query("SELECT * FROM categories WHERE archived = 0 ORDER BY name")
    fun observeActive(): Flow<List<Category>>

    /**
     * Para resolver categorías del sistema (ej: "Costos financieros") por
     * nombre. Solo ACTIVAS: si el usuario archivó la duplicada en un
     * idioma, no debe matchearla (la UI solo conoce categorías activas).
     */
    @Query("SELECT * FROM categories WHERE name = :name AND archived = 0 LIMIT 1")
    suspend fun findByName(name: String): Category?

    /**
     * Soft-delete (comportamiento Money Manager): las transacciones
     * que ya la usan conservan la categoría; solo desaparece de las
     * opciones futuras.
     */
    @Query("UPDATE categories SET archived = 1 WHERE id = :id")
    suspend fun archive(id: Long)
}

// ---------------------------------------------------------------------
// Proyección auxiliar: total gastado por categoría en un rango
// ---------------------------------------------------------------------

data class CategoryTotal(
    val categoryId: Long?,
    val categoryName: String?,   // null = "Sin categoría"
    val colorArgb: Int?,         // Color configurado de la categoría, si tiene
    val totalMinor: Long
)

// ---------------------------------------------------------------------
// Proyección auxiliar: transacción + nombres legibles para el registro
// general (evita que la UI tenga que cruzar IDs contra catálogos).
// ---------------------------------------------------------------------

data class TransactionWithLabels(
    @Embedded val transaction: Transaction,
    val categoryName: String?,
    val categoryColorArgb: Int?,
    val accountName: String?,
    val counterAccountName: String?
)

@Dao
interface TransactionDao {

    @Insert
    suspend fun insert(transaction: Transaction): Long

    @Update
    suspend fun update(transaction: Transaction)

    @Delete
    suspend fun delete(transaction: Transaction)

    /** Borrado en lote desde la selección múltiple del registro. */
    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): Transaction?

    /**
     * Candidatas para deduplicar un extracto (Hito 4): todas las
     * transacciones de la cuenta (cualquier status: también las PENDING
     * de un SMS previo) dentro del rango de fechas del extracto.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE (accountId = :accountId OR counterAccountId = :accountId)
          AND dateMillis BETWEEN :fromMillis AND :toMillis
        """
    )
    suspend fun getForReconciliation(
        accountId: Long,
        fromMillis: Long,
        toMillis: Long
    ): List<Transaction>

    /**
     * Movimientos CONFIRMED de una cuenta en un rango (incluye
     * transferencias donde es destino), con nombres resueltos. Es la
     * versión por-cuenta de observeByRangeWithLabels.
     */
    @Query(
        """
        SELECT t.*, c.name AS categoryName, c.colorArgb AS categoryColorArgb,
               a.name AS accountName,
               ca.name AS counterAccountName
        FROM transactions t
        LEFT JOIN categories c ON c.id = t.categoryId
        LEFT JOIN accounts a ON a.id = t.accountId
        LEFT JOIN accounts ca ON ca.id = t.counterAccountId
        WHERE t.status = 'CONFIRMED'
          AND (t.accountId = :accountId OR t.counterAccountId = :accountId)
          AND t.dateMillis BETWEEN :fromMillis AND :toMillis
        ORDER BY t.dateMillis DESC
        """
    )
    fun observeByAccountRangeWithLabels(
        accountId: Long,
        fromMillis: Long,
        toMillis: Long
    ): Flow<List<TransactionWithLabels>>

    /** El inbox: todo lo capturado automáticamente que espera tu visto bueno. */
    @Query("SELECT * FROM transactions WHERE status = 'PENDING' ORDER BY dateMillis DESC")
    fun observePending(): Flow<List<Transaction>>

    /** Deduplicación: ¿ya existe una transacción con esta huella? */
    @Query("SELECT EXISTS(SELECT 1 FROM transactions WHERE externalRef = :ref)")
    suspend fun existsByExternalRef(ref: String): Boolean

    /**
     * Totales por categoría (gastos o ingresos según :type) en un rango.
     * Las TRANSFER quedan excluidas por diseño: pagar la tarjeta
     * no es un gasto.
     */
    @Query(
        """
        SELECT t.categoryId AS categoryId, c.name AS categoryName,
               c.colorArgb AS colorArgb, SUM(t.amountMinor) AS totalMinor
        FROM transactions t
        LEFT JOIN categories c ON c.id = t.categoryId
        WHERE t.type = :type
          AND t.status = 'CONFIRMED'
          AND t.dateMillis BETWEEN :fromMillis AND :toMillis
        GROUP BY t.categoryId, c.name, c.colorArgb
        ORDER BY totalMinor DESC
        """
    )
    fun observeTotalsByCategory(type: String, fromMillis: Long, toMillis: Long): Flow<List<CategoryTotal>>

    /**
     * Registro general del mes: transacciones CONFIRMED de todas las
     * cuentas con sus nombres ya resueltos, para agrupar por día en la
     * pantalla principal. Las PENDING viven solo en el inbox.
     */
    @Query(
        """
        SELECT t.*, c.name AS categoryName, c.colorArgb AS categoryColorArgb,
               a.name AS accountName,
               ca.name AS counterAccountName
        FROM transactions t
        LEFT JOIN categories c ON c.id = t.categoryId
        LEFT JOIN accounts a ON a.id = t.accountId
        LEFT JOIN accounts ca ON ca.id = t.counterAccountId
        WHERE t.status = 'CONFIRMED'
          AND t.dateMillis BETWEEN :fromMillis AND :toMillis
        ORDER BY t.dateMillis DESC
        """
    )
    fun observeByRangeWithLabels(fromMillis: Long, toMillis: Long): Flow<List<TransactionWithLabels>>

    /**
     * Búsqueda global (todas las fechas) por texto libre: descripción,
     * texto crudo del SMS, categoría o cuenta. LIKE es case-insensitive
     * para ASCII; suficiente para nombres de comercios/categorías.
     */
    @Query(
        """
        SELECT t.*, c.name AS categoryName, c.colorArgb AS categoryColorArgb,
               a.name AS accountName,
               ca.name AS counterAccountName
        FROM transactions t
        LEFT JOIN categories c ON c.id = t.categoryId
        LEFT JOIN accounts a ON a.id = t.accountId
        LEFT JOIN accounts ca ON ca.id = t.counterAccountId
        WHERE t.status = 'CONFIRMED' AND (
            t.description LIKE '%' || :query || '%'
            OR t.merchantRaw LIKE '%' || :query || '%'
            OR c.name LIKE '%' || :query || '%'
            OR a.name LIKE '%' || :query || '%'
        )
        ORDER BY t.dateMillis DESC
        """
    )
    fun searchWithLabels(query: String): Flow<List<TransactionWithLabels>>

    /**
     * Autocompletado del campo descripción: valores ya usados que
     * contienen lo escrito, los más recientes primero. GROUP BY (y no
     * DISTINCT) porque SQLite no permite ordenar por una columna fuera
     * del SELECT con DISTINCT.
     */
    @Query(
        """
        SELECT description FROM transactions
        WHERE description IS NOT NULL
          AND description LIKE '%' || :query || '%'
        GROUP BY description
        ORDER BY MAX(dateMillis) DESC
        LIMIT 5
        """
    )
    fun observeDescriptionSuggestions(query: String): Flow<List<String>>
}

@Dao
interface DeferredPurchaseDao {

    @Insert
    suspend fun insert(purchase: DeferredPurchase): Long

    @Update
    suspend fun update(purchase: DeferredPurchase)

    /**
     * Planes diferidos abiertos de una tarjeta. El EXISTS exige que el
     * plan conserve su transacción ANCLA: al borrar esa transacción (por
     * cualquier vía) el plan deja de listarse, evitando "huérfanos"
     * duplicados — el plan es una entidad aparte y no se borra en cascada.
     */
    @Query(
        """
        SELECT * FROM deferred_purchases dp
        WHERE dp.closed = 0 AND dp.accountId = :cardId
          AND EXISTS (
            SELECT 1 FROM transactions t WHERE t.deferredPurchaseId = dp.id
          )
        """
    )
    fun observeOpenByCard(cardId: Long): Flow<List<DeferredPurchase>>

    /**
     * One-shot para reconciliar extractos (Hito 4): abiertos CON
     * transacción ancla viva. El EXISTS evita que un plan huérfano
     * (cuya transacción se borró) absorba una línea del extracto como
     * "cuota" en vez de crear la transacción nueva.
     */
    @Query(
        """
        SELECT * FROM deferred_purchases dp
        WHERE dp.closed = 0 AND dp.accountId = :cardId
          AND EXISTS (SELECT 1 FROM transactions t WHERE t.deferredPurchaseId = dp.id)
        """
    )
    suspend fun getOpenByCard(cardId: Long): List<DeferredPurchase>

    /** Borra planes sin transacción ancla (limpieza de huérfanos). */
    @Query(
        """
        DELETE FROM deferred_purchases
        WHERE id NOT IN (
            SELECT deferredPurchaseId FROM transactions WHERE deferredPurchaseId IS NOT NULL
        )
        """
    )
    suspend fun deleteOrphans()

    /** Deuda diferida pendiente de facturar en una tarjeta (estimación de cuotas restantes). */
    @Query(
        """
        SELECT COALESCE(SUM(
            (totalInstallments - billedInstallments) *
            COALESCE(installmentAmountMinor, totalAmountMinor / totalInstallments)
        ), 0)
        FROM deferred_purchases dp
        WHERE dp.closed = 0 AND dp.accountId = :cardId
          AND EXISTS (
            SELECT 1 FROM transactions t WHERE t.deferredPurchaseId = dp.id
          )
        """
    )
    fun observeRemainingDeferredDebt(cardId: Long): Flow<Long>

    /** Para no dejar planes huérfanos al borrar su transacción ancla. */
    @Query("DELETE FROM deferred_purchases WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface MerchantAliasDao {

    @Insert
    suspend fun insert(alias: MerchantAlias): Long

    @Update
    suspend fun update(alias: MerchantAlias)

    @Query("SELECT * FROM merchant_aliases")
    suspend fun getAll(): List<MerchantAlias>

    /** Lookup simple por substring; el matching fino se hace en Kotlin. */
    @Query("SELECT * FROM merchant_aliases WHERE :merchantRaw LIKE '%' || rawPattern || '%' LIMIT 1")
    suspend fun findMatch(merchantRaw: String): MerchantAlias?
}

@Dao
interface SmsTemplateDao {

    @Insert
    suspend fun insert(template: SmsTemplate): Long

    @Update
    suspend fun update(template: SmsTemplate)

    /** Orden por id = orden de inserción: la primera que matchea gana. */
    @Query("SELECT * FROM sms_templates WHERE enabled = 1 ORDER BY id")
    suspend fun getEnabled(): List<SmsTemplate>

    @Query("SELECT * FROM sms_templates ORDER BY bankName")
    fun observeAll(): Flow<List<SmsTemplate>>
}
