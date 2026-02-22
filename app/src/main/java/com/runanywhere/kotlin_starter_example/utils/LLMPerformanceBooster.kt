package com.runanywhere.kotlin_starter_example.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log

/**
 * LLM Performance Booster — maximizes device resources for on-device inference.
 *
 * Strategies:
 * 1. Request more heap memory (largeHeap in manifest)
 * 2. Force GC before inference to free RAM
 * 3. Set thread priority to maximize CPU allocation
 * 4. Check available memory and warn if low
 * 5. Trim memory from non-critical caches
 */
object LLMPerformanceBooster {

    private const val TAG = "LLMBooster"

    /**
     * Call before heavy LLM tasks (summary, long generation).
     * Frees memory via GC to maximize available RAM for native inference.
     */
    fun boostForInference() {
        // Run GC to reclaim unused Java objects and free native references
        // This helps release any native memory held by Java wrappers
        System.gc()
        Runtime.getRuntime().gc()
        Log.d(TAG, "Preparing for LLM inference. Free heap: ${getFreeMemoryMB()}MB")
    }

    /**
     * Get system-wide available memory in MB (NOT Java heap - actual OS-level available RAM).
     * This is what matters for native LLM inference memory.
     */
    fun getSystemAvailableMemoryMB(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024 * 1024)
    }

    /**
     * Get per-process native heap allocated size in MB.
     * This is the REAL metric for native OOM detection — system-wide available RAM
     * can look sufficient (e.g. 1632MB) while the process's native heap is full.
     * The "Waiting for blocking GC NativeAlloc" crash signal comes from HERE.
     */
    fun getNativeHeapUsageMB(): Long {
        val allocated = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val total = Debug.getNativeHeapSize() / (1024 * 1024)
        Log.d(TAG, "Native heap: ${allocated}MB allocated / ${total}MB total")
        return allocated
    }

    /**
     * Call after LLM task completes to restore normal priority.
     */
    fun restoreAfterInference() {
        // No-op. Keep scheduler defaults to avoid native side instability.
    }

    /**
     * Force aggressive garbage collection.
     */
    fun forceGC() {
        // Multiple GC passes for better cleanup
        System.runFinalization()
        Runtime.getRuntime().gc()
        System.gc()
        Log.d(TAG, "GC complete. Free mem: ${getFreeMemoryMB()}MB")
    }

    /**
     * Get available memory in MB.
     */
    fun getFreeMemoryMB(): Long {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        return (maxMemory - usedMemory) / (1024 * 1024)
    }

    /**
     * Get device total RAM in MB.
     */
    fun getDeviceRAM(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    /**
     * Check if device has enough memory for a model of given size.
     */
    fun hasEnoughMemory(context: Context, modelSizeMB: Long): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availMB = memInfo.availMem / (1024 * 1024)
        val enough = availMB > modelSizeMB * 1.5 // Need 1.5x model size for safe operation
        Log.d(TAG, "Available: ${availMB}MB, needed: ${modelSizeMB * 1.5}MB, enough: $enough")
        return enough
    }

    /**
     * Get recommended max document chars based on available memory.
     * More RAM = can process more document text.
     *
     * SmartDocumentSearch already filters to only RELEVANT sections (not raw doc).
     * So these limits are generous — the content is pre-filtered and high-value.
     * We need enough context so the LLM answers accurately from the document
     * without hallucinating. Minimum ~2K chars needed for meaningful answers.
     *
     * Memory math (6GB device with SmolLM2-360M at 2048 context):
     *   5000 chars ≈ 1250 tokens → fits in 2048 ctx (leaves 798 for system+answer)
     *
     * ULTIMATE FIX: KV cache cleared on EVERY prompt = no cumulative buildup.
     * Safe to use generous limits for detailed PDF answers.
     */
    fun getRecommendedDocLimit(context: Context, modelMemoryMB: Long = 400): Int {
        val deviceRAM = getDeviceRAM(context)
        // Large models (≥700MB) need much more native memory for KV cache + scratch.
        val isLargeModel = modelMemoryMB >= 700
        // Tiny models (<500MB, e.g. SmolLM2-360M) have only 2048-token context window.
        // SDK allocates KV cache for 8192 tokens → ~1GB native memory → SIGABRT if prompt too long.
        // Must drastically limit doc context to prevent context overflow + native OOM.
        val isTinyModel = modelMemoryMB < 500
        return when {
            // ── Tiny models: 2048 context window, budget ~200 tokens for system+question ──
            isTinyModel -> when {
                deviceRAM >= 6144 -> 1000    // ~250 tokens doc, leaves room for 256 output
                deviceRAM >= 4096 -> 800     // ~200 tokens doc
                else -> 500                  // Minimal
            }
            deviceRAM >= 8192 && isLargeModel -> 3500   // 8GB + big model
            deviceRAM >= 8192 -> 5000                    // 8GB + small model
            deviceRAM >= 6144 && isLargeModel -> 1500    // 6GB + big model → ~375 tokens of doc
            deviceRAM >= 6144 -> 2500                    // 6GB + small model
            deviceRAM >= 4096 && isLargeModel -> 1200    // 4GB + big model
            deviceRAM >= 4096 -> 2000                    // 4GB + small model
            deviceRAM >= 3072 -> 800                     // 3GB
            else -> 500                                  // 2GB
        }
    }

    /**
     * Get maximum safe total prompt length (system prompt + doc context + question).
     * This must fit within (contextLength - maxTokens) token budget.
     *
     * Roughly: 1 token ≈ 4 chars. So:
     *   2048 ctx - 512 maxTokens = 1536 input tokens ≈ 6000 chars
     *   4096 ctx - 768 maxTokens = 3328 input tokens ≈ 13000 chars
     *
     * ULTIMATE FIX: KV cache cleared every prompt = safe to use full context window.
     */
    fun getMaxSafePromptLength(context: Context, modelMemoryMB: Long = 400): Int {
        val deviceRAM = getDeviceRAM(context)
        val isLargeModel = modelMemoryMB >= 700
        val isTinyModel = modelMemoryMB < 500
        // Tiny models: 2048 ctx - 256 maxTokens = 1792 input tokens ≈ 1200 chars safe max.
        return when {
            isTinyModel -> when {
                deviceRAM >= 6144 -> 1400    // ~350 input tokens + 256 output = 606 < 2048
                deviceRAM >= 4096 -> 1200    // ~300 input tokens + 256 output = 556 < 2048
                else -> 800                  // Minimal
            }
            deviceRAM >= 8192 && isLargeModel -> 4000    // 8GB + big model
            deviceRAM >= 8192 -> 6000                     // 8GB + small model
            deviceRAM >= 6144 && isLargeModel -> 2500     // 6GB + big model → enough for detailed answers
            deviceRAM >= 6144 -> 3500                     // 6GB + small model
            deviceRAM >= 4096 && isLargeModel -> 2000     // 4GB + big model (was 1500 — too restrictive)
            deviceRAM >= 4096 -> 3000                     // 4GB + small model
            deviceRAM >= 3072 -> 1400                     // 3GB
            else -> 800                                   // 2GB
        }
    }

    /**
     * Get recommended max tokens based on available memory.
     * Increased to allow detailed, document-grounded responses.
     *
     * BALANCED FIX: 512 tokens ≈ 300-400 words (enough for detailed explanations).
     * Combined with aggressive KV cache reset to prevent crashes.
     *
     * Note: 300 words ≈ 400-450 tokens, so 512 tokens gives room for detailed answers.
     */
    fun getRecommendedMaxTokens(context: Context, modelMemoryMB: Long = 400): Int {
        val deviceRAM = getDeviceRAM(context)
        val isLargeModel = modelMemoryMB >= 700
        val isTinyModel = modelMemoryMB < 500
        // Tiny models: only 2048 context window. 256 output tokens ≈ 180 words.
        // Must leave enough room for prompt (system + doc + question).
        return when {
            isTinyModel -> 256   // 256 output + ~300 input = 556 tokens, safe for 2048 ctx
            deviceRAM >= 8192 && isLargeModel -> 900   // 8GB + big model → ~650 words
            deviceRAM >= 8192 -> 900                    // 8GB + small model
            deviceRAM >= 6144 && isLargeModel -> 800    // 6GB + big model → ~550 words (was 640, caused incomplete)
            deviceRAM >= 6144 -> 800                    // 6GB + small model
            deviceRAM >= 4096 && isLargeModel -> 750    // 4GB+ big model → was 600 (too low)
            deviceRAM >= 4096 -> 800                    // 4GB + small model
            deviceRAM >= 3072 -> 550                    // 3GB
            else -> 350                                 // 2GB
        }
    }

    /**
     * Trim app memory by releasing caches.
     */
    fun trimMemory(context: Context) {
        try {
            // Ask the system to release cached resources
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            // Clear any bitmap caches etc.
            forceGC()
            Log.d(TAG, "Memory trimmed")
        } catch (e: Exception) {
            Log.w(TAG, "Trim failed: ${e.message}")
        }
    }
}
