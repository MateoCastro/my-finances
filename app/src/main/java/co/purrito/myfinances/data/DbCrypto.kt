package co.purrito.myfinances.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/* =====================================================================
 * Cifrado de la BD con SQLCipher.
 *
 * La passphrase son 64 chars hex aleatorios (256 bits) generados una
 * sola vez. Se guarda en SharedPreferences ENVUELTA con una llave
 * AES/GCM del Android Keystore: la llave de envoltura nunca sale del
 * hardware, así que el archivo de prefs por sí solo no sirve de nada.
 *
 * encryptIfPlaintext() migra el archivo SQLite plano preexistente
 * (los datos reales anteriores a 2026-06-12) a SQLCipher una única
 * vez, vía sqlcipher_export(). Si la app se cierra a mitad de la
 * exportación, el archivo original queda intacto y se reintenta al
 * siguiente arranque.
 * ===================================================================== */

object DbCrypto {

    private const val KEYSTORE_ALIAS = "myfinances_db_key"
    private const val PREFS = "db_crypto"
    private const val PREF_WRAPPED = "wrapped_passphrase"

    init {
        System.loadLibrary("sqlcipher")
    }

    fun getOrCreatePassphrase(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(PREF_WRAPPED, null)?.let { return unwrap(it) }

        val passphrase = ByteArray(32)
            .also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        // commit() síncrono: si la passphrase no queda persistida ANTES
        // de cifrar la BD, un crash la dejaría cifrada con llave perdida
        check(prefs.edit().putString(PREF_WRAPPED, wrap(passphrase)).commit()) {
            "No se pudo persistir la passphrase de la BD"
        }
        return passphrase
    }

    /** Migra el archivo SQLite plano a SQLCipher (no-op si ya está cifrado). */
    fun encryptIfPlaintext(context: Context, dbName: String, passphrase: String) {
        val dbFile = context.getDatabasePath(dbName)
        if (!dbFile.exists()) return

        // Un SQLite plano empieza con "SQLite format 3"; uno cifrado
        // con SQLCipher arranca con la sal aleatoria (bytes opacos)
        val header = ByteArray(16)
        dbFile.inputStream().use { it.read(header) }
        if (!header.decodeToString().startsWith("SQLite format 3")) return

        val encrypted = File(dbFile.parentFile, "$dbName.encrypting")
        encrypted.delete()

        // Abrir sin llave (BD plana) y exportar a la copia cifrada.
        // Abrirla también procesa el -wal pendiente de Room.
        // CREATE_IF_NECESSARY es para el ATTACH: los attach heredan los
        // flags de la conexión y sin él no puede CREAR el archivo nuevo
        val plain = SQLiteDatabase.openDatabase(
            dbFile.absolutePath, "", null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            null, null
        )
        val schemaVersion = plain.version
        try {
            plain.execSQL(
                "ATTACH DATABASE ? AS encrypted KEY ?",
                arrayOf(encrypted.absolutePath, passphrase)
            )
            plain.rawQuery("SELECT sqlcipher_export('encrypted')").use { it.moveToFirst() }
            // sqlcipher_export NO copia user_version (la versión de
            // esquema de Room): sin esto, Room intentaría migrar de 0
            plain.execSQL("PRAGMA encrypted.user_version = $schemaVersion")
            plain.execSQL("DETACH DATABASE encrypted")
        } finally {
            plain.close()
        }

        // Reemplazo: hasta aquí el original está intacto; un fallo
        // anterior deja el .encrypting huérfano y se reintenta luego
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        check(dbFile.delete() && encrypted.renameTo(dbFile)) {
            "No se pudo reemplazar la BD por su versión cifrada"
        }
    }

    // --- Envoltura de la passphrase con el Android Keystore -----------

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun wrap(passphrase: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val ciphertext = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private fun unwrap(stored: String): String {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            keystoreKey(),
            GCMParameterSpec(128, raw.copyOfRange(0, 12))
        )
        return String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
    }
}
