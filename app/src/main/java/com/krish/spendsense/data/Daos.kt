package com.krish.spendsense.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.krish.spendsense.parse.Direction
import kotlinx.coroutines.flow.Flow

data class CategoryCount(val categoryId: Long, val n: Int)

@Dao
interface TxnDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(t: Txn): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(t: List<Txn>)

    @Update
    suspend fun update(t: Txn)

    @Delete
    suspend fun delete(t: Txn)

    @Query("SELECT * FROM txn WHERE id = :id")
    suspend fun byId(id: Long): Txn?

    @Query("SELECT * FROM txn WHERE id = :id")
    fun observe(id: Long): Flow<Txn?>

    @Query("SELECT * FROM txn WHERE upiRef = :ref LIMIT 1")
    suspend fun byRef(ref: String): Txn?

    @Query("SELECT * FROM txn WHERE amountPaise = :amount AND direction = :direction AND timestamp BETWEEN :from AND :to")
    suspend fun window(amount: Long, direction: Direction, from: Long, to: Long): List<Txn>

    @Query("SELECT * FROM txn WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp DESC")
    suspend fun range(from: Long, to: Long): List<Txn>

    @Query("SELECT * FROM txn WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp DESC")
    fun observeRange(from: Long, to: Long): Flow<List<Txn>>

    @Query("SELECT * FROM txn ORDER BY timestamp DESC")
    suspend fun all(): List<Txn>

    @Query("SELECT * FROM txn WHERE needsReview = 1 OR accountId IS NULL AND senderKey IS NOT NULL ORDER BY timestamp DESC")
    fun observeNeedsReview(): Flow<List<Txn>>

    @Query("SELECT * FROM txn WHERE accountId = :accountId AND balanceAfterPaise IS NOT NULL ORDER BY timestamp DESC LIMIT 1")
    fun observeLatestBalance(accountId: Long): Flow<Txn?>

    @Query("SELECT categoryId, COUNT(*) AS n FROM txn WHERE direction = 'DEBIT' AND categoryId IS NOT NULL AND timestamp > :since GROUP BY categoryId ORDER BY n DESC LIMIT :limit")
    suspend fun topCategories(since: Long, limit: Int): List<CategoryCount>

    @Query("UPDATE txn SET accountId = :to WHERE accountId = :from")
    suspend fun moveAccount(from: Long, to: Long)

    @Query("UPDATE txn SET accountId = NULL WHERE accountId = :accountId")
    suspend fun unassignAccount(accountId: Long)

    @Query("DELETE FROM txn WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: Long)

    @Query("UPDATE txn SET categoryId = :to WHERE categoryId = :from")
    suspend fun moveCategory(from: Long, to: Long)

    @Query("UPDATE txn SET linkedTxnId = NULL, excludedReason = NULL WHERE linkedTxnId = :id AND exclusionUserSet = 0")
    suspend fun unlinkFrom(id: Long)

    @Query("SELECT * FROM txn WHERE accountId IS NULL AND senderKey = :senderKey AND accountTail = :tail")
    suspend fun unassignedFor(senderKey: String, tail: String): List<Txn>

    @Query("SELECT COUNT(*) FROM txn")
    suspend fun count(): Int
}

@Dao
interface AccountDao {
    @Insert
    suspend fun insert(a: Account): Long

    @Insert
    suspend fun insertAll(a: List<Account>)

    @Update
    suspend fun update(a: Account)

    @Delete
    suspend fun delete(a: Account)

    @Query("SELECT * FROM account ORDER BY isPrimary DESC, createdAt")
    suspend fun all(): List<Account>

    @Query("SELECT * FROM account ORDER BY isPrimary DESC, createdAt")
    fun observeAll(): Flow<List<Account>>

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun byId(id: Long): Account?

    @Query("SELECT * FROM account WHERE id = :id")
    fun observe(id: Long): Flow<Account?>

    @Query("UPDATE account SET isPrimary = (id = :id)")
    suspend fun setPrimary(id: Long)

    @Query("UPDATE account SET simProfileId = :to WHERE simProfileId = :from")
    suspend fun moveSim(from: Long, to: Long)
}

@Dao
interface SimDao {
    @Insert
    suspend fun insert(s: SimProfile): Long

    @Insert
    suspend fun insertAll(s: List<SimProfile>)

    @Update
    suspend fun update(s: SimProfile)

    @Query("SELECT * FROM sim_profile ORDER BY active DESC, slot")
    suspend fun all(): List<SimProfile>

