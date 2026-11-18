package com.coppermux.cutools.core.target

import com.coppermux.cutools.core.target.models.TargetInfoModel

/**
 * Parses genuine `Target` domain wire payloads into typed target models without
 * fabricating any state.
 *
 * Pure and stateless so it is trivially unit-testable. Every method is
 * fail-safe: missing or malformed fields fall back to safe defaults and never
 * throw (No-Fake-Capability rule). [targetId] and [type] are mandatory wire
 * fields for a meaningful target; when either is absent the entry is skipped.
 */
object TargetParser {

    /**
     * Parses the `targetInfos` array from a `Target.getTargets` response result
     * object into a list of [TargetInfoModel]. Returns an empty list when [raw]
     * is not a map or carries no `targetInfos` array; individual malformed
     * entries are skipped.
     */
    fun parseGetTargets(raw: Any?): List<TargetInfoModel> {
        if (raw !is Map<*, *>) return emptyList()
        val infos = raw["targetInfos"]
        if (infos !is List<*>) return emptyList()
        return infos.mapNotNull { parseTargetInfo(it) }
    }

    /**
     * Parses a single raw CDP target descriptor into a [TargetInfoModel].
     *
     * [raw] may be a `targetInfo` object (as carried by `Target.targetCreated`
     * and `Target.targetInfoChanged` events) or a bare `targetId`/`type` map.
     * Returns null when [raw] is not a map or lacks a mandatory `targetId` or
     * `type`.
     */
    fun parseTargetInfo(raw: Any?): TargetInfoModel? {
        if (raw !is Map<*, *>) return null
        val targetId = raw["targetId"] as? String ?: return null
        val type = raw["type"] as? String ?: return null
        return TargetInfoModel(
            targetId = targetId,
            type = type,
            title = (raw["title"] as? String) ?: "",
            url = (raw["url"] as? String) ?: "",
            attached = (raw["attached"] as? Boolean) ?: false,
            openerId = raw["openerId"] as? String,
            browserContextId = raw["browserContextId"] as? String,
        )
    }

    /**
     * Extracts the `targetId` from a `Target.targetDestroyed` event params map,
     * or null when absent.
     */
    fun parseDestroyedTargetId(raw: Any?): String? {
        if (raw !is Map<*, *>) return null
        return raw["targetId"] as? String
    }
}