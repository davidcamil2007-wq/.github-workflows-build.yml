package com.autodroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** No kit: religa o painel sozinho quando o kit liga (se o painel estava ativado). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (ctx.getSharedPreferences("kit", 0).getBoolean("enabled", false)) {
            try { ctx.startForegroundService(Intent(ctx, KitService::class.java)) } catch (e: Exception) { }
        }
    }
}
