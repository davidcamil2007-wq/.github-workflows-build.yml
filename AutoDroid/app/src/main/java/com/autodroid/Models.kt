package com.autodroid

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

data class Msg(
    val sender: String,
    val text: String,
    val pkg: String,
    val reply: Notification.Action?,
    var read: Boolean = false,
    val id: Int = Store.nextId()
)

object Store {
    private val list = mutableListOf<Msg>()
    private val ids = AtomicInteger(1)
    fun nextId(): Int = ids.getAndIncrement()
    @Synchronized fun byId(id: Int): Msg? = list.lastOrNull { it.id == id }

    @Synchronized fun add(m: Msg) {
        list.removeAll { it.sender == m.sender && it.pkg == m.pkg }
        list.add(m)
        if (list.size > 20) list.removeAt(0)
    }
    @Synchronized fun latest(): Msg? = list.lastOrNull()
    @Synchronized fun latestUnread(): Msg? = list.lastOrNull { !it.read }
    @Synchronized fun unreadCount(): Int = list.count { !it.read }
}

object Reply {
    /** Envia a resposta usando a "resposta rápida" da própria notificação. */
    fun send(ctx: Context, m: Msg, text: String): Boolean {
        val action = m.reply ?: return false
        val inputs = action.remoteInputs ?: return false
        val intent = Intent()
        val bundle = Bundle()
        inputs.forEach { bundle.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs, intent, bundle)
        return try {
            action.actionIntent.send(ctx, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        }
    }
}
