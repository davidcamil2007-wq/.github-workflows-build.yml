package com.autodroid

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import kotlin.math.max

/** Tela principal do KIT (estilo Android Auto): apps do celular e do kit + notificações. */
class KitActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("kit", 0) }
    private lateinit var status: TextView
    private lateinit var grid: GridLayout
    private lateinit var notifBox: LinearLayout
    private val h = Handler(Looper.getMainLooper())
    private val icons = HashMap<String, Bitmap?>()
    private val tick = object : Runnable {
        override fun run() {
            status.text = if (KitState.connected) "Celular conectado" else "Celular: ${KitService.status}"
            h.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0B0B0F.toInt())
        }

        // ---- coluna esquerda: relógio + apps ----
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        top.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            textSize = 34f; setTextColor(Color.WHITE)
        })
        status = TextView(this).apply {
            setTextColor(0xFF9E9E9E.toInt()); textSize = 15f
            setPadding(dp(16), 0, dp(8), 0)
        }
        top.addView(status, LinearLayout.LayoutParams(0, wc, 1f))
        top.addView(Button(this).apply {
            text = "⚙ MENU"; textSize = 17f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(0xFF2A2A33.toInt()); cornerRadius = dp(12).toFloat() }
            setOnClickListener { menu() }
        }, LinearLayout.LayoutParams(wc, dp(56)))
        left.addView(top)

        val cols = max(2, (resources.displayMetrics.widthPixels * 0.62f / dp(156)).toInt())
        grid = GridLayout(this).apply { columnCount = cols; setPadding(dp(8), dp(4), dp(8), dp(8)) }
        left.addView(ScrollView(this).apply { addView(grid) }, LinearLayout.LayoutParams(mp, 0, 1f))

        // ---- coluna direita: notificações ----
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF14141A.toInt())
        }
        right.addView(TextView(this).apply {
            text = "Notificações"; textSize = 20f; setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), dp(8))
        })
        notifBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(10), dp(10)) }
        right.addView(ScrollView(this).apply { addView(notifBox) }, LinearLayout.LayoutParams(mp, 0, 1f))

        root.addView(left, LinearLayout.LayoutParams(0, mp, 1.7f))
        root.addView(right, LinearLayout.LayoutParams(0, mp, 1f))
        setContentView(root)

        prefs.edit().putBoolean("enabled", true).apply()
        startForegroundService(Intent(this, KitService::class.java))
    }

    override fun onResume() {
        super.onResume()
        KitState.dashboardVisible = true
        KitState.listener = { runOnUiThread { renderGrid(); renderNotifs() } }
        renderGrid(); renderNotifs()
        h.post(tick)
    }

    override fun onPause() {
        KitState.dashboardVisible = false
        KitState.listener = null
        h.removeCallbacks(tick)
        super.onPause()
    }

    // ---------- apps fixados ----------
    private fun pins(): List<String> {
        val s = prefs.getString("pins", null) ?: return listOf("k:com.waze", "k:com.google.android.apps.maps")
        return s.split(",").filter { it.length > 2 }
    }

    private fun icon(a: KitState.PApp): Bitmap? =
        icons.getOrPut(a.pkg) { BitmapFactory.decodeByteArray(a.icon, 0, a.icon.size) }

    private fun tile(label: String, d: Drawable?, bmp: Bitmap?, emoji: String? = null, click: () -> Unit): View {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply { setColor(0xFF1E1E26.toInt()); cornerRadius = dp(20).toFloat() }
            setOnClickListener { click() }
        }
        if (emoji != null) {
            v.addView(TextView(this).apply { text = emoji; textSize = 40f; gravity = Gravity.CENTER; setTextColor(Color.WHITE) })
        } else {
            v.addView(ImageView(this).apply {
                if (bmp != null) setImageBitmap(bmp) else setImageDrawable(d)
            }, LinearLayout.LayoutParams(dp(72), dp(72)))
        }
        v.addView(TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        })
        v.layoutParams = GridLayout.LayoutParams().apply {
            width = dp(140); height = dp(140); setMargins(dp(8), dp(8), dp(8), dp(8))
        }
        return v
    }

    private fun openMirror() = startActivity(Intent(this, MirrorViewActivity::class.java))

    private fun renderGrid() {
        grid.removeAllViews()
        val pm = packageManager
        for (key in pins()) {
            val pkg = key.substring(2)
            if (key.startsWith("k:")) {
                if (pm.getLaunchIntentForPackage(pkg) == null) continue
                val label = pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
                grid.addView(tile(label, pm.getApplicationIcon(pkg), null) {
                    pm.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                })
            } else {
                val app = KitState.apps.firstOrNull { it.pkg == pkg }
                grid.addView(tile(app?.label ?: pkg, null, app?.let { icon(it) }) {
                    KitService.instance?.launchPhoneApp(pkg)
                    openMirror()
                })
            }
        }
        grid.addView(tile("Tela do celular", null, null, "📱") { openMirror() })
        grid.addView(tile("Editar apps", null, null, "＋") { pickApps() })
    }

    // ---------- notificações ----------
    private fun renderNotifs() {
        notifBox.removeAllViews()
        if (KitState.notifs.isEmpty()) {
            notifBox.addView(TextView(this).apply {
                text = "Sem notificações."; textSize = 16f; setTextColor(0xFF9E9E9E.toInt())
                setPadding(dp(6), dp(6), dp(6), dp(6))
            })
            return
        }
        for (n in KitState.notifs) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { setColor(0xFF1F1F27.toInt()); cornerRadius = dp(14).toFloat() }
            }
            card.addView(TextView(this).apply {
                text = n.sender; textSize = 18f; setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            card.addView(TextView(this).apply {
                text = n.text; textSize = 16f; setTextColor(0xFFCCCCCC.toInt())
                maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, dp(8))
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Button(this).apply {
                text = "ABRIR NO CELULAR"; textSize = 15f; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { setColor(0xFF1565C0.toInt()); cornerRadius = dp(10).toFloat() }
                setOnClickListener { KitService.instance?.openNotification(n.id); openMirror() }
            }, LinearLayout.LayoutParams(0, dp(56), 3f))
            row.addView(Button(this).apply {
                text = "✕"; textSize = 17f; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { setColor(0xFF444444.toInt()); cornerRadius = dp(10).toFloat() }
                setOnClickListener { KitState.notifs.remove(n); renderNotifs() }
            }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(6), 0, 0, 0) })
            card.addView(row)
            notifBox.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(10))
            })
        }
    }

    // ---------- menu e escolha de apps ----------
    private fun menu() {
        val popup = prefs.getBoolean("popup", true)
        val items = arrayOf(
            "Escolher apps da tela principal",
            "Aviso sobre outros apps: ${if (popup) "LIGADO" else "DESLIGADO"}",
            "Permitir exibir sobre outros apps",
            "Testar o aviso",
            "IP do celular (avançado)",
            "Desligar o painel"
        )
        AlertDialog.Builder(this).setTitle("AutoDroid Kit").setItems(items) { _, i ->
            when (i) {
                0 -> pickApps()
                1 -> prefs.edit().putBoolean("popup", !popup).apply()
                2 -> try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    status.text = "Este kit não tem a tela de sobreposição."
                }
                3 -> startForegroundService(Intent(this, KitService::class.java).setAction("TEST"))
                4 -> askIp()
                5 -> {
                    prefs.edit().putBoolean("enabled", false).apply()
                    stopService(Intent(this, KitService::class.java))
                }
            }
        }.show()
    }

    private fun askIp() {
        val e = EditText(this).apply {
            setText(prefs.getString("ip", ""))
            hint = "vazio = automático"
        }
        AlertDialog.Builder(this).setTitle("IP do celular").setView(e)
            .setPositiveButton("SALVAR") { _, _ -> prefs.edit().putString("ip", e.text.toString().trim()).apply() }
            .setNegativeButton("CANCELAR", null).show()
    }

    private fun pickApps() {
        val pm = packageManager
        val kitApps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val keys = ArrayList<String>()
        val labels = ArrayList<String>()
        kitApps.forEach { keys.add("k:${it.first}"); labels.add("${it.second}  (kit)") }
        KitState.apps.forEach { keys.add("p:${it.pkg}"); labels.add("${it.label}  (celular)") }
        val cur = pins()
        val checked = BooleanArray(keys.size) { keys[it] in cur }
        AlertDialog.Builder(this)
            .setTitle(if (KitState.apps.isEmpty()) "Apps do kit (conecte o celular para ver os dele)" else "Apps na tela principal")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("SALVAR") { _, _ ->
                val sel = keys.filterIndexed { i, _ -> checked[i] }
                val ordered = cur.filter { it in sel } + sel.filter { it !in cur }
                prefs.edit().putString("pins", ordered.joinToString(",")).apply()
                renderGrid()
            }
            .setNegativeButton("CANCELAR", null).show()
    }
}