    @Query("SELECT * FROM sim_profile ORDER BY active DESC, slot")
    fun observeAll(): Flow<List<SimProfile>>

    @Query("SELECT * FROM sim_profile WHERE slot = :slot AND active = 1 LIMIT 1")
    suspend fun activeInSlot(slot: Int): SimProfile?
}

@Dao
interface CategoryDao {
    @Insert
    suspend fun insert(c: Category): Long

    @Insert
    suspend fun insertAll(c: List<Category>)

    @Update
    suspend fun update(c: Category)

    @Delete
    suspend fun delete(c: Category)

    @Query("SELECT * FROM category ORDER BY kind, id")
    suspend fun all(): List<Category>

    @Query("SELECT * FROM category ORDER BY kind, id")
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT * FROM category WHERE systemKey = :key LIMIT 1")
    suspend fun bySystemKey(key: String): Category?

    @Query("SELECT COUNT(*) FROM category")
    suspend fun count(): Int
}

@Dao
interface RuleDao {
    @Upsert
    suspend fun upsertPayeeRule(r: PayeeRule)

    @Insert
    suspend fun insertPayeeRules(r: List<PayeeRule>)

    @Delete
    suspend fun deletePayeeRule(r: PayeeRule)

    @Query("SELECT * FROM payee_rule")
    suspend fun payeeRules(): List<PayeeRule>

    @Query("SELECT * FROM payee_rule ORDER BY payeeKey")
    fun observePayeeRules(): Flow<List<PayeeRule>>

    @Query("DELETE FROM payee_rule WHERE categoryId = :categoryId")
    suspend fun deletePayeeRulesFor(categoryId: Long)

    @Insert
    suspend fun insertKeyword(r: KeywordRule): Long

    @Insert
    suspend fun insertKeywords(r: List<KeywordRule>)

    @Delete
    suspend fun deleteKeyword(r: KeywordRule)

    @Query("SELECT * FROM keyword_rule ORDER BY id")
    suspend fun keywordRules(): List<KeywordRule>

    @Query("SELECT * FROM keyword_rule ORDER BY id")
    fun observeKeywordRules(): Flow<List<KeywordRule>>

    @Query("DELETE FROM keyword_rule WHERE categoryId = :categoryId")
    suspend fun deleteKeywordsFor(categoryId: Long)

    @Upsert
    suspend fun upsertMatchRule(r: AccountMatchRule)

    @Insert
    suspend fun insertMatchRules(r: List<AccountMatchRule>)

    @Query("SELECT * FROM account_match_rule")
    suspend fun matchRules(): List<AccountMatchRule>

    @Query("UPDATE account_match_rule SET accountId = :to WHERE accountId = :from")
    suspend fun moveMatchRules(from: Long, to: Long)

    @Query("DELETE FROM account_match_rule WHERE accountId = :accountId")
    suspend fun deleteMatchRulesFor(accountId: Long)
}

@Dao
interface SenderDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(s: AccountSender): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(s: List<AccountSender>)

    @Delete
    suspend fun delete(s: AccountSender)

    @Query("SELECT * FROM account_sender")
    suspend fun all(): List<AccountSender>

    @Query("SELECT * FROM account_sender ORDER BY bank, senderKey")
    fun observeAll(): Flow<List<AccountSender>>

    @Query("UPDATE OR IGNORE account_sender SET accountId = :to WHERE accountId = :from")
    suspend fun moveSenders(from: Long, to: Long)

    @Query("DELETE FROM account_sender WHERE accountId = :accountId")
    suspend fun deleteFor(accountId: Long)
}

@Dao
interface UnparsedDao {
    @Insert
    suspend fun insert(m: UnparsedMessage): Long

    @Insert
    suspend fun insertAll(m: List<UnparsedMessage>)

    @Query("DELETE FROM unparsed_message WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM unparsed_message WHERE id = :id")
    suspend fun byId(id: Long): UnparsedMessage?

    @Query("SELECT * FROM unparsed_message ORDER BY timestamp DESC")
    suspend fun all(): List<UnparsedMessage>

    @Query("SELECT * FROM unparsed_message ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<UnparsedMessage>>
}

@Dao
interface HashDao {
    /** Returns -1 when the hash was already processed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(h: ProcessedHash): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(h: List<ProcessedHash>)

    @Query("SELECT * FROM processed_hash")
    suspend fun all(): List<ProcessedHash>
}
