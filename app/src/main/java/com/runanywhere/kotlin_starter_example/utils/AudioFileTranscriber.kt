package com.runanywhere.kotlin_starter_example.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.extensions.isSTTModelLoaded
import com.runanywhere.sdk.public.extensions.loadSTTModel
import com.runanywhere.sdk.public.extensions.transcribe
import com.runanywhere.sdk.public.extensions.unloadSTTModel
import com.runanywhere.kotlin_starter_example.services.ModelService
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Transcribes audio files to text using the RunAnywhere SDK's Whisper STT engine.
 *
 * Flow:
 *  1. Decode audio file (MP3/WAV/M4A/OGG/etc.) → raw 16kHz mono PCM16 bytes
 *     using Android's MediaExtractor + MediaCodec (hardware-accelerated).
 *  2. Feed PCM bytes to RunAnywhere.transcribe() → Whisper inference on-device.
 *  3. Return transcribed text.
 *
 * Everything runs silently in the background — no speaker, no mic required.
 * The STT model (~75MB) is loaded temporarily and unloaded after transcription.
 */
class AudioFileTranscriber(private val context: Context) {

    companion object {
        private const val TAG = "AudioTranscriber"
        private const val TARGET_SAMPLE_RATE = 16000   // Whisper expects 16kHz
        private const val MAX_DURATION_MS = 10 * 60 * 1000L  // 10 minutes max
    }

    @Volatile var progress: Float = 0f; private set
    @Volatile var isTranscribing: Boolean = false; private set

    /**
     * Transcribe an audio file to text using Whisper via the RunAnywhere SDK.
     *
     * @param uri Audio file URI from content resolver
     * @param onProgress Callback with (progress 0.0–1.0, text accumulated so far)
     * @return Full transcribed text, or error string starting with "⚠️"
     */
    suspend fun transcribe(
        uri: Uri,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): String = withContext(Dispatchers.IO) {

        if (isTranscribing) return@withContext ""
        isTranscribing = true
        progress = 0f

        try {
            // ── Step 1: Decode audio file → PCM16 at 16kHz mono ──
            onProgress(0.1f, "Decoding audio...")
            progress = 0.1f

            val pcmBytes = decodeAudioToPCM(uri)
                ?: return@withContext "⚠️ Could not decode audio file. Unsupported format."

            if (pcmBytes.size < 3200) { // < 100ms of 16kHz audio
                return@withContext "⚠️ Audio file is too short or empty."
            }

            val durationSec = pcmBytes.size / (TARGET_SAMPLE_RATE * 2f) // 2 bytes per sample
            Log.d(TAG, "Decoded ${pcmBytes.size} bytes (${String.format("%.1f", durationSec)}s of 16kHz PCM)")

            if (durationSec > MAX_DURATION_MS / 1000) {
                return@withContext "⚠️ Audio file is too long (max 10 minutes)."
            }

            // ── Step 2: Load Whisper STT model (if not loaded) ──
            onProgress(0.25f, "Loading speech model...")
            progress = 0.25f

            val sttWasLoaded = RunAnywhere.isSTTModelLoaded()
            if (!sttWasLoaded) {
                Log.d(TAG, "Loading Whisper STT model for file transcription")
                try {
                    RunAnywhere.loadSTTModel(
                        ModelService.STT_MODEL_ID
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "STT model load failed: ${e.message}")
                    // Try downloading first
                    return@withContext "⚠️ Whisper STT model not available. Go to Settings and download it first."
                }
            }

            // ── Step 3: Transcribe with Whisper ──
            onProgress(0.4f, "Transcribing with Whisper AI...")
            progress = 0.4f

            val transcriptionResult = try {
                // Whisper processes ~30 second chunks internally
                // For long audio, we chunk it ourselves for progress updates
                if (durationSec <= 30) {
                    // Short audio — single pass
                    onProgress(0.5f, "Transcribing...")
                    progress = 0.5f
                    RunAnywhere.transcribe(pcmBytes)
                } else {
                    // Long audio — chunk into ~25-second segments for progress
                    transcribeLongAudio(pcmBytes, durationSec, onProgress)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Whisper transcription failed: ${e.message}", e)
                return@withContext "⚠️ Transcription failed: ${e.message}"
            }

            // ── Step 4: Cleanup — unload STT if we loaded it ──
            if (!sttWasLoaded) {
                try {
                    Log.d(TAG, "Unloading Whisper STT model to free memory")
                    RunAnywhere.unloadSTTModel()
                    System.gc()
                } catch (e: Exception) {
                    Log.w(TAG, "STT unload failed: ${e.message}")
                }
            }

            progress = 1f
            onProgress(1f, transcriptionResult)

            val result = transcriptionResult.trim()
            Log.d(TAG, "Transcription complete: ${result.length} chars")
            return@withContext result

        } catch (e: Exception) {
            Log.e(TAG, "Transcription pipeline failed: ${e.message}", e)
            return@withContext "⚠️ Transcription failed: ${e.message}"
        } finally {
            isTranscribing = false
            progress = 0f
        }
    }

    /**
     * Transcribe long audio (>30s) in ~25-second chunks with progress updates.
     */
    private suspend fun transcribeLongAudio(
        pcmBytes: ByteArray,
        durationSec: Float,
        onProgress: (Float, String) -> Unit
    ): String {
        val chunkSamples = 25 * TARGET_SAMPLE_RATE  // 25 seconds worth
        val chunkBytes = chunkSamples * 2  // 16-bit = 2 bytes per sample
        val totalChunks = (pcmBytes.size + chunkBytes - 1) / chunkBytes
        val fullText = StringBuilder()

        for (i in 0 until totalChunks) {
            val start = i * chunkBytes
            val end = minOf(start + chunkBytes, pcmBytes.size)
            val chunk = pcmBytes.copyOfRange(start, end)

            val chunkProgress = 0.4f + (0.55f * (i + 1).toFloat() / totalChunks)
            progress = chunkProgress
            onProgress(chunkProgress, fullText.toString())

            val chunkText = RunAnywhere.transcribe(chunk)

            if (chunkText.isNotBlank()) {
                if (fullText.isNotEmpty()) fullText.append(" ")
                fullText.append(chunkText.trim())
            }

            Log.d(TAG, "Chunk ${i+1}/$totalChunks: \"${chunkText.take(40)}\" (total: ${fullText.length} chars)")
        }

        return fullText.toString()
    }

    /**
     * Decode audio file (MP3/WAV/M4A/OGG/FLAC/3GP) to raw 16kHz mono PCM16 bytes
     * using Android's MediaExtractor + MediaCodec (hardware-accelerated decoding).
     *
     * @return Raw PCM16 byte array at 16kHz mono, or null on failure
     */
    private fun decodeAudioToPCM(uri: Uri): ByteArray? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            // Find the audio track
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                Log.e(TAG, "No audio track found in file")
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val sourceSampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceChannels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            Log.d(TAG, "Audio: $mime, ${sourceSampleRate}Hz, ${sourceChannels}ch")

            // Check if it's already raw PCM WAV at 16kHz mono
            if (mime == "audio/raw" && sourceSampleRate == TARGET_SAMPLE_RATE && sourceChannels == 1) {
                // Direct read
                return readRawPCM(extractor)
            }

            // Create decoder for the audio format
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val pcmOutput = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val timeoutUs = 10_000L

            while (true) {
                // Feed input
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Read output
                val outputIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                if (outputIndex >= 0) {
                    if (info.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex) ?: continue
                        val pcmChunk = ByteArray(info.size)
                        outputBuffer.get(pcmChunk)
                        pcmOutput.write(pcmChunk)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)

                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    break
                }
            }

