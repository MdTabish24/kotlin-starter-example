package com.runanywhere.kotlin_starter_example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.runanywhere.kotlin_starter_example.services.ModelService
import com.runanywhere.kotlin_starter_example.ui.components.ModelLoaderWidget
import com.runanywhere.kotlin_starter_example.R
import com.runanywhere.kotlin_starter_example.ui.theme.*
import androidx.compose.ui.platform.LocalContext

import com.runanywhere.kotlin_starter_example.utils.LLMPerformanceBooster
import com.runanywhere.sdk.public.extensions.chat
import com.runanywhere.sdk.public.extensions.generate
import kotlinx.coroutines.launch

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    modelService: ModelService = viewModel(),
    modifier: Modifier = Modifier
) {
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
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
                title = { Text("Chat - LLM") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GlassWhite
                )
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Model loader section
            if (!modelService.isLLMLoaded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    ModelLoaderWidget(
                        modelName = "SmolLM2 360M",
                        isDownloading = modelService.isLLMDownloading,
                        isLoading = modelService.isLLMLoading,
                        isLoaded = modelService.isLLMLoaded,
                        downloadProgress = modelService.llmDownloadProgress,
                        onLoadClick = { modelService.downloadAndLoadLLM() }
                    )

                    modelService.errorMessage?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }

            // Chat messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                if (messages.isEmpty() && modelService.isLLMLoaded) {
                    item {
                        EmptyStateMessage()
                    }
                }

                items(messages) { message ->
                    ChatMessageBubble(message)
                }
            }

            // Input section
            if (modelService.isLLMLoaded) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = GlassWhite,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        TextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Type a message...") },
                            readOnly = isGenerating,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.White.copy(alpha = 0.10f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                                disabledContainerColor = Color.White.copy(alpha = 0.05f),
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(20.dp),
                            maxLines = 4
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        FloatingActionButton(
                            onClick = {
                                if (inputText.isNotBlank() && !isGenerating) {
                                    val userMessage = inputText
                                    messages = messages + ChatMessage(userMessage, isUser = true)
                                    inputText = ""

                                    scope.launch {
                                        isGenerating = true
                                        listState.animateScrollToItem(messages.size)

                                        try {
                                            // Only unload STT/TTS if native heap is actually high (>50% device RAM).
                                            // Blind unload corrupts native allocator state → SIGABRT during generation.
                                            val chatNativeHeap = LLMPerformanceBooster.getNativeHeapUsageMB()
                                            val chatDeviceRAM = LLMPerformanceBooster.getDeviceRAM(context)
                                            if (chatNativeHeap > (chatDeviceRAM * 0.50).toLong() && (modelService.isSTTLoaded || modelService.isTTSLoaded)) {
                                                modelService.freeMemoryForLLM()
                                                kotlinx.coroutines.delay(1000)
                                            }

                                            // Safety: auto-switch to smaller model if current one is too large
                                            modelService.ensureSafeModelForGeneration(context)

                                            // Skip KV reset — the unload/reload cycle causes SIGABRT
                                            // on 6GB devices. Qwen 2.5 has 8192 context, plenty for chat.
                                            // modelService.resetLLMContext()
                                            kotlinx.coroutines.delay(200)

                                            val chatModelMem = (com.runanywhere.kotlin_starter_example.services.ModelService.getLLMOption(modelService.activeLLMModelId)?.memoryRequirement ?: 400_000_000L) / (1024L * 1024L)
                                            val adaptiveMaxTokens = LLMPerformanceBooster.getRecommendedMaxTokens(context, chatModelMem)
                                            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                com.runanywhere.sdk.public.RunAnywhere.generate(
                                                    userMessage,
                                                    com.runanywhere.sdk.public.extensions.LLM.LLMGenerationOptions(
                                                        maxTokens = adaptiveMaxTokens,
                                                        temperature = 0.65f,
                                                        topP = 0.9f
                                                    )
                                                )
                                            }
                                            messages = messages + ChatMessage(result.text, isUser = false)
                                            listState.animateScrollToItem(messages.size)
                                        } catch (e: Exception) {
                                            messages = messages + ChatMessage(
                                                "Error: ${e.message}",
                                                isUser = false
                                            )
                                        } finally {
                                            isGenerating = false
                                            // Reload STT/TTS after generation
                                            if (!modelService.isSTTLoaded) modelService.downloadAndLoadSTT()
                                            if (!modelService.isTTSLoaded) modelService.downloadAndLoadTTS()
                                        }
                                    }
                                }
                            },
                            containerColor = if (isGenerating) AccentViolet else if (inputText.isBlank()) TextMuted else AccentCyan
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color.White
                                )
                            } else {
                                Icon(Icons.AutoMirrored.Rounded.Send, "Send")
                            }
                        }
                    }
                }
            }
        }
    }
    } // Close Box
}

@Composable
private fun EmptyStateMessage() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.SmartToy,
            contentDescription = null,
            tint = AccentCyan,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Start a conversation",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Type a message below to chat with the AI",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted
        )
    }
}

@Composable
private fun ChatMessageBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!message.isUser) {
            Icon(
                imageVector = Icons.Rounded.SmartToy,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier
                    .size(32.dp)
                    .padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(
                topStart = if (message.isUser) 16.dp else 4.dp,
                topEnd = if (message.isUser) 4.dp else 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (message.isUser) AccentCyan else SurfaceCard
            )
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.isUser) Color.White else TextPrimary
            )
        }

        if (message.isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = null,
                tint = AccentViolet,
                modifier = Modifier
                    .size(32.dp)
                    .padding(top = 4.dp)
            )
        }
    }
}
