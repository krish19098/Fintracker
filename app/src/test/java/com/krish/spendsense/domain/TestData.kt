package com.krish.spendsense.domain

import com.krish.spendsense.data.Category
import com.krish.spendsense.data.CategoryKind
import com.krish.spendsense.data.ExcludedReason
import com.krish.spendsense.data.Txn
import com.krish.spendsense.parse.Direction
import com.krish.spendsense.parse.ParseUtils
import com.krish.spendsense.parse.Source
import com.krish.spendsense.parse.TxnApp
import com.krish.spendsense.parse.UpiType
import java.time.LocalDate
import java.time.ZonedDateTime

/** Shared builders for pure unit tests. */
object TestData {
    val TODAY: LocalDate = LocalDate.of(2026, 10, 24)

    fun at(date: LocalDate, hour: Int = 13, minute: Int = 0): Long =
        ZonedDateTime.of(date.atTime(hour, minute), ParseUtils.IST).toInstant().toEpochMilli()

    private var seq = 1000L

    fun txn(
        rupees: Long,
        date: LocalDate = TODAY,
        hour: Int = 13,
        minute: Int = 0,
        direction: Direction = Direction.DEBIT,
        payee: String? = "Shop",
        vpa: String? = null,
        note: String? = null,
        categoryId: Long? = null,
        accountId: Long? = 1,
        excluded: ExcludedReason? = null,
        linked: Long? = null,
        source: Source = Source.SMS,
        ref: String? = null,
        upiType: UpiType? = null,
        counterpartyTail: String? = null,
        id: Long = seq++,
    ) = Txn(
        id = id, upiRef = ref, rawHash = "h$id", amountPaise = rupees * 100, direction = direction,
        accountId = accountId, payeeName = payee, payeeVpa = vpa, app = TxnApp.OTHER_UPI, note = note,
        categoryId = categoryId, timestamp = at(date, hour, minute), sourceFlags = source.flag,
        excludedReason = excluded, linkedTxnId = linked, upiType = upiType, counterpartyTail = counterpartyTail,
    )

    val categories: List<Category> = DefaultCategories.seeds.mapIndexed { i, s ->
        Category(id = i + 1L, name = s.name, icon = s.icon, color = s.color, isDefault = true, kind = s.kind, systemKey = s.key)
    }

    fun catId(key: String) = categories.first { it.systemKey == key }.id

    val FOOD get() = catId(CatKeys.FOOD)
    val SHOPPING get() = catId(CatKeys.SHOPPING)

    @Suppress("unused")
    val INCOME_KINDS = categories.filter { it.kind == CategoryKind.INCOME }
}
