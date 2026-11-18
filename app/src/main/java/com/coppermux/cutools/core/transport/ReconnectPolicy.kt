package com.coppermux.cutools.core.transport

import java.util.Random
import kotlin.math.min

/**
 * Configurable auto-reconnect policy for resilient CDP transports
 * ([CdpSession]).
 *
 * Reconnection is opt-in ([enabled]). When enabled, a dropped socket is retried
 * with exponential backoff ([baseDelayMs] doubling per attempt, capped at
 * [maxDelayMs], with optional [jitterRatio] to avoid thundering-herd
 * synchronization), up to [maxAttempts] retries.
 *
 * [heartbeatIntervalMs] / [heartbeatTimeoutMs] drive active-channel monitoring:
 * when no message is observed within [heartbeatTimeoutMs] of the last observed
 * message, the socket is treated as stale and force-reconnected.
 */
data class ReconnectPolicy(
    val enabled: Boolean = true,
    val maxAttempts: Int = 5,
    val baseDelayMs: Long = 500,
    val maxDelayMs: Long = 8_000,
    val jitterRatio: Double = 0.2,
    val heartbeatIntervalMs: Long = 15_000,
    val heartbeatTimeoutMs: Long = 45_000,
)

/**
 * Pure exponential-backoff delay calculator.
 *
 * [pureDelayMsForAttempt] is deterministic: `min(base * 2^(attempt-1), max)`,
 * so tests can assert exact values. [delayMsForAttempt] applies optional
 * [ReconnectPolicy.jitterRatio] spread over the pure delay using the injected
 * [random] source, which callers may seed for reproducibility.
 */
class BackoffCalculator(
    private val policy: ReconnectPolicy,
    private val random: Random = Random(),
) {

    /**
     * Deterministic exponential-backoff delay for [attempt] (1-based), before
     * jitter. A disabled policy or an invalid attempt yields `0`.
     */
    fun pureDelayMsForAttempt(attempt: Int): Long {
        if (!policy.enabled || attempt < 1) return 0L
        var exponent = 1L
        var shift = attempt - 1
        while (shift > 0) {
            // Cap the shift so a pathological attempt count cannot overflow.
            if (exponent > Long.MAX_VALUE / 2) {
                exponent = Long.MAX_VALUE
                break
            }
            exponent = exponent shl 1
            shift--
        }
        val base = policy.baseDelayMs.coerceAtLeast(0L)
        val doubled = if (base > Long.MAX_VALUE / exponent) Long.MAX_VALUE else base * exponent
        return min(doubled, policy.maxDelayMs.coerceAtLeast(0L))
    }

    /**
     * Jittered delay for [attempt]: the pure backoff value spread by ±
     * `jitterRatio * value`. The result is never below `1ms` when the policy is
     * enabled, so a retry is always scheduled in bounded time.
     */
    fun delayMsForAttempt(attempt: Int): Long {
        val pure = pureDelayMsForAttempt(attempt)
        if (pure <= 0L) return 0L
        if (policy.jitterRatio <= 0.0) return pure
        val magnitude = (pure * policy.jitterRatio.coerceIn(0.0, 1.0)).toLong().coerceAtLeast(1L)
        val offset = ((random.nextDouble() * 2.0) - 1.0) * magnitude
        return (pure + offset).toLong().coerceAtLeast(1L)
    }
}