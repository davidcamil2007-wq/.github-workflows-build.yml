package com.autodroid

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged

/** Tela do KIT: liga/desliga o painel de notificações que flutua sobre o Waze. */
class KitActivity : Activity() {

    private lateinit var status: TextView
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { render(); h.postDelayed(this, 1000) }
    }
    private val prefs by lazy { getSharedPreferences("kit", 0) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(Color.BLACK)
        }
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 20f }
        root.addView(status)

        fun btn(label: String, color: Int, click: () -> Unit) {
            root.addView(Button(this).apply {
                text = label; textSize = 22f; setTextColor(Color.WHITE)
                setBackgroundColor(color)
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)).apply { setMargins(0, dp(10), 0, 0) })
        }

        btn("1. PERMITIR EXIBIR SOBRE OUTROS APPS", 0xFF1565C0.toInt()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: ActivityNotFoundException) {
                status.text = "Este kit não tem a tela de permissão de sobreposição. Me avise para eu criar outra solução."
            }
        }
        btn("2. LIGAR PAINEL", 0xFF2E7D32.toInt()) {
            prefs.edit().putBoolean("enabled", true).apply()
            startForegroundService(Intent(this, KitService::class.java))
        }
        btn("TESTAR PAINEL", 0xFF1565C0.toInt()) {
            startForegroundService(Intent(this, KitService::class.java).setAction("TEST"))
        }
        btn("DESLIGAR PAINEL", 0xFFC62828.toInt()) {
            prefs.edit().putBoolean("enabled", false).apply()
            stopService(Intent(this, KitService::class.java))
        }

        root.addView(TextView(this).apply {
            text = "\nIP do celular (deixe vazio para automático):"
            setTextColor(Color.LTGRAY); textSize = 16f
        })
        root.addView(EditText(this).apply {
            setTextColor(Color.WHITE)
            keyListener = DigitsKeyListener.getInstance("0123456789.")
            gravity = Gravity.CENTER
            setText(prefs.getString("ip", ""))
            doAfterTextChanged { prefs.edit().putString("ip", it.toString().trim()).apply() }
        })

        btn("ESPELHAMENTO DA TELA (avançado)", 0xFF455A64.toInt()) {
            startActivity(Intent(this, MirrorViewActivity::class.java))
        }

        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(root) })

        if (prefs.getBoolean("enabled", false)) startForegroundService(Intent(this, KitService::class.java))
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { h.removeCallbacks(tick); super.onPause() }

    private fun render() {
        val ov = Settings.canDrawOverlays(this)
        status.text = "Painel: ${KitService.status}\n" +
            "Sobreposição: ${if (ov) "OK" else "FALTA (botão 1)"}\n\n" +
            "Como usar: ligue o hotspot do celular, conecte o kit nele, " +
            "toque em LIGAR PAINEL e abra o Waze. As notificações aparecem no canto da tela."
    }
}