            val decodedPCM = pcmOutput.toByteArray()
            Log.d(TAG, "Decoded ${decodedPCM.size} bytes of raw PCM")

            // Resample and convert to mono 16kHz if needed
            return resampleToTarget(decodedPCM, sourceSampleRate, sourceChannels)

        } catch (e: Exception) {
            Log.e(TAG, "Audio decode failed: ${e.message}", e)
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    /** Read raw PCM data directly from extractor (for WAV files). */
    private fun readRawPCM(extractor: MediaExtractor): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(65536)
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val bytes = ByteArray(size)
            buffer.flip()
            buffer.get(bytes)
            output.write(bytes)
            extractor.advance()
        }
        return output.toByteArray()
    }

    /**
     * Resample decoded PCM to 16kHz mono (Whisper's expected format).
     * Input: PCM16 at [sourceSampleRate] Hz with [sourceChannels] channels.
     * Output: PCM16 at 16kHz mono.
     */
    private fun resampleToTarget(
        pcm: ByteArray,
        sourceSampleRate: Int,
        sourceChannels: Int
    ): ByteArray {
        // Convert bytes to short samples
        val shortBuffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val totalSamples = shortBuffer.remaining()
        val samples = ShortArray(totalSamples)
        shortBuffer.get(samples)

        // Step 1: Convert to mono (average channels)
        val monoSamples = if (sourceChannels > 1) {
            val monoLen = totalSamples / sourceChannels
            ShortArray(monoLen) { i ->
                var sum = 0L
                for (ch in 0 until sourceChannels) {
                    sum += samples[i * sourceChannels + ch]
                }
                (sum / sourceChannels).toInt().toShort()
            }
        } else {
            samples
        }

        // Step 2: Resample to 16kHz (linear interpolation)
        if (sourceSampleRate == TARGET_SAMPLE_RATE) {
            // Already at target rate
            val output = ByteArray(monoSamples.size * 2)
            ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(monoSamples)
            return output
        }

        val ratio = sourceSampleRate.toDouble() / TARGET_SAMPLE_RATE
        val outputLen = (monoSamples.size / ratio).toInt()
        val resampled = ShortArray(outputLen)

        for (i in 0 until outputLen) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()
            val frac = srcPos - srcIndex

            val s0 = monoSamples[srcIndex.coerceAtMost(monoSamples.size - 1)]
            val s1 = monoSamples[(srcIndex + 1).coerceAtMost(monoSamples.size - 1)]
            resampled[i] = (s0 + frac * (s1 - s0)).toInt().toShort()
        }

        val output = ByteArray(resampled.size * 2)
        ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(resampled)

        Log.d(TAG, "Resampled: ${sourceSampleRate}Hz ${sourceChannels}ch → ${TARGET_SAMPLE_RATE}Hz mono (${output.size} bytes)")
        return output
    }
}
