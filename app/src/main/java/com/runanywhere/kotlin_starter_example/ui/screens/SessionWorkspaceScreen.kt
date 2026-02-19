package com.runanywhere.kotlin_starter_example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.concurrent.CancellationException
import com.runanywhere.kotlin_starter_example.data.*
import com.runanywhere.kotlin_starter_example.services.ModelService
import com.runanywhere.kotlin_starter_example.ui.components.ModelLoaderWidget
import com.runanywhere.kotlin_starter_example.ui.theme.*
import com.runanywhere.kotlin_starter_example.utils.DocumentReader
import com.runanywhere.kotlin_starter_example.utils.LLMPerformanceBooster
import com.runanywhere.kotlin_starter_example.utils.SmartDocumentSearch
import com.runanywhere.kotlin_starter_example.utils.AccentTTSManager
import com.runanywhere.kotlin_starter_example.utils.SimpleAudioRecorder
import com.runanywhere.kotlin_starter_example.utils.playWavAudioData
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.extensions.transcribe
import com.runanywhere.sdk.public.extensions.synthesize
import com.runanywhere.sdk.public.extensions.TTS.TTSOptions
import com.runanywhere.sdk.foundation.bridge.extensions.CppBridgeLLM
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

    // Document state — content is stored on disk, NOT in memory!
    var documentName by remember { mutableStateOf(session.documentName) }
    var hasDocument by remember { mutableStateOf(session.hasDocument) }

    // Voice session state - Nova-style VAD pipeline
    var voiceState by remember { mutableStateOf(VoiceSessionState.IDLE) }
    var isSpeaking by remember { mutableStateOf(false) }
    val audioRecorder = remember { SimpleAudioRecorder() }
    var liveAmplitude by remember { mutableStateOf(0f) }
    var liveTranscript by remember { mutableStateOf("") }

    // Android built-in TTS — supports Indian 🇮🇳, US 🇺🇸, UK 🇬🇧, AU 🇦🇺 accents (zero download)
    val accentTTS = remember { AccentTTSManager(context) }
    DisposableEffect(Unit) { onDispose { accentTTS.shutdown() } }

    // UI state - only 2 tabs now (Chat, Notes) - Document tab removed
    var selectedTab by remember { mutableIntStateOf(0) }
    var showExplainDialog by remember { mutableStateOf(false) }
    var selectedParagraph by remember { mutableStateOf("") }
    var explanation by remember { mutableStateOf("") }
    var isExplaining by remember { mutableStateOf(false) }
    var saveIndicator by remember { mutableStateOf("") }

    // KV cache token tracker — now cleared on EVERY prompt for zero crash risk
    var cumulativeTokens by remember { mutableIntStateOf(0) }

    // Document info for dropdown (not shown in chat)
    var docInfoChunks by remember { mutableIntStateOf(0) }
    var docInfoChars by remember { mutableIntStateOf(0) }
    var docInfoFormat by remember { mutableStateOf("") }
    var showDocInfoDropdown by remember { mutableStateOf(false) }

    // Key Points state — for "Generate Key Points" long press option
    val keyPointsMap = remember { mutableStateMapOf<Long, String>() }
    var generatingKeyPointsFor by remember { mutableStateOf<Long?>(null) }

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

                val result = withContext(Dispatchers.IO) {
                    DocumentReader.readDocument(context, it)
                }

                // CRITICAL: Force aggressive GC to reclaim PDF native memory
                // BEFORE any LLM operations to prevent fragmentation
                System.gc()
                System.runFinalization()
                kotlinx.coroutines.delay(200)
                System.gc()
                kotlinx.coroutines.delay(100)

                // Remove loading message
                messages = messages.filter { msg -> msg !== loadingMsg }

                if (result != null) {
                    val (name, content) = result
                    documentName = name
                    hasDocument = true

                    // 💾 Save document to FILE (not memory!) and index it
                    SmartDocumentSearch.saveDocumentToFile(content, context, session.id)
                    val numChunks = SmartDocumentSearch.indexDocument(content, context)
                    // content string will be garbage collected — NOT stored in state!

                    // Store doc info for dropdown (not shown in chat)
                    val ext = name.substringAfterLast('.', "").uppercase()
                    docInfoFormat = ext
                    docInfoChars = content.length
                    docInfoChunks = numChunks

                    session = session.copy(
                        title = name.substringBeforeLast('.'),
                        documentName = name,
                        documentContent = null,  // NOT stored in memory
                        hasDocument = true
                    )

                    // Simple confirmation — no technical details in chat
                    val successMsg = SessionMessage(
                        "✅ \"$name\" loaded. Ask me anything about this document!",
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
        // ━━ CRITICAL: Fresh context for new sessions ━━
        // SmartDocumentSearch is a singleton — old doc index leaks across sessions.
        // Clear it, then re-index if this session has a saved document FROM FILE.
        SmartDocumentSearch.clear()

        if (session.hasDocument || !session.documentContent.isNullOrBlank()) {
            // Try loading from file first (new format)
            val docFromFile = SmartDocumentSearch.loadDocumentFromFile(context, session.id)
            if (docFromFile != null) {
                val numChunks = SmartDocumentSearch.indexDocument(docFromFile, context)
                hasDocument = true
                // Populate doc info for dropdown
                docInfoChars = docFromFile.length
                docInfoChunks = numChunks
                docInfoFormat = session.documentName?.substringAfterLast('.', "")?.uppercase() ?: ""
                // docFromFile string is now garbage-collectible
            } else if (!session.documentContent.isNullOrBlank()) {
                // Migration: old session had documentContent in JSON
                // Save to file, index, then clear from session
                SmartDocumentSearch.saveDocumentToFile(session.documentContent!!, context, session.id)
                val migChunks = SmartDocumentSearch.indexDocument(session.documentContent!!, context)
                hasDocument = true
                docInfoChars = session.documentContent!!.length
                docInfoChunks = migChunks
                docInfoFormat = session.documentName?.substringAfterLast('.', "")?.uppercase() ?: ""
                session = session.copy(documentContent = null, hasDocument = true)
            }
        }

        while (true) {
            delay(5000)
            session = session.copy(
                messages = messages.toMutableList(),
                notes = notes,
                documentName = documentName,
                documentContent = null,  // NEVER store doc content in session JSON
                hasDocument = hasDocument,
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

    // ── SPEED OPTIMIZED: Direct bridge generation with tuned sampling params ──
    // SDK's generateStreamWithMetrics() ignores stopSequences and uses default topK=40.
    // By calling CppBridgeLLM.generateStream() directly, we can:
    //   1. Set topK=20 (much faster token sampling — 50% less vocab to evaluate)
    //   2. Actually apply stopSequences (SDK wrapper drops them!)
    //   3. Set repeatPenalty=1.15 for better quality (avoids repetition loops)
    //   4. Guarantee our temperature/topP/maxTokens are used (no SDK default override)
    // NOTE: Model is still loaded via RunAnywhere.loadLLMModel() — no crash risk.
    //       We only bypass the GENERATION wrapper, not the model loading.
    data class BridgeStreamResult(
        val fullText: String,
        val tokensGenerated: Int,
        val generationTimeMs: Long,
        val tokensPerSecond: Float
    )

    suspend fun generateWithBridge(
        prompt: String,
        maxTok: Int? = null,
        temp: Float = 0.65f,
        onToken: (String) -> Unit
    ): BridgeStreamResult = withContext(Dispatchers.IO) {
        val modelMem = (ModelService.getLLMOption(modelService.activeLLMModelId)?.memoryRequirement ?: 400_000_000L) / (1024L * 1024L)
        val adaptiveMaxTokens = maxTok ?: LLMPerformanceBooster.getRecommendedMaxTokens(context, modelMem)

        // Build optimized GenerationConfig with fields the SDK wrapper ignores
        val config = CppBridgeLLM.GenerationConfig(
            maxTokens = adaptiveMaxTokens,
            temperature = temp,
            topP = 0.85f,
            topK = 20,                  // ⚡ SDK default is 40 — halving speeds up sampling
            repeatPenalty = 1.15f,       // Prevents repetition loops (SDK default 1.1)
            stopSequences = listOf("\n\nQuestion:", "\n\nQ:", "\n---", "<|endoftext|>"),
            seed = -1L
        )

        android.util.Log.d("BridgeGen", "⚡ Direct bridge generate: maxTok=$adaptiveMaxTokens, temp=$temp, topK=20, topP=0.85, repeatPen=1.15")

        val startTime = System.currentTimeMillis()
        val sb = StringBuilder()
        var tokenCount = 0
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        try {
            val result = CppBridgeLLM.generateStream(prompt, config) { token ->
                sb.append(token)
                tokenCount++
                // Post UI update to main thread without blocking native generate thread
                val currentText = sb.toString()
                handler.post { onToken(currentText) }
                true // continue generating
            }

            val genTime = System.currentTimeMillis() - startTime
            android.util.Log.d("BridgeGen", "✅ Done: ${result.tokensGenerated} tokens, ${result.tokensPerSecond} tok/s, ${genTime}ms")

            BridgeStreamResult(
                fullText = result.text ?: sb.toString(),
                tokensGenerated = result.tokensGenerated,
                generationTimeMs = genTime,
                tokensPerSecond = result.tokensPerSecond
            )
        } catch (e: Exception) {
            val genTime = System.currentTimeMillis() - startTime
            android.util.Log.e("BridgeGen", "❌ Bridge generate failed: ${e.message}, falling back", e)
            // Return whatever we collected so far
            BridgeStreamResult(
                fullText = sb.toString(),
                tokensGenerated = tokenCount,
                generationTimeMs = genTime,
                tokensPerSecond = if (genTime > 0) (tokenCount * 1000f / genTime) else 0f
            )
        }
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
            // Brief delay for OS to reclaim freed native memory pages.
            kotlinx.coroutines.delay(200)
            LLMPerformanceBooster.forceGC()
            kotlinx.coroutines.delay(100)
            android.util.Log.d("LLMBoost", "After unload — native heap: ${LLMPerformanceBooster.getNativeHeapUsageMB()}MB")
        }

        // SAFETY NET: Only switches to SmolLM2-360M if native heap exceeds 75% of
        // device RAM — a real danger zone. Now that docs are on disk, the user's chosen
        // model (even 1.7B at ~1700MB) is safe on 6GB devices.
        val didSwitchModel = modelService.ensureSafeModelForGeneration(context)

        // ━━ ALWAYS reset KV cache before EVERY prompt ━━
        // llama.cpp accumulates KV cache across generate() calls.
        // SmolLM2-360M has only 2048 context tokens — one doc-augmented prompt
        // can fill ~1200 tokens, so the 2nd prompt OVERFLOWS → SIGABRT.
        // Resetting ensures each query gets a fresh, full context window.
        // Skip if ensureSafeModelForGeneration just loaded a fresh model (KV already clean).
        if (!didSwitchModel) {
            android.util.Log.d("LLMBoost", "Resetting KV cache for clean context")
            modelService.resetLLMContext()
        }
        cumulativeTokens = 0
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
        if (text.isBlank() || isGenerating || generatingKeyPointsFor != null || !modelService.isLLMLoaded) return
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
                // Scale document limits by model size — 1.7B needs less doc than 360M
                val modelMem = (ModelService.getLLMOption(modelService.activeLLMModelId)?.memoryRequirement ?: 400_000_000L) / (1024L * 1024L)
                val docLimit = LLMPerformanceBooster.getRecommendedDocLimit(context, modelMem)
                val contextPrompt = buildString {
                    if (hasDocument && SmartDocumentSearch.isDocumentIndexed()) {
                        // 📖 RAG prompt optimized for small LLMs (1B-3B)
                        // Detect if this is a general/overview question about the whole document
                        val queryLower = text.lowercase().trim()
                        val isOverviewQuestion = queryLower.let { q ->
                            q.contains("about this document") || q.contains("about this doc") ||
                            q.contains("what is this") || q.contains("summarize") ||
                            q.contains("summary") || q.contains("overview") ||
                            q.contains("tell me about") || q.contains("describe this") ||
                            q.contains("what does this") || q.contains("document about") ||
                            q.contains("isme kya hai") || q.contains("ye kya hai") ||
                            q.contains("is document") || q.contains("is doc")
                        }

                        // Smart answer length: let LLM decide based on question type
                        val answerInstruction = when {
                            // Overview/summary questions need longer answers
                            isOverviewQuestion -> "Give a comprehensive overview in about 200-300 words."
                            // Concept/definition questions need detailed explanations
                            queryLower.let { q ->
                                q.startsWith("what is") || q.startsWith("what are") ||
                                q.startsWith("explain") || q.startsWith("describe") ||
                                q.startsWith("how does") || q.startsWith("how do") ||
                                q.startsWith("how is") || q.startsWith("why") ||
                                q.contains("difference between") || q.contains("compare") ||
                                q.contains("kya hai") || q.contains("kaise") ||
                                q.contains("samjhao") || q.contains("batao")
                            } -> "Give a detailed answer in about 200-300 words with examples if applicable."
                            // List/type questions need medium answers
                            queryLower.let { q ->
                                q.startsWith("list") || q.contains("types of") ||
                                q.contains("advantages") || q.contains("features")
                            } -> "Give a clear answer in about 150-200 words."
                            // Factual/short questions need brief answers
                            else -> "Give a direct, concise answer. Be brief if the answer is simple, detailed if it needs explanation."
                        }

                        append("Answer the question using the document below.\n")
                        append("$answerInstruction\n")
                        append("Use exact names, numbers, and details from the document.\n\n")
                        append("Question: $text\n\n")
                        append("Document:\n")
                        val relevantContext = if (isOverviewQuestion) {
                            // For overview questions, send full document overview instead of keyword search
                            SmartDocumentSearch.getDocumentOverview(docLimit)
                        } else {
                            SmartDocumentSearch.getRelevantContext(text, docLimit)
                        }
                        if (relevantContext != null) {
                            append(relevantContext)
                        }
                        append("\n\nAnswer:")
                    } else {
                        append("You are a helpful assistant. Give a detailed answer with examples and explanations.\n\n")
                        append("Question: $text\n\nAnswer:")
                    }
                }

                // Safety cap: keep prompt within context window to avoid native OOM.
                // Dynamic based on device RAM AND model size.
                // IMPORTANT: Question is placed BEFORE document, so truncation only
                // cuts document content (not the question itself).
                val maxPromptLen = LLMPerformanceBooster.getMaxSafePromptLength(context, modelMem)
                val safePrompt = if (contextPrompt.length > maxPromptLen) {
                    android.util.Log.w("SessionWorkspace", "Prompt too long (${contextPrompt.length}), truncating to $maxPromptLen chars")
                    // Truncate at last newline before limit so we don't cut mid-sentence
                    val truncated = contextPrompt.take(maxPromptLen)
                    val lastNewline = truncated.lastIndexOf('\n')
                    val cleanTruncated = if (lastNewline > maxPromptLen / 2) truncated.substring(0, lastNewline) else truncated
                    if (cleanTruncated.endsWith("Answer:")) cleanTruncated else "$cleanTruncated\n\nAnswer:"
                } else contextPrompt

                var fullResponse = try {
                    streamingResponse = "Thinking..."
                    // ⚡ Direct bridge generation: bypasses SDK wrapper for speed
                    // topK=20 (vs SDK default 40), stopSequences applied, temp/topP guaranteed
                    val bridgeResult = generateWithBridge(safePrompt) { currentText ->
                        streamingResponse = currentText
                    }
                    bridgeResult.fullText
                } catch (e: Exception) {
                    android.util.Log.e("SessionWorkspace", "Chat failed: ${e.message}", e)
                    ""
                }

                if (fullResponse.isBlank()) fullResponse = "I couldn't generate a response. The model may need more memory \u2014 try a smaller model in Settings."

                // Note: KV cache is now cleared before EVERY prompt in boostForLLM()
                // No need to track cumulative tokens - each query is independent

                val aiMsg = SessionMessage(fullResponse.trim(), isUser = false)
                messages = messages + aiMsg
                streamingResponse = ""

                // Smart notes: auto-add to notes
                if (smartNotesEnabled && fullResponse.isNotBlank()) {
                    val bullet = "• Q: ${text.take(60)}${if (text.length > 60) "…" else ""}\n  → ${fullResponse.take(200).trim()}${if (fullResponse.length > 200) "…" else ""}\n\n"
                    notes += bullet
                }

                try { listState.animateScrollToItem(maxOf(0, messages.size - 1)) } catch (_: Exception) {}
            } catch (e: CancellationException) {
                // MutationInterruptedException extends CancellationException — ignore scroll conflicts
            } catch (e: Exception) {
                messages = messages + SessionMessage("Error: ${e.message}", isUser = false)
            } finally {
                isGenerating = false
                streamingResponse = ""
                LLMPerformanceBooster.restoreAfterInference()
                // NOTE: Do NOT reload STT/TTS here after text queries!
                // Reloading them after every query causes massive native memory churn
                // (7 alloc/dealloc cycles per query) → fragmentation → SIGABRT on 3rd query.
                // STT/TTS will be reloaded on-demand when voice button is pressed.
            }
        }
    }

    fun generateKeyPoints(message: SessionMessage) {
        if (generatingKeyPointsFor != null || isGenerating) return
        if (!modelService.isLLMLoaded) return
        generatingKeyPointsFor = message.timestamp
        isGenerating = true  // Block send button while key points generate

        scope.launch {
            try {
                boostForLLM()

                val prompt = buildString {
                    append("Summarize the following into concise, numbered key points.\n")
                    append("Only output the key points, nothing else.\n\n")
                    append("Text:\n${message.text}\n\n")
                    append("Key Points:\n1.")
                }

                val result = generateWithBridge(prompt, maxTok = 300, temp = 0.5f) { currentText ->
                    keyPointsMap[message.timestamp] = "1.$currentText"
                }

                val finalText = result.fullText.trim()
                keyPointsMap[message.timestamp] = if (finalText.startsWith("1.")) finalText else "1.$finalText"

                android.util.Log.d("KeyPoints", "✅ Generated key points: ${result.tokensGenerated} tokens, ${result.tokensPerSecond} tok/s")
            } catch (e: CancellationException) {
                // Ignore scroll/mutation cancellations
            } catch (e: Exception) {
                android.util.Log.e("KeyPoints", "❌ Key points generation failed: ${e.message}", e)
                keyPointsMap[message.timestamp] = "⚠️ Failed to generate key points"
            } finally {
                generatingKeyPointsFor = null
                isGenerating = false  // Re-enable send button
                LLMPerformanceBooster.restoreAfterInference()
            }
        }
    }

    fun speakText(text: String) {
        if (isSpeaking) return
        isSpeaking = true
        scope.launch {
            try {
                if (prefs.useNativeTTS && accentTTS.isReady) {
                    // 🇮🇳 Android TTS — supports Indian & other accents, no download
                    accentTTS.speakAndWait(text.take(1000), prefs.ttsAccent, prefs.ttsSpeed, prefs.ttsPitch)
                } else if (modelService.isTTSLoaded) {
                    // Fallback to Piper TTS
                    val output = withContext(Dispatchers.IO) {
                        RunAnywhere.synthesize(text.take(500), TTSOptions(
                            rate = prefs.ttsSpeed,
                            pitch = prefs.ttsPitch
                        ))
                    }
                    playWavAudioData(output.audioData)
                }
            } catch (_: Exception) {
            } finally {
                isSpeaking = false
            }
        }
    }

    // Forward-ref workaround: stopVoiceAndProcess is defined after startVoiceSession
    var doStopVoiceAndProcess: () -> Unit = {}

    // ── Nova-style Voice Pipeline: Speak → VAD silence detect → auto-respond ──
    fun startVoiceSession() {
        if (!hasAudioPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (voiceState != VoiceSessionState.IDLE) return
        if (!modelService.isLLMLoaded) return

        // On-demand STT reload: STT may have been unloaded during text query
        if (!modelService.isSTTLoaded) {
            scope.launch {
                modelService.downloadAndLoadSTT()
                // Wait for STT to load, then start recording
                var waited = 0
                while (!modelService.isSTTLoaded && waited < 8000) {
                    kotlinx.coroutines.delay(200)
                    waited += 200
                }
                if (modelService.isSTTLoaded) startVoiceSession()
            }
            return
        }

        voiceState = VoiceSessionState.LISTENING
        liveTranscript = ""

        // VAD callback: auto-stop when user pauses speaking
        audioRecorder.onSilenceDetected = {
            scope.launch(Dispatchers.Main) {
                if (voiceState == VoiceSessionState.LISTENING) {
                    doStopVoiceAndProcess()
                }
            }
        }

        scope.launch {
            try {
                // Start recording with VAD — auto-stops after 1.5s silence
                val started = withContext(Dispatchers.IO) { audioRecorder.startRecording(enableVAD = true) }
                if (!started) {
                    voiceState = VoiceSessionState.IDLE
                    audioRecorder.onSilenceDetected = null
                    return@launch
                }
                // Poll amplitude for live UI visualization (20 fps)
                while (voiceState == VoiceSessionState.LISTENING) {
                    liveAmplitude = audioRecorder.currentAmplitude
                    delay(50)
                }
                liveAmplitude = 0f
            } catch (e: Exception) {
                voiceState = VoiceSessionState.IDLE
                audioRecorder.onSilenceDetected = null
                liveAmplitude = 0f
            }
        }
    }

    fun stopVoiceAndProcess() {
        if (voiceState != VoiceSessionState.LISTENING) return
        audioRecorder.onSilenceDetected = null  // prevent double-trigger
        liveAmplitude = 0f

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

                liveTranscript = transcribedText  // show what user said in status bar

                // Add user message
                val userMsg = SessionMessage(transcribedText, isUser = true)
                messages = messages + userMsg
                try { listState.animateScrollToItem(maxOf(0, messages.size - 1)) } catch (_: Exception) {}

                // Step 3: Generate LLM response
                voiceState = VoiceSessionState.THINKING
                isGenerating = true
                streamingResponse = ""

                val voiceModelMem = (ModelService.getLLMOption(modelService.activeLLMModelId)?.memoryRequirement ?: 400_000_000L) / (1024L * 1024L)
                val voiceDocLimit = LLMPerformanceBooster.getRecommendedDocLimit(context, voiceModelMem)
                val contextPrompt = buildString {
                    if (hasDocument && SmartDocumentSearch.isDocumentIndexed()) {
                        // Same smart RAG prompt as text chat
                        val queryLower = transcribedText.lowercase().trim()
                        val isOverviewQuestion = queryLower.let { q ->
                            q.contains("about this document") || q.contains("about this doc") ||
                            q.contains("what is this") || q.contains("summarize") ||
                            q.contains("summary") || q.contains("overview") ||
                            q.contains("tell me about") || q.contains("describe this") ||
                            q.contains("what does this") || q.contains("document about") ||
                            q.contains("isme kya hai") || q.contains("ye kya hai") ||
                            q.contains("is document") || q.contains("is doc")
                        }

                        val answerInstruction = when {
                            isOverviewQuestion -> "Give a comprehensive overview in about 200-300 words."
                            queryLower.let { q ->
                                q.startsWith("what is") || q.startsWith("what are") ||
                                q.startsWith("explain") || q.startsWith("describe") ||
                                q.startsWith("how does") || q.startsWith("how do") ||
                                q.startsWith("how is") || q.startsWith("why") ||
                                q.contains("difference between") || q.contains("compare") ||
                                q.contains("kya hai") || q.contains("kaise") ||
                                q.contains("samjhao") || q.contains("batao")
                            } -> "Give a detailed answer in about 200-300 words with examples if applicable."
                            queryLower.let { q ->
                                q.startsWith("list") || q.contains("types of") ||
                                q.contains("advantages") || q.contains("features")
                            } -> "Give a clear answer in about 150-200 words."
                            else -> "Give a direct, concise answer. Be brief if the answer is simple, detailed if it needs explanation."
                        }

                        append("Answer the question using the document below.\n")
                        append("$answerInstruction\n")
                        append("Use exact names, numbers, and details from the document.\n\n")
                        append("Question: $transcribedText\n\n")
                        append("Document:\n")
                        val relevantContext = if (isOverviewQuestion) {
                            SmartDocumentSearch.getDocumentOverview(voiceDocLimit)
                        } else {
                            SmartDocumentSearch.getRelevantContext(transcribedText, voiceDocLimit)
                        }
                        if (relevantContext != null) {
                            append(relevantContext)
                        }
                        append("\n\nAnswer:")
                    } else {
                        append("You are a helpful assistant. Give a detailed answer with examples and explanations.\n\n")
                        append("Question: $transcribedText\n\nAnswer:")
                    }
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
                // Question is placed BEFORE document, so truncation only cuts document content
                val voiceMaxPrompt = LLMPerformanceBooster.getMaxSafePromptLength(context, voiceModelMem)
                val voiceSafePrompt = if (contextPrompt.length > voiceMaxPrompt) {
                    val truncated = contextPrompt.take(voiceMaxPrompt)
                    val lastNewline = truncated.lastIndexOf('\n')
                    val cleanTruncated = if (lastNewline > voiceMaxPrompt / 2) truncated.substring(0, lastNewline) else truncated
                    if (cleanTruncated.endsWith("Answer:")) cleanTruncated else "$cleanTruncated\n\nAnswer:"
                } else contextPrompt

                // ── Generate with real streaming ──
                val voiceStartTime = System.currentTimeMillis()

                var fullResponse = try {
                    streamingResponse = "Thinking..."
                    // ⚡ Direct bridge generation for voice chat
                    val bridgeResult = generateWithBridge(voiceSafePrompt) { currentText ->
                        streamingResponse = currentText
                    }
                    bridgeResult.fullText
                } catch (e: Exception) {
                    android.util.Log.e("SessionWorkspace", "Voice chat failed: ${e.message}", e)
                    ""
                }

                if (fullResponse.isBlank()) fullResponse = "I couldn't generate a response. Try a smaller model."

                // Note: KV cache is now cleared before EVERY prompt in boostForLLM()
                // No need to track cumulative tokens - each query is independent

                val aiMsg = SessionMessage(fullResponse.trim(), isUser = false)
                messages = messages + aiMsg
                streamingResponse = ""
                isGenerating = false

                // Smart notes
                if (smartNotesEnabled && fullResponse.isNotBlank()) {
                    val bullet = "• Q: ${transcribedText.take(60)}${if (transcribedText.length > 60) "…" else ""}\n  → ${fullResponse.take(200).trim()}${if (fullResponse.length > 200) "…" else ""}\n\n"
                    notes += bullet
                }

                try { listState.animateScrollToItem(maxOf(0, messages.size - 1)) } catch (_: Exception) {}

                // Step 4: Speak with accent TTS (🇮🇳 Indian, 🇺🇸 US, 🇬🇧 UK etc.)
                // Android TTS is a system service — zero native heap, no reload needed!
                voiceState = VoiceSessionState.SPEAKING
                isSpeaking = true
                try {
                    if (prefs.useNativeTTS && accentTTS.isReady) {
                        accentTTS.speakAndWait(
                            text = fullResponse.trim().take(1000),
                            accentId = prefs.ttsAccent,
                            speed = prefs.ttsSpeed,
                            pitch = prefs.ttsPitch
                        )
                    } else {
                        // Fallback: Piper TTS
                        if (!modelService.isTTSLoaded) {
                            modelService.downloadAndLoadTTS()
                            var waited = 0
                            while (!modelService.isTTSLoaded && waited < 5000) {
                                kotlinx.coroutines.delay(200)
                                waited += 200
                            }
                        }
                        if (modelService.isTTSLoaded) {
                            val output = withContext(Dispatchers.IO) {
                                RunAnywhere.synthesize(fullResponse.trim().take(500), TTSOptions(
                                    rate = prefs.ttsSpeed,
                                    pitch = prefs.ttsPitch
                                ))
                            }
                            playWavAudioData(output.audioData)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    isSpeaking = false
                }
                // Reload STT for next voice session
                if (!modelService.isSTTLoaded) modelService.downloadAndLoadSTT()
                liveTranscript = ""

                voiceState = VoiceSessionState.IDLE
            } catch (e: CancellationException) {
                // MutationInterruptedException — ignore scroll conflicts
                voiceState = VoiceSessionState.IDLE
            } catch (e: Exception) {
                isGenerating = false
                streamingResponse = ""
                voiceState = VoiceSessionState.IDLE
                messages = messages + SessionMessage("Voice error: ${e.message}", isUser = false)
            }
        }
    }

    // Wire up forward-ref lambda for VAD callback
    doStopVoiceAndProcess = { stopVoiceAndProcess() }

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
                val prompt = "Explain the following text in simple terms with examples.\n\nText:\n${paragraph.take(400)}\n\nExplanation:"
                val fullExplanation = try {
                    // ⚡ Direct bridge generation for explain (non-streaming)
                    val bridgeResult = generateWithBridge(prompt, maxTok = 384) { currentText ->
                        explanation = currentText
                    }
                    bridgeResult.fullText
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
        if (!hasDocument || !SmartDocumentSearch.isDocumentIndexed() || isGenerating || !modelService.isLLMLoaded) return
        isGenerating = true
        streamingResponse = ""

        // Add a user message to chat showing what we're doing
        val userMsg = SessionMessage("📝 Summarize: ${documentName ?: "document"}", isUser = true)
        messages = messages + userMsg

        scope.launch {
            try {
                try { listState.animateScrollToItem(maxOf(0, messages.size - 1)) } catch (_: Exception) {}
                boostForLLM()

                if (!hasEnoughMemoryForLLM()) {
                    messages = messages + SessionMessage(
                        "Not enough free memory for ${modelService.getActiveLLMName()}. Switch to SmolLM2 360M in Settings and try again.",
                        isUser = false
                    )
                    return@launch
                }

                // Adaptive document limit based on device RAM — read from DISK
                val sumModelMem = (ModelService.getLLMOption(modelService.activeLLMModelId)?.memoryRequirement ?: 400_000_000L) / (1024L * 1024L)
                val sumDocLimit = LLMPerformanceBooster.getRecommendedDocLimit(context, sumModelMem)
                val docText = SmartDocumentSearch.getDocumentOverview(sumDocLimit)
                val prompt = buildString {
                    append("Create detailed study notes from the following document.\n")
                    append("Include key concepts, definitions, important details, and examples.\n")
                    append("Organize with clear headings and bullet points.\n\n")
                    append("Document:\n")
                    append(docText)
                    append("\n\nStudy Notes:")
                }

                // Safety cap: keep prompt within context window
                val safePrompt = prompt.take(LLMPerformanceBooster.getMaxSafePromptLength(context, sumModelMem))

                // ── Generate with real streaming ──
                val sumStartTime = System.currentTimeMillis()

                var fullResponse = try {
                    streamingResponse = "Generating summary..."
                    // ⚡ Direct bridge generation for summary
                    val bridgeResult = generateWithBridge(safePrompt) { currentText ->
                        streamingResponse = currentText
                    }
                    bridgeResult.fullText
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

                try { listState.animateScrollToItem(maxOf(0, messages.size - 1)) } catch (_: Exception) {}
            } catch (e: CancellationException) {
                // MutationInterruptedException — ignore
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
                                documentContent = null,
                                hasDocument = hasDocument,
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
                            VoiceStatusBar(voiceState, liveAmplitude, liveTranscript)
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
                                amplitude = liveAmplitude,
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

            // ── Model check — only show when truly not loaded AND not generating ──
            // During generation, boostForLLM() briefly unloads/reloads the model
            // for KV cache reset. Don't flash the loader widget during that.
            if (!modelService.isLLMLoaded && !isGenerating) {
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

            // ── Document Info Dropdown Arrow (below tabs) ──
            if (hasDocument && documentName != null) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    // Small clickable arrow centered below tabs
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDocInfoDropdown = !showDocInfoDropdown }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (showDocInfoDropdown) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Document info",
                            tint = AccentCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Dropdown content
                    androidx.compose.material3.DropdownMenu(
                        expanded = showDocInfoDropdown,
                        onDismissRequest = { showDocInfoDropdown = false },
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                "📄 Document Info",
                                style = MaterialTheme.typography.titleSmall,
                                color = AccentCyan,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))

                            // Document name
                            DocInfoRow("Name", documentName ?: "Unknown")

                            // Format
                            if (docInfoFormat.isNotBlank()) {
                                DocInfoRow("Format", docInfoFormat)
                            }

                            // Size
                            if (docInfoChars > 0) {
                                val sizeText = when {
                                    docInfoChars > 100_000 -> "${docInfoChars / 1000}K characters"
                                    docInfoChars > 1000 -> "${docInfoChars / 1000}K characters"
                                    else -> "$docInfoChars characters"
                                }
                                DocInfoRow("Size", sizeText)
                            }

                            // Sections indexed
                            if (docInfoChunks > 0) {
                                DocInfoRow("Sections", "$docInfoChunks indexed")
                            }

                            // Search stats
                            val stats = SmartDocumentSearch.getStats()
                            if (stats != "No document indexed") {
                                DocInfoRow("Index", stats)
                            }
                        }
                    }
                }
            }

            // ── Tab Content ──
            when (selectedTab) {
                0 -> ChatTabContentWithDocument(
                    messages = messages,
                    streamingResponse = streamingResponse,
                    isGenerating = isGenerating,
                    isSpeaking = isSpeaking,
                    isTTSLoaded = modelService.isTTSLoaded || accentTTS.isReady,
                    listState = listState,
                    onSpeak = { speakText(it) },
                    keyPointsMap = keyPointsMap,
                    generatingKeyPointsFor = generatingKeyPointsFor,
                    onGenerateKeyPoints = { msg -> generateKeyPoints(msg) }
                )

                1 -> NotesTabContent(
                    notes = notes,
                    onNotesChanged = { notes = it },
                    smartNotesEnabled = smartNotesEnabled,
                    onSmartNotesToggle = { smartNotesEnabled = it },
                    diagramsEnabled = diagramsEnabled,
                    onDiagramsToggle = { diagramsEnabled = it },
                    hasDocument = hasDocument,
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
// ── Document Info Row (for dropdown) ──
// ═══════════════════════════════════════════

@Composable
private fun DocInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            modifier = Modifier.width(70.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
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
    amplitude: Float = 0f,
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

        // Amplitude-responsive glow (LISTENING only — pulses with voice)
        if (voiceState == VoiceSessionState.LISTENING && amplitude > 0.05f) {
            val ampGlow = 1f + amplitude * 0.7f
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .scale(ampGlow)
                    .clip(CircleShape)
                    .background(activeColor.copy(alpha = 0.15f + amplitude * 0.35f))
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
private fun VoiceStatusBar(voiceState: VoiceSessionState, amplitude: Float = 0f, liveTranscript: String = "") {
    val statusColor = when (voiceState) {
        VoiceSessionState.LISTENING -> Color(0xFFEF4444)
        VoiceSessionState.TRANSCRIBING -> AccentCyan
        VoiceSessionState.THINKING -> AccentViolet
        VoiceSessionState.SPEAKING -> AccentGreen
        else -> TextMuted
    }

    val statusText = when (voiceState) {
        VoiceSessionState.LISTENING -> "🎤 Listening… auto-stops when you pause"
        VoiceSessionState.TRANSCRIBING -> "✍️ Transcribing your speech…"
        VoiceSessionState.THINKING -> "🧠 AI is thinking…"
        VoiceSessionState.SPEAKING -> "🔊 Speaking with accent…"
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
                fontWeight = FontWeight.Medium
            )
            // Amplitude bar during LISTENING
            if (voiceState == VoiceSessionState.LISTENING) {
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(statusColor.copy(alpha = 0.2f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(amplitude.coerceIn(0f, 1f))
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(statusColor)
                    )
                }
            }
            // Live transcript
            if (liveTranscript.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "“$liveTranscript”",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
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
    onSpeak: (String) -> Unit,
    keyPointsMap: Map<Long, String>,
    generatingKeyPointsFor: Long?,
    onGenerateKeyPoints: (SessionMessage) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize(),
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
                onSpeak = onSpeak,
                onLongPress = null,
                keyPoints = keyPointsMap[message.timestamp],
                isGeneratingKeyPoints = generatingKeyPointsFor == message.timestamp,
                onGenerateKeyPoints = { onGenerateKeyPoints(message) }
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
                    isStreaming = true,
                    onLongPress = null
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
            "Import a document and ask questions, or just chat with AI.\n\n🎤 Tap the mic — speak freely, it auto-detects when you stop!\n🇮🇳 Indian accent TTS • No extra downloads",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

// ═══════════════════════════════════════════
// ── Chat Bubble ──
// ═══════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(
    message: SessionMessage,
    isTTSLoaded: Boolean,
    isSpeaking: Boolean,
    onSpeak: (String) -> Unit,
    isStreaming: Boolean = false,
    onLongPress: ((String) -> Unit)? = null,
    keyPoints: String? = null,
    isGeneratingKeyPoints: Boolean = false,
    onGenerateKeyPoints: (() -> Unit)? = null
) {
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var showOptionsMenu by remember { mutableStateOf(false) }

    if (message.isUser) {
        // ── User message: right-aligned bubble with avatar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Card(
                modifier = Modifier.widthIn(max = 300.dp),
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 4.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp
                ),
                colors = CardDefaults.cardColors(containerColor = AccentViolet)
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }
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
    } else {
        // ── AI message: logo above, full-width card, no margin ──
        Column(modifier = Modifier.fillMaxWidth()) {
            // Logo icon above the message box
            Row(
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(AccentCyan.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.School,
                        null,
                        tint = AccentCyan,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    "AI",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentCyan.copy(alpha = 0.7f)
                )
            }

            // Full-width message card with long press
            Box {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                if (!isStreaming) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showOptionsMenu = true
                                }
                            }
                        ),
                    shape = RoundedCornerShape(4.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        MarkdownText(
                            markdown = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFF1FAFF)
                        )
                        if (isStreaming) {
                            Text("▊", color = AccentCyan, style = MaterialTheme.typography.bodyMedium)
                        }

                        // ── Key Points Section (inside the same card) ──
                        if (isGeneratingKeyPoints && keyPoints == null) {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(color = AccentCyan.copy(alpha = 0.2f))
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = AccentCyan
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Generating key points…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AccentCyan.copy(alpha = 0.7f)
                                )
                            }
                        }

                        if (keyPoints != null) {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(color = AccentCyan.copy(alpha = 0.2f))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "📝 Summarized Key Points:",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = AccentCyan
                            )
                            Spacer(Modifier.height(4.dp))
                            MarkdownText(
                                markdown = keyPoints,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFF1FAFF).copy(alpha = 0.9f)
                            )
                            if (isGeneratingKeyPoints) {
                                Text("▊", color = AccentCyan, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                // ── Long press options dropdown ──
                DropdownMenu(
                    expanded = showOptionsMenu,
                    onDismissRequest = { showOptionsMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("📋  Copy") },
                        onClick = {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(message.text))
                            showOptionsMenu = false
                        },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(20.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text(if (keyPoints != null) "✅  Key Points Generated" else "🔑  Generate Key Points") },
                        onClick = {
                            if (keyPoints == null && !isGeneratingKeyPoints) {
                                onGenerateKeyPoints?.invoke()
                            }
                            showOptionsMenu = false
                        },
                        enabled = keyPoints == null && !isGeneratingKeyPoints,
                        leadingIcon = { Icon(Icons.Rounded.ListAlt, null, modifier = Modifier.size(20.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("📄  Add to Document") },
                        onClick = {
                            // TODO: Add response to document/notes
                            showOptionsMenu = false
                        },
                        leadingIcon = { Icon(Icons.Rounded.NoteAdd, null, modifier = Modifier.size(20.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("📊  Generate Diagram/Visual") },
                        onClick = {
                            // TODO: Generate diagram from this response
                            showOptionsMenu = false
                        },
                        leadingIcon = { Icon(Icons.Rounded.AutoGraph, null, modifier = Modifier.size(20.dp)) }
                    )
                }
            }

            // TTS button
            if (isTTSLoaded && !isStreaming) {
                IconButton(
                    onClick = { onSpeak(message.text) },
                    modifier = Modifier
                        .size(28.dp)
                        .padding(start = 12.dp)
                ) {
                    Icon(
                        Icons.Rounded.VolumeUp,
                        "Speak",
                        tint = AccentPink.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
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

// ═══════════════════════════════════════════
// ── Markdown Renderer ──
// ═══════════════════════════════════════════

// ── LaTeX symbol map for rendering Greek/math symbols ──
private val latexSymbolMap = mapOf(
    "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
    "\\epsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η", "\\theta" to "θ",
    "\\iota" to "ι", "\\kappa" to "κ", "\\lambda" to "λ", "\\mu" to "μ",
    "\\nu" to "ν", "\\xi" to "ξ", "\\pi" to "π", "\\rho" to "ρ",
    "\\sigma" to "σ", "\\tau" to "τ", "\\upsilon" to "υ", "\\phi" to "φ",
    "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω",
    "\\Alpha" to "Α", "\\Beta" to "Β", "\\Gamma" to "Γ", "\\Delta" to "Δ",
    "\\Epsilon" to "Ε", "\\Theta" to "Θ", "\\Lambda" to "Λ", "\\Pi" to "Π",
    "\\Sigma" to "Σ", "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω",
    "\\infty" to "∞", "\\sum" to "Σ", "\\prod" to "∏", "\\int" to "∫",
    "\\partial" to "∂", "\\nabla" to "∇", "\\pm" to "±", "\\times" to "×",
    "\\div" to "÷", "\\neq" to "≠", "\\leq" to "≤", "\\geq" to "≥",
    "\\approx" to "≈", "\\equiv" to "≡", "\\forall" to "∀", "\\exists" to "∃",
    "\\in" to "∈", "\\notin" to "∉", "\\subset" to "⊂", "\\supset" to "⊃",
    "\\cup" to "∪", "\\cap" to "∩", "\\emptyset" to "∅", "\\rightarrow" to "→",
    "\\leftarrow" to "←", "\\Rightarrow" to "⇒", "\\Leftarrow" to "⇐",
    "\\sqrt" to "√", "\\therefore" to "∴", "\\because" to "∵",
    "\\degree" to "°", "\\angle" to "∠", "\\triangle" to "△",
    "\\cdot" to "·", "\\ldots" to "…", "\\dots" to "…"
)

/** Replace LaTeX symbols and \{...\} brace notation with Unicode */
private fun replaceLatexSymbols(text: String): String {
    var result = text
    // Replace \{SYMBOL\} brace notation first (e.g. \{\alpha\} → α)
    // Android ICU regex: use [{}] char class instead of \{ which ICU rejects
    try {
        result = Regex("\\\\[{]\\\\(\\w+)\\\\[}]").replace(result) { match ->
            latexSymbolMap["\\${match.groupValues[1]}"] ?: match.value
        }
    } catch (_: Exception) { /* regex safety — never crash on rendering */ }
    // Replace bare \symbol notation (sorted longest-first to avoid partial matches)
    for ((latex, unicode) in latexSymbolMap.entries.sortedByDescending { it.key.length }) {
        result = result.replace(latex, unicode)
    }
    // Replace $..$ inline math delimiters (just strip the $)
    try {
        result = Regex("\\$([^$]+)\\$").replace(result) { it.groupValues[1] }
    } catch (_: Exception) { /* regex safety */ }
    return result
}

/**
 * Lightweight Markdown renderer for LLM responses.
 * Supports: **bold**, *italic*, `code`, ```code blocks```,
 * # headings, - bullet lists, | tables |, [links](url),
 * LaTeX symbols (\alpha → α), and clickable URLs.
 */
@Composable
private fun MarkdownText(
    markdown: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val processed = replaceLatexSymbols(markdown)
    val lines = processed.lines()
    var i = 0

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // --- Code block ---
            if (trimmed.startsWith("```")) {
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                if (i < lines.size) i++ // skip closing ```
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = codeLines.joinToString("\n"),
                        style = style.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color(0xFF8BE9FD)
                        ),
                        modifier = Modifier.padding(10.dp)
                    )
                }
                continue
            }

            // --- Table (lines starting with |) ---
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                val tableRows = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().let { it.startsWith("|") && it.endsWith("|") }) {
                    tableRows.add(lines[i].trim())
                    i++
                }
                MarkdownTable(tableRows, style, color)
                continue
            }

            // --- Heading ---
            if (trimmed.startsWith("#")) {
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 4)
                val headingText = trimmed.dropWhile { it == '#' }.trim()
                val headingSize = when (level) {
                    1 -> 18.sp
                    2 -> 16.sp
                    3 -> 15.sp
                    else -> 14.sp
                }
                Spacer(Modifier.height(if (level <= 2) 6.dp else 3.dp))
                Text(
                    text = parseInlineMarkdown(headingText, color),
                    style = style.copy(fontSize = headingSize, fontWeight = FontWeight.Bold, color = color),
                )
                i++
                continue
            }

            // --- Bullet list ---
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.matches(Regex("^\\d+\\.\\s.*"))) {
                val bulletText = when {
                    trimmed.startsWith("- ") -> trimmed.removePrefix("- ")
                    trimmed.startsWith("* ") -> trimmed.removePrefix("* ")
                    else -> trimmed.replaceFirst(Regex("^\\d+\\.\\s"), "")
                }
                val bullet = if (trimmed.matches(Regex("^\\d+\\.\\s.*"))) trimmed.substringBefore(" ") else "•"
                Row(modifier = Modifier.padding(start = 8.dp, top = 1.dp, bottom = 1.dp)) {
                    Text(
                        text = "$bullet ",
                        style = style,
                        color = AccentCyan
                    )
                    Text(
                        text = parseInlineMarkdown(bulletText, color),
                        style = style.copy(color = color),
                    )
                }
                i++
                continue
            }

            // --- Horizontal rule ---
            if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = color.copy(alpha = 0.2f)
                )
                i++
                continue
            }

            // --- Empty line ---
            if (trimmed.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                i++
                continue
            }

            // --- Regular paragraph with inline formatting ---
            Text(
                text = parseInlineMarkdown(trimmed, color),
                style = style.copy(color = color),
            )
            i++
        }
    }
}

