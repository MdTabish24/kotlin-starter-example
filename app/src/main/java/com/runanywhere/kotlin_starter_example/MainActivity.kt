package com.runanywhere.kotlin_starter_example

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.Debug
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.runanywhere.kotlin_starter_example.services.ModelService
import com.runanywhere.kotlin_starter_example.ui.screens.*
import com.runanywhere.kotlin_starter_example.ui.theme.KotlinStarterTheme
import com.runanywhere.sdk.core.onnx.ONNX
import com.runanywhere.sdk.foundation.bridge.extensions.CppBridgeModelPaths
import com.runanywhere.sdk.llm.llamacpp.LlamaCPP
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.SDKEnvironment
import com.runanywhere.sdk.storage.AndroidPlatformContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // ── CRASH DIAGNOSTICS: dump full memory state on ANY uncaught exception ──
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val nativeHeap = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
                val nativeTotal = Debug.getNativeHeapSize() / (1024 * 1024)
                val nativeFree = Debug.getNativeHeapFreeSize() / (1024 * 1024)
                val rt = Runtime.getRuntime()
                val javaUsed = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                val javaMax = rt.maxMemory() / (1024 * 1024)
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am?.getMemoryInfo(mi)
                val systemAvail = mi.availMem / (1024 * 1024)
                val systemTotal = mi.totalMem / (1024 * 1024)

                Log.e("CRASH-DIAG", "╔══════════════════════════════════════════════════════════")
                Log.e("CRASH-DIAG", "║ UNCAUGHT EXCEPTION on thread: ${thread.name}")
                Log.e("CRASH-DIAG", "║ Exception: ${throwable.javaClass.simpleName}: ${throwable.message}")
                Log.e("CRASH-DIAG", "║ ── MEMORY STATE AT CRASH ──")
                Log.e("CRASH-DIAG", "║ Native: ${nativeHeap}MB used / ${nativeTotal}MB total / ${nativeFree}MB free")
                Log.e("CRASH-DIAG", "║ Java:   ${javaUsed}MB used / ${javaMax}MB max")
                Log.e("CRASH-DIAG", "║ System: ${systemAvail}MB avail / ${systemTotal}MB total")
                Log.e("CRASH-DIAG", "║ LowMem: ${mi.lowMemory} (threshold=${mi.threshold/(1024*1024)}MB)")
                Log.e("CRASH-DIAG", "╚══════════════════════════════════════════════════════════")

                // Print full stack trace with CRASH-DIAG tag so our filter catches it
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                sw.toString().lines().forEach { line ->
                    if (line.isNotBlank()) Log.e("CRASH-DIAG", "║ $line")
                }
            } catch (_: Exception) {
                // Can't risk throwing during crash handler
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Initialize Android platform context FIRST - this sets up storage paths
        // The SDK requires this before RunAnywhere.initialize() on Android
        AndroidPlatformContext.initialize(this)

        // Initialize RunAnywhere SDK for development
        RunAnywhere.initialize(environment = SDKEnvironment.DEVELOPMENT)

        // Set the base directory for model storage
        val runanywherePath = java.io.File(filesDir, "runanywhere").absolutePath
        CppBridgeModelPaths.setBaseDirectory(runanywherePath)

        // Register backends FIRST - these must be registered before loading any models
        // They provide the inference capabilities (TEXT_GENERATION, STT, TTS)
        LlamaCPP.register(priority = 100)  // For LLM (GGUF models)
        ONNX.register(priority = 100)      // For STT/TTS (ONNX models)

        // Register default models
        ModelService.registerDefaultModels()

        // SDK services (device registration, telemetry) initialize lazily on first chat() call.
        // All native SDK calls (chat, loadModel, etc.) now run on Dispatchers.IO,
        // so the lazy init happens safely off the main thread.

        setContent {
            KotlinStarterTheme {
                RunAnywhereApp()
            }
        }
    }
}

@Composable
fun RunAnywhereApp() {
    val navController = rememberNavController()
    val modelService: ModelService = viewModel()
    val context = androidx.compose.ui.platform.LocalContext.current
    
    var showSplash by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }

    // Load saved model preference
    androidx.compose.runtime.LaunchedEffect(Unit) {
        modelService.initWithPreferences(context)
    }
    
    if (showSplash) {
        SplashScreen(onSplashComplete = { showSplash = false })
    } else {
        NavHost(
            navController = navController,
            startDestination = "youlearn_home"
        ) {
        // ── YouLearn Screens ──
        composable("youlearn_home") {
            YouLearnHomeScreen(
                onNewSession = { navController.navigate("workspace/new") },
                onHistory = { navController.navigate("history") },
                onSettings = { navController.navigate("settings") },
                modelService = modelService
            )
        }

        composable("workspace/{sessionId}") { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId")
            SessionWorkspaceScreen(
                sessionId = sessionId,
                onNavigateBack = { navController.popBackStack() },
                modelService = modelService
            )
        }

        composable("history") {
            SessionHistoryScreen(
                onOpenSession = { id -> navController.navigate("workspace/$id") },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                modelService = modelService
            )
        }

        // ── Legacy SDK Demo Screens (kept for reference) ──
        composable("chat") {
            ChatScreen(
                onNavigateBack = { navController.popBackStack() },
                modelService = modelService
            )
        }

        composable("stt") {
            SpeechToTextScreen(
                onNavigateBack = { navController.popBackStack() },
                modelService = modelService
            )
        }

        composable("tts") {
            TextToSpeechScreen(
                onNavigateBack = { navController.popBackStack() },
                modelService = modelService
            )
        }

        composable("voice_pipeline") {
            VoicePipelineScreen(
                onNavigateBack = { navController.popBackStack() },
                modelService = modelService
            )
        }
        }
    }
}
