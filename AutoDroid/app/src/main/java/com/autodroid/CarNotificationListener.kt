package com.autodroid

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class CarNotificationListener : NotificationListenerService() {

    private val apps = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
        "com.instagram.android", "com.facebook.orca"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in apps) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        if (Regex("^\\d+ (novas )?mensagens?").containsMatchIn(text)) return

        val reply = n.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
            ?: Notification.WearableExtender(n).actions.firstOrNull { it.remoteInputs?.isNotEmpty() == true }

        val sender = title.replace(Regex("\\s*\\(\\d+.*\\)$"), "").trim()
        val msg = Msg(sender, text, sbn.packageName, reply)
        Store.add(msg)
        VoiceService.instance?.onNewMessage(msg)
        KitLink.push(msg)
    }
}
