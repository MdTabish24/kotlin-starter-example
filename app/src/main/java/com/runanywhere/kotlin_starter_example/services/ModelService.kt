package com.runanywhere.kotlin_starter_example.services

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runanywhere.kotlin_starter_example.data.AppPreferences
import com.runanywhere.sdk.core.types.InferenceFramework
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.extensions.Models.ModelCategory
import com.runanywhere.sdk.public.extensions.registerModel
import com.runanywhere.sdk.public.extensions.downloadModel
import com.runanywhere.sdk.public.extensions.loadLLMModel
import com.runanywhere.sdk.public.extensions.loadSTTModel
import com.runanywhere.sdk.public.extensions.loadTTSVoice
import com.runanywhere.sdk.public.extensions.unloadLLMModel
import com.runanywhere.sdk.public.extensions.unloadSTTModel
import com.runanywhere.sdk.public.extensions.unloadTTSVoice
import com.runanywhere.sdk.public.extensions.isLLMModelLoaded
import com.runanywhere.sdk.public.extensions.isSTTModelLoaded
import com.runanywhere.sdk.public.extensions.isTTSVoiceLoaded
import com.runanywhere.sdk.public.extensions.availableModels
import com.runanywhere.kotlin_starter_example.utils.LLMPerformanceBooster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Available LLM models that can be used with LlamaCPP backend.
 * All are GGUF format from HuggingFace.
 */
data class LLMModelOption(
    val id: String,
    val name: String,
    val url: String,
    val size: String,
    val description: String,
    val memoryRequirement: Long,
    val recommended: Boolean = false
)

class ModelService : ViewModel() {

    companion object {
        private const val TAG = "ModelService"

        const val STT_MODEL_ID = "sherpa-onnx-whisper-tiny.en"
        const val TTS_MODEL_ID = "vits-piper-en_US-lessac-medium"

        /** All available LLM models the user can choose from */
        val LLM_OPTIONS = listOf(
            LLMModelOption(
                id = "smollm2-360m-instruct-q8_0",
                name = "SmolLM2 360M",
                url = "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/smollm2-360m-instruct-q8_0.gguf",
                size = "~400 MB",
                description = "Ultra-lightweight, fastest. Basic answers.",
                memoryRequirement = 400_000_000
            ),
            LLMModelOption(
                id = "smollm2-1.7b-instruct-q4_k_m",
                name = "SmolLM2 1.7B",
                url = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
                size = "~1.0 GB",
                description = "Good balance of speed and quality.",
                memoryRequirement = 1_100_000_000,
                recommended = true
            ),
            LLMModelOption(
                id = "Llama-3.2-1B-Instruct-Q4_K_M",
                name = "Llama 3.2 1B",
                url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                size = "~750 MB",
                description = "Meta's best small model. Great quality.",
                memoryRequirement = 800_000_000,
                recommended = true
            ),
            LLMModelOption(
                id = "Llama-3.2-3B-Instruct-Q4_K_M",
                name = "Llama 3.2 3B",
                url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                size = "~2.0 GB",
                description = "Best quality. Needs 4GB+ RAM.",
                memoryRequirement = 2_200_000_000
            ),
            LLMModelOption(
                id = "Qwen2.5-1.5B-Instruct-Q4_K_M",
                name = "Qwen 2.5 1.5B",
                url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                size = "~1.0 GB",
                description = "Strong reasoning, multilingual.",
                memoryRequirement = 1_100_000_000
            ),
            LLMModelOption(
                id = "tinyllama-1.1b-chat-v1.0.Q4_K_M",
                name = "TinyLlama 1.1B",
                url = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
                size = "~670 MB",
                description = "Lightweight, fast responses.",
                memoryRequirement = 700_000_000
            )
        )

        /** Get model option by ID */
        fun getLLMOption(id: String): LLMModelOption? = LLM_OPTIONS.find { it.id == id }

        /** Register all available models with the SDK */
        fun registerDefaultModels() {
            Log.d(TAG, "Registering all models...")

            // Register ALL LLM options so any can be downloaded/loaded
            LLM_OPTIONS.forEach { model ->
                RunAnywhere.registerModel(
                    id = model.id,
                    name = model.name,
                    url = model.url,
                    framework = InferenceFramework.LLAMA_CPP,
                    modality = ModelCategory.LANGUAGE,
                    memoryRequirement = model.memoryRequirement
                )
                Log.d(TAG, "Registered LLM: ${model.id}")
            }

            RunAnywhere.registerModel(
                id = STT_MODEL_ID,
                name = "Sherpa Whisper Tiny (ONNX)",
                url = "https://github.com/RunanywhereAI/sherpa-onnx/releases/download/runanywhere-models-v1/sherpa-onnx-whisper-tiny.en.tar.gz",
                framework = InferenceFramework.ONNX,
                modality = ModelCategory.SPEECH_RECOGNITION
            )
            Log.d(TAG, "Registered STT: $STT_MODEL_ID")

            RunAnywhere.registerModel(
                id = TTS_MODEL_ID,
                name = "Piper TTS (US English - Medium)",
                url = "https://github.com/RunanywhereAI/sherpa-onnx/releases/download/runanywhere-models-v1/vits-piper-en_US-lessac-medium.tar.gz",
                framework = InferenceFramework.ONNX,
                modality = ModelCategory.SPEECH_SYNTHESIS
            )
            Log.d(TAG, "Registered TTS: $TTS_MODEL_ID")
        }
    }

