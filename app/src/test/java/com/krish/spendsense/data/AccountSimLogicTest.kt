package com.krish.spendsense.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSimLogicTest {
    private val cub = Account(id = 1, bank = "CUB", last4 = "6578", nickname = "")
    private val cub2 = Account(id = 2, bank = "CUB", last4 = "1278", nickname = "")
    private val hdfc = Account(id = 3, bank = "HDFC", last4 = "9924", nickname = "")

    @Test
    fun suffixMatchesUniqueAccount() {
        assertEquals(AccountMatch.Found(cub), AccountMatcher.match("CUB", "78", "CUBANK", listOf(cub, hdfc), emptyList()))
        assertEquals(AccountMatch.Ambiguous, AccountMatcher.match("CUB", "78", "CUBANK", listOf(cub, cub2), emptyList()))
        assertEquals(AccountMatch.NotFound(false), AccountMatcher.match("CUB", "78", "CUBANK", listOf(hdfc), emptyList()))
        assertEquals(AccountMatch.NotFound(true), AccountMatcher.match("CUB", "4321", "CUBANK", listOf(cub), emptyList()))
        assertEquals(AccountMatch.Found(hdfc), AccountMatcher.match("HDFC", "9924", "HDFCBK", listOf(cub, hdfc), emptyList()))
    }

    @Test
    fun matchRuleWinsForAmbiguousTail() {
        val rule = AccountMatchRule(senderKey = "CUBANK", tail = "78", accountId = 2)
        assertEquals(AccountMatch.Found(cub2), AccountMatcher.match("CUB", "78", "CUBANK", listOf(cub, cub2), listOf(rule)))
        assertEquals(AccountMatch.Ambiguous, AccountMatcher.match("CUB", "78", "CUBLTD", listOf(cub, cub2), listOf(rule)))
    }

    @Test
    fun simSwapDetectedAndRemapped() {
        val stored = listOf(SimProfile(id = 10, subscriptionId = 1, slot = 1, label = "Personal"), SimProfile(id = 11, subscriptionId = 2, slot = 2, label = "Work"))
        val now = listOf(DetectedSim(1, 1, "Jio", null), DetectedSim(5, 2, "Airtel", "9999"))
        val change = SimLogic.detectChanges(stored, now).single() as SimChange.Swapped
        assertEquals(11L, change.old.id)
        // Keep accounts linked: same profile id, new subscription.
        val kept = SimLogic.keepLinked(change.old, change.sim, 5)
        assertEquals(11L, kept.id)
        assertEquals(5, kept.subscriptionId)
        assertEquals("Work", kept.label)
        // Set up fresh: old kept but inactive, new profile in slot 2.
        val (old, fresh) = SimLogic.setupFresh(change.old, change.sim, 5)
        assertFalse(old.active)
        assertEquals(11L, old.id)
        assertEquals(0L, fresh.id)
        assertEquals(2, fresh.slot)
        assertTrue(SimLogic.detectChanges(stored, listOf(DetectedSim(1, 1, null, null))).isEmpty())
        assertTrue(SimLogic.detectChanges(emptyList(), now).all { it is SimChange.Added })
    }

    @Test
    fun accountSimAutoUpdate() {
        val a = cub.copy(simProfileId = 10)
        assertEquals(a.copy(simProfileId = 11) to true, SimLogic.onAccountSeen(a, 11))
        assertEquals(a to false, SimLogic.onAccountSeen(a.copy(autoUpdateSim = false), 11).let { it.first.copy(autoUpdateSim = true) to it.second })
        assertEquals(cub.copy(simProfileId = 11) to false, SimLogic.onAccountSeen(cub, 11))
        assertEquals(a to false, SimLogic.onAccountSeen(a, 10))
    }
}
