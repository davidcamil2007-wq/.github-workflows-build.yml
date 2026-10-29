package com.autodroid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/** Roda no KIT: conecta no celular, recebe notificações e mostra um painel pequeno sobre qualquer app (Waze). */
class KitService : Service() {

    companion object {
        const val PORT = 5556
        @Volatile var status = "parado"
    }

    // Respostas rápidas: texto enviado -> rótulo do botão (edite aqui se quiser outras)
    private val replies = listOf(
        "Já estou chegando." to "Já chego",
        "Estou dirigindo, respondo depois." to "Dirigindo",
        "Ok!" to "Ok"
    )

    private val h = Handler(Looper.getMainLooper())
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var card: View? = null
    private val hide = Runnable { hideCard() }
    @Volatile private var active = false
    @Volatile private var sock: Socket? = null
    @Volatile private var outS: DataOutputStream? = null
    private val sender = Executors.newSingleThreadExecutor()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("kit", "Painel do kit", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, "kit")
            .setContentTitle("AutoDroid Kit ativo")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).build()
        ServiceCompat.startForeground(this, 3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        active = true
        Thread { loop() }.start()
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "TEST") {
            h.post { showCard("Teste", "Se você está vendo este painel, a sobreposição funciona.", -1) }
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun gateway(): String {
        val w = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val g = w.dhcpInfo.gateway
        return if (g == 0) "192.168.43.1"
        else "${g and 255}.${(g shr 8) and 255}.${(g shr 16) and 255}.${(g shr 24) and 255}"
    }

    private fun loop() {
        val prefs = getSharedPreferences("kit", 0)
        while (active) {
            val manual = prefs.getString("ip", "").orEmpty().trim()
            val ip = if (manual.isNotEmpty()) manual else gateway()
            status = "procurando o celular em $ip (hotspot ligado? modo carro ativo no celular?)"
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, PORT), 4000)
                s.tcpNoDelay = true
                s.keepAlive = true
                sock = s
                outS = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                status = "conectado ao celular ($ip)"
                val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
                while (active) {
                    when (inp.readUnsignedByte()) {
                        10 -> {
                            val name = inp.readUTF()
                            val text = inp.readUTF()
                            inp.readUTF()
                            val id = inp.readInt()
                            h.post { showCard(name, text, id) }
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                try { sock?.close() } catch (e: Exception) { }
                sock = null
                outS = null
            }
            if (active) {
                status = "desconectado, tentando de novo..."
                try { Thread.sleep(2500) } catch (e: InterruptedException) { }
            }
        }
    }

    private fun sendReply(id: Int, text: String) {
        sender.execute {
            try {
                outS?.let {
                    synchronized(it) { it.writeByte(11); it.writeInt(id); it.writeUTF(text); it.flush() }
                }
            } catch (e: Exception) { }
        }
    }

    // ---------- painel flutuante ----------
    private fun showCard(name: String, text: String, id: Int) {
        if (!Settings.canDrawOverlays(this)) { status = "FALTA permitir exibir sobre outros apps"; return }
        hideCard()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(0xF0202020.toInt()); cornerRadius = dp(16).toFloat() }
        }
        root.addView(TextView(this).apply {
            this.text = name; setTextColor(Color.WHITE); textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        root.addView(TextView(this).apply {
            this.text = text; setTextColor(0xFFDDDDDD.toInt()); textSize = 19f
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(10))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun b(label: String, color: Int, click: () -> Unit) {
            row.addView(Button(this).apply {
                this.text = label; textSize = 17f; setTextColor(Color.WHITE)
                minHeight = dp(60); minimumHeight = dp(60)
                background = GradientDrawable().apply { setColor(color); cornerRadius = dp(10).toFloat() }
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        if (id >= 0) {
            replies.forEach { (full, label) ->
                b(label, 0xFF1565C0.toInt()) {
                    sendReply(id, full)
                    Toast.makeText(this, "Enviado: $full", Toast.LENGTH_SHORT).show()
                    hideCard()
                }
            }
        }
        b("✕", 0xFF555555.toInt()) { hideCard() }
        root.addView(row)

        val lp = WindowManager.LayoutParams(
            dp(420), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(8) }
        try {
            wm.addView(root, lp)
            card = root
            h.postDelayed(hide, 20_000)
        } catch (e: Exception) {
            status = "não foi possível exibir o painel: ${e.message}"
        }
    }

    private fun hideCard() {
        h.removeCallbacks(hide)
        card?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        card = null
    }

    override fun onDestroy() {
        active = false
        status = "parado"
        try { sock?.close() } catch (e: Exception) { }
        hideCard()
        super.onDestroy()
    }
}
