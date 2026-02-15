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
     */
    fun getRecommendedDocLimit(context: Context): Int {
        val deviceRAM = getDeviceRAM(context)
        return when {
            deviceRAM >= 8192 -> 12000  // 8GB+ → 12K chars (fits 4096 ctx window)
            deviceRAM >= 6144 -> 5000   // 6GB → 5K chars (fits 2048 ctx with SmolLM2)
            deviceRAM >= 4096 -> 3500   // 4GB → 3.5K chars
            deviceRAM >= 3072 -> 2000   // 3GB → 2K chars
            else -> 1200                // 2GB → 1.2K minimum
        }
    }

    /**
     * Get maximum safe total prompt length (system prompt + doc context + question).
     * This must fit within (contextLength - maxTokens) token budget.
     *
     * Roughly: 1 token ≈ 4 chars. So:
     *   2048 ctx - 384 maxTokens = 1664 input tokens ≈ 6500 chars
     *   4096 ctx - 768 maxTokens = 3328 input tokens ≈ 13000 chars
     */
    fun getMaxSafePromptLength(context: Context): Int {
        val deviceRAM = getDeviceRAM(context)
        return when {
            deviceRAM >= 8192 -> 13000  // 8GB+: ~3250 tokens in 4096 ctx
            deviceRAM >= 6144 -> 6500   // 6GB: ~1625 tokens in 2048 ctx
            deviceRAM >= 4096 -> 4500   // 4GB: ~1125 tokens
            deviceRAM >= 3072 -> 2500   // 3GB: ~625 tokens
            else -> 1500                // 2GB: ~375 tokens
        }
    }

    /**
     * Get recommended max tokens based on available memory.
     * Increased to allow detailed, document-grounded responses.
     */
    fun getRecommendedMaxTokens(context: Context): Int {
        val deviceRAM = getDeviceRAM(context)
        return when {
            deviceRAM >= 8192 -> 768   // 8GB+ → long detailed answers
            deviceRAM >= 6144 -> 384   // 6GB → only value proven safe (512 caused SIGABRT)
            deviceRAM >= 4096 -> 256   // 4GB → decent detail
            deviceRAM >= 3072 -> 192   // 3GB → reasonable answers
            else -> 128                // 2GB → compact but complete
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
