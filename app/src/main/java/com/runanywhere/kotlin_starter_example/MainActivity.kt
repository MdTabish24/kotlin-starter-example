package com.runanywhere.kotlin_starter_example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

    // Load saved model preference
    androidx.compose.runtime.LaunchedEffect(Unit) {
        modelService.initWithPreferences(context)
    }

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
