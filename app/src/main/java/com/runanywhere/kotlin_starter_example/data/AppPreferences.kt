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

    /** TTS accent — "indian", "us", "uk", "australian", "canadian", etc. */
    var ttsAccent: String
        get() = prefs.getString("tts_accent", "indian") ?: "indian"
        set(v) = prefs.edit().putString("tts_accent", v).apply()

    /** Use Android's built-in TTS (supports accents) instead of Piper. Default true. */
    var useNativeTTS: Boolean
        get() = prefs.getBoolean("use_native_tts", true)
        set(v) = prefs.edit().putBoolean("use_native_tts", v).apply()
}

