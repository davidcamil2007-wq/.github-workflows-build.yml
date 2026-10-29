package com.autodroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.text.Normalizer
import java.util.Locale

class VoiceService : Service(), TextToSpeech.OnInitListener {

    companion object {
        @Volatile var instance: VoiceService? = null
        @Volatile var lastInfo = "iniciando..."
        private const val CH = "autodroid"
    }

    private enum class S { IDLE, ASK_REPLY, DICTATE, CONFIRM }

    private var state = S.IDLE
    private val h = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var rec: SpeechRecognizer? = null
    private var speaking = false
    private var running = false
    private var current: Msg? = null
    private var pending = ""
    private var focus: AudioFocusRequest? = null
    private var afterSpeech: (() -> Unit)? = null
    private val am by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    // escuta contínua (palavra "carro") e modo "falar agora" (sem palavra de ativação)
    @Volatile private var continuous = true
    @Volatile private var wakeFree = false
    private val resetWake = Runnable { wakeFree = false }
    private val wakeRe = Regex("^(ok |oi |ei |e )?(carro|carros|caro|carlo|carlos|carrao|carrinho)\\b[ ,]*(.*)$")

    override fun onBind(i: Intent?): IBinder? = null

    // ---------- notificação (mostra o que o app ouviu / erros) ----------
    private fun notif(text: String): Notification {
        fun act(a: String, label: String, code: Int) = NotificationCompat.Action.Builder(
            0, label,
            PendingIntent.getService(
                this, code, Intent(this, VoiceService::class.java).setAction(a),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ).build()
        return NotificationCompat.Builder(this, CH)
            .setContentTitle("AutoDroid ativo")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(act("TALK", "FALAR", 1))
            .addAction(act("TOGGLE", "CONTÍNUO", 2))
            .build()
    }

    private fun info(t: String) {
        lastInfo = t
        getSystemService(NotificationManager::class.java).notify(1, notif(t))
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Modo Carro", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, 1, notif("iniciando..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        instance = this
        KitLink.start(applicationContext)
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        when (i?.action) {
            "TALK" -> pushToTalk()
            "TOGGLE" -> toggle()
        }
        return START_NOT_STICKY
    }

    private fun armWake() {
        wakeFree = true
        h.removeCallbacks(resetWake)
        h.postDelayed(resetWake, 10_000)
    }

    private fun pushToTalk() {
        h.post {
            armWake()
            if (running && !speaking) { rec?.cancel(); listen(0) }
        }
    }

    private fun toggle() {
        h.post {
            continuous = !continuous
            info(if (continuous) "escuta contínua LIGADA" else "escuta contínua DESLIGADA (use FALAR)")
            if (continuous) listen(0) else rec?.cancel()
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { info("erro: voz do celular (TTS) indisponível"); stopSelf(); return }
        tts?.language = Locale("pt", "BR")
        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { done() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { done() }
        })
        ttsReady = true
        running = true
        info("pronto: diga \"carro\" + comando")
        say("Modo carro ativado. Diga carro, e o comando.")
    }

    private fun done() {
        h.post {
            speaking = false
            abandon()
            val f = afterSpeech
            afterSpeech = null
            if (f != null) f() else listen()
        }
    }

    // ---------- fala ----------
    private fun say(t: String, then: (() -> Unit)? = null) {
        if (!ttsReady || !running) return
        h.post {
            rec?.cancel()
            speaking = true
            afterSpeech = then
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                ).build()
            focus = req
            am.requestAudioFocus(req)
            tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
        }
    }

    private fun abandon() {
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }

    // ---------- escuta ----------
    private fun errName(e: Int) = when (e) {
        1 -> "tempo de rede esgotado"
        2 -> "rede"
        3 -> "áudio"
        4 -> "servidor"
        5 -> "cliente"
        8 -> "ocupado"
        9 -> "sem permissão"
        11 -> "servidor desconectado"
        12 -> "idioma não suportado"
        13 -> "idioma indisponível"
        else -> "desconhecido"
    }

