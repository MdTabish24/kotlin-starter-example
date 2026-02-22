package com.runanywhere.kotlin_starter_example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
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
import com.runanywhere.kotlin_starter_example.utils.AndroidSTTManager
import com.runanywhere.kotlin_starter_example.utils.AudioFileTranscriber
import com.runanywhere.kotlin_starter_example.utils.PdfExportManager
import com.runanywhere.kotlin_starter_example.utils.PdfQAEntry
import com.runanywhere.kotlin_starter_example.R
import android.provider.OpenableColumns
import android.widget.Toast
import com.runanywhere.sdk.foundation.bridge.extensions.CppBridgeLLM
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.extensions.unloadSTTModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val androidSTT = remember { AndroidSTTManager(context) }
    var liveAmplitude by remember { mutableStateOf(0f) }
    var liveTranscript by remember { mutableStateOf("") }
    var voiceResponseText by remember { mutableStateOf("") }

    // Android built-in TTS — supports Indian 🇮🇳, US 🇺🇸, UK 🇬🇧, AU 🇦🇺 accents (zero download)
    val accentTTS = remember { AccentTTSManager(context) }
    DisposableEffect(Unit) { onDispose { accentTTS.shutdown(); androidSTT.shutdown() } }

    // UI state - only 2 tabs now (Chat, Notes) - Document tab removed
    var selectedTab by remember { mutableIntStateOf(0) }
    var showExplainDialog by remember { mutableStateOf(false) }
    var selectedParagraph by remember { mutableStateOf("") }
    var explanation by remember { mutableStateOf("") }
    var isExplaining by remember { mutableStateOf(false) }
    var saveIndicator by remember { mutableStateOf("") }

    // KV cache token tracker — reset only when approaching context limit
    // Qwen2.5: reset at 5000 tokens (context=8192). SmolLM2-360M: reset at 1200 (context=2048)
    var cumulativeTokens by remember { mutableIntStateOf(0) }

    // Document info for dropdown (not shown in chat)
    var docInfoChunks by remember { mutableIntStateOf(0) }
    var docInfoChars by remember { mutableIntStateOf(0) }
    var docInfoFormat by remember { mutableStateOf("") }
    var showDocInfoDropdown by remember { mutableStateOf(false) }

    // Key Points state — for "Generate Key Points" long press option
    val keyPointsMap = remember { mutableStateMapOf<Long, String>() }
    var generatingKeyPointsFor by remember { mutableStateOf<Long?>(null) }

    // Diagram state — for "Generate Diagram/Visual" long press option
    val diagramMermaidMap = remember { mutableStateMapOf<Long, String>() }
    val diagramBitmapMap = remember { mutableStateMapOf<Long, ByteArray>() }  // cached Mermaid bitmaps
    var generatingDiagramFor by remember { mutableStateOf<Long?>(null) }
    var showDiagramTimestamp by remember { mutableStateOf<Long?>(null) }

    // PDF Document entries — accumulated Q&A pairs for export
    val pdfEntries = remember { mutableStateListOf<PdfQAEntry>() }
    var isGeneratingPdf by remember { mutableStateOf(false) }

    // Audio transcription state
    var isTranscribingAudio by remember { mutableStateOf(false) }
    var audioTranscriptionProgress by remember { mutableFloatStateOf(0f) }

    // Voice conversation job (for cancellation)
    var voiceJob by remember { mutableStateOf<Job?>(null) }

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
        uri?.let { fileUri ->
            scope.launch {
                // ── Detect audio files ──
                val mimeType = context.contentResolver.getType(fileUri)
                val isAudioFile = mimeType?.startsWith("audio/") == true

                if (isAudioFile) {
                    // ━━ AUDIO FILE → WHISPER STT → TEXT ━━
                    // Decode audio file to PCM, transcribe silently using on-device
                    // Whisper model, then save transcribed text and index it like a document.
                    isTranscribingAudio = true
                    audioTranscriptionProgress = 0f

                    try {
                        // Get filename from content resolver
                        val audioName = context.contentResolver.query(
                            fileUri,
                            arrayOf(OpenableColumns.DISPLAY_NAME),
                            null, null, null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: "Audio Recording.mp3"

                        // ── Ensure Whisper STT model is downloaded & loaded ──
                        audioTranscriptionProgress = 0.05f
                        val sttReady = modelService.ensureSTTReady()
                        if (!sttReady) {
                            messages = messages + SessionMessage(
                                "Whisper speech model not available.\nGo to Settings → Download Speech Model first.",
                                isUser = false
                            )
                            return@launch
                        }

                        val transcriber = AudioFileTranscriber(context)
                        val transcribedText = transcriber.transcribe(fileUri) { prog, _ ->
                            audioTranscriptionProgress = prog
                        }

                        if (transcribedText.isNotBlank() &&
                            !transcribedText.startsWith("⚠️") &&
                            transcribedText.length > 10
                        ) {
                            // ── Save & index like a document ──
                            documentName = audioName
                            hasDocument = true

                            SmartDocumentSearch.saveDocumentToFile(
                                transcribedText, context, session.id
                            )
                            val numChunks = SmartDocumentSearch.indexDocument(
                                transcribedText, context
                            )

                            val ext = audioName.substringAfterLast('.', "").uppercase()
                            docInfoFormat = "$ext (Audio)"
                            docInfoChars = transcribedText.length
                            docInfoChunks = numChunks

                            session = session.copy(
                                title = audioName.substringBeforeLast('.'),
                                documentName = audioName,
                                documentContent = null,
                                hasDocument = true
                            )

                            messages = messages + SessionMessage(
                                "\"$audioName\" transcribed & imported!\n" +
                                "${transcribedText.length} characters extracted.\n" +
                                "Ask me anything about this audio!",
                                isUser = false
                            )
                        } else {
                            // STT returned empty or warning
                            val errorText = if (transcribedText.startsWith("⚠️")) transcribedText
                                else "Could not transcribe audio. The file may be empty or in an unsupported format."
                            messages = messages + SessionMessage(errorText, isUser = false)
                        }
                    } catch (e: Exception) {
                        messages = messages + SessionMessage(
                            "Audio transcription failed: ${e.message}",
                            isUser = false
                        )
                    } finally {
                        isTranscribingAudio = false
                        // Unload Whisper STT to free ~75MB native memory
                        try {
                            RunAnywhere.unloadSTTModel()
                        } catch (_: Exception) {}
                        System.gc()
                    }

                } else {
                    // ━━ DOCUMENT FILE (existing flow) ━━
                    val loadingMsg = SessionMessage("Importing & indexing document...", isUser = false)
                    messages = messages + loadingMsg

                    val result = withContext(Dispatchers.IO) {
                        DocumentReader.readDocument(context, fileUri)
                    }

                    // DocumentReader.readDocument already closes TextRecognizer and frees native memory.
                    // DO NOT GC here — it would shrink the native heap's dirty page pool that
                    // llama.cpp needs for compute buffer allocation during generation.

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
                            "\"$name\" loaded. Ask me anything about this document!",
                            isUser = false
                        )
                        messages = messages + successMsg
                    } else {
                        val errorMsg = SessionMessage(
                            "Could not extract text from the file. Make sure it's a supported format (PDF, DOCX, PPTX, PNG, JPG, TXT).",
                            isUser = false
                        )
                        messages = messages + errorMsg
                    }
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
        // ── Critical pre-generation memory snapshot (for SIGABRT diagnosis) ──
        val preGenNativeHeap = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val preGenNativeFree = android.os.Debug.getNativeHeapFreeSize() / (1024 * 1024)
        val preGenNativeTotal = android.os.Debug.getNativeHeapSize() / (1024 * 1024)
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val sysAvail = mi.availMem / (1024 * 1024)
        android.util.Log.d("BridgeGen", "╔═ PRE-GENERATE MEMORY ═══════════════════╗")
        android.util.Log.d("BridgeGen", "║ Native: ${preGenNativeHeap}MB used / ${preGenNativeTotal}MB total / ${preGenNativeFree}MB FREE")
        android.util.Log.d("BridgeGen", "║ System: ${sysAvail}MB available | LowMem=${mi.lowMemory}")
        android.util.Log.d("BridgeGen", "║ Prompt: ${prompt.length} chars | maxTokens=$adaptiveMaxTokens")
        android.util.Log.d("BridgeGen", "╚═════════════════════════════════════════╝")

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
        val nativeHeapMB = LLMPerformanceBooster.getNativeHeapUsageMB()
        val deviceRAM = LLMPerformanceBooster.getDeviceRAM(context)
        val systemAvailMB = LLMPerformanceBooster.getSystemAvailableMemoryMB(context)
        val nativeFreeMB = android.os.Debug.getNativeHeapFreeSize() / (1024 * 1024)
        android.util.Log.d("LLMBoost", "PRE-GEN STATE: deviceRAM=${deviceRAM}MB, nativeHeap=${nativeHeapMB}MB, " +
            "nativeFree=${nativeFreeMB}MB, systemAvail=${systemAvailMB}MB, cumulativeTokens=$cumulativeTokens")

        // ━━ DO NOT GC BEFORE GENERATION ━━
        // CRITICAL: Native heap "dirty pages" (freed but reusable memory from OCR etc.)
        // are what llama.cpp NEEDS to allocate its compute graph buffer (~30-50MB).
        // Aggressive GC + trimMemory returns these pages to the OS kernel,
        // shrinking nativeFree from ~47MB to ~3MB → llama.cpp can't allocate → SIGABRT.
        // Java heap is tiny (~16MB) and irrelevant. Leave native heap alone.
        val isLowRAM = deviceRAM <= 6144

        // ━━ STT/TTS: Using Android built-in — zero native heap ━━
        // No SDK models to unload. SpeechRecognizer + AccentTTSManager are system services.

        // SAFETY NET: switch to SmolLM2-360M if native heap > 75% of device RAM.
        val didSwitchModel = modelService.ensureSafeModelForGeneration(context)

        // ━━ CONDITIONAL KV cache reset ━━
        // Only reset when KV cache is filling up. Full reset (unload→reload)
        // is expensive and can cause memory pressure on 6GB devices.
        //
        // Context sizes (SDK defaults, cannot be configured at runtime):
        //   Qwen2.5-1.5B: context=4096 → KV cache ~112MB
        //   SmolLM2-360M: context=2048 → KV cache ~28MB
        //
        // Thresholds: reset before KV cache fills to ~75% capacity.
        //   Low-RAM (≤6GB) or tiny model: reset at 1200 tokens (~30% of 4096)
        //   Higher-RAM: reset at 2800 tokens (~68% of 4096)
        // First message: cumulativeTokens=0 → NO reset, KV cache is clean.
        val modelMem = (ModelService.getLLMOption(modelService.activeLLMModelId)?.memoryRequirement ?: 400_000_000L) / (1024L * 1024L)
        val isTinyModel = modelMem < 500
        val kvResetThreshold = if (isTinyModel || isLowRAM) 1200 else 2800

        if (!didSwitchModel && cumulativeTokens > kvResetThreshold) {
            android.util.Log.d("LLMBoost", "KV cache needs reset: $cumulativeTokens tokens > $kvResetThreshold threshold")
            modelService.resetLLMContext()
            cumulativeTokens = 0
        } else if (didSwitchModel) {
            cumulativeTokens = 0  // Fresh model loaded — KV cache is already clean
        } else {
            android.util.Log.d("LLMBoost", "KV cache OK: $cumulativeTokens tokens < $kvResetThreshold threshold, skipping reset")
        }
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

                // Track cumulative tokens for KV cache management.
                // KV cache fills with input + output tokens across calls.
                // Rough estimate: 1 token ≈ 4 chars.
                val estimatedInputTokens = safePrompt.length / 4
                val estimatedOutputTokens = fullResponse.length / 4
                cumulativeTokens += estimatedInputTokens + estimatedOutputTokens
                android.util.Log.d("KVTrack", "Tokens this turn: ~${estimatedInputTokens + estimatedOutputTokens}, cumulative: $cumulativeTokens")

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
                // ━━ MUST reset KV cache before key points ━━
                // The previous answer generation filled the KV cache with 500+ tokens.
                // Key points needs its own fresh context window. Without reset,
                // the KV cache overflows → SIGABRT in librac_backend_llamacpp.so.
                android.util.Log.d("KeyPoints", "Resetting KV cache before key points (cumTokens=$cumulativeTokens)")
                modelService.resetLLMContext()
                cumulativeTokens = 0
                LLMPerformanceBooster.boostForInference()

                // Safety: if model got unloaded somehow, abort gracefully
                if (!modelService.isLLMLoaded) {
                    keyPointsMap[message.timestamp] = "⚠️ Model not ready. Try again."
                    return@launch
                }

                val prompt = buildString {
                    append("Summarize the following into concise, numbered key points.\n")
                    append("Only output the key points, nothing else.\n\n")
                    append("Text:\n${message.text}\n\n")
                    append("Key Points:\n1.")
                }

                val result = generateWithBridge(prompt, maxTok = 512, temp = 0.5f) { currentText ->
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // SMART DIAGRAM — built from content, NO LLM call needed
    // Instant, always meaningful, zero syntax errors
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    fun buildSmartDiagram(content: String, keyPointsText: String?): String {
        val emojis = listOf("", "", "", "", "", "", "", "", "", "", "", "", "", "")
        val nodeIds = ('A'..'Z').map { it.toString() }
        val stopWords = setOf(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "to", "for", "of", "in", "on", "at", "by", "with", "from", "and", "or",
            "not", "no", "can", "will", "would", "could", "should", "may", "might",
            "do", "does", "did", "has", "have", "had", "it", "its", "this", "that",
            "these", "those", "they", "them", "their", "also", "as", "which", "who",
            "whom", "where", "when", "how", "what", "than", "then", "each", "every",
            "all", "both", "few", "more", "most", "other", "some", "such", "into",
            "over", "after", "before", "between", "through", "during", "about",
            "using", "used", "based", "allows", "allowing", "use", "uses",
            "involves", "involve", "include", "includes", "including", "called",
            "whether", "new", "well", "like", "one", "two", "three", "make",
            "makes", "made", "making", "help", "helps", "helping", "provide",
            "provides", "providing", "instance", "instances", "process", "only",
            "example", "examples", "type", "set", "way", "case", "refer", "refers",
            "particular", "specific", "given", "known", "without", "within",
            "learn", "learning", "learned", "while", "there", "here", "any",
            "such", "much", "very", "just", "still", "already", "often", "even"
        )

        // Helper: extract meaningful words from text
        fun meaningful(text: String, count: Int = 3): String {
            return text.split(Regex("\\s+"))
                .map { it.replace(Regex("[^\\w]"), "").trim() }
                .filter { it.length >= 3 && it.lowercase() !in stopWords }
                .take(count)
                .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
        }

        // ── Step 1: Extract main topic from first sentence ──
        val firstLine = content.split(Regex("[.!?\\n]"))
            .firstOrNull { it.trim().length > 5 }?.trim() ?: ""
        val mainTopic = meaningful(firstLine, 3)
            .ifBlank { "Topic Overview" }
            .take(25)

        // ── Step 2: Extract key concepts from key points or content ──
        val concepts = mutableListOf<String>()
        val textToParse = if (!keyPointsText.isNullOrBlank()) keyPointsText else content

        // Split into segments: numbered points or sentences
        val segments = if (textToParse.contains(Regex("\\d+\\.\\s"))) {
            textToParse.split(Regex("(?=\\d+\\.?\\d*\\.\\s)"))
                .map { it.replace(Regex("^\\d+\\.?\\d*\\.\\s*"), "").trim() }
                .filter { it.length > 10 }
        } else {
            textToParse.split(Regex("[.!?\\n]"))
                .map { it.trim() }
                .filter { it.length > 15 }
        }

        for (segment in segments.take(10)) {
            val label = meaningful(segment, 3)
            if (label.length >= 4 && label.lowercase() != mainTopic.lowercase()) {
                concepts.add(label.take(25))
            }
        }

        val uniqueConcepts = concepts.distinct().take(7)

        if (uniqueConcepts.isEmpty()) {
            return "graph TD\n    A[$mainTopic] --> B[Key Concepts]"
        }

        // ── Step 3: Build hierarchical Mermaid diagram ──
        val sb = StringBuilder("graph TD\n")
        var nodeIdx = 1 // A (index 0) is main topic

        if (uniqueConcepts.size <= 3) {
            // Simple star: main → each concept
            for (i in uniqueConcepts.indices) {
                val nid = nodeIds[nodeIdx]
                val emoji = emojis.getOrElse(nodeIdx) { "" }
                sb.appendLine("    A[$mainTopic] --> $nid[$emoji${uniqueConcepts[i]}]")
                nodeIdx++
            }
        } else {
            // Grouped hierarchy for 4+ concepts — creates 2-3 level tree
            // Every 3 concepts: first is parent, next 1-2 are children
            var i = 0
            while (i < uniqueConcepts.size) {
                val parentNid = nodeIds[nodeIdx]
                val parentEmoji = emojis.getOrElse(nodeIdx) { "" }
                sb.appendLine("    A[$mainTopic] --> $parentNid[$parentEmoji${uniqueConcepts[i]}]")
                nodeIdx++

                // Attach next 1-2 concepts as children of this parent
                val remainAfterThis = uniqueConcepts.size - i - 1
                val childCount = if (remainAfterThis > 2) 2 else minOf(remainAfterThis, 2)
                for (c in 1..childCount) {
                    if (i + c < uniqueConcepts.size) {
                        val childNid = nodeIds[nodeIdx]
                        val childEmoji = emojis.getOrElse(nodeIdx) { "" }
                        sb.appendLine("    $parentNid --> $childNid[$childEmoji${uniqueConcepts[i + c]}]")
                        nodeIdx++
                    }
                }
                i += 1 + childCount
            }
        }

        return sb.toString().trimEnd()
    }

    fun generateDiagram(message: SessionMessage) {
        if (generatingDiagramFor != null) return
        generatingDiagramFor = message.timestamp

        // ━━ NO LLM CALL — build diagram instantly from existing content ━━
        scope.launch {
            try {
                val content = message.text
                val keyPoints = keyPointsMap[message.timestamp]

                val mermaidCode = buildSmartDiagram(content, keyPoints)
                diagramMermaidMap[message.timestamp] = mermaidCode
                showDiagramTimestamp = message.timestamp

                android.util.Log.d("Diagram", "✅ Smart diagram (instant):\n$mermaidCode")
            } catch (e: Exception) {
                android.util.Log.e("Diagram", "❌ Diagram error: ${e.message}", e)
                diagramMermaidMap[message.timestamp] = "graph TD\n    A[Topic] --> B[Details]"
                showDiagramTimestamp = message.timestamp
            } finally {
                generatingDiagramFor = null
            }
        }
    }

    fun speakText(text: String) {
        if (isSpeaking) return
        isSpeaking = true
        scope.launch {
            try {
                if (accentTTS.isReady) {
                    // 🇮🇳 Android TTS — supports Indian & other accents, no download, zero native heap
                    accentTTS.speakAndWait(text.take(1000), prefs.ttsAccent, prefs.ttsSpeed, prefs.ttsPitch)
                }
            } catch (_: Exception) {
            } finally {
                isSpeaking = false
            }
        }
    }

    // ── Nova-style Voice Pipeline: Android STT → LLM → AccentTTS ──
    // No SDK models needed — SpeechRecognizer handles recording + VAD + transcription
    fun startVoiceSession() {
        if (!hasAudioPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (voiceState != VoiceSessionState.IDLE) return
        if (!modelService.isLLMLoaded) return

        if (!androidSTT.isAvailable) {
            messages = messages + SessionMessage("Speech recognition not available on this device. Install Google app.", isUser = false)
            return
        }

        voiceState = VoiceSessionState.LISTENING
        liveTranscript = ""
        liveAmplitude = 0f

        voiceJob = scope.launch {
            try {
                // ━━ Continuous conversation loop ━━
                // LISTEN → THINK → SPEAK → LISTEN → ... until user taps circle
                while (voiceState != VoiceSessionState.IDLE) {
                    voiceState = VoiceSessionState.LISTENING
                    liveTranscript = ""
                    liveAmplitude = 0f
                    voiceResponseText = ""

                    // ── LISTEN with 500ms VAD for fast response ──
                    val transcribedText = withContext(Dispatchers.Main) {
                        androidSTT.listenAndTranscribe(
                            silenceMs = 500L,
                            onPartialResult = { partial ->
                                liveTranscript = partial
                            },
                            onAmplitude = { amp ->
                                liveAmplitude = amp
                            }
                        )
                    }

                    liveAmplitude = 0f
                    if (voiceState == VoiceSessionState.IDLE) break

                    // Skip empty transcriptions (noise/silence)
                    if (transcribedText.isBlank()) continue

                    liveTranscript = transcribedText

                    // Add user message to chat
                    val userMsg = SessionMessage(transcribedText, isUser = true)
                    messages = messages + userMsg
                    try { listState.animateScrollToItem(maxOf(0, messages.size - 1)) } catch (_: Exception) {}

                    // ── THINK + SPEAK (overlapped — TTS streams as LLM generates) ──
                    voiceState = VoiceSessionState.THINKING
                    isGenerating = true
                    streamingResponse = ""

                    val voiceModelMem = (ModelService.getLLMOption(modelService.activeLLMModelId)?.memoryRequirement ?: 400_000_000L) / (1024L * 1024L)

                    // ⚡ Compact voice prompt — minimal tokens for fast prefill
                    val contextPrompt = buildString {
                        if (hasDocument && SmartDocumentSearch.isDocumentIndexed()) {
                            append("Reply in 1-2 sentences using facts from the document.\n\n")
                            val relevantContext = SmartDocumentSearch.getRelevantContext(
                                transcribedText,
                                minOf(LLMPerformanceBooster.getRecommendedDocLimit(context, voiceModelMem), 600)
                            )
                            if (relevantContext != null) {
                                append("Doc: $relevantContext\n\n")
                            }
                            append("Q: $transcribedText\nA:")
                        } else {
                            append("Reply in 1-2 sentences. Be helpful and direct.\n\nQ: $transcribedText\nA:")
                        }
                    }

                    boostForLLM()

                    if (!hasEnoughMemoryForLLM()) {
                        messages = messages + SessionMessage("Low memory. Try a smaller model.", isUser = false)
                        isGenerating = false
                        break
                    }

                    // Safety cap
                    val voiceMaxPrompt = LLMPerformanceBooster.getMaxSafePromptLength(context, voiceModelMem)
                    val voiceSafePrompt = if (contextPrompt.length > voiceMaxPrompt) {
                        val truncated = contextPrompt.take(voiceMaxPrompt)
                        val lastNewline = truncated.lastIndexOf('\n')
                        val clean = if (lastNewline > voiceMaxPrompt / 2) truncated.substring(0, lastNewline) else truncated
                        if (clean.endsWith("A:")) clean else "$clean\n\nA:"
                    } else contextPrompt

                    // ── Streaming TTS: speak first sentence while LLM keeps generating ──
                    var firstSentSpoken = false
                    var firstSentEnd = 0

                    // ⚡ maxTok=60 → 1-2 sentence voice reply, ~15-30s generation
                    var fullResponse = try {
                        streamingResponse = "…"
                        val bridgeResult = generateWithBridge(
                            voiceSafePrompt,
                            maxTok = 60,
                            temp = 0.7f
                        ) { currentText ->
                            streamingResponse = currentText

                            // ── Detect first complete sentence → start TTS immediately ──
                            if (!firstSentSpoken && currentText.length > 10) {
                                for (i in 10 until currentText.length) {
                                    if (currentText[i] in ".!?" &&
                                        (i + 1 >= currentText.length || currentText[i + 1].isWhitespace())) {
                                        val firstSent = currentText.substring(0, i + 1).trim()
                                        if (firstSent.isNotBlank() && accentTTS.isReady) {
                                            accentTTS.speakAsync(firstSent, prefs.ttsAccent, prefs.ttsSpeed, prefs.ttsPitch)
                                            firstSentSpoken = true
                                            firstSentEnd = i + 1
                                            voiceState = VoiceSessionState.SPEAKING
                                            isSpeaking = true
                                        }
                                        break
                                    }
                                }
                            }
                        }
                        bridgeResult.fullText
                    } catch (e: Exception) {
                        android.util.Log.e("VoiceChat", "Generation: ${e.message}")
                        // Recover streamed text even if coroutine was cancelled
                        val streamed = streamingResponse
                        if (streamed.isNotBlank() && streamed != "…") streamed else ""
                    }

                    if (voiceState == VoiceSessionState.IDLE) break
                    if (fullResponse.isBlank()) fullResponse = "Sorry, I couldn't catch that."

                    // Track KV cache tokens
                    val voiceInputTokens = voiceSafePrompt.length / 4
                    val voiceOutputTokens = fullResponse.length / 4
                    cumulativeTokens += voiceInputTokens + voiceOutputTokens

                    val aiMsg = SessionMessage(fullResponse.trim(), isUser = false)
                    messages = messages + aiMsg
                    voiceResponseText = fullResponse.trim()
                    streamingResponse = ""
                    isGenerating = false

                    // Smart notes
                    if (smartNotesEnabled && fullResponse.isNotBlank()) {
                        val bullet = "• Q: ${transcribedText.take(60)}${if (transcribedText.length > 60) "…" else ""}\n  → ${fullResponse.take(200).trim()}\n\n"
                        notes += bullet
                    }

                    try { listState.animateScrollToItem(maxOf(0, messages.size - 1)) } catch (_: Exception) {}
                    if (voiceState == VoiceSessionState.IDLE) break

                    // ── Speak remaining text (after first sentence) ──
                    voiceState = VoiceSessionState.SPEAKING
                    isSpeaking = true
                    try {
                        if (accentTTS.isReady) {
                            val trimmedFull = fullResponse.trim()
                            if (firstSentSpoken && trimmedFull.length > firstSentEnd) {
                                // Queue remainder after first sentence
                                val remainder = trimmedFull.substring(firstSentEnd).trim()
                                if (remainder.isNotBlank()) {
                                    accentTTS.speakQueued(remainder, prefs.ttsAccent, prefs.ttsSpeed, prefs.ttsPitch)
                                }
                            } else if (!firstSentSpoken) {
                                // No sentence boundary found during streaming — speak entire response now
                                accentTTS.speakAsync(trimmedFull.take(500), prefs.ttsAccent, prefs.ttsSpeed, prefs.ttsPitch)
                            }
                            // Wait for all TTS speech to finish
                            accentTTS.waitUntilDone()
                        }
                    } catch (_: Exception) {
                    } finally {
                        isSpeaking = false
                    }

                    if (voiceState == VoiceSessionState.IDLE) break

                    // Small breath before next listen cycle
                    liveTranscript = ""
                    voiceResponseText = ""
                    delay(500)
                }
            } catch (e: CancellationException) {
                // Job cancelled by stopVoiceSession()
            } catch (e: Exception) {
                messages = messages + SessionMessage("Voice error: ${e.message}", isUser = false)
            } finally {
                voiceState = VoiceSessionState.IDLE
                isGenerating = false
                streamingResponse = ""
                liveTranscript = ""
                liveAmplitude = 0f
                isSpeaking = false
                voiceResponseText = ""
                voiceJob = null
            }
        }
    }

    // Stop the entire voice conversation
    fun stopVoiceSession() {
        voiceState = VoiceSessionState.IDLE
        androidSTT.cancel()
        accentTTS.stop()
        liveTranscript = ""
        liveAmplitude = 0f
        isGenerating = false
        streamingResponse = ""
        isSpeaking = false
        voiceResponseText = ""
        voiceJob?.cancel()
        voiceJob = null
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
            }
        }
    }

    fun generateSummary() {
        if (!hasDocument || !SmartDocumentSearch.isDocumentIndexed() || isGenerating || !modelService.isLLMLoaded) return
        isGenerating = true
        streamingResponse = ""

        // Add a user message to chat showing what we're doing
        val userMsg = SessionMessage("Summarize: ${documentName ?: "document"}", isUser = true)
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
            }
        }
    }

    // ── UI ──

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
    Image(
        painter = painterResource(id = R.drawable.app_background),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    )
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
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
                            "image/webp",
                            // Audio files — transcribed via STT
                            "audio/mpeg",
                            "audio/wav",
                            "audio/x-wav",
                            "audio/mp4",
                            "audio/ogg",
                            "audio/3gpp",
                            "audio/amr",
                            "audio/flac",
                            "audio/*"
                        ))
                    }) {
                        Icon(Icons.Rounded.UploadFile, "Import", tint = AccentCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = GlassWhite)
            )
        },
        bottomBar = {
            // Input bar (visible in Chat tab)
            if (selectedTab == 0 && modelService.isLLMLoaded) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = GlassWhite,
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // ── Voice Pipeline Button (the main mic) ──
                            VoicePipelineButton(
                                voiceState = voiceState,
                                isVoiceReady = androidSTT.isAvailable && modelService.isLLMLoaded,
                                amplitude = liveAmplitude,
                                onTap = {
                                    if (voiceState == VoiceSessionState.IDLE) {
                                        startVoiceSession()
                                    }
                                    // When active, the fullscreen overlay handles stopping
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
                                    focusedContainerColor = Color.White.copy(alpha = 0.10f),
                                    unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                shape = RoundedCornerShape(20.dp),
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
        containerColor = Color.Transparent
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
                containerColor = GlassWhite,
                contentColor = AccentCyan,
                divider = {
                    HorizontalDivider(color = TextMuted.copy(alpha = 0.15f))
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Chat") },
                    selectedContentColor = AccentCyan,
                    unselectedContentColor = TextMuted
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Preview")
                            if (pdfEntries.isNotEmpty()) {
                                Spacer(Modifier.width(4.dp))
                                Badge(containerColor = AccentCyan) {
                                    Text("${pdfEntries.size}", color = Color.White, fontSize = 10.sp)
                                }
                            }
                        }
                    },
                    selectedContentColor = AccentCyan,
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
                                "Document Info",
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
                    isTTSLoaded = accentTTS.isReady,
                    listState = listState,
                    onSpeak = { speakText(it) },
                    keyPointsMap = keyPointsMap,
                    generatingKeyPointsFor = generatingKeyPointsFor,
                    onGenerateKeyPoints = { msg -> generateKeyPoints(msg) },
                    diagramMermaidMap = diagramMermaidMap,
                    generatingDiagramFor = generatingDiagramFor,
                    onGenerateDiagram = { msg -> generateDiagram(msg) },
                    onShowDiagram = { ts -> showDiagramTimestamp = ts },
                    onAddToDocument = { aiMsg ->
                        // Find the user question right before this AI answer
                        val idx = messages.indexOf(aiMsg)
                        val userQuestion = if (idx > 0) messages[idx - 1].text else "Question"
                        val entry = PdfQAEntry(
                            question = userQuestion,
                            answer = aiMsg.text,
                            keyPoints = keyPointsMap[aiMsg.timestamp],
                            diagramCode = diagramMermaidMap[aiMsg.timestamp],
                            timestamp = aiMsg.timestamp
                        )
                        // Don't add duplicates
                        if (pdfEntries.none { it.timestamp == aiMsg.timestamp }) {
                            pdfEntries.add(entry)
                            Toast.makeText(context, "✅ Added to document (${pdfEntries.size} entries)", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Already added!", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                1 -> PreviewTabContent(
                    pdfEntries = pdfEntries,
                    documentName = documentName,
                    onRemoveEntry = { entry -> pdfEntries.remove(entry) },
                    onClearAll = { pdfEntries.clear() },
                    isGeneratingPdf = isGeneratingPdf,
                    onDownloadPdf = {
                        if (pdfEntries.isNotEmpty() && !isGeneratingPdf) {
                            isGeneratingPdf = true
                            scope.launch {
                                try {
                                    // Step 1: Use pre-captured bitmaps from diagram dialog
                                    val diagramImages = mutableMapOf<Long, ByteArray>()
                                    for (entry in pdfEntries.toList()) {
                                        if (!entry.diagramCode.isNullOrBlank()) {
                                            // Use cached bitmap if available (captured from dialog)
                                            val cached = diagramBitmapMap[entry.timestamp]
                                            if (cached != null) {
                                                diagramImages[entry.timestamp] = cached
                                            } else {
                                                // Fallback: try off-screen capture
                                                val bytes = PdfExportManager.captureMermaidBitmap(context, entry.diagramCode)
                                                if (bytes != null) {
                                                    diagramImages[entry.timestamp] = bytes
                                                }
                                            }
                                        }
                                    }
                                    // Step 2: Generate PDF (IO thread)
                                    val pdfFile = withContext(Dispatchers.IO) {
                                        PdfExportManager.generatePdf(
                                            context = context,
                                            entries = pdfEntries.toList(),
                                            diagramImages = diagramImages,
                                            documentName = documentName
                                        )
                                    }
                                    // Step 3: Share
                                    if (pdfFile != null) {
                                        PdfExportManager.sharePdf(context, pdfFile)
                                    } else {
                                        Toast.makeText(context, "❌ PDF generation failed", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "❌ Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isGeneratingPdf = false
                                }
                            }
                        }
                    }
                )
            }
        }
    }
    } // Close glassmorphism background Box

    // ── Explain Dialog ──
    if (showExplainDialog) {
        AlertDialog(
            onDismissRequest = { showExplainDialog = false },
            title = {
                Text("AI Explanation", style = MaterialTheme.typography.titleMedium)
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
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f))
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
            containerColor = Color.White.copy(alpha = 0.10f),
            shape = RoundedCornerShape(24.dp)
        )
    }

    // ── Fullscreen Voice Conversation Overlay ──
    if (voiceState != VoiceSessionState.IDLE) {
        Dialog(
            onDismissRequest = { stopVoiceSession() },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            val stateColor = when (voiceState) {
                VoiceSessionState.LISTENING -> Color(0xFF38BDF8)   // Light Blue
                VoiceSessionState.THINKING -> Color(0xFF22D3EE)    // Cyan
                VoiceSessionState.SPEAKING -> AccentGreen
                else -> TextMuted
            }
            val stateText = when (voiceState) {
                VoiceSessionState.LISTENING -> "Listening..."
                VoiceSessionState.THINKING -> "Thinking..."
                VoiceSessionState.SPEAKING -> "Speaking..."
                else -> ""
            }
            val stateIcon = when (voiceState) {
                VoiceSessionState.LISTENING -> Icons.Rounded.Mic
                VoiceSessionState.THINKING -> Icons.Rounded.Psychology
                VoiceSessionState.SPEAKING -> Icons.Rounded.VolumeUp
                else -> Icons.Rounded.Mic
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xDD1A1040),
                                Color(0xF00E0820)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    // State label
                    Text(
                        stateText,
                        color = stateColor,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(32.dp))

                    // ── Glowing Ring Circle (like reference image) ──
                    val voiceTransition = rememberInfiniteTransition(label = "voice_glow")

                    // Rotation animation — ring rotates continuously
                    val rotation by voiceTransition.animateFloat(
                        initialValue = 0f, targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            tween(4000, easing = LinearEasing)
                        ), label = "rotation"
                    )

                    // Breathing scale — ring gently pulses
                    val breathScale by voiceTransition.animateFloat(
                        initialValue = 0.95f, targetValue = 1.08f,
                        animationSpec = infiniteRepeatable(
                            tween(1500, easing = FastOutSlowInEasing),
                            RepeatMode.Reverse
                        ), label = "breathe"
                    )

                    // Glow pulse alpha
                    val glowAlpha by voiceTransition.animateFloat(
                        initialValue = 0.3f, targetValue = 0.7f,
                        animationSpec = infiniteRepeatable(
                            tween(1200, easing = FastOutSlowInEasing),
                            RepeatMode.Reverse
                        ), label = "glow"
                    )

                    // Second ring offset rotation
                    val rotation2 by voiceTransition.animateFloat(
                        initialValue = 360f, targetValue = 0f,
                        animationSpec = infiniteRepeatable(
                            tween(6000, easing = LinearEasing)
                        ), label = "rotation2"
                    )

                    // Amplitude boost for listening
                    val ampBoost = if (voiceState == VoiceSessionState.LISTENING) 1f + liveAmplitude * 0.4f else 1f

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(220.dp)
                            .clickable { stopVoiceSession() }
                    ) {
                        // Outer glow halo
                        Canvas(
                            modifier = Modifier
                                .size(220.dp)
                                .scale(breathScale * ampBoost)
                                .graphicsLayer { alpha = glowAlpha }
                        ) {
                            drawCircle(
                                color = stateColor.copy(alpha = 0.15f),
                                radius = size.minDimension / 2
                            )
                        }

                        // Primary glowing ring — rotates
                        Canvas(
                            modifier = Modifier
                                .size(180.dp)
                                .scale(breathScale * ampBoost)
                        ) {
                            rotate(rotation) {
                                drawArc(
                                    brush = Brush.sweepGradient(
                                        0f to stateColor.copy(alpha = 0.0f),
                                        0.3f to stateColor.copy(alpha = 0.9f),
                                        0.6f to Color.White.copy(alpha = 0.95f),
                                        0.8f to stateColor.copy(alpha = 0.9f),
                                        1f to stateColor.copy(alpha = 0.0f)
                                    ),
                                    startAngle = 0f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                        }

                        // Secondary subtle ring — counter-rotates
                        Canvas(
                            modifier = Modifier
                                .size(195.dp)
                                .scale(breathScale)
                        ) {
                            rotate(rotation2) {
                                drawArc(
                                    brush = Brush.sweepGradient(
                                        0f to stateColor.copy(alpha = 0.0f),
                                        0.4f to stateColor.copy(alpha = 0.4f),
                                        0.7f to Color.White.copy(alpha = 0.5f),
                                        1f to stateColor.copy(alpha = 0.0f)
                                    ),
                                    startAngle = 0f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                        }

                        // Inner dark circle with icon
                        Box(
                            modifier = Modifier
                                .size(130.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0A0E1A).copy(alpha = 0.85f))
                                .border(
                                    width = 1.5.dp,
                                    brush = Brush.linearGradient(
                                        listOf(stateColor.copy(alpha = 0.5f), Color.White.copy(alpha = 0.2f))
                                    ),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                stateIcon,
                                contentDescription = "Tap to stop",
                                tint = stateColor,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(32.dp))

                    // Live transcript
                    if (liveTranscript.isNotBlank()) {
                        Text(
                            "\u201C$liveTranscript\u201D",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }

                    // Streaming response preview during thinking
                    if (voiceState == VoiceSessionState.THINKING && streamingResponse.isNotBlank() && streamingResponse != "...") {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            streamingResponse.take(120) + if (streamingResponse.length > 120) "..." else "",
                            color = AccentCyan.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }

                    // Show AI response text while speaking
                    if (voiceState == VoiceSessionState.SPEAKING && voiceResponseText.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "\"${voiceResponseText.take(200)}${if (voiceResponseText.length > 200) "..." else ""}\"",
                            color = AccentGreen.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    Text(
                        "Tap circle to stop",
                        color = Color.White.copy(alpha = 0.3f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    // ── Audio Transcription Loading Dialog ──
    if (isTranscribingAudio) {
        Dialog(
            onDismissRequest = { /* Cannot dismiss during transcription */ },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.10f)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Animated mic icon
                    val infiniteTransition = rememberInfiniteTransition(label = "stt")
                    val pulse by infiniteTransition.animateFloat(
                        initialValue = 0.9f,
                        targetValue = 1.15f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulse"
                    )
                    Icon(
                        Icons.Rounded.Mic,
                        contentDescription = "Transcribing",
                        tint = AccentCyan,
                        modifier = Modifier
                            .size(48.dp)
                            .scale(pulse)
                    )

                    Spacer(Modifier.height(16.dp))

                    Text(
                        "Transcribing Audio...",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(12.dp))

                    // Progress bar
                    LinearProgressIndicator(
                        progress = { audioTranscriptionProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = AccentCyan,
                        trackColor = TextMuted.copy(alpha = 0.2f)
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        "${(audioTranscriptionProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.titleLarge,
                        color = AccentCyan,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        "\uD83D\uDD0A Audio is playing for transcription.\nPlease wait in a quiet environment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }

    // ── Diagram Dialog (fullscreen WebView with Mermaid.js) ──
    if (showDiagramTimestamp != null) {
        val mermaidCode = diagramMermaidMap[showDiagramTimestamp]
        val ts = showDiagramTimestamp!!
        if (mermaidCode != null) {
            MermaidDiagramDialog(
                mermaidCode = mermaidCode,
                onDismiss = { showDiagramTimestamp = null },
                onBitmapCaptured = { bytes ->
                    diagramBitmapMap[ts] = bytes
                    android.util.Log.d("Diagram", "Bitmap captured from dialog: ${bytes.size} bytes")
                }
            )
        }
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
        VoiceSessionState.LISTENING -> "Listening... auto-stops when you pause"
        VoiceSessionState.TRANSCRIBING -> "Transcribing your speech..."
        VoiceSessionState.THINKING -> "AI is thinking..."
        VoiceSessionState.SPEAKING -> "Speaking with accent..."
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
    onGenerateKeyPoints: (SessionMessage) -> Unit,
    diagramMermaidMap: Map<Long, String>,
    generatingDiagramFor: Long?,
    onGenerateDiagram: (SessionMessage) -> Unit,
    onShowDiagram: (Long) -> Unit,
    onAddToDocument: ((SessionMessage) -> Unit)? = null
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
                onGenerateKeyPoints = { onGenerateKeyPoints(message) },
                hasDiagram = diagramMermaidMap.containsKey(message.timestamp),
                isGeneratingDiagram = generatingDiagramFor == message.timestamp,
                onGenerateDiagram = { onGenerateDiagram(message) },
                onShowDiagram = { onShowDiagram(message.timestamp) },
                onAddToDocument = if (!message.isUser) {{ onAddToDocument?.invoke(message) }} else null
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
    onGenerateKeyPoints: (() -> Unit)? = null,
    hasDiagram: Boolean = false,
    isGeneratingDiagram: Boolean = false,
    onGenerateDiagram: (() -> Unit)? = null,
    onShowDiagram: (() -> Unit)? = null,
    onAddToDocument: (() -> Unit)? = null
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
                colors = CardDefaults.cardColors(containerColor = AccentCyan)
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
                Image(
                    painter = painterResource(id = R.drawable.chat_logo),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "AI",
                    style = MaterialTheme.typography.labelMedium,
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
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        MarkdownText(
                            markdown = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
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
                                "Summarized Key Points:",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = AccentCyan
                            )
                            Spacer(Modifier.height(4.dp))
                            MarkdownText(
                                markdown = keyPoints,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary.copy(alpha = 0.9f)
                            )
                            if (isGeneratingKeyPoints) {
                                Text("▊", color = AccentCyan, style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        // ── Diagram Section (inside the same card) ──
                        if (isGeneratingDiagram) {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(color = AccentPink.copy(alpha = 0.2f))
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = AccentPink
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Generating diagram…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AccentPink.copy(alpha = 0.7f)
                                )
                            }
                        }

                        if (hasDiagram && !isGeneratingDiagram) {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(color = AccentPink.copy(alpha = 0.2f))
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { onShowDiagram?.invoke() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = AccentPink
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AccentPink.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Rounded.AutoGraph, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("View Diagram", style = MaterialTheme.typography.labelMedium)
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
                        text = { Text("Copy") },
                        onClick = {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(message.text))
                            showOptionsMenu = false
                        },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(20.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text(if (keyPoints != null) "Key Points Generated" else "Generate Key Points") },
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
                        text = { Text("Add to Document") },
                        onClick = {
                            onAddToDocument?.invoke()
                            showOptionsMenu = false
                        },
                        leadingIcon = { Icon(Icons.Rounded.NoteAdd, null, modifier = Modifier.size(20.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text(
                            if (hasDiagram) "View Diagram"
                            else if (isGeneratingDiagram) "Generating Diagram..."
                            else "Generate Diagram/Visual"
                        ) },
                        onClick = {
                            if (hasDiagram) {
                                onShowDiagram?.invoke()
                            } else if (!isGeneratingDiagram) {
                                onGenerateDiagram?.invoke()
                            }
                            showOptionsMenu = false
                        },
                        enabled = !isGeneratingDiagram,
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
// ── Preview Tab (PDF Document Preview) ──
// ═══════════════════════════════════════════

@Composable
private fun PreviewTabContent(
    pdfEntries: List<PdfQAEntry>,
    documentName: String?,
    onRemoveEntry: (PdfQAEntry) -> Unit,
    onClearAll: () -> Unit,
    onDownloadPdf: () -> Unit,
    isGeneratingPdf: Boolean = false
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // ── Action bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Document Preview",
                    style = MaterialTheme.typography.titleSmall,
                    color = AccentCyan
                )
                Text(
                    if (pdfEntries.isEmpty()) "Long-press AI answers → 'Add to Document'"
                    else "${pdfEntries.size} Q&A entries ready",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
            if (pdfEntries.isNotEmpty()) {
                IconButton(onClick = onClearAll, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.DeleteSweep,
                        "Clear all",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = onDownloadPdf,
                    enabled = !isGeneratingPdf,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    if (isGeneratingPdf) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Generating…", color = Color.White, fontSize = 11.sp)
                    } else {
                        Icon(Icons.Rounded.Download, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("PDF", fontSize = 12.sp)
                    }
                }
            }
        }

        if (pdfEntries.isEmpty()) {
            // ── Empty state ──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Rounded.Description,
                    null,
                    modifier = Modifier.size(56.dp),
                    tint = TextMuted.copy(alpha = 0.3f)
                )
                Spacer(Modifier.height(16.dp))
                Text("No entries yet", style = MaterialTheme.typography.titleMedium, color = TextMuted)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Long-press any AI answer in Chat tab\nand tap 'Add to Document'",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted.copy(alpha = 0.7f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            // ── Paper-like PDF preview ──
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.06f)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // PDF Title header
                item {
                    Card(
                        shape = RoundedCornerShape(6.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                documentName?.let { "Study Notes: $it" } ?: "YouLearn Study Notes",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = AccentCyan,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(Modifier.height(4.dp))
                            val dateStr = remember {
                                java.text.SimpleDateFormat("MMMM dd, yyyy", java.util.Locale.US)
                                    .format(java.util.Date())
                            }
                            Text(
                                "Generated on $dateStr",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF78829A)
                            )
                            Text(
                                "${pdfEntries.size} Q&A entries",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF78829A)
                            )
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(color = Color(0xFF00BCD4), thickness = 2.dp)
                        }
                    }
                }

                // Q&A entries (paper-style cards)
                items(pdfEntries.size) { index ->
                    PdfPreviewEntryCard(
                        index = index + 1,
                        entry = pdfEntries[index],
                        onRemove = { onRemoveEntry(pdfEntries[index]) }
                    )
                }

                // Footer
                item {
                    Card(
                        shape = RoundedCornerShape(6.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            HorizontalDivider(color = Color(0xFF00BCD4), thickness = 1.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Generated by YouLearn AI • On-Device Learning Assistant",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF78829A)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPreviewEntryCard(
    index: Int,
    entry: PdfQAEntry,
    onRemove: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Header: Q number + remove ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Q$index.",
                    style = MaterialTheme.typography.titleSmall,
                    color = AccentCyan,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        "Remove",
                        tint = Color(0xFF78829A),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // ── Question box (violet background, like PDF) ──
            Card(
                shape = RoundedCornerShape(6.dp),
                colors = CardDefaults.cardColors(containerColor = AccentCyan.copy(alpha = 0.10f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan.copy(alpha = 0.3f))
            ) {
                Text(
                    entry.question,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF1F5F9)
                )
            }

            Spacer(Modifier.height(10.dp))

            // ── Answer label + full text ──
            Text(
                "Answer:",
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF00BCD4),
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))

            MarkdownText(
                markdown = entry.answer,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFF1F5F9)
            )

            // ── Key Points (if any) ──
            if (!entry.keyPoints.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Key Points",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFFF59E0B),
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Card(
                    shape = RoundedCornerShape(6.dp),
                    colors = CardDefaults.cardColors(containerColor = NoteAmber.copy(alpha = 0.10f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        entry.keyPoints.lines()
                            .filter { it.trim().isNotEmpty() }
                            .forEach { line ->
                                Text(
                                    line.trim(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFF1F5F9),
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }
                    }
                }
            }

            // ── Diagram (if any) — rendered via WebView ──
            if (!entry.diagramCode.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Visual Diagram",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF00BCD4),
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Card(
                    shape = RoundedCornerShape(6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00BCD4))
                ) {
                    val diagramHtml = remember(entry.diagramCode) {
                        buildPreviewDiagramHtml(entry.diagramCode)
                    }
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                settings.setSupportZoom(true)
                                isVerticalScrollBarEnabled = true
                                isHorizontalScrollBarEnabled = true
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                webViewClient = WebViewClient()
                                loadDataWithBaseURL(
                                    "https://cdn.jsdelivr.net",
                                    diagramHtml,
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                    )
                }
            }
        }
    }
}

/** Build HTML for inline Mermaid diagram preview (matches PDF capture styling) */
private fun buildPreviewDiagramHtml(mermaidCode: String): String {
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
            <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }
                body { background: #0F1629; padding: 12px; display: flex; justify-content: center; align-items: flex-start; font-family: 'Segoe UI', system-ui, sans-serif; min-height: 100vh; }
                .mermaid { width: 100%; }
                .mermaid svg { width: 100% !important; height: auto !important; min-height: 300px; }
                .node rect, .node polygon {
                    fill: rgba(56,189,248,0.12) !important; stroke: #38BDF8 !important;
                    stroke-width: 2px !important; rx: 10px !important;
                    filter: drop-shadow(0px 2px 4px rgba(0,0,0,0.3)) !important;
                }
                .nodeLabel { color: #F1F5F9 !important; font-size: 14px !important; font-weight: 600 !important; }
                .edgePath .path { stroke: #94A3B8 !important; stroke-width: 2px !important; }
                marker path { fill: #94A3B8 !important; }
                .error-text { color: #E53E3E; font-family: monospace; font-size: 13px; padding: 16px; text-align: center; }
            </style>
        </head>
        <body>
            <div class="mermaid">${mermaidCode}</div>
            <script src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"></script>
            <script>
                mermaid.initialize({
                    startOnLoad: true, theme: 'dark',
                    themeVariables: { primaryColor: 'rgba(56,189,248,0.12)', primaryTextColor: '#F1F5F9', primaryBorderColor: '#38BDF8', lineColor: '#94A3B8', fontSize: '14px' },
                    flowchart: { useMaxWidth: true, htmlLabels: true, curve: 'basis', nodeSpacing: 40, rankSpacing: 50 }
                });
                mermaid.parseError = function(err, hash) {
                    var msg = (typeof err === 'string') ? err : (err.message || err.str || JSON.stringify(err));
                    document.querySelector('.mermaid').innerHTML = '<p class="error-text">Diagram error: ' + msg + '</p>';
                };
            </script>
        </body>
        </html>
    """.trimIndent()
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
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = codeLines.joinToString("\n"),
                        style = style.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = AccentCyan
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
                            background = Color.White.copy(alpha = 0.10f),
                            color = AccentCyan
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
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(2.dp)) {
            allCells.forEachIndexed { rowIdx, cells ->
                val isHeader = rowIdx == 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isHeader) Modifier.background(AccentCyan.copy(alpha = 0.15f))
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

// ═══════════════════════════════════════════
// ── Mermaid Diagram Dialog ──
// ═══════════════════════════════════════════

@Composable
private fun MermaidDiagramDialog(
    mermaidCode: String,
    onDismiss: () -> Unit,
    onBitmapCaptured: (ByteArray) -> Unit = {}
) {
    var bitmapCaptured by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header with gradient
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color(0xFF6C63FF), Color(0xFF00BCD4))
                            )
                        )
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.AutoGraph,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Visual Diagram",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.White
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, "Close", tint = Color.White.copy(alpha = 0.9f))
                    }
                }

                // WebView with Mermaid
                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes">
                        <style>
                            * { margin: 0; padding: 0; box-sizing: border-box; }
                            body {
                                background: #FFFFFF;
                                display: flex;
                                justify-content: center;
                                align-items: flex-start;
                                min-height: 100vh;
                                padding: 24px 12px;
                                overflow: auto;
                                font-family: 'Segoe UI', system-ui, -apple-system, sans-serif;
                            }
                            #diagram {
                                width: 100%;
                                display: flex;
                                justify-content: center;
                                min-height: 80vh;
                            }
                            .mermaid {
                                width: 100%;
                            }
                            .mermaid svg {
                                width: 100% !important;
                                height: auto !important;
                                min-height: 60vh;
                            }
                            /* Professional clean styling */
                            .node rect, .node polygon {
                                fill: #F0F4FF !important;
                                stroke: #3B4C6B !important;
                                stroke-width: 2px !important;
                                rx: 10px !important;
                                ry: 10px !important;
                                filter: drop-shadow(0px 2px 6px rgba(0,0,0,0.12)) !important;
                            }
                            .node .label {
                                font-size: 16px !important;
                                font-weight: 600 !important;
                            }
                            .nodeLabel {
                                color: #1A202C !important;
                                fill: #1A202C !important;
                                font-size: 16px !important;
                                font-weight: 600 !important;
                                font-family: 'Segoe UI', system-ui, sans-serif !important;
                            }
                            .edgeLabel {
                                color: #4A5568 !important;
                                fill: #4A5568 !important;
                                font-size: 13px !important;
                                font-weight: 500 !important;
                                background-color: #FFFFFF !important;
                            }
                            .edgePath .path {
                                stroke: #2D3748 !important;
                                stroke-width: 2.5px !important;
                            }
                            marker path {
                                fill: #2D3748 !important;
                            }
                            .error-text {
                                color: #E53E3E;
                                font-family: monospace;
                                font-size: 14px;
                                padding: 20px;
                                text-align: center;
                            }
                        </style>
                    </head>
                    <body>
                        <div id="diagram" class="mermaid">
                        ${mermaidCode}
                        </div>
                        <script src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"></script>
                        <script>
                            mermaid.initialize({
                                startOnLoad: true,
                                theme: 'base',
                                themeVariables: {
                                    primaryColor: '#F0F4FF',
                                    primaryTextColor: '#1A202C',
                                    primaryBorderColor: '#3B4C6B',
                                    lineColor: '#2D3748',
                                    secondaryColor: '#EDF2F7',
                                    tertiaryColor: '#FFFFFF',
                                    fontFamily: 'Segoe UI, system-ui, sans-serif',
                                    fontSize: '16px',
                                    nodeBorder: '#3B4C6B',
                                    mainBkg: '#F0F4FF',
                                    nodeTextColor: '#1A202C'
                                },
                                flowchart: {
                                    useMaxWidth: true,
                                    htmlLabels: true,
                                    curve: 'basis',
                                    padding: 20,
                                    nodeSpacing: 50,
                                    rankSpacing: 60,
                                    diagramPadding: 20
                                }
                            });
                            mermaid.parseError = function(err, hash) {
                                var msg = (typeof err === 'string') ? err : (err.message || err.str || JSON.stringify(err));
                                document.getElementById('diagram').innerHTML =
                                    '<p class="error-text">⚠️ Diagram syntax error<br>' + msg + '</p>';
                            };
                        </script>
                    </body>
                    </html>
                """.trimIndent()

                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.setSupportZoom(true)
                            setBackgroundColor(android.graphics.Color.WHITE)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    // Wait for Mermaid to render, then capture bitmap
                                    view?.postDelayed({
                                        if (!bitmapCaptured) {
                                            try {
                                                val w = view.width
                                                val h = view.contentHeight
                                                if (w > 0 && h > 0) {
                                                    val bitmap = android.graphics.Bitmap.createBitmap(
                                                        w, minOf(h, 2000),
                                                        android.graphics.Bitmap.Config.ARGB_8888
                                                    )
                                                    val canvas = android.graphics.Canvas(bitmap)
                                                    canvas.drawColor(android.graphics.Color.WHITE)
                                                    view.draw(canvas)
                                                    val baos = java.io.ByteArrayOutputStream()
                                                    bitmap.compress(
                                                        android.graphics.Bitmap.CompressFormat.PNG, 90, baos
                                                    )
                                                    bitmap.recycle()
                                                    val bytes = baos.toByteArray()
                                                    if (bytes.size > 1000) {
                                                        onBitmapCaptured(bytes)
                                                        bitmapCaptured = true
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                android.util.Log.w("Diagram", "Bitmap capture failed: ${e.message}")
                                            }
                                        }
                                    }, 3000)  // 3s delay for Mermaid JS to render
                                }
                            }
                            loadDataWithBaseURL(
                                "https://cdn.jsdelivr.net",
                                html,
                                "text/html",
                                "UTF-8",
                                null
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                )
            }
        }
    }
}
