package com.runanywhere.kotlin_starter_example.data

import org.json.JSONArray
import org.json.JSONObject

data class SessionMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson() = JSONObject().apply {
        put("text", text)
        put("isUser", isUser)
        put("timestamp", timestamp)
    }

    companion object {
        fun fromJson(json: JSONObject) = SessionMessage(
            text = json.optString("text", ""),
            isUser = json.optBoolean("isUser", true),
            timestamp = json.optLong("timestamp", System.currentTimeMillis())
        )
    }
}

data class StudySession(
    val id: String = java.util.UUID.randomUUID().toString(),
    var title: String = "Untitled Session",
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var documentName: String? = null,
    var documentContent: String? = null,  // Kept for in-memory use only — NOT serialized to JSON
    var hasDocument: Boolean = false,     // Flag: doc stored in separate file
    var notes: String = "",
    var messages: MutableList<SessionMessage> = mutableListOf(),
    var smartNotesEnabled: Boolean = false,
    var diagramsEnabled: Boolean = false
) {
    fun toJson() = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("documentName", documentName ?: "")
        // NOTE: documentContent is NOT saved here anymore!
        // It's stored in a separate file via SmartDocumentSearch.saveDocumentToFile()
        // This prevents the session JSON from being 500KB+ and eating memory on load.
        put("hasDocument", hasDocument)
        put("notes", notes)
        put("smartNotesEnabled", smartNotesEnabled)
        put("diagramsEnabled", diagramsEnabled)
        put("messages", JSONArray().apply {
            messages.forEach { put(it.toJson()) }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): StudySession {
            val msgs = mutableListOf<SessionMessage>()
            json.optJSONArray("messages")?.let { arr ->
                for (i in 0 until arr.length()) {
                    msgs.add(SessionMessage.fromJson(arr.getJSONObject(i)))
                }
            }
            // Backward compat: if old session has documentContent, detect it
            val oldDocContent = json.optString("documentContent", "").ifEmpty { null }
            val docName = json.optString("documentName", "").ifEmpty { null }
            return StudySession(
                id = json.optString("id", java.util.UUID.randomUUID().toString()),
                title = json.optString("title", "Untitled"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                documentName = docName,
                documentContent = oldDocContent,  // Only loaded from old sessions for migration
                hasDocument = json.optBoolean("hasDocument", oldDocContent != null),
                notes = json.optString("notes", ""),
                messages = msgs,
                smartNotesEnabled = json.optBoolean("smartNotesEnabled", false),
                diagramsEnabled = json.optBoolean("diagramsEnabled", false)
            )
        }
    }
}
