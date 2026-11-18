package com.coppermux.cutools.core.emulation

import com.coppermux.cutools.core.emulation.models.DeviceMetricsModel
import com.coppermux.cutools.core.emulation.models.MediaFeatureModel
import com.coppermux.cutools.core.emulation.models.ScreenOrientationModel

/**
 * Pure, stateless, fail-safe parser for genuine CDP `Emulation` domain wire
 * payloads.
 *
 * Parsing never deserializes into the typed models (avoiding class-loading /
 * overflow risk) and never throws on missing or malformed fields: mandatory
 * fields are required only where the CDP contract demands them (e.g. a
 * `DeviceMetricsModel` requires width/height/deviceScaleFactor/mobile), while
 * every optional field falls back to a safe default when absent or malformed.
 * No emulation state is ever fabricated (No-Fake-Capability).
 *
 * Threading: the parser is an immutable, stateless singleton; all methods are
 * pure so it is trivially safe to call from any thread.
 */
object EmulationParser {

    /**
     * Parses a raw CDP device metrics object (a JSON map carrying `width`,
     * `height`, `deviceScaleFactor`, `mobile` plus optional fields) into an
     * immutable [DeviceMetricsModel]. Returns null when [raw] is not a map or
     * lacks a mandatory field. Optional fields default safely.
     */
    fun parseDeviceMetrics(raw: Any?): DeviceMetricsModel? {
        if (raw !is Map<*, *>) return null
        val width = (raw["width"] as? Number)?.toInt() ?: return null
        val height = (raw["height"] as? Number)?.toInt() ?: return null
        val deviceScaleFactor = (raw["deviceScaleFactor"] as? Number)?.toDouble() ?: return null
        val mobile = (raw["mobile"] as? Boolean) ?: return null
        return DeviceMetricsModel(
            width = width,
            height = height,
            deviceScaleFactor = deviceScaleFactor,
            mobile = mobile,
            scale = (raw["scale"] as? Number)?.toDouble(),
            screenWidth = (raw["screenWidth"] as? Number)?.toInt(),
            screenHeight = (raw["screenHeight"] as? Number)?.toInt(),
            positionX = (raw["positionX"] as? Number)?.toInt(),
            positionY = (raw["positionY"] as? Number)?.toInt(),
            dontSetVisibleSize = raw["dontSetVisibleSize"] as? Boolean,
            screenOrientation = parseScreenOrientation(raw["screenOrientation"]),
        )
    }

    /**
     * Parses a raw CDP `ScreenOrientation` map (carrying `type` and `angle`)
     * into a [ScreenOrientationModel]. Returns null when [raw] is not a map or
     * lacks the mandatory `type`/`angle`.
     */
    fun parseScreenOrientation(raw: Any?): ScreenOrientationModel? {
        if (raw !is Map<*, *>) return null
        val type = raw["type"] as? String ?: return null
        val angle = (raw["angle"] as? Number)?.toInt() ?: return null
        return ScreenOrientationModel(type = type, angle = angle)
    }

    /**
     * Parses a raw `Emulation.setEmulatedMedia` result object (a JSON map that
     * may carry a `features` array of `{name, value}` objects) into a list of
     * [MediaFeatureModel]. Returns empty list when absent or malformed; never
     * throws.
     */
    fun parseMediaFeatures(raw: Any?): List<MediaFeatureModel> {
        if (raw !is Map<*, *>) return emptyList()
        val features = raw["features"]
        if (features !is List<*>) return emptyList()
        return features.mapNotNull { parseMediaFeature(it) }
    }

    /**
     * Parses a single raw `Emulation.setEmulatedMedia` feature map into a
     * [MediaFeatureModel]. Returns null when [raw] is not a map or lacks a
     * mandatory `name`/`value`.
     */
    fun parseMediaFeature(raw: Any?): MediaFeatureModel? {
        if (raw !is Map<*, *>) return null
        val name = raw["name"] as? String ?: return null
        val value = raw["value"] as? String ?: return null
        return MediaFeatureModel(name = name, value = value)
    }

    /**
     * Safely extracts the CPU throttling rate (`rate`) from a raw
     * `Emulation.setCPUThrottlingRate` acknowledgment object. Returns the
     * genuine rate when present, or null when absent / not a number.
     */
    fun parseCpuThrottlingRate(raw: Any?): Double? {
        if (raw !is Map<*, *>) return null
        return (raw["rate"] as? Number)?.toDouble()
    }

    /**
     * Safely extracts a boolean from a raw object (e.g. current
     * `mobile` flag) with a fallback default when absent or malformed.
     */
    fun parseBoolean(raw: Any?, key: String, default: Boolean): Boolean =
        if (raw is Map<*, *>) (raw[key] as? Boolean) ?: default else default
}
