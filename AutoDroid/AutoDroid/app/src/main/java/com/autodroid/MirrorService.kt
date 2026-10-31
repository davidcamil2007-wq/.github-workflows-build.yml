package com.autodroid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Roda no CELULAR: captura a tela, codifica em H.264 e envia por TCP (porta 5555) para o kit.
 * Recebe do kit: toques (tipo 1) e botões Voltar/Início/Recentes (tipo 2).
 * Protocolo celular -> kit: [int largura][int altura] e depois quadros: [byte tipo][int tamanho][bytes]
 *   tipo 0 = configuração (SPS/PPS), 1 = quadro-chave, 2 = quadro normal.
 */
class MirrorService : Service() {

    companion object {
        const val PORT = 5555
        @Volatile var isOn = false
    }

    private val lock = Any()
    @Volatile private var running = false
    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var enc: MediaCodec? = null
    private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    private var out: DataOutputStream? = null
    @Volatile private var config: ByteArray? = null
    private var needKey = true
    private var ew = 0
    private var eh = 0
    private var realW = 1
    private var realH = 1

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun readData(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra("data", Intent::class.java)
        else i.getParcelableExtra("data")

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (running || i == null) return START_NOT_STICKY
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("mirror", "Espelhamento", NotificationManager.IMPORTANCE_LOW)
        )
        val n = NotificationCompat.Builder(this, "mirror")
            .setContentTitle("AutoDroid: espelhando a tela")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true).build()
        ServiceCompat.startForeground(this, 2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = i.getIntExtra("code", 0)
        val data = readData(i)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        try { startMirror(code, data) } catch (e: Exception) { stopSelf() }
        return START_NOT_STICKY
    }

    private fun startMirror(code: Int, data: Intent) {
        val dm = DisplayMetrics()
        getSystemService(DisplayManager::class.java).getDisplay(0).getRealMetrics(dm)
        realW = dm.widthPixels
        realH = dm.heightPixels
        val sc = 1280f / max(realW, realH)
        ew = max(16, (realW * sc / 16).roundToInt() * 16)
        eh = max(16, (realH * sc / 16).roundToInt() * 16)

        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        proj = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))

        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ew, eh).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000)
        }
        val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        e.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surf = e.createInputSurface()
        e.start()
        enc = e

        vd = mp.createVirtualDisplay(
            "autodroid", ew, eh, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surf, null, null
        )

        running = true
        isOn = true
        Thread { acceptLoop() }.start()
        Thread { drainLoop() }.start()
    }

    private fun writeFrame(o: DataOutputStream, type: Int, b: ByteArray) {
        o.writeByte(type)
        o.writeInt(b.size)
        o.write(b)
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket(PORT)
            server = ss
            while (running) {
                val s = ss.accept()
                s.tcpNoDelay = true
                client = s
                synchronized(lock) {
                    try {
                        val o = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 65536))
                        o.writeInt(ew)
                        o.writeInt(eh)
                        config?.let { writeFrame(o, 0, it) }
                        o.flush()
                        needKey = true
                        out = o
                    } catch (ex: IOException) {
                        out = null
                    }
                }
                try {
                    enc?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                } catch (ex: Exception) { }
                try { readLoop(DataInputStream(BufferedInputStream(s.getInputStream()))) } catch (ex: IOException) { }
                synchronized(lock) { out = null }
                try { s.close() } catch (ex: IOException) { }
            }
        } catch (ex: IOException) { }
        if (running) stopSelf()
    }

    private fun readLoop(i: DataInputStream) {
        while (running) {
            when (i.readUnsignedByte()) {
                1 -> {
                    val x1 = i.readFloat(); val y1 = i.readFloat()
                    val x2 = i.readFloat(); val y2 = i.readFloat()
                    val d = i.readInt()
                    AutoAccess.instance?.gesture(x1 * realW, y1 * realH, x2 * realW, y2 * realH, d)
                }
                2 -> AutoAccess.instance?.global(i.readInt())
            }
        }
    }

    private fun drainLoop() {
        val e = enc ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = e.dequeueOutputBuffer(info, 100_000)
                if (idx < 0) continue
                val buf = e.getOutputBuffer(idx)
                if (buf == null) { e.releaseOutputBuffer(idx, false); continue }
                val bytes = ByteArray(info.size)
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                buf.get(bytes)
                e.releaseOutputBuffer(idx, false)

                val isCfg = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                if (isCfg) config = bytes

                synchronized(lock) {
                    val o = out
                    if (o != null) {
                        try {
                            if (isCfg) writeFrame(o, 0, bytes)
                            else if (isKey || !needKey) {
                                needKey = false
                                writeFrame(o, if (isKey) 1 else 2, bytes)
                            }
                            o.flush()
                        } catch (ex: IOException) {
                            out = null
                            try { client?.close() } catch (ex2: IOException) { }
                        }
                    }
                }
            }
        } catch (ex: IllegalStateException) { }
    }

    override fun onDestroy() {
        running = false
        isOn = false
        try { server?.close() } catch (ex: IOException) { }
        try { client?.close() } catch (ex: IOException) { }
        try { vd?.release() } catch (ex: Exception) { }
        try { enc?.stop() } catch (ex: Exception) { }
        try { enc?.release() } catch (ex: Exception) { }
        try { proj?.stop() } catch (ex: Exception) { }
        super.onDestroy()
    }
}