/** Parse inline markdown: **bold**, *italic*, `code`, ***bold-italic***, [link](url), bare URLs */
private fun parseInlineMarkdown(text: String, baseColor: Color): AnnotatedString {
    return buildAnnotatedString {
        // Set default color for all text so ClickableText/Text inherits it
        pushStyle(SpanStyle(color = baseColor))
        var i = 0
        while (i < text.length) {
            when {
                // [link text](url)
                text[i] == '[' -> {
                    val closeBracket = text.indexOf(']', i + 1)
                    if (closeBracket != -1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                        val closeParen = text.indexOf(')', closeBracket + 2)
                        if (closeParen != -1) {
                            val linkText = text.substring(i + 1, closeBracket)
                            val url = text.substring(closeBracket + 2, closeParen)
                            pushStringAnnotation("URL", url)
                            withStyle(SpanStyle(
                                color = Color(0xFF58A6FF),
                                fontWeight = FontWeight.Medium
                            )) {
                                append(linkText)
                            }
                            pop()
                            i = closeParen + 1
                        } else {
                            append(text[i])
                            i++
                        }
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Bare URL detection: http:// or https://
                i + 7 < text.length && (text.substring(i).startsWith("http://") || text.substring(i).startsWith("https://")) -> {
                    val urlEnd = text.indexOfFirst(i) { it == ' ' || it == '\n' || it == ')' || it == ']' || it == '>' || it == '"' }
                    val end = if (urlEnd == -1) text.length else urlEnd
                    val url = text.substring(i, end).trimEnd('.', ',', ';', ':', '!', '?')
                    pushStringAnnotation("URL", url)
                    withStyle(SpanStyle(
                        color = Color(0xFF58A6FF),
                        fontWeight = FontWeight.Medium
                    )) {
                        append(url)
                    }
                    pop()
                    i += url.length
                }
                // ***bold italic*** or ___bold italic___
                i + 2 < text.length && text.substring(i, i + 3).let { it == "***" || it == "___" } -> {
                    val marker = text.substring(i, i + 3)
                    val end = text.indexOf(marker, i + 3)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 3, end))
                        }
                        i = end + 3
                    } else {
                        append(marker)
                        i += 3
                    }
                }
                // **bold**
                i + 1 < text.length && text.substring(i, i + 2) == "**" -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append("**")
                        i += 2
                    }
                }
                // *italic*
                text[i] == '*' || text[i] == '_' -> {
                    val marker = text[i]
                    val end = text.indexOf(marker, i + 1)
                    if (end != -1 && end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(marker)
                        i++
                    }
                }
                // `inline code`
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end != -1) {
                        withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color(0xFF1A1A2E),
                            color = Color(0xFF8BE9FD)
                        )) {
                            append(" ${text.substring(i + 1, end)} ")
                        }
                        i = end + 1
                    } else {
                        append('`')
                        i++
                    }
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
        pop() // close default color style
    }
}