    // ── Download/Load States ──
    private var appContext: Context? = null

    var isLLMDownloading by mutableStateOf(false)
        private set
    var isSTTDownloading by mutableStateOf(false)
        private set
    var isTTSDownloading by mutableStateOf(false)
        private set

    var llmDownloadProgress by mutableStateOf(0f)
        private set
    var sttDownloadProgress by mutableStateOf(0f)
        private set
    var ttsDownloadProgress by mutableStateOf(0f)
        private set

    var isLLMLoading by mutableStateOf(false)
        private set
    var isSTTLoading by mutableStateOf(false)
        private set
    var isTTSLoading by mutableStateOf(false)
        private set

    var isLLMLoaded by mutableStateOf(false)
        private set
    var isSTTLoaded by mutableStateOf(false)
        private set
    var isTTSLoaded by mutableStateOf(false)
        private set

    var isVoiceAgentReady by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set
    var detailedError by mutableStateOf<String?>(null)
        private set

    /** Currently selected/active LLM model ID */
    var activeLLMModelId by mutableStateOf("smollm2-360m-instruct-q8_0")
        private set

    init {
        viewModelScope.launch {
            refreshModelState()
        }
    }

    /** Initialize with saved preference */
    fun initWithPreferences(context: Context) {
        appContext = context.applicationContext
        val prefs = AppPreferences(context)
        activeLLMModelId = prefs.selectedLLMModel

        // Log device info for debugging
        val deviceRAM = LLMPerformanceBooster.getDeviceRAM(context)
        Log.d(TAG, "Active LLM: $activeLLMModelId (deviceRAM: ${deviceRAM}MB)")
    }

    private suspend fun refreshModelState() {
        isLLMLoaded = RunAnywhere.isLLMModelLoaded()
        isSTTLoaded = RunAnywhere.isSTTModelLoaded()
        isTTSLoaded = RunAnywhere.isTTSVoiceLoaded()
        isVoiceAgentReady = isLLMLoaded && isSTTLoaded && isTTSLoaded
    }

    private suspend fun isModelDownloaded(modelId: String): Boolean {
        val models = RunAnywhere.availableModels()
        val model = models.find { it.id == modelId }
        val downloaded = model?.localPath != null
        Log.d(TAG, "Model $modelId downloaded: $downloaded")
        return downloaded
    }

