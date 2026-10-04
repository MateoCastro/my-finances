package co.purrito.myfinances.data

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import co.purrito.myfinances.R
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import co.purrito.myfinances.data.dao.*
import co.purrito.myfinances.data.model.*
import co.purrito.myfinances.service.DefaultSmsTemplates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/* =====================================================================
 * Base de datos Room
 *
 * HAY DATOS REALES (desde 2026-06-12): no existe red de seguridad
 * destructiva. Todo cambio de esquema exige:
 *   1. Subir `version`
 *   2. Definir la Migration v(N-1)→vN abajo y registrarla en
 *      .addMigrations(...)
 *   3. Compilar (KSP exporta el esquema nuevo a app/schemas/) y
 *      comparar el JSON contra la versión anterior para escribir el SQL
 * Si falta la migración, Room lanza IllegalStateException al abrir:
 * la app no arranca, pero los datos quedan intactos.
 *
 * Pendiente: cifrado con SQLCipher. Es agregar la dependencia y pasar
 * un SupportOpenHelperFactory con la passphrase (guardada en Android
 * Keystore) al builder.
 * ===================================================================== */

/**
 * v4 → v5: `transactions.reconciled` (movimiento ya confirmado por un
 * extracto). Aditiva: no toca datos existentes salvo el backfill.
 *
 * Backfill: lo que ya pasó por una importación se da por conciliado —
 * las líneas nacidas de un extracto (source STATEMENT) y, en cada
 * tarjeta, los movimientos CONFIRMED hasta la fecha de su último
 * extracto importado (los ajustes de saldo anteriores ya los cuadraron).
 * Lo posterior queda en 0 y lo confirma el próximo extracto.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions ADD COLUMN reconciled INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            """
            UPDATE transactions SET reconciled = 1
            WHERE source = 'STATEMENT'
               OR (status = 'CONFIRMED' AND EXISTS (
                    SELECT 1 FROM accounts a
                    WHERE a.type = 'CREDIT_CARD'
                      AND a.id IN (transactions.accountId, transactions.counterAccountId)
                      AND transactions.dateMillis <= (
                          SELECT MAX(s.dateMillis) FROM transactions s
                          WHERE s.source = 'STATEMENT'
                            AND (s.accountId = a.id OR s.counterAccountId = a.id))))
            """
        )
    }
}

/**
 * v5 → v6 (Hito 7, enseñar SMS): `sms_templates.exampleBody` y
 * `lastMatchedMillis`. Aditiva. Además corrige en los datos el remitente
 * de las plantillas de Davivienda: el banco pasó de 89xxxx a 87188 y sus
 * SMS dejaron de reconocerse (el patrón viejo exigía "89").
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sms_templates ADD COLUMN exampleBody TEXT")
        db.execSQL("ALTER TABLE sms_templates ADD COLUMN lastMatchedMillis INTEGER")
        db.execSQL(
            """UPDATE sms_templates SET senderPattern = '^8\d{4,5}$' WHERE senderPattern = '^89\d{4}$'"""
        )
    }
}

@Database(
    entities = [
        Account::class,
        Category::class,
        Transaction::class,
        DeferredPurchase::class,
        MerchantAlias::class,
        SmsTemplate::class
    ],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun deferredPurchaseDao(): DeferredPurchaseDao
    abstract fun merchantAliasDao(): MerchantAliasDao
    abstract fun smsTemplateDao(): SmsTemplateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Builder compartido. Expuesto aparte de [get] para poder abrir una
         * COPIA de la BD (mismo cifrado y migraciones) al verificar una
         * migración contra datos reales sin tocar el archivo original.
         */
        fun build(context: Context, name: String, passphrase: String): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .openHelperFactory(
                    SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8))
                )
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                // Solo las BD LEGACY (v1–v3, de builds previos a la
                // exportación de esquemas) se recrean: no existe su
                // historial de esquemas para migrarlas. De v4 en
                // adelante NO hay fallback: todo cambio exige su
                // Migration (.addMigrations(MIGRATION_4_5, ...)).
                .fallbackToDestructiveMigrationFrom(1, 2, 3)
                .build()

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val passphrase = DbCrypto.getOrCreatePassphrase(context)
                    // Migra la BD plana preexistente a SQLCipher (una vez)
                    DbCrypto.encryptIfPlaintext(context, "myfinances.db", passphrase)
                    build(context, "myfinances.db", passphrase)
                }
                    .also { db ->
                        INSTANCE = db
                        // Auto-seed: si la BD está vacía (primera ejecución
                        // o recreada por migración destructiva), se cargan
                        // las cuentas y plantillas por defecto. Sin esto,
                        // el parser de SMS no tiene contra qué matchear.
                        CoroutineScope(Dispatchers.IO).launch {
                            if (db.accountDao().count() == 0) seedDefaults(db, context)
                        }
                    }
            }
    }
}