/** Extension: find first index from start where predicate matches */
private inline fun String.indexOfFirst(start: Int, predicate: (Char) -> Boolean): Int {
    for (idx in start until length) {
        if (predicate(this[idx])) return idx
    }
    return -1
}

/** Render a markdown table with proper grid layout */
@Composable
private fun MarkdownTable(
    rows: List<String>,
    style: androidx.compose.ui.text.TextStyle,
    color: Color
) {
    // Filter out separator rows (|---|---|)
    val dataRows = rows.filter { row ->
        val cells = row.trim('|').split('|').map { it.trim() }
        !cells.all { it.all { c -> c == '-' || c == ':' } }
    }
    if (dataRows.isEmpty()) return

    val allCells = dataRows.map { row ->
        row.trim('|').split('|').map { it.trim() }
    }
    val colCount = allCells.maxOf { it.size }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141425)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(2.dp)) {
            allCells.forEachIndexed { rowIdx, cells ->
                val isHeader = rowIdx == 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isHeader) Modifier.background(AccentCyan.copy(alpha = 0.1f))
                            else Modifier
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    for (col in 0 until colCount) {
                        val cellText = cells.getOrElse(col) { "" }
                        Text(
                            text = if (isHeader) AnnotatedString(cellText) else parseInlineMarkdown(cellText, color),
                            style = style.copy(
                                fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp
                            ),
                            color = if (isHeader) AccentCyan else color,
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                        )
                    }
                }
                if (rowIdx < allCells.size - 1) {
                    HorizontalDivider(color = color.copy(alpha = 0.1f))
                }
            }
        }
    }
}