    private suspend fun cleanPartialDownload(modelId: String) {
        try {
            val models = RunAnywhere.availableModels()
            val model = models.find { it.id == modelId }
            model?.localPath?.let { path ->
                val modelFile = java.io.File(path)
                if (modelFile.exists()) {
                    Log.d(TAG, "Cleaning: $path")
                    if (modelFile.isDirectory) modelFile.deleteRecursively() else modelFile.delete()
                }
                modelFile.parentFile?.let { parent ->
                    if (parent.exists()) {
                        parent.listFiles()?.forEach { file ->
                            if (file.name.contains(modelId) &&
                                (file.name.endsWith(".tmp") || file.name.endsWith(".download"))) {
                                file.delete()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Clean failed for $modelId: ${e.message}")
        }
    }

    // ═══════════════════════════════════════
    // LLM - supports multiple model options
    // ═══════════════════════════════════════

    fun downloadAndLoadLLM(modelId: String? = null) {
        val targetId = modelId ?: activeLLMModelId
        if (isLLMDownloading || isLLMLoading) return

        viewModelScope.launch {
            try {
                errorMessage = null
                Log.d(TAG, "Loading LLM: $targetId")

                // Unload current model if a different one is loaded
                if (isLLMLoaded && targetId != activeLLMModelId) {
                    Log.d(TAG, "Unloading current LLM to switch models...")
                    try { withContext(Dispatchers.IO) { RunAnywhere.unloadLLMModel() } } catch (_: Exception) {}
                    isLLMLoaded = false
                    // CRITICAL: Give native memory time to fully free before loading new model.
                    // Without this, the OS hasn't reclaimed the old model's ~700MB+ of native
                    // allocations, and loading a new model on top causes SIGABRT.
                    delay(800)
                    LLMPerformanceBooster.forceGC()
                    delay(500)
                    Log.d(TAG, "After unload — native heap: ${LLMPerformanceBooster.getNativeHeapUsageMB()}MB")
                }

                if (!isModelDownloaded(targetId)) {
                    isLLMDownloading = true
                    llmDownloadProgress = 0f

                    RunAnywhere.downloadModel(targetId)
                        .catch { e -> errorMessage = "LLM download failed: ${e.message}" }
                        .collect { llmDownloadProgress = it.progress }

                    isLLMDownloading = false
                }

                isLLMLoading = true
                try {
                    withContext(Dispatchers.IO) { RunAnywhere.loadLLMModel(targetId) }
                } catch (loadErr: Exception) {
                    Log.w(TAG, "LLM load failed (${loadErr.message}), re-downloading...")
                    isLLMLoading = false
                    cleanPartialDownload(targetId)

                    isLLMDownloading = true
                    llmDownloadProgress = 0f
                    RunAnywhere.downloadModel(targetId)
                        .catch { e -> throw Exception("LLM re-download failed: ${e.message}") }
                        .collect { llmDownloadProgress = it.progress }
                    isLLMDownloading = false

                    isLLMLoading = true
                    withContext(Dispatchers.IO) { RunAnywhere.loadLLMModel(targetId) }
                }

                isLLMLoaded = true
                isLLMLoading = false
                activeLLMModelId = targetId

                refreshModelState()
                Log.d(TAG, "LLM loaded: $targetId")
            } catch (e: Exception) {
                errorMessage = "LLM load failed: ${e.message}"
                isLLMDownloading = false
                isLLMLoading = false
            }
        }
    }

    /** Switch to a different LLM model */
    fun switchLLMModel(newModelId: String, context: Context) {
        if (newModelId == activeLLMModelId && isLLMLoaded) return

        // Save preference
        val prefs = AppPreferences(context)
        prefs.selectedLLMModel = newModelId
        activeLLMModelId = newModelId

        // Download and load the new model
        downloadAndLoadLLM(newModelId)
    }

    // ═══════════════════════════════════════
    // STT
    // ═══════════════════════════════════════

    fun downloadAndLoadSTT() {
        if (isSTTDownloading || isSTTLoading) return

        viewModelScope.launch {
            try {
                errorMessage = null
                detailedError = null

                if (!isModelDownloaded(STT_MODEL_ID)) {
                    cleanPartialDownload(STT_MODEL_ID)
                    isSTTDownloading = true
                    sttDownloadProgress = 0f

                    var downloadError: Throwable? = null
                    RunAnywhere.downloadModel(STT_MODEL_ID)
                        .catch { e -> downloadError = e }
                        .collect { sttDownloadProgress = it.progress }

                    isSTTDownloading = false

                    if (downloadError != null) {
                        cleanPartialDownload(STT_MODEL_ID)
                        isSTTDownloading = true
                        sttDownloadProgress = 0f
                        RunAnywhere.downloadModel(STT_MODEL_ID)
                            .catch { e -> throw e }
                            .collect { sttDownloadProgress = it.progress }
                        isSTTDownloading = false
                    }
                }

                isSTTLoading = true
                try {
                    withContext(Dispatchers.IO) { RunAnywhere.loadSTTModel(STT_MODEL_ID) }
                } catch (loadErr: Exception) {
                    Log.w(TAG, "STT load failed (${loadErr.message}), re-downloading...")
                    isSTTLoading = false
                    cleanPartialDownload(STT_MODEL_ID)

                    isSTTDownloading = true
                    sttDownloadProgress = 0f
                    RunAnywhere.downloadModel(STT_MODEL_ID)
                        .catch { e -> throw Exception("STT re-download failed: ${e.message}") }
                        .collect { sttDownloadProgress = it.progress }
                    isSTTDownloading = false

                    isSTTLoading = true
                    withContext(Dispatchers.IO) { RunAnywhere.loadSTTModel(STT_MODEL_ID) }
                }
                isSTTLoaded = true
                isSTTLoading = false
                refreshModelState()
            } catch (e: Exception) {
                errorMessage = "STT load failed: ${e.message}"
                detailedError = e.stackTraceToString()
                isSTTDownloading = false
                isSTTLoading = false
            }
        }
    }

    // ═══════════════════════════════════════
    // TTS
    // ═══════════════════════════════════════

    fun downloadAndLoadTTS() {
        if (isTTSDownloading || isTTSLoading) return

        viewModelScope.launch {
            try {
                errorMessage = null

                if (!isModelDownloaded(TTS_MODEL_ID)) {
                    cleanPartialDownload(TTS_MODEL_ID)
                    isTTSDownloading = true
                    ttsDownloadProgress = 0f

                    RunAnywhere.downloadModel(TTS_MODEL_ID)
                        .catch { e -> errorMessage = "TTS download failed: ${e.message}" }
                        .collect { ttsDownloadProgress = it.progress }

                    isTTSDownloading = false
                }

                isTTSLoading = true
                try {
                    withContext(Dispatchers.IO) { RunAnywhere.loadTTSVoice(TTS_MODEL_ID) }
                } catch (loadErr: Exception) {
                    // Load failed (error -422 = corrupted files). Clean and re-download.
                    Log.w(TAG, "TTS load failed (${loadErr.message}), re-downloading...")
                    isTTSLoading = false
                    cleanPartialDownload(TTS_MODEL_ID)

                    isTTSDownloading = true
                    ttsDownloadProgress = 0f
                    RunAnywhere.downloadModel(TTS_MODEL_ID)
                        .catch { e -> throw Exception("TTS re-download failed: ${e.message}") }
                        .collect { ttsDownloadProgress = it.progress }
                    isTTSDownloading = false

                    isTTSLoading = true
                    withContext(Dispatchers.IO) { RunAnywhere.loadTTSVoice(TTS_MODEL_ID) }
                }
                isTTSLoaded = true
                isTTSLoading = false
                refreshModelState()
            } catch (e: Exception) {
                errorMessage = "TTS load failed: ${e.message}"
                isTTSDownloading = false
                isTTSLoading = false
            }
        }
    }

    // ═══════════════════════════════════════
    // Bulk operations
    // ═══════════════════════════════════════

    fun downloadAndLoadAllModels() {
        viewModelScope.launch {
            // Sequential downloads — smallest first for fast feedback.
            // Parallel downloads split bandwidth 3-way making ALL of them crawl.
            // TTS (67 MB) → STT (152 MB) → LLM (386 MB)

            if (!isTTSLoaded && !isTTSDownloading && !isTTSLoading) {
                downloadAndLoadTTS()
                delay(150) // let coroutine set flags
                while (isTTSDownloading || isTTSLoading) delay(300)
            }

            if (!isSTTLoaded && !isSTTDownloading && !isSTTLoading) {
                downloadAndLoadSTT()
                delay(150)
                while (isSTTDownloading || isSTTLoading) delay(300)
            }

            if (!isLLMLoaded && !isLLMDownloading && !isLLMLoading) {
                downloadAndLoadLLM()
                // LLM is last — no need to wait here, it runs on its own
            }
        }
    }

    fun unloadAllModels() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RunAnywhere.unloadLLMModel()
                    RunAnywhere.unloadSTTModel()
                    RunAnywhere.unloadTTSVoice()
                }
                refreshModelState()
            } catch (e: Exception) {
                errorMessage = "Failed to unload: ${e.message}"
            }
        }
    }

    // reloadWithReducedContext REMOVED — telemetry confirmed it never worked
    // (context_length stayed at 8192) AND it caused SIGABRT during model switch
    // by doing 4 native load/unload transitions that corrupted bridge state.
    // Protection strategy now relies on:
    //   1. ensureSafeModelForGeneration() auto-switches to SmolLM2-360M on low RAM
    //   2. getMaxSafePromptLength() caps prompt to fit within context window
    //   3. freeMemoryForLLM() unloads STT/TTS before generation
    //   4. Proper delay+GC during model switch in downloadAndLoadLLM()

    /**
     * Unload STT and TTS to free native memory for LLM generation.
     * Call this before heavy LLM inference when system memory is low.
     * Models can be reloaded later via downloadAndLoadSTT/TTS.
     */
    suspend fun freeMemoryForLLM() {
        try {
            if (isTTSLoaded) {
                Log.d(TAG, "Unloading TTS to free memory for LLM generation")
                withContext(Dispatchers.IO) { RunAnywhere.unloadTTSVoice() }
                isTTSLoaded = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS unload failed: ${e.message}")
        }
        try {
            if (isSTTLoaded) {
                Log.d(TAG, "Unloading STT to free memory for LLM generation")
                withContext(Dispatchers.IO) { RunAnywhere.unloadSTTModel() }
                isSTTLoaded = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "STT unload failed: ${e.message}")
        }
        refreshModelState()
    }

    /**
     * Runtime safety check: if the loaded LLM is too large for generation,
     * unload it and load SmolLM2-360M.
     *
     * The SIGABRT crash: SDK hardcodes context_length=8192, generate() allocates
     * KV cache (~224MB) + scratch (~100MB). Qwen2.5-1.5B at 732MB + 324MB = 1056MB
     * exceeds per-process native heap limit → malloc fails → abort() → SIGABRT.
     * SmolLM2-360M at ~400MB + 300MB = ~700MB fits safely.
     */
    suspend fun ensureSafeModelForGeneration(context: Context) {
        val nativeHeapMB = LLMPerformanceBooster.getNativeHeapUsageMB()
        val deviceRAM = LLMPerformanceBooster.getDeviceRAM(context)
        val SAFE_MODEL = "smollm2-360m-instruct-q8_0"

        if (deviceRAM <= 6144 && nativeHeapMB > 900 && activeLLMModelId != SAFE_MODEL && isLLMLoaded) {
            Log.w(TAG, "SAFETY: native heap ${nativeHeapMB}MB too high on ${deviceRAM}MB device")
            Log.w(TAG, "Switching $activeLLMModelId -> $SAFE_MODEL to prevent SIGABRT")

            try {
                withContext(Dispatchers.IO) { RunAnywhere.unloadLLMModel() }
                isLLMLoaded = false
                Log.d(TAG, "Unloaded large model. Native heap: ${LLMPerformanceBooster.getNativeHeapUsageMB()}MB")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unload LLM: ${e.message}")
                return
            }

            delay(500)
            LLMPerformanceBooster.forceGC()
            delay(300)

            try {
                isLLMLoading = true
                withContext(Dispatchers.IO) { RunAnywhere.loadLLMModel(SAFE_MODEL) }
                isLLMLoaded = true
                isLLMLoading = false
                activeLLMModelId = SAFE_MODEL
                Log.d(TAG, "Safe model loaded. Native heap: ${LLMPerformanceBooster.getNativeHeapUsageMB()}MB")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load safe model: ${e.message}")
                isLLMLoading = false
            }
        }
    }

    fun clearError() {
        errorMessage = null
        detailedError = null
    }

    fun forceRedownloadSTT() {
        viewModelScope.launch {
            try {
                if (isSTTLoaded) {
                    try { withContext(Dispatchers.IO) { RunAnywhere.unloadSTTModel() } } catch (_: Exception) {}
                    isSTTLoaded = false
                }
                deleteSTTModel()
                downloadAndLoadSTT()
            } catch (e: Exception) {
                errorMessage = "Force re-download failed: ${e.message}"
            }
        }
    }

    fun deleteSTTModel() {
        viewModelScope.launch {
            try {
                val models = RunAnywhere.availableModels()
                val sttModel = models.find { it.id == STT_MODEL_ID }
                sttModel?.localPath?.let { path ->
                    val file = java.io.File(path)
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
                isSTTLoaded = false
                refreshModelState()
            } catch (e: Exception) {
                errorMessage = "Delete failed: ${e.message}"
            }
        }
    }

    /** Get the display name of the currently active LLM */
    fun getActiveLLMName(): String {
        return getLLMOption(activeLLMModelId)?.name ?: activeLLMModelId
    }
}
