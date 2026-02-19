package com.runanywhere.kotlin_starter_example.utils

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio recorder with Voice Activity Detection (VAD).
 * Records 16kHz mono PCM. When VAD is enabled, automatically stops
 * recording after 1.5 seconds of silence — no button press needed.
 *
 * Exposes [currentAmplitude] (0–1) for real-time UI visualization.
 */
class SimpleAudioRecorder {
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var isRecording = false
    private val audioData = ByteArrayOutputStream()

    /** Current audio amplitude (0.0 – 1.0) for UI waveform / glow effects. */
    @Volatile
    var currentAmplitude: Float = 0f
        private set

    /** Fired from the recording thread when VAD detects silence after speech. */
    @Volatile
    var onSilenceDetected: (() -> Unit)? = null

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // ── VAD tuning ──
        private const val SILENCE_THRESHOLD_RMS = 450    // RMS below this = silence
        private const val SILENCE_DURATION_MS  = 1500L   // 1.5s silence → auto-stop
        private const val MIN_SPEECH_BYTES     = 16000   // ≈0.5s — ignore very short clips
    }

    /**
     * Start recording.
     * @param enableVAD When true, recording auto-stops after [SILENCE_DURATION_MS]
     *   of silence once speech has been detected. [onSilenceDetected] is invoked.
     */
    fun startRecording(enableVAD: Boolean = false): Boolean {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) return false

        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return false

            audioData.reset()
            currentAmplitude = 0f
            audioRecord?.startRecording()
            isRecording = true

            var silenceStartMs = 0L
            var hasSpeechStarted = false

            Thread {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        synchronized(audioData) {
                            audioData.write(buffer, 0, read)
                        }

                        // ── Compute RMS amplitude for UI + VAD ──
                        var sum = 0L
                        val samples = read / 2
                        var i = 0
                        while (i < read - 1) {
                            val lo = buffer[i].toInt() and 0xFF
                            val hi = buffer[i + 1].toInt() shl 8
                            val sample = lo or hi
                            val signed = if (sample > 32767) sample - 65536 else sample
                            sum += signed.toLong() * signed.toLong()
                            i += 2
                        }
                        val rms = if (samples > 0) Math.sqrt(sum.toDouble() / samples).toFloat() else 0f
                        currentAmplitude = (rms / 6000f).coerceIn(0f, 1f)

                        // ── VAD: detect silence after user has spoken ──
                        if (enableVAD) {
                            if (rms > SILENCE_THRESHOLD_RMS) {
                                hasSpeechStarted = true
                                silenceStartMs = 0L
                            } else if (hasSpeechStarted && audioData.size() > MIN_SPEECH_BYTES) {
                                if (silenceStartMs == 0L) {
                                    silenceStartMs = System.currentTimeMillis()
                                } else if (System.currentTimeMillis() - silenceStartMs >= SILENCE_DURATION_MS) {
                                    // Silence after speech → auto-stop
                                    onSilenceDetected?.invoke()
                                    break
                                }
                            }
                        }
                    }
                }
            }.start()
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun stopRecording(): ByteArray {
        isRecording = false
        currentAmplitude = 0f
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        synchronized(audioData) {
            return audioData.toByteArray()
        }
    }
}

/**
 * Play WAV audio data using AudioTrack.
 */
suspend fun playWavAudioData(wavData: ByteArray) = withContext(Dispatchers.IO) {
    if (wavData.size < 44) return@withContext

    try {
        val buffer = ByteBuffer.wrap(wavData).order(ByteOrder.LITTLE_ENDIAN)

        // Parse WAV header
        buffer.position(20)
        buffer.short // audioFormat
        val numChannels = buffer.short.toInt()
        val sampleRate = buffer.int
        buffer.int // byteRate
        buffer.short // blockAlign
        val bitsPerSample = buffer.short.toInt()

        // Find data chunk
        var dataOffset = 36
        while (dataOffset < wavData.size - 8) {
            if (wavData[dataOffset].toInt().toChar() == 'd' &&
                wavData[dataOffset + 1].toInt().toChar() == 'a' &&
                wavData[dataOffset + 2].toInt().toChar() == 't' &&
                wavData[dataOffset + 3].toInt().toChar() == 'a'
            ) break
            dataOffset++
        }
        dataOffset += 8
        if (dataOffset >= wavData.size) return@withContext

        val pcmData = wavData.copyOfRange(dataOffset, wavData.size)

        val channelConfig = if (numChannels == 1) AudioFormat.CHANNEL_OUT_MONO
        else AudioFormat.CHANNEL_OUT_STEREO

        val audioFormatConfig = if (bitsPerSample == 16) AudioFormat.ENCODING_PCM_16BIT
        else AudioFormat.ENCODING_PCM_8BIT

        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormatConfig)

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(audioFormatConfig)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBufferSize, pcmData.size))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack.write(pcmData, 0, pcmData.size)
        audioTrack.play()

        val durationMs = (pcmData.size.toLong() * 1000) / (sampleRate * numChannels * (bitsPerSample / 8))
        delay(durationMs + 100)

        audioTrack.stop()
        audioTrack.release()
    } catch (_: Exception) {
        // Silently handle playback errors
    }
}
