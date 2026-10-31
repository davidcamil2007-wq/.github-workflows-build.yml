package com.autodroid

import java.util.concurrent.CopyOnWriteArrayList

/** Estado compartilhado no KIT entre o serviço de conexão e a tela principal. */
object KitState {
    class Notif(val id: Int, val sender: String, val text: String, val pkg: String)
    class PApp(val label: String, val pkg: String, val icon: ByteArray)

    @Volatile var connected = false
    @Volatile var dashboardVisible = false
    @Volatile var apps: List<PApp> = emptyList()
    val notifs = CopyOnWriteArrayList<Notif>()
    @Volatile var listener: (() -> Unit)? = null

    fun changed() { listener?.invoke() }
}
