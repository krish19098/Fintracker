package com.krish.spendsense.ingest

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.krish.spendsense.SpendSenseApp
import kotlinx.coroutines.launch

/** Handles "category" and "Add note" actions on transaction notifications. */
class NotifActionReceiver : BroadcastReceiver() {
    companion object {
        private const val ACTION_CATEGORY = "com.krish.spendsense.SET_CATEGORY"
        private const val ACTION_NOTE = "com.krish.spendsense.ADD_NOTE"
        private const val EXTRA_TXN = "txn"
        private const val EXTRA_CAT = "cat"

        fun setCategory(ctx: Context, txnId: Long, categoryId: Long): PendingIntent = PendingIntent.getBroadcast(
            ctx, (txnId * 31 + categoryId).toInt(),
            Intent(ctx, NotifActionReceiver::class.java).setAction(ACTION_CATEGORY).putExtra(EXTRA_TXN, txnId).putExtra(EXTRA_CAT, categoryId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Mutable: RemoteInput must be able to add the typed text.
        fun addNote(ctx: Context, txnId: Long): PendingIntent = PendingIntent.getBroadcast(
            ctx, txnId.toInt(),
            Intent(ctx, NotifActionReceiver::class.java).setAction(ACTION_NOTE).putExtra(EXTRA_TXN, txnId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val c = (context.applicationContext as SpendSenseApp).container
        val txnId = intent.getLongExtra(EXTRA_TXN, -1).takeIf { it > 0 } ?: return
        val pending = goAsync()
        c.appScope.launch {
            try {
                when (intent.action) {
                    ACTION_CATEGORY -> c.repository.setCategory(txnId, intent.getLongExtra(EXTRA_CAT, -1), always = false)
                    ACTION_NOTE -> RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_NOTE)?.toString()?.let { note ->
                        c.repository.txn(txnId)?.let { c.repository.updateTxn(it.copy(note = note.trim().ifEmpty { null })) }
                    }
                }
                c.notifier.cancelTxn(txnId)
            } finally {
                pending.finish()
            }
        }
    }
}
