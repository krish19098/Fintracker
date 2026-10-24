package com.krish.spendsense.data

import android.content.Context
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom

@Database(
    entities = [
        SimProfile::class, Account::class, AccountSender::class, AccountMatchRule::class, Txn::class,
        Category::class, PayeeRule::class, KeywordRule::class, UnparsedMessage::class, ProcessedHash::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun txnDao(): TxnDao
    abstract fun accountDao(): AccountDao
    abstract fun simDao(): SimDao
    abstract fun categoryDao(): CategoryDao
    abstract fun ruleDao(): RuleDao
    abstract fun senderDao(): SenderDao
    abstract fun unparsedDao(): UnparsedDao
    abstract fun hashDao(): HashDao

    companion object {
        private const val NAME = "spendsense.db"

        /** Encrypted on-device database (SQLCipher), keyed from Android Keystore-backed prefs. */
        fun build(context: Context): AppDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(DbKey.get(context)))
                .addMigrations(*Migrations.ALL)
                .build()
        }
    }
}

/** Migration scaffold: add `Migration(n, n + 1)` objects here and bump the version above. */
object Migrations {
    val ALL: Array<Migration> = emptyArray()
}

/** 32-byte DB key generated once and kept in EncryptedSharedPreferences (Keystore master key). */
object DbKey {
    private const val PREFS = "spendsense_secure"
    private const val KEY = "db_key"

    fun get(context: Context): ByteArray {
        val prefs = EncryptedSharedPreferences.create(
            PREFS,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        prefs.getString(KEY, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY, Base64.encodeToString(key, Base64.NO_WRAP)).commit()
        return key
    }
}
