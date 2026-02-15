package com.runanywhere.kotlin_starter_example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.runanywhere.kotlin_starter_example.data.AppPreferences
import com.runanywhere.kotlin_starter_example.services.LLMModelOption
import com.runanywhere.kotlin_starter_example.services.ModelService
import com.runanywhere.kotlin_starter_example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    modelService: ModelService,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context.applicationContext) }

    var ttsSpeed by remember { mutableFloatStateOf(prefs.ttsSpeed) }
    var ttsPitch by remember { mutableFloatStateOf(prefs.ttsPitch) }
    var selectedLanguage by remember { mutableStateOf(prefs.language) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PrimaryDark)
            )
        },
        containerColor = PrimaryDark
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            // ═══════════════════════════════════════
            // LLM MODEL SELECTION
            // ═══════════════════════════════════════
            Text("LLM Model", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text("Choose the AI brain for chat responses", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            Spacer(Modifier.height(16.dp))

            ModelService.LLM_OPTIONS.forEach { model ->
                val isActive = model.id == modelService.activeLLMModelId
                val isCurrentlyLoaded = isActive && modelService.isLLMLoaded

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clickable {
                            if (!modelService.isLLMDownloading && !modelService.isLLMLoading) {
                                modelService.switchLLMModel(model.id, context)
                            }
                        },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) AccentCyan.copy(alpha = 0.1f) else SurfaceCard.copy(alpha = 0.5f)
                    ),
                    border = if (isActive) {
                        androidx.compose.foundation.BorderStroke(1.dp, AccentCyan.copy(alpha = 0.4f))
                    } else null
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Radio indicator
                        RadioButton(
                            selected = isActive,
                            onClick = {
                                if (!modelService.isLLMDownloading && !modelService.isLLMLoading) {
                                    modelService.switchLLMModel(model.id, context)
                                }
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = AccentCyan),
                            modifier = Modifier.size(20.dp)
                        )

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    model.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isActive) AccentCyan else TextPrimary
                                )
                                if (model.recommended) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "RECOMMENDED",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AccentGreen,
                                        modifier = Modifier
                                            .background(
                                                AccentGreen.copy(alpha = 0.15f),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${model.description} (${model.size})",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }

                        // Status indicator
                        if (isActive && (modelService.isLLMDownloading || modelService.isLLMLoading)) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = AccentCyan,
                                strokeWidth = 2.dp
                            )
                        } else if (isCurrentlyLoaded) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                null,
                                tint = AccentGreen,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Download progress
                    if (isActive && modelService.isLLMDownloading) {
                        LinearProgressIndicator(
                            progress = { modelService.llmDownloadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp)
                                .padding(bottom = 10.dp)
                                .height(3.dp),
                            color = AccentCyan,
                            trackColor = AccentCyan.copy(alpha = 0.15f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ═══════════════════════════════════════
            // STT & TTS MODEL STATUS
            // ═══════════════════════════════════════
            Text("Voice Models", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text("Speech recognition & synthesis", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            Spacer(Modifier.height(16.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // STT
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(AccentViolet.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Mic, null, tint = AccentViolet, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Speech-to-Text", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                            Text("Whisper Tiny English (~75 MB)", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                        if (modelService.isSTTLoaded) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = AccentGreen, modifier = Modifier.size(20.dp))
                        } else if (modelService.isSTTDownloading || modelService.isSTTLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentViolet, strokeWidth = 2.dp)
                        } else {
                            Button(
                                onClick = { modelService.downloadAndLoadSTT() },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Load", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    // STT progress
                    AnimatedVisibility(visible = modelService.isSTTDownloading) {
                        LinearProgressIndicator(
                            progress = { modelService.sttDownloadProgress },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp),
                            color = AccentViolet,
                            trackColor = AccentViolet.copy(alpha = 0.15f)
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = TextMuted.copy(alpha = 0.08f))

                    // TTS
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(AccentPink.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.VolumeUp, null, tint = AccentPink, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Text-to-Speech", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                            Text("Piper US English (~65 MB)", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                        if (modelService.isTTSLoaded) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = AccentGreen, modifier = Modifier.size(20.dp))
                        } else if (modelService.isTTSDownloading || modelService.isTTSLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentPink, strokeWidth = 2.dp)
                        } else {
                            Button(
                                onClick = { modelService.downloadAndLoadTTS() },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentPink),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Load", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    // TTS progress
                    AnimatedVisibility(visible = modelService.isTTSDownloading) {
                        LinearProgressIndicator(
                            progress = { modelService.ttsDownloadProgress },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp),
                            color = AccentPink,
                            trackColor = AccentPink.copy(alpha = 0.15f)
                        )
                    }
                }
            }

            // Error message
            modelService.errorMessage?.let { error ->
                Spacer(Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        if (error.contains("STT", ignoreCase = true)) {
                            TextButton(
                                onClick = {
                                    modelService.clearError()
                                    modelService.forceRedownloadSTT()
                                }
                            ) {
                                Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Retry STT Download", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // ═══════════════════════════════════════
            // VOICE CONFIGURATION
            // ═══════════════════════════════════════
            Text("Voice Configuration", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text("Customize how the AI speaks", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            Spacer(Modifier.height(16.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // Speech Speed
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Speech Speed", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(
                            "${String.format("%.1f", ttsSpeed)}x",
                            style = MaterialTheme.typography.titleSmall,
                            color = AccentCyan
                        )
                    }
                    Slider(
                        value = ttsSpeed,
                        onValueChange = {
                            ttsSpeed = it
                            prefs.ttsSpeed = it
                        },
                        valueRange = 0.5f..2.0f,
                        steps = 5,
                        colors = SliderDefaults.colors(
                            thumbColor = AccentCyan,
                            activeTrackColor = AccentCyan,
                            inactiveTrackColor = AccentCyan.copy(alpha = 0.2f)
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("0.5x", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        Text("1.0x", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        Text("2.0x", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 16.dp),
                        color = TextMuted.copy(alpha = 0.1f)
                    )

                    // Voice Pitch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Voice Pitch", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(
                            String.format("%.1f", ttsPitch),
                            style = MaterialTheme.typography.titleSmall,
                            color = AccentViolet
                        )
                    }
                    Slider(
                        value = ttsPitch,
                        onValueChange = {
                            ttsPitch = it
                            prefs.ttsPitch = it
                        },
                        valueRange = 0.5f..1.5f,
                        steps = 4,
                        colors = SliderDefaults.colors(
                            thumbColor = AccentViolet,
                            activeTrackColor = AccentViolet,
                            inactiveTrackColor = AccentViolet.copy(alpha = 0.2f)
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Low", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        Text("Normal", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        Text("High", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // ═══════════════════════════════════════
            // LANGUAGE
            // ═══════════════════════════════════════
            Text("Language", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text("AI response and voice language", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            Spacer(Modifier.height(16.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    val languages = listOf(
                        "en" to "English (US)",
                        "en-gb" to "English (UK)",
                        "hi" to "Hindi"
                    )
                    languages.forEachIndexed { index, (code, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            RadioButton(
                                selected = selectedLanguage == code,
                                onClick = {
                                    selectedLanguage = code
                                    prefs.language = code
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = AccentCyan)
                            )
                        }
                        if (index < languages.size - 1) {
                            HorizontalDivider(color = TextMuted.copy(alpha = 0.08f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── Reset ──
            OutlinedButton(
                onClick = {
                    ttsSpeed = 1.0f
                    ttsPitch = 1.0f
                    selectedLanguage = "en"
                    prefs.ttsSpeed = 1.0f
                    prefs.ttsPitch = 1.0f
                    prefs.language = "en"
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = ButtonDefaults.outlinedButtonBorder(true)
            ) {
                Icon(Icons.Rounded.RestartAlt, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reset to Defaults", color = TextMuted)
            }

            Spacer(Modifier.height(28.dp))

            // ── About ──
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "YouLearn v1.0",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "100% Offline AI Study Companion",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Powered by RunAnywhere SDK",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentCyan.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}
