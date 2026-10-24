package com.krish.spendsense.ingest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.krish.spendsense.data.DetectedSim

/** Reads active SIMs via SubscriptionManager; degrades to "unknown" without READ_PHONE_STATE. */
class SimDetector(private val context: Context) {
    private val sm get() = context.getSystemService(SubscriptionManager::class.java)

    private fun allowed() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION", "MissingPermission")
    fun current(): List<DetectedSim> {
        if (!allowed()) return emptyList()
        return try {
            sm?.activeSubscriptionInfoList.orEmpty().map {
                DetectedSim(it.subscriptionId, it.simSlotIndex + 1, it.carrierName?.toString(), it.number?.takeIf { n -> n.isNotBlank() })
            }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    /** Slot (1 or 2) for an SMS subscription id. */
    @Suppress("MissingPermission")
    fun slotFor(subId: Int?): Int? {
        if (subId == null || subId < 0 || !allowed()) return null
        return try {
            sm?.getActiveSubscriptionInfo(subId)?.simSlotIndex?.plus(1)
        } catch (_: SecurityException) {
            null
        }
    }
}
