package com.runanywhere.kotlin_starter_example.utils

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Android built-in Speech-to-Text using Google's SpeechRecognizer.
 *
 * Advantages over CppBridgeSTT (Whisper):
 *  • Zero native heap — runs in Google app's process
 *  • ~0MB memory vs ~100MB for Whisper model
 *  • No model download needed
 *  • Better accuracy (server-side or on-device Google STT)
 *  • Built-in silence detection
 *
 * This replaces the SDK's CppBridgeSTT + SimpleAudioRecorder + RunAnywhere.transcribe() pipeline
 * with a single SpeechRecognizer call that handles recording + transcription.
 */
class AndroidSTTManager(private val context: Context) {

    companion object {
        private const val TAG = "AndroidSTT"
    }

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    @Volatile
    var isListening = false
        private set

    /** Live partial transcript while user is speaking */
    @Volatile
    var partialResult: String = ""
        private set

    /** Live amplitude (0..1) for UI visualization */
    @Volatile
    var currentAmplitude: Float = 0f
        private set

    private var speechRecognizer: SpeechRecognizer? = null

    /**
     * Start listening and return the final transcribed text.
     * Suspends until the user stops speaking (auto-detected by Google's VAD).
     *
     * @param onPartialResult callback for live partial transcripts
     * @param silenceMs silence duration (ms) before auto-stop (500 = fast conversational, 1500 = default)
     * @param onAmplitude callback for live audio amplitude (0..1)
     * @return transcribed text, or empty string on error/no speech
     */
    suspend fun listenAndTranscribe(
        silenceMs: Long = 1500L,
        onPartialResult: (String) -> Unit = {},
        onAmplitude: (Float) -> Unit = {}
    ): String = suspendCancellableCoroutine { continuation ->

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // VAD silence thresholds — parameterized for conversational vs normal mode
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L.coerceAtMost(silenceMs))
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                partialResult = ""
                currentAmplitude = 0f
                Log.d(TAG, "Ready for speech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "User started speaking")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Convert RMS dB to 0..1 range for UI visualization
                // Typical range: -2 to 10 dB
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                currentAmplitude = normalized
                onAmplitude(normalized)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListening = false
                currentAmplitude = 0f
                Log.d(TAG, "End of speech detected")
            }

            override fun onError(error: Int) {
                isListening = false
                currentAmplitude = 0f
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "No audio permission"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
                    else -> "Unknown error ($error)"
                }
                Log.w(TAG, "STT error: $errorMsg")
                cleanup()
                if (continuation.isActive) continuation.resume("")
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                currentAmplitude = 0f
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                Log.d(TAG, "Final result: ${text.take(60)}...")
                cleanup()
                if (continuation.isActive) continuation.resume(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull() ?: ""
                if (partial.isNotBlank()) {
                    partialResult = partial
                    onPartialResult(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer.startListening(intent)
        Log.d(TAG, "Started listening")

        continuation.invokeOnCancellation {
            Log.d(TAG, "Listening cancelled")
            stopListening()
        }
    }

    /** Stop listening immediately (manual stop by user). */
    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "stopListening error: ${e.message}")
        }
        isListening = false
        currentAmplitude = 0f
    }

    /** Cancel without returning results. */
    fun cancel() {
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "cancel error: ${e.message}")
        }
        cleanup()
    }

    private fun cleanup() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy error: ${e.message}")
        }
        speechRecognizer = null
        isListening = false
        currentAmplitude = 0f
        partialResult = ""
    }

    fun shutdown() {
        cancel()
    }
}
