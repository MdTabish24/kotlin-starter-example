package com.runanywhere.kotlin_starter_example.utils

/**
 * Coalesces frequent producer updates into a bounded number of UI updates.
 *
 * LLM token callbacks can arrive faster than Compose can measure and lay out a
 * growing markdown response. Keeping the newest value while one update is
 * pending preserves every token in the final response without flooding the
 * main thread.
 */
class StreamingUiUpdateThrottler(
    private val minimumIntervalMs: Long
) {
    private var lastDispatchAtMs: Long? = null
    private var updatePending = false

    init {
        require(minimumIntervalMs >= 0) { "minimumIntervalMs must not be negative" }
    }

    /** Returns the delay for the next UI update, or null when one is already queued. */
    fun scheduleDelayMs(nowMs: Long): Long? {
        if (updatePending) return null

        updatePending = true
        val nextAllowedAtMs = lastDispatchAtMs?.plus(minimumIntervalMs) ?: nowMs
        return (nextAllowedAtMs - nowMs).coerceAtLeast(0)
    }

    /** Marks the queued update as delivered so a newer snapshot can be queued. */
    fun markDispatched(nowMs: Long) {
        lastDispatchAtMs = nowMs
        updatePending = false
    }
}
