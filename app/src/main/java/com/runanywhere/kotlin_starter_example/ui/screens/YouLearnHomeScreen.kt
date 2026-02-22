package com.runanywhere.kotlin_starter_example.ui.screens



import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.runanywhere.kotlin_starter_example.R
import com.runanywhere.kotlin_starter_example.services.ModelService
import com.runanywhere.kotlin_starter_example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouLearnHomeScreen(
    onNewSession: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    modelService: ModelService,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
    ) {
        Image(
            painter = painterResource(id = R.drawable.app_background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Spacer(Modifier.height(24.dp))

            // Top action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Rounded.Settings, "Settings", tint = TextMuted)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Logo + Title
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "YouLearn Logo",
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(28.dp)),
                    contentScale = ContentScale.Crop
                )

                Spacer(Modifier.height(20.dp))

                Text(
                    text = "YouLearn",
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Your 100% Offline AI Study Companion",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(16.dp))

            // Offline badge
            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .background(
                        AccentGreen.copy(alpha = 0.12f),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.AirplanemodeActive,
                    null,
                    tint = AccentGreen,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Works in Airplane Mode",
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentGreen
                )
            }

            Spacer(Modifier.height(44.dp))

            // ── Main Action Buttons ──
            Button(
                onClick = onNewSession,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("New Study Session", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = onHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp),
                border = ButtonDefaults.outlinedButtonBorder(true).copy(
                    brush = Brush.linearGradient(
                        listOf(TextMuted.copy(alpha = 0.3f), TextMuted.copy(alpha = 0.3f))
                    )
                )
            ) {
                Icon(Icons.Rounded.History, null, tint = AccentCyan)
                Spacer(Modifier.width(12.dp))
                Text("Past Sessions", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            }

            Spacer(Modifier.height(48.dp))

            // ── Loaded Models Status (compact) ──
            Text(
                text = "AI Models",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Spacer(Modifier.height(4.dp))

            val allLoaded = modelService.isLLMLoaded
            Text(
                text = if (allLoaded) "All models ready" else "LLM needs loading",
                style = MaterialTheme.typography.bodySmall,
                color = if (allLoaded) AccentGreen else TextMuted
            )
            Spacer(Modifier.height(12.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // LLM status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(AccentCyan.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Psychology, null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("LLM", style = MaterialTheme.typography.labelMedium, color = AccentCyan)
                            Text(
                                modelService.getActiveLLMName(),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )
                        }
                        Text(
                            if (modelService.isLLMLoaded) "Ready"
                            else if (modelService.isLLMDownloading) "${(modelService.llmDownloadProgress * 100).toInt()}%"
                            else if (modelService.isLLMLoading) "Loading..."
                            else "Not loaded",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (modelService.isLLMLoaded) AccentGreen else if (modelService.isLLMDownloading || modelService.isLLMLoading) AccentCyan else TextMuted
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = TextMuted.copy(alpha = 0.08f))

                    // STT status
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
                            Text("STT", style = MaterialTheme.typography.labelMedium, color = AccentViolet)
                            Text("Android Built-in", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                        }
                        Text(
                            "Ready",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentGreen
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = TextMuted.copy(alpha = 0.08f))

                    // TTS status
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
                            Text("TTS", style = MaterialTheme.typography.labelMedium, color = AccentPink)
                            Text("Android Built-in", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                        }
                        Text(
                            "Ready",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentGreen
                        )
                    }
                }
            }

            // Load All / Manage buttons
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!allLoaded) {
                    val anyBusy = modelService.isLLMDownloading || modelService.isLLMLoading
                    FilledTonalButton(
                        onClick = { modelService.downloadAndLoadAllModels() },
                        enabled = !anyBusy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = AccentCyan.copy(alpha = 0.15f)
                        )
                    ) {
                        Icon(Icons.Rounded.Download, null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (anyBusy) "Downloading..." else "Load All",
                            color = AccentCyan,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                OutlinedButton(
                    onClick = onSettings,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Settings, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Manage Models", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                }
            }

            // Error message
            modelService.errorMessage?.let { error ->
                Spacer(Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        if (error.contains("STT", ignoreCase = true)) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(
                                onClick = {
                                    modelService.clearError()
                                    modelService.forceRedownloadSTT()
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            ) {
                                Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Force Re-download STT", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(40.dp))

            // Privacy card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White.copy(alpha = 0.08f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Shield,
                        null,
                        tint = AccentGreen.copy(alpha = 0.8f),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            "Zero Latency, 100% Privacy",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "All AI processing happens locally. No data leaves your device. Ever.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

