package com.coppermux.cutools.core.memory

import com.coppermux.cutools.core.memory.models.DomCountersModel
import com.coppermux.cutools.core.memory.models.HeapSamplingNodeModel
import com.coppermux.cutools.core.memory.models.HeapSamplingProfileModel

/**
 * Pure, stateless, fail-safe parser for genuine CDP `HeapProfiler` and `Memory`
 * domain wire payloads.
 *
 * Parsing never deserializes into the typed models and never throws on missing
 * or unknown fields: malformed entries are skipped and every missing field
 * falls back to a safe default. Heap snapshot chunks are extracted one at a time
 * (never accumulated by the parser, so no memory can leak here), and sampling
 * profile trees are converted into an immutable hierarchy. No memory state is
 * ever fabricated (No-Fake-Capability rule).
 *
 * Threading: the parser is an immutable, stateless singleton; all methods are
 * pure so it is trivially safe to call from any thread.
 */
object MemoryParser {

    /**
     * Parses a raw `Memory.getDOMCounters` response result map into an immutable
     * [DomCountersModel]. Returns null when [raw] is not a map. Missing counter
     * fields default to `0`. Never throws.
     */
    fun parseDomCounters(raw: Any?): DomCountersModel? {
        if (raw !is Map<*, *>) return null
        return DomCountersModel(
            documents = (raw["documents"] as? Number)?.toInt() ?: 0,
            nodes = (raw["nodes"] as? Number)?.toInt() ?: 0,
            jsEventListeners = (raw["jsEventListeners"] as? Number)?.toInt() ?: 0,
        )
    }

    /**
     * Extracts the raw `chunk` text from a `HeapProfiler.addHeapSnapshotChunk`
     * event params map. Returns null when [raw] is not a map or carries no
     * genuine string `chunk`. The parser does not buffer chunks — it returns the
     * single fragment for the caller to accumulate — so it cannot leak memory.
     * Never throws.
     */
    fun parseHeapSnapshotChunk(raw: Any?): String? {
        if (raw !is Map<*, *>) return null
        return raw["chunk"] as? String
    }

    /**
     * Parses a raw `HeapProfiler.stopSampling` result payload (a map carrying a
     * `profile` object) into a [HeapSamplingProfileModel]. The `head` node is
     * parsed hierarchically and `samplesCount` is derived from the genuine
     * `samples` array when present. Returns null when [raw] is not a map or has
     * no valid `head` node, so a malformed result surfaces as a failure rather
     * than fabricated profile data. Never throws.
     */
    fun parseSamplingProfile(raw: Any?): HeapSamplingProfileModel? {
        if (raw !is Map<*, *>) return null
        val head = parseSamplingNode(raw["head"]) ?: return null
        val samples = raw["samples"] as? List<*>
        return HeapSamplingProfileModel(
            head = head,
            samplesCount = samples?.size ?: 0,
        )
    }

    /**
     * Parses a single wire `SamplingHeapProfileNode` (a JSON map) into a
     * [HeapSamplingNodeModel]. Returns null when [raw] is not a map or carries
     * no valid numeric `id`. Missing call-frame fields fall back to safe
     * defaults and `selfSize` defaults to `0L`. Never throws.
     */
    fun parseSamplingNode(raw: Any?): HeapSamplingNodeModel? {
        if (raw !is Map<*, *>) return null
        val id = (raw["id"] as? Number)?.toInt() ?: return null
        val callFrame = raw["callFrame"] as? Map<*, *>
        val children = (raw["children"] as? List<*>)
            ?.mapNotNull { parseSamplingNode(it) }
            ?: emptyList()
        return HeapSamplingNodeModel(
            id = id,
            callFrameFunction = callFrame?.get("functionName") as? String,
            scriptId = callFrame?.get("scriptId") as? String,
            url = callFrame?.get("url") as? String,
            lineNumber = (callFrame?.get("lineNumber") as? Number)?.toInt(),
            columnNumber = (callFrame?.get("columnNumber") as? Number)?.toInt(),
            selfSize = (raw["selfSize"] as? Number)?.toLong() ?: 0L,
            children = children,
        )
    }
}
