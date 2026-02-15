package com.runanywhere.kotlin_starter_example.data

import android.content.Context

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("youlearn_settings", Context.MODE_PRIVATE)

    var ttsSpeed: Float
        get() = prefs.getFloat("tts_speed", 1.0f)
        set(v) = prefs.edit().putFloat("tts_speed", v).apply()

    var ttsPitch: Float
        get() = prefs.getFloat("tts_pitch", 1.0f)
        set(v) = prefs.edit().putFloat("tts_pitch", v).apply()

    var language: String
        get() = prefs.getString("language", "en") ?: "en"
        set(v) = prefs.edit().putString("language", v).apply()

    var selectedLLMModel: String
        get() = prefs.getString("llm_model", "smollm2-360m-instruct-q8_0") ?: "smollm2-360m-instruct-q8_0"
        set(v) = prefs.edit().putString("llm_model", v).apply()
}

