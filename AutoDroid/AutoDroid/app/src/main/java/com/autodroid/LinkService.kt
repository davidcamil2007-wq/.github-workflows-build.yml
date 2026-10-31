package com.autodroid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Roda no CELULAR: mantém a ligação com o kit (notificações e lista de apps). */
class LinkService : Service() {

    companion object { @Volatile var running = false }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("link", "Painel do carro", NotificationManager.IMPORTANCE_LOW))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val mirror = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).putExtra("mirror", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        val n = NotificationCompat.Builder(this, "link")
            .setContentTitle("AutoDroid: painel do carro ativo")
            .setContentText("Ligue o hotspot e abra o AutoDroid Kit no kit.")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .addAction(0, "ESPELHAR TELA", mirror)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        KitLink.start(applicationContext)
        running = true
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        running = false
        KitLink.stop()
        super.onDestroy()
    }
}
