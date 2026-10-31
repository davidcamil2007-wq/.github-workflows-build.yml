package com.autodroid

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * CELULAR <-> KIT, pela rede do hotspot (porta 5556).
 *  celular -> kit: 10 notificação [sender][texto][pkg][id]
 *                  20 lista de apps [int n] + n x ([label][pkg][int tam][png])
 *  kit -> celular: 11 resposta rápida [id][texto]
 *                  21 abrir app [pkg]
 *                  22 abrir a conversa da notificação [id]
 */
object KitLink {
    const val PORT = 5556
    @Volatile private var started = false
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    private val io = Executors.newSingleThreadExecutor()

    val connected: Boolean get() = out != null

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        Thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(PORT))
                server = ss
                while (started) {
                    val s = ss.accept()
                    s.tcpNoDelay = true
                    s.keepAlive = true
                    try { client?.close() } catch (e: IOException) { }
                    client = s
                    out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 65536))
                    sendApps(app)
                    Thread {
                        try { read(app, DataInputStream(BufferedInputStream(s.getInputStream()))) } catch (e: IOException) { }
                        if (client === s) out = null
                        try { s.close() } catch (e: IOException) { }
                    }.start()
                }
            } catch (e: IOException) { }
            started = false
        }.start()
    }

    fun stop() {
        started = false
        try { server?.close() } catch (e: IOException) { }
        try { client?.close() } catch (e: IOException) { }
        out = null
    }

    fun push(m: Msg) {
        io.execute {
            val o = out ?: return@execute
            try {
                synchronized(o) {
                    o.writeByte(10)
                    o.writeUTF(m.sender)
                    o.writeUTF(m.text)
                    o.writeUTF(m.pkg)
                    o.writeInt(m.id)
                    o.flush()
                }
            } catch (e: IOException) {
                out = null
                try { client?.close() } catch (e2: IOException) { }
            }
        }
    }

    private fun iconPng(d: Drawable): ByteArray {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        val bo = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bo)
        return bo.toByteArray()
    }

    private fun sendApps(ctx: Context) {
        io.execute {
            val o = out ?: return@execute
            try {
                val pm = ctx.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val list = pm.queryIntentActivities(intent, 0)
                    .filter { it.activityInfo.packageName != ctx.packageName }
                    .distinctBy { it.activityInfo.packageName }
                    .sortedBy { it.loadLabel(pm).toString().lowercase() }
                    .take(150)
                val entries = list.map { Triple(it.loadLabel(pm).toString(), it.activityInfo.packageName, iconPng(it.loadIcon(pm))) }
                synchronized(o) {
                    o.writeByte(20)
                    o.writeInt(entries.size)
                    for ((label, pkg, png) in entries) {
                        o.writeUTF(label)
                        o.writeUTF(pkg)
                        o.writeInt(png.size)
                        o.write(png)
                    }
                    o.flush()
                }
            } catch (e: Exception) { }
        }
    }

    private fun launchApp(ctx: Context, pkg: String) {
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
            ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun openMsg(ctx: Context, m: Msg) {
        val pi = m.content
        if (pi != null) {
            try {
                val opts = ActivityOptions.makeBasic()
                if (Build.VERSION.SDK_INT >= 34) {
                    opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                pi.send(ctx, 0, null, null, null, null, opts.toBundle())
                return
            } catch (e: Exception) { }
        }
        launchApp(ctx, m.pkg)
    }

    private fun read(ctx: Context, i: DataInputStream) {
        while (true) {
            when (i.readUnsignedByte()) {
                11 -> {
                    val id = i.readInt()
                    val text = i.readUTF()
                    val m = Store.byId(id)
                    if (m != null) { Reply.send(ctx, m, text); m.read = true }
                }
                21 -> launchApp(ctx, i.readUTF())
                22 -> {
                    val m = Store.byId(i.readInt())
                    if (m != null) { m.read = true; openMsg(ctx, m) }
                }
            }
        }
    }
}
