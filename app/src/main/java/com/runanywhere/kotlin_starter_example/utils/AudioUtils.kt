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
 * Simple audio recorder for STT.
 * Records 16kHz mono PCM audio.
 */
class SimpleAudioRecorder {
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var isRecording = false
    private val audioData = ByteArrayOutputStream()

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    fun startRecording(): Boolean {
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
            audioRecord?.startRecording()
            isRecording = true

            Thread {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        synchronized(audioData) {
                            audioData.write(buffer, 0, read)
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
