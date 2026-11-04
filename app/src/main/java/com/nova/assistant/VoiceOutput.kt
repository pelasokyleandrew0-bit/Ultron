package com.nova.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

/**
 * Speaks text in a deep, slow, slightly metallic synthetic voice.
 *
 * Steps: Android TTS renders the text to a WAV file (with lowered pitch and rate),
 * then we add a ring-modulation + short echo effect and play the result.
 */
class VoiceOutput(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                chooseDeepVoice()
                ready = true
                pending?.invoke()
                pending = null
            }
        }
    }

    /** Prefer a male-sounding offline Google voice if the phone has one. Otherwise keep the default. */
    private fun chooseDeepVoice() {
        val t = tts ?: return
        val voices = try { t.voices } catch (e: Exception) { null } ?: return
        val preferred = listOf("en-us-x-iom-local", "en-us-x-tpd-local", "en-us-x-iol-local")
        val v = preferred.firstNotNullOfOrNull { n -> voices.firstOrNull { it.name == n } }
        if (v != null) t.voice = v
    }

    /** Speaks [text]. [finished] runs on the main thread when playback ends (or if anything fails). */
    fun speak(text: String, finished: () -> Unit) {
        val t = tts
        if (t == null) { finished(); return }
        if (!ready) { pending = { speak(text, finished) }; return }

        t.setPitch(Prefs.pitch(context))
        t.setSpeechRate(Prefs.rate(context))

        val clean = text.take(TextToSpeech.getMaxSpeechInputLength() - 1)
        val id = UUID.randomUUID().toString()
        val file = File(context.cacheDir, "nova_$id.wav")

        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                Thread { playWithEffect(file, finished) }.start()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                file.delete()
                main.post(finished)
            }
        })

        if (t.synthesizeToFile(clean, Bundle(), file, id) != TextToSpeech.SUCCESS) {
            main.post(finished)
        }
    }

    private fun playWithEffect(file: File, finished: () -> Unit) {
        var track: AudioTrack? = null
        try {
            val bytes = file.readBytes()
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val channels = bb.getShort(22).toInt()
            val sampleRate = bb.getInt(24)

            // Find the "data" chunk of the WAV file.
            var pos = 12
            var dataStart = -1
            var dataLen = 0
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val len = bb.getInt(pos + 4)
                if (id == "data") {
                    dataStart = pos + 8
                    dataLen = if (len <= 0 || dataStart + len > bytes.size) bytes.size - dataStart else len
                    break
                }
                pos += 8 + len + (len and 1)
            }
            if (dataStart < 0 || channels !in 1..2) return

            val n = dataLen / 2
            val src = ShortArray(n)
            ByteBuffer.wrap(bytes, dataStart, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(src)

            // Metallic effect: blend the voice with a copy multiplied by a ~48 Hz sine (ring modulation),
            // plus a very short feedback echo.
            val amount = Prefs.metal(context).toDouble()
            val out = ShortArray(n)
            val echoDelay = (sampleRate * 0.02).toInt() * channels
            val w = 2.0 * PI * 48.0 / sampleRate
            for (i in 0 until n) {
                val frame = i / channels
                val dry = src[i].toDouble()
                val ring = dry * sin(w * frame)
                var v = dry * (1.0 - amount) + ring * amount * 1.4
                if (amount > 0.0 && i >= echoDelay) v += out[i - echoDelay] * 0.3 * amount
                out[i] = v.coerceIn(-32768.0, 32767.0).toInt().toShort()
            }

            val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(mask)
                        .build()
                )
                .setBufferSizeInBytes(n * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track = t
            t.write(out, 0, n)
            t.play()

            val frames = n / channels
            while (t.playbackHeadPosition < frames && t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                Thread.sleep(30)
            }
            Thread.sleep(100)
        } catch (e: Exception) {
            // Fall through: finished() still runs below so the assistant never gets stuck.
        } finally {
            try { track?.release() } catch (e: Exception) {}
            file.delete()
            main.post(finished)
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