    private fun listen(delay: Long = 300) {
        h.postDelayed({
            if (!running || speaking) return@postDelayed
            if (!continuous && state == S.IDLE && !wakeFree) return@postDelayed
            rec?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            rec = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) {
                    val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    if (list.isEmpty()) { listen(); return }
                    info("ouvi: ${list.first()}")
                    handle(list)
                }
                override fun onError(e: Int) {
                    when (e) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { info("sem permissão de microfone"); stopSelf() }
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listen(200)
                        else -> {
                            info("erro de voz $e (${errName(e)})")
                            listen(if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 3000)
                        }
                    }
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            })
        }, delay)
    }

    // ---------- lógica ----------
    private fun handle(alts: List<String>) {
        val raw = alts.first()
        val t = norm(raw)
        when (state) {
            S.DICTATE -> {
                if (t == "cancelar") cancel()
                else {
                    pending = raw.trim()
                    state = S.CONFIRM
                    say("Vou enviar: $pending. Diga enviar, repetir ou cancelar.")
                }
            }
            S.CONFIRM -> when {
                has(t, "cancelar", "nao") -> cancel()
                has(t, "repetir", "de novo", "refazer") -> { state = S.DICTATE; say("Pode falar a resposta.") }
                has(t, "enviar", "envia", "manda", "sim", "pode") -> send()
                else -> say("Diga enviar, repetir ou cancelar.")
            }
            S.ASK_REPLY -> when {
                has(t, "nao", "deixa", "cancelar") -> { state = S.IDLE; say("Ok.") }
                has(t, "sim", "responder", "responde", "quero") -> { state = S.DICTATE; say("Pode falar a resposta.") }
                else -> say("Quer responder? Diga sim ou não.")
            }
            S.IDLE -> {
                if (wakeFree) {
                    wakeFree = false
                    h.removeCallbacks(resetWake)
                    command(t, raw)
                    return
                }
                for (a in alts) {
                    val m = wakeRe.find(norm(a))
                    if (m != null) { command(m.groupValues[3], a); return }
                }
                listen()
            }
        }
    }

    private fun command(c: String, raw: String) {
        when {
            c.isEmpty() -> { armWake(); say("Pois não?") }
            has(c, "responder", "responde", "responda") -> {
                val m = current ?: Store.latest()
                if (m == null) { say("Não tenho nenhuma conversa para responder."); return }
                current = m
                val txt = Regex("(?i)respond\\w*[ ,:]*(.*)$").find(raw)?.groupValues?.get(1)?.trim().orEmpty()
                if (txt.isNotBlank()) {
                    pending = txt
                    state = S.CONFIRM
                    say("Vou enviar para ${m.sender}: $txt. Diga enviar, repetir ou cancelar.")
                } else {
                    state = S.DICTATE
                    say("Responder para ${m.sender}. Pode falar.")
                }
            }
            has(c, "proxima", "proximo", "pular", "avancar") -> { key(KeyEvent.KEYCODE_MEDIA_NEXT); listen(1000) }
            has(c, "anterior", "voltar") -> { key(KeyEvent.KEYCODE_MEDIA_PREVIOUS); listen(1000) }
            has(c, "pausar", "pausa", "pare", "parar") -> { key(KeyEvent.KEYCODE_MEDIA_PAUSE); listen(800) }
            has(c, "tocar", "toca", "continuar", "retomar", "play") -> { key(KeyEvent.KEYCODE_MEDIA_PLAY); listen(800) }
            has(c, "volume") -> {
                val up = has(c, "aumentar", "aumenta", "mais", "subir", "sobe")
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0
                )
                listen(800)
            }
            has(c, "abrir", "abre", "abra") && has(c, "whatsapp", "zap") -> {
                val i = packageManager.getLaunchIntentForPackage("com.whatsapp")
                if (i != null) { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); listen(1500) }
                else say("WhatsApp não encontrado.")
            }
            has(c, "ler", "leia", "mensagem", "mensagens", "novas") -> readNext()
            has(c, "desligar", "encerrar", "sair", "desativar") -> say("Modo carro desativado.") { stopSelf() }
            else -> say("Não entendi.")
        }
    }

    private fun readNext() {
        val m = Store.latestUnread()
        if (m == null) { say("Você não tem mensagens novas."); return }
        val n = Store.unreadCount()
        m.read = true
        current = m
        state = S.ASK_REPLY
        val intro = if (n > 1) "Você tem $n mensagens novas. A mais recente, " else ""
        say("${intro}de ${m.sender}: ${m.text}. Quer responder?")
    }

    private fun send() {
        val m = current
        val ok = m != null && Reply.send(this, m, pending)
        state = S.IDLE
        say(if (ok) "Mensagem enviada." else "Não consegui enviar. Abra a conversa no aplicativo e tente de novo.")
    }

    private fun cancel() { state = S.IDLE; say("Cancelado.") }

    fun onNewMessage(m: Msg) {
        h.post {
            if (running && state == S.IDLE && !speaking) {
                current = m
                say("Nova mensagem de ${m.sender}. Diga carro, ler.")
            }
        }
    }

    // ---------- utilidades ----------
    private fun key(code: Int) {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun has(t: String, vararg w: String) = w.any { Regex("\\b$it\\b").containsMatchIn(t) }

    private fun norm(s: String) =
        Normalizer.normalize(s.lowercase(Locale("pt", "BR")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

    override fun onDestroy() {
        running = false
        instance = null
        KitLink.stop()
        h.removeCallbacksAndMessages(null)
        rec?.destroy()
        tts?.shutdown()
        abandon()
        super.onDestroy()
    }
}
