package com.runanywhere.kotlin_starter_example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.runanywhere.kotlin_starter_example.data.SessionRepository
import com.runanywhere.kotlin_starter_example.data.StudySession
import com.runanywhere.kotlin_starter_example.R
import com.runanywhere.kotlin_starter_example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionHistoryScreen(
    onOpenSession: (String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { SessionRepository(context.applicationContext) }
    var sessions by remember { mutableStateOf(repository.getAll()) }
    var showDeleteDialog by remember { mutableStateOf<StudySession?>(null) }

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
                title = { Text("Past Sessions") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = GlassWhite)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        if (sessions.isEmpty()) {
            // Empty state
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Rounded.FolderOpen,
                    null,
                    tint = TextMuted.copy(alpha = 0.5f),
                    modifier = Modifier.size(72.dp)
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    "No Sessions Yet",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Start a new study session to see it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            }
        } else {
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                item {
                    Text(
                        "${sessions.size} session${if (sessions.size > 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                }

                items(sessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        onClick = { onOpenSession(session.id) },
                        onDelete = { showDeleteDialog = session }
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { session ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Session?") },
            text = { Text("\"${session.title}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    repository.delete(session.id)
                    sessions = repository.getAll()
                    showDeleteDialog = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel", color = AccentCyan)
                }
            },
            containerColor = Color(0xFF0D1117),
            shape = RoundedCornerShape(24.dp),
            titleContentColor = TextPrimary,
            textContentColor = TextMuted
        )
    }
    } // Close Box
}

@Composable
private fun SessionCard(
    session: StudySession,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy · h:mm a", Locale.getDefault()) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Icon based on content
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                brush = Brush.linearGradient(
                                    if (session.documentName != null) listOf(AccentViolet, AccentCyan)
                                    else listOf(AccentCyan, AccentGreen)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (session.documentName != null) Icons.Rounded.Description
                            else Icons.Rounded.Chat,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(Modifier.width(14.dp))

                    Column {
                        Text(
                            text = session.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = dateFormat.format(Date(session.updatedAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }

                // Delete button
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.Delete,
                        "Delete",
                        tint = TextMuted.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Preview info
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Message count
                if (session.messages.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Chat,
                            null,
                            tint = AccentCyan.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${session.messages.size} messages",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }

                // Document
                if (session.documentName != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Description,
                            null,
                            tint = AccentViolet.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            session.documentName ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Notes indicator
                if (session.notes.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Note,
                            null,
                            tint = NoteAmber.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Has notes",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }
            }

            // Last message preview
            val lastMessage = session.messages.lastOrNull { !it.isUser }
            if (lastMessage != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = lastMessage.text.take(100) + if (lastMessage.text.length > 100) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
