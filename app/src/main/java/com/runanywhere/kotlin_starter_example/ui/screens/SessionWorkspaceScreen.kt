package com.runanywhere.kotlin_starter_example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.runanywhere.kotlin_starter_example.data.*
import com.runanywhere.kotlin_starter_example.services.ModelService
import com.runanywhere.kotlin_starter_example.ui.components.ModelLoaderWidget
import com.runanywhere.kotlin_starter_example.ui.theme.*
import com.runanywhere.kotlin_starter_example.utils.DocumentReader
import com.runanywhere.kotlin_starter_example.utils.LLMPerformanceBooster
import com.runanywhere.kotlin_starter_example.utils.SmartDocumentSearch
import com.runanywhere.kotlin_starter_example.utils.SimpleAudioRecorder
import com.runanywhere.kotlin_starter_example.utils.playWavAudioData
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.extensions.chat
import com.runanywhere.sdk.public.extensions.generate
import com.runanywhere.sdk.public.extensions.generateStreamWithMetrics
import com.runanywhere.sdk.public.extensions.transcribe
import com.runanywhere.sdk.public.extensions.synthesize
import com.runanywhere.sdk.public.extensions.TTS.TTSOptions
import com.runanywhere.sdk.public.extensions.LLM.LLMGenerationOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Voice session state enum ──
enum class VoiceSessionState {
    IDLE,           // Not active
    LISTENING,      // Recording user's voice
    TRANSCRIBING,   // Converting speech to text
    THINKING,       // LLM generating response
    SPEAKING        // TTS playing the response
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionWorkspaceScreen(
    sessionId: String?,
    onNavigateBack: () -> Unit,
    modelService: ModelService,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SessionRepository(context.applicationContext) }
    val prefs = remember { AppPreferences(context.applicationContext) }

    // ── Session State ──
    var session by remember {
        mutableStateOf(
            if (sessionId != null && sessionId != "new") {
                repository.load(sessionId) ?: StudySession()
            } else {
                StudySession()
            }
        )
    }

    // Chat state
    var messages by remember { mutableStateOf(session.messages.toList()) }
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var streamingResponse by remember { mutableStateOf("") }

    // Notes state
    var notes by remember { mutableStateOf(session.notes) }
    var smartNotesEnabled by remember { mutableStateOf(session.smartNotesEnabled) }
    var diagramsEnabled by remember { mutableStateOf(session.diagramsEnabled) }

    // Document state
    var documentName by remember { mutableStateOf(session.documentName) }
    var documentContent by remember { mutableStateOf(session.documentContent) }

    // Voice session state - the new real-time voice pipeline
    var voiceState by remember { mutableStateOf(VoiceSessionState.IDLE) }
    var isSpeaking by remember { mutableStateOf(false) }
    val audioRecorder = remember { SimpleAudioRecorder() }

    // UI state - only 2 tabs now (Chat, Notes) - Document tab removed
    var selectedTab by remember { mutableIntStateOf(0) }
    var showExplainDialog by remember { mutableStateOf(false) }
    var selectedParagraph by remember { mutableStateOf("") }
    var explanation by remember { mutableStateOf("") }
    var isExplaining by remember { mutableStateOf(false) }
    var saveIndicator by remember { mutableStateOf("") }

    // (Document card removed — clean chat-only interface)

    val listState = rememberLazyListState()