/* =====================================================================
 * Datos por defecto: la configuración REAL del usuario (cuentas,
 * categorías base, plantillas SMS de sus bancos y alias conocidos).
 * No incluye transacciones: esas nacen de los SMS o del formulario.
 *
 * Los saldos iniciales arrancan en 0; cuando exista la pantalla de
 * edición de cuentas se podrán ajustar al saldo real.
 * ===================================================================== */

suspend fun seedDefaults(db: AppDatabase, context: Context) {
    // Categorías semilla en el idioma ACTIVO de la app. Se resuelven con
    // un contexto configurado al locale per-app (AppCompatDelegate), para
    // que funcione igual en API < 33 (donde el contexto de aplicación no
    // siempre refleja el locale per-app).
    val res = localizedContext(context)
    fun s(id: Int) = res.getString(id)

    val food = db.categoryDao().insert(Category(name = s(R.string.seed_cat_restaurants)))
    db.categoryDao().insert(Category(name = s(R.string.seed_cat_groceries)))
    db.categoryDao().insert(Category(name = s(R.string.seed_cat_transport)))
    db.categoryDao().insert(Category(name = s(R.string.seed_cat_tech)))
    db.categoryDao().insert(Category(name = s(R.string.seed_cat_salary), isIncome = true))
    // Cargos del banco al importar extractos (Hito 4): intereses, cuota
    // de manejo, seguros. El importador la busca por su nombre por defecto.
    db.categoryDao().insert(Category(name = s(R.string.seed_cat_financial)))

    val cash = db.accountDao().insert(
        Account(name = s(R.string.seed_acc_cash), type = AccountType.CASH)
    )
    val bank = db.accountDao().insert(
        Account(name = "Bancolombia", type = AccountType.BANK)
    )
    val card = db.accountDao().insert(
        Account(
            name = "TC Bancolombia",
            type = AccountType.CREDIT_CARD,
            lastFourDigits = "1111"
        )
    )
    val cardDavivienda = db.accountDao().insert(
        Account(
            name = "TC Davivienda",
            type = AccountType.CREDIT_CARD,
            lastFourDigits = "1111"
        )
    )

    DefaultSmsTemplates.bancolombia(
        cuentaId = bank,
        tarjetaCreditoId = card,
        efectivoId = cash
    ).forEach { db.smsTemplateDao().insert(it) }
    DefaultSmsTemplates.davivienda(tarjetaCreditoId = cardDavivienda)
        .forEach { db.smsTemplateDao().insert(it) }

    db.merchantAliasDao().insert(
        MerchantAlias(
            rawPattern = "PAYU*RAPPI",
            displayName = "Rappi",
            defaultCategoryId = food
        )
    )
}

/**
 * Contexto con el locale per-app activo (el que fija AppCompatDelegate).
 * En API < 33 el contexto de aplicación puede no reflejarlo, así que se
 * construye uno explícito; si no hay locale per-app, se usa el del sistema.
 */
private fun localizedContext(context: Context): Context {
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return context
    val config = Configuration(context.resources.configuration)
    config.setLocales(LocaleList(locales[0]))
    return context.createConfigurationContext(config)
}
