package com.autodroid

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.min

/** Roda no KIT MULTIMÍDIA: conecta no celular (gateway do hotspot), mostra o vídeo e envia toques. */
class MirrorViewActivity : Activity(), TextureView.SurfaceTextureListener {

    private lateinit var host: FrameLayout
    private lateinit var tv: TextureView
    private lateinit var panel: LinearLayout
    private lateinit var info: TextView
    private lateinit var bar: LinearLayout

    @Volatile private var surface: Surface? = null
    @Volatile private var active = false
    @Volatile private var gen = 0
    @Volatile private var sock: Socket? = null
    @Volatile private var outS: DataOutputStream? = null
    @Volatile private var ipValue = ""
    private val sender = Executors.newSingleThreadExecutor()

    private fun ui(r: () -> Unit) { runOnUiThread { r() } }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val d = resources.displayMetrics.density
        val mp = ViewGroup.LayoutParams.MATCH_PARENT

        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.BLACK) }
        host = FrameLayout(this)
        tv = TextureView(this).apply { surfaceTextureListener = this@MirrorViewActivity }
        host.addView(tv, FrameLayout.LayoutParams(mp, mp, Gravity.CENTER))

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
        }
        info = TextView(this).apply { setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER }
        val ipEdit = EditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "IP do celular (vazio = automático)"
            keyListener = DigitsKeyListener.getInstance("0123456789.")
            gravity = Gravity.CENTER
            doAfterTextChanged { ipValue = it.toString().trim() }
        }
        panel.addView(info)
        panel.addView(ipEdit, LinearLayout.LayoutParams((320 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        host.addView(panel, FrameLayout.LayoutParams(mp, mp))

        bar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        fun b(label: String, click: () -> Unit) {
            bar.addView(Button(this).apply {
                text = label; textSize = 26f
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(mp, 0, 1f))
        }
        b("◀") { sendKey(1) }
        b("●") { sendKey(2) }
        b("▢") { sendKey(3) }
        b("✕") { finish() }

        root.addView(host, LinearLayout.LayoutParams(0, mp, 1f))
        root.addView(bar, LinearLayout.LayoutParams((90 * d).toInt(), mp))
        setContentView(root)

        var downX = 0f; var downY = 0f; var downT = 0L
        tv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downT = e.eventTime }
                MotionEvent.ACTION_UP -> {
                    val w = v.width.toFloat(); val h = v.height.toFloat()
                    fun c(x: Float) = x.coerceIn(0f, 1f)
                    sendTouch(c(downX / w), c(downY / h), c(e.x / w), c(e.y / h), (e.eventTime - downT).toInt())
                }
            }
            true
        }
    }

    override fun onStart() {
        super.onStart()
        active = true
        val g = ++gen
        Thread { loop(g) }.start()
    }

    override fun onStop() {
        active = false
        try { sock?.close() } catch (e: Exception) { }
        super.onStop()
    }

    // ---------- conexão ----------
    @Suppress("DEPRECATION")
    private fun gateway(): String {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val g = wm.dhcpInfo.gateway
        return if (g == 0) "192.168.43.1"
        else "${g and 255}.${(g shr 8) and 255}.${(g shr 16) and 255}.${(g shr 24) and 255}"
    }

    private fun loop(g: Int) {
        while (active && g == gen) {
            val ip = ipValue.ifBlank { getSharedPreferences("kit", 0).getString("ip", "").orEmpty().ifBlank { gateway() } }
            ui { info.text = "Procurando o celular em $ip...\n(hotspot ligado? app do celular em ESPELHAR?)"; panel.visibility = View.VISIBLE; bar.visibility = View.GONE }
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, MirrorService.PORT), 4000)
                s.tcpNoDelay = true
                sock = s
                session(s, g)
            } catch (e: Exception) {
            } finally {
                try { sock?.close() } catch (e: Exception) { }
                sock = null
                outS = null
            }
            try { Thread.sleep(2500) } catch (e: InterruptedException) { }
        }
    }

    private fun session(s: Socket, g: Int) {
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 65536))
        val w = inp.readInt()
        val h = inp.readInt()
        outS = DataOutputStream(s.getOutputStream())

        var t = 0
        while (surface == null && t++ < 100 && active) Thread.sleep(50)
        val sf = surface ?: return

        ui { fit(w, h); panel.visibility = View.GONE; bar.visibility = View.VISIBLE }

        val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        fmt.setInteger("low-latency", 1)
        dec.configure(fmt, sf, null, 0)
        dec.start()
        val bi = MediaCodec.BufferInfo()

        fun drain() {
            var o = dec.dequeueOutputBuffer(bi, 0)
            while (o >= 0) { dec.releaseOutputBuffer(o, true); o = dec.dequeueOutputBuffer(bi, 0) }
        }

        try {
            while (active && g == gen) {
                val type = inp.readUnsignedByte()
                val len = inp.readInt()
                val b = ByteArray(len)
                inp.readFully(b)
                var idx = dec.dequeueInputBuffer(10_000)
                while (idx < 0 && active) { drain(); idx = dec.dequeueInputBuffer(10_000) }
                if (idx < 0) break
                val ib = dec.getInputBuffer(idx)!!
                ib.clear()
                ib.put(b)
                val flags = if (type == 0) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                dec.queueInputBuffer(idx, 0, len, System.nanoTime() / 1000, flags)
                drain()
            }
        } finally {
            try { dec.stop() } catch (e: Exception) { }
            try { dec.release() } catch (e: Exception) { }
        }
    }

    private fun fit(w: Int, h: Int) {
        val cw = if (host.width > 0) host.width else resources.displayMetrics.widthPixels
        val ch = if (host.height > 0) host.height else resources.displayMetrics.heightPixels
        val sc = min(cw.toFloat() / w, ch.toFloat() / h)
        tv.layoutParams = FrameLayout.LayoutParams((w * sc).toInt(), (h * sc).toInt(), Gravity.CENTER)
    }

    // ---------- envio de toques ----------
    private fun sendTouch(x1: Float, y1: Float, x2: Float, y2: Float, dur: Int) {
        sender.execute {
            try {
                outS?.apply {
                    writeByte(1); writeFloat(x1); writeFloat(y1); writeFloat(x2); writeFloat(y2); writeInt(dur); flush()
                }
            } catch (e: Exception) { }
        }
    }

    private fun sendKey(code: Int) {
        sender.execute {
            try { outS?.apply { writeByte(2); writeInt(code); flush() } } catch (e: Exception) { }
        }
    }

    // ---------- TextureView ----------
    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { surface = Surface(st) }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        surface?.release(); surface = null; return true
    }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
