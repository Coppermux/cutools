package com.coppermux.cutools.core.performance

import com.coppermux.cutools.core.performance.models.MetricValueModel
import com.coppermux.cutools.core.performance.models.PerformanceMetricsSnapshotModel

/**
 * Pure, stateless, fail-safe parser for genuine CDP `Performance` domain wire
 * payloads.
 *
 * Parsing never deserializes into the typed models and never throws on missing
 * or unknown metric attributes: malformed or absent metric entries are skipped,
 * unknown metric names are preserved verbatim, and every missing field falls
 * back to a safe default. No metric state is ever fabricated (No-Fake-Capability).
 *
 * Threading: the parser is an immutable, stateless singleton; all methods are
 * pure so it is trivially safe to call from any thread.
 */
object PerformanceParser {

    /**
     * Parses a raw `Performance.getMetrics` response result or
     * `Performance.metrics` event params map (carrying a `metrics` array) into
     * an immutable [PerformanceMetricsSnapshotModel]. Returns null when [raw]
     * is not a map. A missing or malformed `metrics` array yields an empty
     * snapshot. Never throws.
     */
    fun parseSnapshot(raw: Any?): PerformanceMetricsSnapshotModel? {
        if (raw !is Map<*, *>) return null
        return PerformanceMetricsSnapshotModel(
            metrics = parseMetrics(raw["metrics"]),
        )
    }

    /**
     * Parses a raw `metrics` array into a list of [MetricValueModel]. Non-map
     * or name-less entries are skipped; a missing numeric `value` defaults to
     * `0.0`. Never throws.
     */
    fun parseMetrics(raw: Any?): List<MetricValueModel> {
        if (raw !is List<*>) return emptyList()
        return raw.mapNotNull { parseMetricValue(it) }
    }

    /**
     * Parses a single CDP `MetricValue` map into a [MetricValueModel]. Returns
     * null when [raw] is not a map or carries no genuine `name`. Never throws.
     */
    fun parseMetricValue(raw: Any?): MetricValueModel? {
        if (raw !is Map<*, *>) return null
        val name = raw["name"] as? String ?: return null
        val value = (raw["value"] as? Number)?.toDouble() ?: 0.0
        return MetricValueModel(name = name, value = value)
    }
}