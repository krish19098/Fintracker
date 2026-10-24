package com.krish.spendsense.data

import com.krish.spendsense.domain.Dates
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class BackupData(
    val version: Int = 1,
    val sims: List<SimProfile>,
    val accounts: List<Account>,
    val senders: List<AccountSender>,
    val matchRules: List<AccountMatchRule>,
    val txns: List<Txn>,
    val categories: List<Category>,
    val payeeRules: List<PayeeRule>,
    val keywordRules: List<KeywordRule>,
    val unparsed: List<UnparsedMessage>,
    val hashes: List<ProcessedHash>,
)

/**
 * Passphrase-encrypted backup file: "SSB1" | salt(16) | iv(12) | AES-256-GCM(JSON).
 * The key is PBKDF2-HMAC-SHA256 over the user's passphrase.
 */
object Backup {
    private val MAGIC = "SSB1".toByteArray()
    private const val ITERATIONS = 120_000
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(pass: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pass, salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(data: BackupData, pass: CharArray): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(pass, salt), GCMParameterSpec(128, iv))
        return MAGIC + salt + iv + cipher.doFinal(json.encodeToString(BackupData.serializer(), data).toByteArray())
    }

    /** Throws on a wrong passphrase or a corrupt file. */
    fun decrypt(bytes: ByteArray, pass: CharArray): BackupData {
        require(bytes.size > 32 && bytes.copyOfRange(0, 4).contentEquals(MAGIC)) { "Not a SpendSense backup" }
        val salt = bytes.copyOfRange(4, 20)
        val iv = bytes.copyOfRange(20, 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(pass, salt), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(bytes, 32, bytes.size - 32)
        return json.decodeFromString(BackupData.serializer(), String(plain))
    }

    private fun csv(s: String?): String {
        val v = s ?: ""
        return if (v.any { it == ',' || it == '"' || it == '\n' }) "\"${v.replace("\"", "\"\"")}\"" else v
    }

    /** Plain CSV of transactions (amounts in rupees with 2 decimals, from integer paise). */
    fun toCsv(txns: List<Txn>, accounts: Map<Long, Account>, categories: Map<Long, Category>): String = buildString {
        appendLine("date,time,direction,amount,payee,vpa,category,account,app,upi_ref,note,excluded")
        txns.sortedBy { it.timestamp }.forEach { t ->
            val amount = "${t.amountPaise / 100}.${(t.amountPaise % 100).toString().padStart(2, '0')}"
            appendLine(
                listOf(
                    Dates.date(t.timestamp).toString(), Dates.time(t.timestamp), t.direction.name, amount, csv(t.payeeName), csv(t.payeeVpa),
                    csv(t.categoryId?.let { categories[it]?.name }), csv(t.accountId?.let { accounts[it]?.label }), t.app.name,
                    t.upiRef ?: "", csv(t.note), t.excludedReason?.name ?: "",
                ).joinToString(","),
            )
        }
    }
}