    // Audio permission
    var hasAudioPermission by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        hasAudioPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasAudioPermission = it }

    // File picker
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                // Show loading message
                val loadingMsg = SessionMessage("📄 Importing & indexing document...", isUser = false)
                messages = messages + loadingMsg

                val result = DocumentReader.readDocument(context, it)

                // Remove loading message
                messages = messages.filter { msg -> msg !== loadingMsg }

                if (result != null) {
                    val (name, content) = result
                    documentName = name
                    documentContent = content
                    session = session.copy(
                        title = name.substringBeforeLast('.'),
                        documentName = name,
                        documentContent = content
                    )
                    // 🔍 Index document for smart search (RAG)
                    val numChunks = SmartDocumentSearch.indexDocument(content)

                    // Simple success message — no card, no summary button
                    val ext = name.substringAfterLast('.', "").uppercase()
                    val successMsg = SessionMessage(
                        "✅ \"$name\" loaded ($ext, ${content.length} chars, $numChunks sections indexed).\n" +
                        "Ask me anything about this document — I'll answer only from its content.",
                        isUser = false
                    )
                    messages = messages + successMsg
                } else {
                    val errorMsg = SessionMessage(
                        "❌ Could not extract text from the file. Make sure it's a supported format (PDF, DOCX, PPTX, PNG, JPG, TXT).",
                        isUser = false
                    )
                    messages = messages + errorMsg
                }
            }
        }
    }

    // ── Auto-save every 5 seconds ──
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            session = session.copy(
                messages = messages.toMutableList(),
                notes = notes,
                documentName = documentName,
                documentContent = documentContent,
                smartNotesEnabled = smartNotesEnabled,
                diagramsEnabled = diagramsEnabled,
                updatedAt = System.currentTimeMillis()
            )
            withContext(Dispatchers.IO) { repository.save(session) }
            saveIndicator = "Saved"
            delay(1500)
            saveIndicator = ""
        }
    }

    // ── Helper: Create optimized LLM options to maximize speed ──
    fun getOptions(maxTok: Int? = null, temp: Float = 0.65f): LLMGenerationOptions {
        val adaptiveMaxTokens = maxTok ?: LLMPerformanceBooster.getRecommendedMaxTokens(context)
        return LLMGenerationOptions(
            maxTokens = adaptiveMaxTokens,
            temperature = temp,
            topP = 0.9f
        )
    }

    // ── Helper: Free memory before heavy LLM tasks ──
    suspend fun boostForLLM() {
        LLMPerformanceBooster.boostForInference()

        // Log native heap usage (per-process) — this is the REAL constraint.
        // System-wide available RAM can look fine (e.g. 1632MB) while the process's
        // native heap is exhausted. The SIGABRT crash happens because:
        //   LLM model (~1GB) + KV cache (~224MB) + STT (~75MB) + TTS (~63MB)
        //   + inference scratch buffers (~200-400MB) > process native heap limit.
        // "Waiting for blocking GC NativeAlloc" in the logs confirms this.
        val nativeHeapMB = LLMPerformanceBooster.getNativeHeapUsageMB()
        val deviceRAM = LLMPerformanceBooster.getDeviceRAM(context)
        android.util.Log.d("LLMBoost", "Device RAM: ${deviceRAM}MB, Native heap: ${nativeHeapMB}MB")

        // On devices with ≤8GB RAM, ALWAYS unload STT/TTS before LLM generation.
        // The per-process native memory can't handle all 3 models + inference buffers.
        // STT/TTS will be reloaded after generation completes.
        if (deviceRAM <= 8192 && (modelService.isSTTLoaded || modelService.isTTSLoaded)) {
            android.util.Log.d("LLMBoost", "Unloading STT/TTS to free ~140MB native memory for LLM inference")
            modelService.freeMemoryForLLM()
            // CRITICAL: Give the OS time to reclaim freed native memory pages.
            // Without this delay, the memory may not be available yet when generate() starts.
            kotlinx.coroutines.delay(500)
            // Run GC again after unloading to clean up Java references to native objects
            LLMPerformanceBooster.forceGC()
            kotlinx.coroutines.delay(300)
            android.util.Log.d("LLMBoost", "After unload — native heap: ${LLMPerformanceBooster.getNativeHeapUsageMB()}MB")
        }

        // CRITICAL: Check if the LLM model itself is too large for generation.
        // On 6GB devices, Qwen2.5-1.5B at 732MB + ~300MB generation overhead → SIGABRT.
        // This auto-switches to SmolLM2-360M if needed.
        modelService.ensureSafeModelForGeneration(context)
    }

    fun hasEnoughMemoryForLLM(): Boolean {
        // If model is already loaded, it's already in RAM — inference overhead is minimal
        // No need to re-check memory; the SDK will handle allocation
        if (modelService.isLLMLoaded) return true
        val model = ModelService.getLLMOption(modelService.activeLLMModelId) ?: return true
        val modelSizeMB = (model.memoryRequirement / (1024L * 1024L)).coerceAtLeast(1L)
        return LLMPerformanceBooster.hasEnoughMemory(context, modelSizeMB)
    }

    // ── Functions ──

    fun sendMessage(text: String) {
        if (text.isBlank() || isGenerating || !modelService.isLLMLoaded) return
        val userMsg = SessionMessage(text, isUser = true)
        messages = messages + userMsg
        inputText = ""
        isGenerating = true
        streamingResponse = ""

        scope.launch {
            try {
                listState.animateScrollToItem(maxOf(0, messages.size - 1))
                boostForLLM()

                if (!hasEnoughMemoryForLLM()) {
                    messages = messages + SessionMessage(
                        "Not enough free memory for ${modelService.getActiveLLMName()}. Switch to SmolLM2 360M in Settings and try again.",
                        isUser = false
                    )
                    return@launch
                }

                // 🔍 Smart Search: find relevant sections instead of sending whole doc
                val docLimit = LLMPerformanceBooster.getRecommendedDocLimit(context)
                val contextPrompt = buildString {
                    if (documentContent != null && SmartDocumentSearch.isDocumentIndexed()) {
                        append("You are YouLearn, a study AI. Answer using ONLY the document below. Give a detailed, multi-paragraph answer.\n")
                        val relevantContext = SmartDocumentSearch.getRelevantContext(text, docLimit)
                        if (relevantContext != null) {
                            append("\nDocument:\n")
                            append(relevantContext)
                            append("\n\n")
                        }
                    } else if (documentContent != null) {
                        append("You are YouLearn, a study AI. Answer using ONLY the document below. Give a detailed, multi-paragraph answer.\n")
                        append("\nDocument:\n")
                        append(documentContent!!.take(docLimit))
                        append("\n\n")
                    } else {
                        append("You are YouLearn, a study AI. Give a detailed answer with examples.\n\n")
                    }
                    append("Question: $text\n\nAnswer:")
                }

                // Safety cap: keep prompt within context window to avoid native OOM.
                // Dynamic based on device RAM: 6GB→6500 chars, 8GB→13000 chars.
                val maxPromptLen = LLMPerformanceBooster.getMaxSafePromptLength(context)
                val safePrompt = if (contextPrompt.length > maxPromptLen) {
                    android.util.Log.w("SessionWorkspace", "Prompt too long (${contextPrompt.length}), truncating to $maxPromptLen chars")
                    contextPrompt.take(maxPromptLen) + "\n\nQ: $text\nA:"
                } else contextPrompt

                var fullResponse = try {
                    streamingResponse = "Thinking..."
                    // Real streaming: collect tokens as they generate
                    val streamResult = RunAnywhere.generateStreamWithMetrics(safePrompt, getOptions())
                    val sb = StringBuilder()
                    withContext(Dispatchers.IO) {
                        streamResult.stream.collect { token ->
                            sb.append(token)
                            // Update UI on main thread via streamingResponse (recompose)
                            withContext(Dispatchers.Main) {
                                streamingResponse = sb.toString()
                            }
                        }
                    }
                    sb.toString()
                } catch (e: Exception) {
                    android.util.Log.e("SessionWorkspace", "Chat failed: ${e.message}", e)
                    ""
                }

                if (fullResponse.isBlank()) fullResponse = "I couldn't generate a response. The model may need more memory \u2014 try a smaller model in Settings."

                val aiMsg = SessionMessage(fullResponse.trim(), isUser = false)
                messages = messages + aiMsg
                streamingResponse = ""

                // Smart notes: auto-add to notes
                if (smartNotesEnabled && fullResponse.isNotBlank()) {
                    val bullet = "• Q: ${text.take(60)}${if (text.length > 60) "…" else ""}\n  → ${fullResponse.take(200).trim()}${if (fullResponse.length > 200) "…" else ""}\n\n"
                    notes += bullet
                }

                listState.animateScrollToItem(maxOf(0, messages.size - 1))
            } catch (e: Exception) {
                messages = messages + SessionMessage("Error: ${e.message}", isUser = false)
            } finally {
                isGenerating = false
                streamingResponse = ""
                LLMPerformanceBooster.restoreAfterInference()
                // Reload STT/TTS in background after generation frees scratch buffers
                if (!modelService.isSTTLoaded) modelService.downloadAndLoadSTT()
                if (!modelService.isTTSLoaded) modelService.downloadAndLoadTTS()
            }
        }
    }

    fun speakText(text: String) {
        if (isSpeaking || !modelService.isTTSLoaded) return
        isSpeaking = true
        scope.launch {
            try {
                val output = withContext(Dispatchers.IO) {
                    RunAnywhere.synthesize(text.take(500), TTSOptions(
                        rate = prefs.ttsSpeed,
                        pitch = prefs.ttsPitch
                    ))
                }
                playWavAudioData(output.audioData)
            } catch (_: Exception) {
            } finally {
                isSpeaking = false
            }
        }
    }

    // ── Real-time Voice Pipeline: One-tap STT → LLM → TTS ──
    fun startVoiceSession() {
        if (!hasAudioPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (voiceState != VoiceSessionState.IDLE) return
        if (!modelService.isSTTLoaded || !modelService.isLLMLoaded) return

        voiceState = VoiceSessionState.LISTENING

        scope.launch {
            try {
                // Step 1: Start recording
                val started = withContext(Dispatchers.IO) { audioRecorder.startRecording() }
                if (!started) {
                    voiceState = VoiceSessionState.IDLE
                    return@launch
                }
            } catch (e: Exception) {
                voiceState = VoiceSessionState.IDLE
            }
        }
    }

    fun stopVoiceAndProcess() {
        if (voiceState != VoiceSessionState.LISTENING) return

        scope.launch {
            try {
                // Step 2: Stop recording & transcribe
                voiceState = VoiceSessionState.TRANSCRIBING

                val audioData = withContext(Dispatchers.IO) { audioRecorder.stopRecording() }

                if (audioData.isEmpty() || !modelService.isSTTLoaded) {
                    voiceState = VoiceSessionState.IDLE
                    return@launch
                }

                val transcribedText = withContext(Dispatchers.IO) {
                    RunAnywhere.transcribe(audioData)
                }

                if (transcribedText.isBlank()) {
                    voiceState = VoiceSessionState.IDLE
                    return@launch
                }

                // Add user message
                val userMsg = SessionMessage(transcribedText, isUser = true)
                messages = messages + userMsg
                listState.animateScrollToItem(maxOf(0, messages.size - 1))

                // Step 3: Generate LLM response
                voiceState = VoiceSessionState.THINKING
                isGenerating = true
                streamingResponse = ""

                val voiceDocLimit = LLMPerformanceBooster.getRecommendedDocLimit(context)
                val contextPrompt = buildString {
                    if (documentContent != null && SmartDocumentSearch.isDocumentIndexed()) {
                        append("You are YouLearn, a study AI. Answer using ONLY the document below. Give a detailed, multi-paragraph answer.\n")
                        val relevantContext = SmartDocumentSearch.getRelevantContext(transcribedText, voiceDocLimit)
                        if (relevantContext != null) {
                            append("\nDocument:\n")
                            append(relevantContext)
                            append("\n\n")
                        }
                    } else if (documentContent != null) {
                        append("You are YouLearn, a study AI. Answer using ONLY the document below. Give a detailed, multi-paragraph answer.\n")
                        append("\nDocument:\n")
                        append(documentContent!!.take(voiceDocLimit))
                        append("\n\n")
                    } else {
                        append("You are YouLearn, a study AI. Give a detailed answer with examples.\n\n")
                    }
                    append("Question: $transcribedText\n\nAnswer:")
                }

                boostForLLM()

                if (!hasEnoughMemoryForLLM()) {
                    messages = messages + SessionMessage(
                        "Not enough free memory for ${modelService.getActiveLLMName()}. Switch to SmolLM2 360M in Settings and try again.",
                        isUser = false
                    )
                    isGenerating = false
                    streamingResponse = ""
                    voiceState = VoiceSessionState.IDLE
                    return@launch
                }

                // Safety cap for voice pipeline (same dynamic limit as chat)
                val voiceMaxPrompt = LLMPerformanceBooster.getMaxSafePromptLength(context)
                val voiceSafePrompt = if (contextPrompt.length > voiceMaxPrompt) {
                    contextPrompt.take(voiceMaxPrompt) + "\n\nQ: $transcribedText\nA:"
                } else contextPrompt

                // ── Generate with real streaming ──
                val voiceStartTime = System.currentTimeMillis()

                var fullResponse = try {
                    streamingResponse = "Thinking..."
                    val streamResult = RunAnywhere.generateStreamWithMetrics(voiceSafePrompt, getOptions())
                    val sb = StringBuilder()
                    withContext(Dispatchers.IO) {
                        streamResult.stream.collect { token ->
                            sb.append(token)
                            withContext(Dispatchers.Main) {
                                streamingResponse = sb.toString()
                            }
                        }
                    }
                    sb.toString()
                } catch (e: Exception) {
                    android.util.Log.e("SessionWorkspace", "Voice chat failed: ${e.message}", e)
                    ""
                }

                if (fullResponse.isBlank()) fullResponse = "I couldn't generate a response. Try a smaller model."

                val aiMsg = SessionMessage(fullResponse.trim(), isUser = false)
                messages = messages + aiMsg
                streamingResponse = ""
                isGenerating = false

                // Smart notes
                if (smartNotesEnabled && fullResponse.isNotBlank()) {
                    val bullet = "• Q: ${transcribedText.take(60)}${if (transcribedText.length > 60) "…" else ""}\n  → ${fullResponse.take(200).trim()}${if (fullResponse.length > 200) "…" else ""}\n\n"
                    notes += bullet
                }

                listState.animateScrollToItem(maxOf(0, messages.size - 1))

                // Step 4: Speak the response with TTS
                // TTS may have been unloaded by boostForLLM() — try to reload it
                if (!modelService.isTTSLoaded) {
                    android.util.Log.d("VoicePipeline", "TTS was unloaded, reloading for voice response...")
                    modelService.downloadAndLoadTTS()
                    // Wait for TTS to reload (typically ~3s)
                    var waited = 0
                    while (!modelService.isTTSLoaded && waited < 5000) {
                        kotlinx.coroutines.delay(200)
                        waited += 200
                    }
                }
                if (modelService.isTTSLoaded) {
                    voiceState = VoiceSessionState.SPEAKING
                    isSpeaking = true
                    try {
                        val output = withContext(Dispatchers.IO) {
                            RunAnywhere.synthesize(fullResponse.trim().take(500), TTSOptions(
                                rate = prefs.ttsSpeed,
                                pitch = prefs.ttsPitch
                            ))
                        }
                        playWavAudioData(output.audioData)
                    } catch (_: Exception) {
                    } finally {
                        isSpeaking = false
                    }
                }
                // Reload STT for next voice session
                if (!modelService.isSTTLoaded) modelService.downloadAndLoadSTT()

                voiceState = VoiceSessionState.IDLE
            } catch (e: Exception) {
                isGenerating = false
                streamingResponse = ""
                voiceState = VoiceSessionState.IDLE
                messages = messages + SessionMessage("Voice error: ${e.message}", isUser = false)
            }
        }
    }

    fun explainParagraph(paragraph: String) {
        selectedParagraph = paragraph
        explanation = ""
        showExplainDialog = true
        isExplaining = true

        scope.launch {
            try {
                boostForLLM()
                if (!hasEnoughMemoryForLLM()) {
                    explanation = "Not enough free memory for ${modelService.getActiveLLMName()}. Switch to SmolLM2 360M in Settings."
                    return@launch
                }
                val prompt = "Explain this for a student in detail with examples.\n\n\"${paragraph.take(400)}\"\n\nExplanation:"
                val fullExplanation = try {
                    val result = withContext(Dispatchers.IO) {
                        RunAnywhere.generate(prompt, getOptions(maxTok = 384))
                    }
                    result.text
                } catch (e: Exception) {
                    android.util.Log.e("SessionWorkspace", "Explain chat failed: ${e.message}", e)
                    ""
                }
                explanation = fullExplanation
                if (fullExplanation.isBlank()) explanation = "Could not explain. Try a smaller model in Settings."
            } catch (e: Exception) {
                explanation = "Error: ${e.message}"
            } finally {
                isExplaining = false
                LLMPerformanceBooster.restoreAfterInference()
                // Reload STT/TTS after generation
                if (!modelService.isSTTLoaded) modelService.downloadAndLoadSTT()
                if (!modelService.isTTSLoaded) modelService.downloadAndLoadTTS()
            }
        }
    }

    fun generateSummary() {
        if (documentContent.isNullOrBlank() || isGenerating || !modelService.isLLMLoaded) return
        isGenerating = true
        streamingResponse = ""

        // Add a user message to chat showing what we're doing
        val userMsg = SessionMessage("📝 Summarize: ${documentName ?: "document"}", isUser = true)
        messages = messages + userMsg

        scope.launch {
            try {
                listState.animateScrollToItem(maxOf(0, messages.size - 1))
                boostForLLM()

                if (!hasEnoughMemoryForLLM()) {
                    messages = messages + SessionMessage(
                        "Not enough free memory for ${modelService.getActiveLLMName()}. Switch to SmolLM2 360M in Settings and try again.",
                        isUser = false
                    )
                    return@launch
                }

                // Adaptive document limit based on device RAM
                val docText = documentContent!!.take(LLMPerformanceBooster.getRecommendedDocLimit(context))
                val prompt = buildString {
                    append("Create detailed, comprehensive study notes from this document. Include key concepts, definitions, important details, and examples. Organize with clear headings and bullet points.\n\n")
                    append(docText)
                    append("\n\nDetailed Study Notes:")
                }

                // Safety cap: keep prompt within context window
                val safePrompt = prompt.take(LLMPerformanceBooster.getMaxSafePromptLength(context))

                // ── Generate with real streaming ──
                val sumStartTime = System.currentTimeMillis()

                var fullResponse = try {
                    streamingResponse = "Generating summary..."
                    val streamResult = RunAnywhere.generateStreamWithMetrics(safePrompt, getOptions())
                    val sb = StringBuilder()
                    withContext(Dispatchers.IO) {
                        streamResult.stream.collect { token ->
                            sb.append(token)
                            withContext(Dispatchers.Main) {
                                streamingResponse = sb.toString()
                                // Auto-scroll as tokens arrive
                                try { listState.scrollToItem(maxOf(0, messages.size)) } catch (_: Exception) {}
                            }
                        }
                    }
                    sb.toString()
                } catch (e: Exception) {
                    android.util.Log.e("SessionWorkspace", "Summary chat failed: ${e.message}", e)
                    ""
                }
                val sumGenTimeSec = (System.currentTimeMillis() - sumStartTime) / 1000.0
                notes = fullResponse

                if (fullResponse.isBlank()) fullResponse = "⚠️ Could not generate summary. The model may need more memory — try switching to a smaller model (SmolLM2 360M) in Settings."

                val trimmedSummary = fullResponse.trim()

                // Show stats briefly
                val sumTokens = (trimmedSummary.length / 4.0).toInt().coerceAtLeast(1)
                val sumTps = if (sumGenTimeSec > 0) String.format("%.1f", sumTokens / sumGenTimeSec) else "—"
                streamingResponse = "$trimmedSummary\n\n⚡ ~${sumTokens} tokens · ${String.format("%.1f", sumGenTimeSec)}s · $sumTps tok/s"
                delay(2000)

                val aiMsg = SessionMessage(trimmedSummary, isUser = false)
                messages = messages + aiMsg
                streamingResponse = ""

                listState.animateScrollToItem(maxOf(0, messages.size - 1))
            } catch (e: Exception) {
                val errorMsg = "⚠️ Summary failed: ${e.message}. Try a smaller model."
                messages = messages + SessionMessage(errorMsg, isUser = false)
                notes += "\n\n$errorMsg"
            } finally {
                isGenerating = false
                streamingResponse = ""
                LLMPerformanceBooster.restoreAfterInference()
                // Reload STT/TTS after generation
                if (!modelService.isSTTLoaded) modelService.downloadAndLoadSTT()
                if (!modelService.isTTSLoaded) modelService.downloadAndLoadTTS()
            }
        }
    }

    // ── UI ──

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            session.title.take(30),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (saveIndicator.isNotEmpty()) {
                            Text(
                                saveIndicator,
                                style = MaterialTheme.typography.bodySmall,
                                color = AccentGreen
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        // Save before leaving
                        scope.launch {
                            session = session.copy(
                                messages = messages.toMutableList(),
                                notes = notes,
                                documentName = documentName,
                                documentContent = documentContent,
                                updatedAt = System.currentTimeMillis()
                            )
                            withContext(Dispatchers.IO) { repository.save(session) }
                        }
                        onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Import document
                    IconButton(onClick = {
                        filePickerLauncher.launch(arrayOf(
                            "text/plain",
                            "text/*",
                            "application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/msword",
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                            "application/vnd.ms-powerpoint",
                            "image/png",
                            "image/jpeg",
                            "image/jpg",
                            "image/bmp",
                            "image/webp"
                        ))
                    }) {
                        Icon(Icons.Rounded.UploadFile, "Import", tint = AccentCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PrimaryDark)
            )
        },
        bottomBar = {
            // Input bar (visible in Chat tab)
            if (selectedTab == 0 && modelService.isLLMLoaded) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = SurfaceCard.copy(alpha = 0.95f),
                    tonalElevation = 4.dp
                ) {
                    Column {
                        // Voice session status bar
                        if (voiceState != VoiceSessionState.IDLE) {
                            VoiceStatusBar(voiceState)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // ── Voice Pipeline Button (the main mic) ──
                            VoicePipelineButton(
                                voiceState = voiceState,
                                isVoiceReady = modelService.isSTTLoaded && modelService.isLLMLoaded,
                                onTap = {
                                    if (voiceState == VoiceSessionState.IDLE) {
                                        startVoiceSession()
                                    } else if (voiceState == VoiceSessionState.LISTENING) {
                                        stopVoiceAndProcess()
                                    }
                                }
                            )

                            Spacer(Modifier.width(8.dp))

                            // Text input
                            TextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                modifier = Modifier.weight(1f),
                                placeholder = {
                                    Text(
                                        when (voiceState) {
                                            VoiceSessionState.LISTENING -> "Listening…"
                                            VoiceSessionState.TRANSCRIBING -> "Transcribing…"
                                            VoiceSessionState.THINKING -> "AI is thinking…"
                                            VoiceSessionState.SPEAKING -> "Playing response…"
                                            else -> "Ask anything…"
                                        }
                                    )
                                },
                                readOnly = isGenerating || voiceState != VoiceSessionState.IDLE,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = PrimaryMid,
                                    unfocusedContainerColor = PrimaryMid,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                shape = RoundedCornerShape(14.dp),
                                maxLines = 3
                            )

                            Spacer(Modifier.width(8.dp))

                            // Send button
                            FloatingActionButton(
                                onClick = { sendMessage(inputText) },
                                modifier = Modifier.size(48.dp),
                                containerColor = if (isGenerating || inputText.isBlank()) TextMuted else AccentCyan
                            ) {
                                if (isGenerating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.Send,
                                        "Send",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        containerColor = PrimaryDark
    ) { padding ->
        Column(modifier = modifier.fillMaxSize().padding(padding)) {

            // ── Model check ──
            if (!modelService.isLLMLoaded) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ModelLoaderWidget(
                        modelName = "SmolLM2 360M (required)",
                        isDownloading = modelService.isLLMDownloading,
                        isLoading = modelService.isLLMLoading,
                        isLoaded = modelService.isLLMLoaded,
                        downloadProgress = modelService.llmDownloadProgress,
                        onLoadClick = { modelService.downloadAndLoadLLM() }
                    )
                }
            }

            // ── Tab Row (2 tabs only: Chat, Notes) ──
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = PrimaryDark,
                contentColor = AccentCyan,
                divider = {
                    HorizontalDivider(color = TextMuted.copy(alpha = 0.15f))
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("💬 Chat") },
                    selectedContentColor = AccentCyan,
                    unselectedContentColor = TextMuted
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("📝 Notes") },
                    selectedContentColor = NoteAmber,
                    unselectedContentColor = TextMuted
                )
            }

            // ── Tab Content ──
            when (selectedTab) {
                0 -> ChatTabContentWithDocument(
                    messages = messages,
                    streamingResponse = streamingResponse,
                    isGenerating = isGenerating,
                    isSpeaking = isSpeaking,
                    isTTSLoaded = modelService.isTTSLoaded,
                    listState = listState,
                    onSpeak = { speakText(it) }
                )

                1 -> NotesTabContent(
                    notes = notes,
                    onNotesChanged = { notes = it },
                    smartNotesEnabled = smartNotesEnabled,
                    onSmartNotesToggle = { smartNotesEnabled = it },
                    diagramsEnabled = diagramsEnabled,
                    onDiagramsToggle = { diagramsEnabled = it },
                    hasDocument = documentContent != null,
                    isGenerating = isGenerating,
                    onGenerateSummary = { generateSummary() }
                )
            }
        }
    }

    // ── Explain Dialog ──
    if (showExplainDialog) {
        AlertDialog(
            onDismissRequest = { showExplainDialog = false },
            title = {
                Text("🧠 AI Explanation", style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 400.dp)
                ) {
                    // Original text
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = PrimaryMid)
                    ) {
                        Text(
                            text = selectedParagraph.take(300),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            fontStyle = FontStyle.Italic
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Explanation
                    if (isExplaining && explanation.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = AccentCyan
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Thinking…", color = TextMuted)
                        }
                    } else {
                        Text(
                            text = explanation.ifEmpty { "Generating…" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }
                }
            },
            confirmButton = {
                Row {
                    // Add to notes
                    if (explanation.isNotBlank()) {
                        TextButton(onClick = {
                            notes += "\n📖 Explanation:\n${explanation.trim()}\n\n"
                            showExplainDialog = false
                        }) {
                            Text("Add to Notes", color = NoteAmber)
                        }
                    }
                    TextButton(onClick = { showExplainDialog = false }) {
                        Text("Close", color = AccentCyan)
                    }
                }
            },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

// ═══════════════════════════════════════════
// ── Voice Pipeline Button with Pulsing Circle ──
// ═══════════════════════════════════════════

@Composable
private fun VoicePipelineButton(
    voiceState: VoiceSessionState,
    isVoiceReady: Boolean,
    onTap: () -> Unit
) {
    val isActive = voiceState != VoiceSessionState.IDLE

    // Pulsing animation for the outer circles
    val infiniteTransition = rememberInfiniteTransition(label = "voice_pulse")

    val pulse1 by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse1"
    )
    val pulse1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse1Alpha"
    )

    val pulse2 by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse2"
    )
    val pulse2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse2Alpha"
    )

    // Color based on state
    val activeColor = when (voiceState) {
        VoiceSessionState.LISTENING -> Color(0xFFEF4444)    // Red while listening
        VoiceSessionState.TRANSCRIBING -> AccentCyan        // Cyan while transcribing
        VoiceSessionState.THINKING -> AccentViolet          // Violet while thinking
        VoiceSessionState.SPEAKING -> AccentGreen           // Green while speaking
        else -> AccentViolet
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(56.dp)
    ) {
        // Pulsing rings (only when active)
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .scale(pulse2)
                    .clip(CircleShape)
                    .background(activeColor.copy(alpha = pulse2Alpha))
            )
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .scale(pulse1)
                    .clip(CircleShape)
                    .background(activeColor.copy(alpha = pulse1Alpha))
            )
        }

        // Main button
        FloatingActionButton(
            onClick = onTap,
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            containerColor = when {
                isActive -> activeColor
                isVoiceReady -> AccentViolet
                else -> TextMuted.copy(alpha = 0.3f)
            }
        ) {
            Icon(
                imageVector = when (voiceState) {
                    VoiceSessionState.LISTENING -> Icons.Rounded.Stop
                    VoiceSessionState.TRANSCRIBING -> Icons.Rounded.Hearing
                    VoiceSessionState.THINKING -> Icons.Rounded.Psychology
                    VoiceSessionState.SPEAKING -> Icons.Rounded.VolumeUp
                    else -> Icons.Rounded.Mic
                },
                contentDescription = "Voice",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// ═══════════════════════════════════════════
// ── Voice Status Bar ──
// ═══════════════════════════════════════════

@Composable
private fun VoiceStatusBar(voiceState: VoiceSessionState) {
    val statusColor = when (voiceState) {
        VoiceSessionState.LISTENING -> Color(0xFFEF4444)
        VoiceSessionState.TRANSCRIBING -> AccentCyan
        VoiceSessionState.THINKING -> AccentViolet
        VoiceSessionState.SPEAKING -> AccentGreen
        else -> TextMuted
    }

    val statusText = when (voiceState) {
        VoiceSessionState.LISTENING -> "🎤 Listening… Tap mic to stop"
        VoiceSessionState.TRANSCRIBING -> "✍️ Transcribing your speech…"
        VoiceSessionState.THINKING -> "🧠 AI is thinking…"
        VoiceSessionState.SPEAKING -> "🔊 Playing response…"
        else -> ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(statusColor.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (voiceState == VoiceSessionState.LISTENING) {
            // Pulsing red dot
            val infiniteTransition = rememberInfiniteTransition(label = "dot_pulse")
            val dotAlpha by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dotAlpha"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color.Red.copy(alpha = dotAlpha))
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = statusColor,
                strokeWidth = 2.dp
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            statusText,
            style = MaterialTheme.typography.bodySmall,
            color = statusColor,
            fontWeight = FontWeight.Medium
        )
    }
}

// ═══════════════════════════════════════════
// ── Chat Tab with Document Integration ──
// ═══════════════════════════════════════════

@Composable
private fun ChatTabContentWithDocument(
    messages: List<SessionMessage>,
    streamingResponse: String,
    isGenerating: Boolean,
    isSpeaking: Boolean,
    isTTSLoaded: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSpeak: (String) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // No document card — clean chat only

        if (messages.isEmpty() && streamingResponse.isEmpty()) {
            item { ChatEmptyState() }
        }

        items(messages) { message ->
            ChatBubble(
                message = message,
                isTTSLoaded = isTTSLoaded,
                isSpeaking = isSpeaking,
                onSpeak = onSpeak
            )
        }

        // Streaming response
        if (streamingResponse.isNotEmpty()) {
            item {
                ChatBubble(
                    message = SessionMessage(streamingResponse, isUser = false),
                    isTTSLoaded = false,
                    isSpeaking = false,
                    onSpeak = {},
                    isStreaming = true
                )
            }
        }

        if (isGenerating && streamingResponse.isEmpty()) {
            item {
                Row(
                    modifier = Modifier.padding(start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = AccentCyan
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Thinking…", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// DocumentInChatCard removed — clean chat-only interface
// Document is referenced via SmartDocumentSearch in the LLM prompt

// ═══════════════════════════════════════════
// ── Chat Empty State ──
// ═══════════════════════════════════════════

@Composable
private fun ChatEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Rounded.AutoAwesome,
            null,
            tint = AccentCyan,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Start Learning!",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Import a document and ask questions, or just chat with AI.\n\n🎤 Tap the mic button for real-time voice conversation!",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

// ═══════════════════════════════════════════
// ── Chat Bubble ──
// ═══════════════════════════════════════════

@Composable
private fun ChatBubble(
    message: SessionMessage,
    isTTSLoaded: Boolean,
    isSpeaking: Boolean,
    onSpeak: (String) -> Unit,
    isStreaming: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!message.isUser) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(AccentCyan.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.School,
                    null,
                    tint = AccentCyan,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start) {
            Card(
                modifier = Modifier.widthIn(max = 300.dp),
                shape = RoundedCornerShape(
                    topStart = if (message.isUser) 16.dp else 4.dp,
                    topEnd = if (message.isUser) 4.dp else 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (message.isUser) AccentViolet else SurfaceCard
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (message.isUser) Color.White else TextPrimary
                    )
                    if (isStreaming) {
                        Text("▊", color = AccentCyan, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // TTS button for AI messages
            if (!message.isUser && isTTSLoaded && !isStreaming) {
                IconButton(
                    onClick = { onSpeak(message.text) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        if (isSpeaking) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeUp,
                        "Speak",
                        tint = AccentPink.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        if (message.isUser) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(AccentViolet.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Person,
                    null,
                    tint = AccentViolet,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════
// ── Notes Tab (kept unchanged) ──
// ═══════════════════════════════════════════

@Composable
private fun NotesTabContent(
    notes: String,
    onNotesChanged: (String) -> Unit,
    smartNotesEnabled: Boolean,
    onSmartNotesToggle: (Boolean) -> Unit,
    diagramsEnabled: Boolean,
    onDiagramsToggle: (Boolean) -> Unit,
    hasDocument: Boolean,
    isGenerating: Boolean,
    onGenerateSummary: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Toggles
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard.copy(alpha = 0.6f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Smart Notes", style = MaterialTheme.typography.titleSmall, color = NoteAmber)
                        Text(
                            "Auto-generate notes from AI answers",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    Switch(
                        checked = smartNotesEnabled,
                        onCheckedChange = onSmartNotesToggle,
                        colors = SwitchDefaults.colors(checkedTrackColor = NoteAmber)
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = TextMuted.copy(alpha = 0.1f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Diagrams Mode", style = MaterialTheme.typography.titleSmall, color = AccentCyan)
                        Text(
                            "AI creates tables & text diagrams",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    Switch(
                        checked = diagramsEnabled,
                        onCheckedChange = onDiagramsToggle,
                        colors = SwitchDefaults.colors(checkedTrackColor = AccentCyan)
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Generate summary button
        if (hasDocument) {
            OutlinedButton(
                onClick = onGenerateSummary,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isGenerating,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Rounded.Summarize, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isGenerating) "Generating…" else "✨ Generate Summary from Document")
            }
            Spacer(Modifier.height(16.dp))
        }

        // Notes editor
        Text("Your Notes", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
        Spacer(Modifier.height(8.dp))

        TextField(
            value = notes,
            onValueChange = onNotesChanged,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 300.dp),
            placeholder = {
                Text(
                    "Your notes will appear here…\n\n• Enable 'Smart Notes' to auto-generate from AI answers\n• Or type your own notes\n• Import a document and click 'Generate Summary'",
                    color = TextMuted.copy(alpha = 0.5f)
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = SurfaceCard.copy(alpha = 0.4f),
                unfocusedContainerColor = SurfaceCard.copy(alpha = 0.4f),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            shape = RoundedCornerShape(14.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary)
        )

        if (notes.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { onNotesChanged("") }) {
                Icon(
                    Icons.Rounded.ClearAll,
                    null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Clear Notes", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
