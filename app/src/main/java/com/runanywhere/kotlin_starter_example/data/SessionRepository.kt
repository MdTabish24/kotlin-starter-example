package com.runanywhere.kotlin_starter_example.data

import android.content.Context
import org.json.JSONObject
import java.io.File

class SessionRepository(context: Context) {
    private val dir = File(context.filesDir, "youlearn_sessions").apply { mkdirs() }

    fun save(session: StudySession) {
        session.updatedAt = System.currentTimeMillis()
        File(dir, "${session.id}.json").writeText(session.toJson().toString())
    }

    fun load(id: String): StudySession? {
        val file = File(dir, "$id.json")
        return if (file.exists()) {
            try {
                StudySession.fromJson(JSONObject(file.readText()))
            } catch (_: Exception) {
                null
            }
        } else null
    }

    fun getAll(): List<StudySession> {
        return dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    StudySession.fromJson(JSONObject(file.readText()))
                } catch (_: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}
