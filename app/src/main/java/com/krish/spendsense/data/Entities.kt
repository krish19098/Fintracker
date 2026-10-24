package com.krish.spendsense.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.krish.spendsense.parse.Direction
import com.krish.spendsense.parse.Source
import com.krish.spendsense.parse.TxnApp
import com.krish.spendsense.parse.UpiType
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "sim_profile", indices = [Index("slot")])
data class SimProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subscriptionId: Int,
    val slot: Int,
    val carrier: String? = null,
    val label: String,
    val phoneNumber: String? = null,
    val trackSms: Boolean = true,
    val active: Boolean = true,
    val lastSeenAt: Long = 0,
)

@Serializable
@Entity(tableName = "account", indices = [Index("bank", "last4")])
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bank: String,
    /** Account tail as first seen (2-4 chars, usually the last 4 digits). */
    val last4: String,
    val nickname: String,
    val color: Long = 0xFF0F766E,
    val icon: String = "bank",
    val simProfileId: Long? = null,
    val autoUpdateSim: Boolean = true,
    val tracked: Boolean = true,
    val includeInTotals: Boolean = true,
    val isOwn: Boolean = true,
    val isCreditCard: Boolean = false,
    val isPrimary: Boolean = false,
    val dailyLimitPaise: Long? = null,
    /** False until the user confirms or renames an auto-created account. */
    val named: Boolean = false,
    val createdAt: Long = 0,
) {
    val label: String get() = nickname.ifBlank { "$bank ••$last4" }
}

/** A bank SMS sender key. accountId == null means a bank-level default. */
@Serializable
@Entity(tableName = "account_sender", indices = [Index(value = ["senderKey", "accountId"], unique = true)])
data class AccountSender(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long? = null,
    val bank: String,
    val senderKey: String,
)

/** "Always use this account for this sender/last4", created from the Review inbox. */
@Serializable
@Entity(tableName = "account_match_rule", indices = [Index(value = ["senderKey", "tail"], unique = true)])
data class AccountMatchRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val senderKey: String,
    val tail: String,
    val accountId: Long,
)

enum class ExcludedReason { SELF_TRANSFER, CC_BILL, REFUND, MANUAL }

@Serializable
@Entity(
    tableName = "txn",
    indices = [
        Index(value = ["upiRef"], unique = true),
        Index(value = ["rawHash"], unique = true),
        Index("timestamp"),
        Index("accountId"),
        Index("categoryId"),
    ],
)
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val upiRef: String? = null,
    /** SHA-256 of the first source that created this row. Every processed source is in [ProcessedHash]. */
    val rawHash: String,
    val amountPaise: Long,
    val direction: Direction,
    val accountId: Long? = null,
    val payeeName: String? = null,
    val payeeVpa: String? = null,
    val app: TxnApp = TxnApp.OTHER_UPI,
    val note: String? = null,
    val categoryId: Long? = null,
    val timestamp: Long,
    /** Bitmask of [Source.flag]. */
    val sourceFlags: Int,
    val simSlot: Int? = null,
    val excludedReason: ExcludedReason? = null,
    val linkedTxnId: Long? = null,
    val balanceAfterPaise: Long? = null,
    val needsReview: Boolean = false,
    // Capture context, kept so unassigned rows can be matched to an account later.
    val bank: String? = null,
    val accountTail: String? = null,
    val senderKey: String? = null,
    val counterpartyTail: String? = null,
    val upiType: UpiType? = null,
    val categoryUserSet: Boolean = false,
    val exclusionUserSet: Boolean = false,
) {
    fun hasSource(s: Source) = sourceFlags and s.flag != 0
    val displayPayee: String get() = payeeName ?: payeeVpa ?: counterpartyTail?.let { "a/c ••$it" } ?: "Unknown"
}

enum class CategoryKind { SPEND, INCOME }

@Serializable
@Entity(tableName = "category")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String,
    val color: Long,
    val monthlyBudgetPaise: Long? = null,
    val isDefault: Boolean = false,
    val kind: CategoryKind = CategoryKind.SPEND,
    /** Stable identity for categories the app logic refers to (survives renames). */
    val systemKey: String? = null,
)

@Serializable
@Entity(tableName = "payee_rule")
data class PayeeRule(
    @PrimaryKey val payeeKey: String,
    val categoryId: Long,
)

@Serializable
@Entity(tableName = "keyword_rule")
data class KeywordRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val categoryId: Long,
)

@Serializable
@Entity(tableName = "unparsed_message", indices = [Index("timestamp")])
data class UnparsedMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val body: String,
    val timestamp: Long,
    val simSlot: Int? = null,
    val source: Source,
    val bank: String? = null,
)

/** Every SMS / notification hash ever processed, so no source is handled twice. */
@Serializable
@Entity(tableName = "processed_hash")
data class ProcessedHash(
    @PrimaryKey val hash: String,
    val at: Long,
)
