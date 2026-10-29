package com.autodroid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat

/** Dispara quando o celular conecta no Bluetooth do carro (o escolhido no app). */
class CarBtReceiver : BroadcastReceiver() {

    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        try {
            val dev: BluetoothDevice = (
                if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            ) ?: return
            val saved = ctx.getSharedPreferences("car", 0).getString("addr", null) ?: return
            if (dev.address != saved) return
        } catch (e: SecurityException) {
            return
        }

        // 1) tenta ligar o modo carro (o Android pode bloquear o microfone em segundo plano)
        try { ctx.startForegroundService(Intent(ctx, VoiceService::class.java)) } catch (e: Exception) { }

        // 2) aviso para ligar o hotspot e/ou abrir o app
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("carro", "Carro conectado", NotificationManager.IMPORTANCE_HIGH))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openApp = PendingIntent.getActivity(ctx, 10, Intent(ctx, MainActivity::class.java), flags)
        val hotspot = PendingIntent.getActivity(ctx, 11, Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val n = NotificationCompat.Builder(ctx, "carro")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Carro conectado")
            .setContentText("Ligue o hotspot para o painel no kit.")
            .setContentIntent(openApp)
            .addAction(0, "LIGAR HOTSPOT", hotspot)
            .addAction(0, "ABRIR AUTODROID", openApp)
            .setAutoCancel(true)
            .build()
        nm.notify(20, n)
    }
}
