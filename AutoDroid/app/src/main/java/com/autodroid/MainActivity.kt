package com.autodroid

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

class MainActivity : Activity() {

    private lateinit var status: TextView
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { render(); h.postDelayed(this, 1000) }
    }
    private val car by lazy { getSharedPreferences("car", 0) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            setBackgroundColor(Color.BLACK)
        }
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 17f }
        root.addView(status)

        fun title(t: String) {
            root.addView(TextView(this).apply {
                text = t; setTextColor(0xFF90CAF9.toInt()); textSize = 15f
                setPadding(0, dp(22), 0, 0)
            })
        }
        fun btn(label: String, color: Int, height: Int = 72, click: () -> Unit) {
            root.addView(Button(this).apply {
                text = label; textSize = 20f; setTextColor(Color.WHITE)
                setBackgroundColor(color)
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)).apply { setMargins(0, dp(10), 0, 0) })
        }

        val green = 0xFF2E7D32.toInt(); val red = 0xFFC62828.toInt()
        val blue = 0xFF1565C0.toInt(); val gray = 0xFF455A64.toInt()

        title("MODO CARRO")
        btn("ATIVAR MODO CARRO", green, 100) { startVoice() }
        btn("FALAR AGORA (sem dizer \"carro\")", blue, 84) {
            startForegroundService(Intent(this, VoiceService::class.java).setAction("TALK"))
        }
        btn("ESCUTA CONTÍNUA: LIGAR / DESLIGAR", blue) {
            startForegroundService(Intent(this, VoiceService::class.java).setAction("TOGGLE"))
        }
        btn("DESATIVAR", red) { stopService(Intent(this, VoiceService::class.java)) }

        title("CONFIGURAR (UMA VEZ)")
        btn("ESCOLHER O BLUETOOTH DO CARRO", blue) { pickCar() }
        btn("1. MICROFONE, BLUETOOTH E AVISOS", gray) {
            requestPermissions(arrayOf(
                Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.BLUETOOTH_CONNECT
            ), 1)
        }
        btn("2. ACESSO ÀS NOTIFICAÇÕES", gray) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btn("3. EXIBIR SOBRE OUTROS APPS", gray) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("4. BATERIA SEM RESTRIÇÕES", gray) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        btn("LIGAR O HOTSPOT (abre as configurações)", blue) {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }

        title("AVANÇADO (OPCIONAL)")
        btn("ESPELHAR A TELA NO KIT", gray, 64) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), 42)
        }
        btn("PARAR ESPELHAMENTO", gray, 64) { stopService(Intent(this, MirrorService::class.java)) }
        btn("ACESSIBILIDADE (tocar pelo kit)", gray, 64) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(root) })

        if (s == null && hasMic()) startVoice()
    }

    @SuppressLint("MissingPermission")
    private fun pickCar() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
            return
        }
        val devs = getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList().orEmpty()
        if (devs.isEmpty()) {
            Toast.makeText(this, "Nenhum aparelho Bluetooth pareado.", Toast.LENGTH_LONG).show()
            return
        }
        val names = devs.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Qual é o Bluetooth do carro?")
            .setItems(names) { _, i ->
                car.edit().putString("addr", devs[i].address).putString("name", names[i]).apply()
            }.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 42 && res == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, MirrorService::class.java).putExtra("code", res).putExtra("data", data)
            )
        }
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { h.removeCallbacks(tick); super.onPause() }

    private fun render() {
        val nl = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        status.text = "Voz: ${VoiceService.lastInfo}\n\n" +
            "Carro: ${car.getString("name", "não escolhido")}\n" +
            "Microfone: ${if (hasMic()) "OK" else "falta"}\n" +
            "Notificações: ${if (nl) "OK" else "falta"}\n" +
            "Sobreposição: ${if (Settings.canDrawOverlays(this)) "OK" else "falta"}\n\n" +
            "Diga \"carro\" + comando: próxima música, pausar, volume mais, ler, " +
            "responder estou chegando, abrir WhatsApp, desligar."
    }

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startVoice() {
        if (!hasMic()) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return }
        startForegroundService(Intent(this, VoiceService::class.java))
    }
}
