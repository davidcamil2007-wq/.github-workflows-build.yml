package com.autodroid

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * CELULAR -> KIT, pela rede do hotspot (porta 5556).
 *  celular -> kit: [10][sender UTF][texto UTF][app UTF][id int]   (nova mensagem)
 *  kit -> celular: [11][id int][texto UTF]                          (resposta rápida)
 */
object KitLink {
    const val PORT = 5556
    @Volatile private var started = false
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    private val io = Executors.newSingleThreadExecutor()

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
                    out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
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

    private fun read(ctx: Context, i: DataInputStream) {
        while (true) {
            when (i.readUnsignedByte()) {
                11 -> {
                    val id = i.readInt()
                    val text = i.readUTF()
                    val m = Store.byId(id)
                    if (m != null) { Reply.send(ctx, m, text); m.read = true }
                }
            }
        }
    }
}
