package com.runanywhere.kotlin_starter_example.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Voice accent for Android's built-in TTS engine.
 * No download required — all accents are built into Android.
 */
data class VoiceAccent(
    val id: String,
    val name: String,
    val locale: Locale,
    val flag: String
)

/**
 * Accent-aware TTS using Android's built-in TextToSpeech engine.
 *
 * Advantages over Piper TTS:
 *  • Zero download (built into Android)
 *  • Supports 10+ English accents including Indian 🇮🇳
 *  • Runs as system service — doesn't use app's native heap
 *  • Speed & pitch controls
 */
class AccentTTSManager(context: Context) {

    private var tts: TextToSpeech? = null

    @Volatile
    var isReady = false
        private set

    companion object {
        private const val TAG = "AccentTTS"

        val ACCENTS = listOf(
            VoiceAccent("indian", "Indian English", Locale("en", "IN"), "🇮🇳"),
            VoiceAccent("us", "American English", Locale.US, "🇺🇸"),
            VoiceAccent("uk", "British English", Locale.UK, "🇬🇧"),
            VoiceAccent("australian", "Australian English", Locale("en", "AU"), "🇦🇺"),
            VoiceAccent("canadian", "Canadian English", Locale.CANADA, "🇨🇦"),
            VoiceAccent("south_african", "South African English", Locale("en", "ZA"), "🇿🇦"),
            VoiceAccent("irish", "Irish English", Locale("en", "IE"), "🇮🇪"),
            VoiceAccent("scottish", "Scottish English", Locale("en", "GB"), "🏴󠁧󠁢󠁳󠁣󠁴󠁿"),
            VoiceAccent("nigerian", "Nigerian English", Locale("en", "NG"), "🇳🇬"),
            VoiceAccent("singaporean", "Singaporean English", Locale("en", "SG"), "🇸🇬"),
        )

        fun getAccent(id: String): VoiceAccent = ACCENTS.find { it.id == id } ?: ACCENTS[0]
    }

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            isReady = status == TextToSpeech.SUCCESS
            if (isReady) {
                Log.d(TAG, "Android TTS ready. Accents: ${tts?.availableLanguages?.size ?: 0}")
            } else {
                Log.e(TAG, "Android TTS init failed (status=$status)")
            }
        }
    }

    /**
     * Speak text with the selected accent. Suspends until speech completes.
     * This is a coroutine-friendly wrapper around Android's async TTS.
     */
    suspend fun speakAndWait(
        text: String,
        accentId: String,
        speed: Float = 1.0f,
        pitch: Float = 1.0f
    ) {
        if (!isReady || text.isBlank()) return

        val accent = getAccent(accentId)
        tts?.language = accent.locale
        tts?.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
        tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))

        suspendCancellableCoroutine { continuation ->
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                @Deprecated("Deprecated in API")
                override fun onError(utteranceId: String?) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.w(TAG, "TTS error code=$errorCode for accent=${accent.id}")
                    if (continuation.isActive) continuation.resume(Unit)
                }
            })

            val utteranceId = "youlearn_${System.currentTimeMillis()}"
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

            continuation.invokeOnCancellation {
                tts?.stop()
            }
        }
    }

    /** Fire-and-forget speak (no waiting for completion). */
    fun speakAsync(text: String, accentId: String, speed: Float = 1.0f, pitch: Float = 1.0f) {
        if (!isReady || text.isBlank()) return
        val accent = getAccent(accentId)
        tts?.language = accent.locale
        tts?.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
        tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "youlearn_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
