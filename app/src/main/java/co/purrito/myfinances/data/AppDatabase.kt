package co.purrito.myfinances.data

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import co.purrito.myfinances.R
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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

@Database(
    entities = [
        Account::class,
        Category::class,
        Transaction::class,
        DeferredPurchase::class,
        MerchantAlias::class,
        SmsTemplate::class
    ],
    version = 4,
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

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val passphrase = DbCrypto.getOrCreatePassphrase(context)
                    // Migra la BD plana preexistente a SQLCipher (una vez)
                    DbCrypto.encryptIfPlaintext(context, "myfinances.db", passphrase)
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "myfinances.db"
                    )
                        .openHelperFactory(
                            SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8))
                        )
                        // Solo las BD LEGACY (v1–v3, de builds previos a la
                        // exportación de esquemas) se recrean: no existe su
                        // historial de esquemas para migrarlas. De v4 en
                        // adelante NO hay fallback: todo cambio exige su
                        // Migration (.addMigrations(MIGRATION_4_5, ...)).
                        .fallbackToDestructiveMigrationFrom(1, 2, 3)
                        .build()
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
