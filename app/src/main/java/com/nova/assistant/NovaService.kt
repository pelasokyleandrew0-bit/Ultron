package com.nova.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Always-on listener. Flow:
 *   listen for "Hey Ultron"  ->  take the command  ->  ask Claude (which may act on the phone)  ->  speak the reply
 * If the reply is a question, Ultron listens for your answer without needing the wake word again.
 */
class NovaService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recognizer: SpeechRecognizer? = null
    private lateinit var voice: VoiceOutput
    private lateinit var claude: ClaudeClient

    private var running = false
    private var listening = false
    private var busy = false            // thinking or speaking
    private var awaitingCommand = false // next utterance is a command, no wake word needed

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Microphone permission missing")
            }
            startAsForeground()
        } catch (e: Exception) {
            stopSelf()
            return
        }
        voice = VoiceOutput(this)
        claude = ClaudeClient(this)
        running = true
        main.post { startListening() }
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        if (::voice.isInitialized) voice.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ultron listening", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Ultron is listening")
            .setContentText("Say \"Hey Ultron\"")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    // ---- listening -------------------------------------------------------------------------

    private fun startListening() {
        if (!running || busy || listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopSelf()
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        listening = true
        recognizer?.startListening(intent)
    }

    private fun restartListening(delayMs: Long) {
        main.postDelayed({ startListening() }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            listening = false
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stopSelf()
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> {
                    awaitingCommand = false
                    restartListening(250)
                }
                else -> restartListening(1200)
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            handle(text)
        }
    }

    // ---- handling what was heard -----------------------------------------------------------

    private fun handle(heard: String) {
        val t = heard.lowercase().trim()
        if (t.isEmpty()) {
            restartListening(250)
            return
        }

        if (awaitingCommand) {
            awaitingCommand = false
            process(t)
            return
        }

        val m = WAKE.find(t)
        if (m == null) {
            restartListening(100)
            return
        }
        val command = t.substring(m.range.last + 1).trim(' ', ',', '.')
        if (command.isEmpty()) {
            awaitingCommand = true
            say("Yes?")
        } else {
            process(command)
        }
    }

    private fun process(command: String) {
        busy = true
        scope.launch {
            val reply = try {
                claude.ask(command)
            } catch (e: ClaudeException) {
                e.message ?: "Something failed."
            } catch (e: Exception) {
                "Something failed."
            }
            main.post { say(reply) }
        }
    }

    private fun say(reply: String) {
        busy = true
        voice.speak(reply) {
            busy = false
            if (reply.trimEnd().endsWith("?")) awaitingCommand = true
            startListening()
        }
    }

    companion object {
        private const val CHANNEL = "nova_listening"
        private val WAKE = Regex("""\b(?:hey|hi|ok|okay)?[\s,]*(?:ultron|altron|oltron|ultra on|all tron)\b""")
    }
}
