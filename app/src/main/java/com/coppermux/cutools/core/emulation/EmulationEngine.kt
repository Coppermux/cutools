package com.coppermux.cutools.core.emulation

import com.coppermux.cutools.core.emulation.models.DeviceMetricsModel
import com.coppermux.cutools.core.emulation.models.EmulationActionResult
import com.coppermux.cutools.core.emulation.models.EmulationCapabilityStatus
import com.coppermux.cutools.core.emulation.models.EmulationError
import com.coppermux.cutools.core.emulation.models.MediaFeatureModel
import com.coppermux.cutools.core.emulation.models.ScreenOrientationModel
import com.coppermux.cutools.core.emulation.models.UserAgentOverrideModel
import com.coppermux.cutools.core.error.ConnectionException
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CommandDispatcher
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates real-time device, viewport, network, and media-feature emulation
 * for the currently attached page target via the CDP `Emulation` domain.
 *
 * Phase 9B — Emulation & Device Metrics Engine:
 * - [setDeviceMetricsOverride] applies a genuine device metrics override
 *   (width/height/scale/orientation) via `Emulation.setDeviceMetricsOverride`.
 * - [clearDeviceMetricsOverride] removes the active override via
 *   `Emulation.clearDeviceMetricsOverride`.
 * - [setUserAgentOverride] applies a genuine user-agent override via
 *   `Emulation.setUserAgentOverride`.
 * - [setTouchEmulationEnabled] enables/disables touch emulation via
 *   `Emulation.setTouchEmulationEnabled`.
 * - [setEmulatedMedia] sets emulated media type and features via
 *   `Emulation.setEmulatedMedia`.
 * - [setCPUThrottlingRate] throttles the CPU via `Emulation.setCPUThrottlingRate`.
 * - The pure [EmulationParser] ingests genuine CDP `Emulation` acknowledgments
 *   and state objects — never fabricating device, media, or throttling state
 *   (No-Fake-Capability rule).
 * - Session attach/detach lifecycle resets all target-scoped state; a
 *   generation counter rejects stale async results delivered after detach /
 *   re-bind.
 * - Protocol failures, missing sessions, unavailable methods, and connection
 *   loss each resolve to an explicit [EmulationActionResult.Failure] carrying
 *   an [EmulationError] (`Unsupported` / `Unavailable` / `ExecutionFailed` /
 *   `TargetDisconnected`).
 *
 * Threading: all shared state is `@Volatile`; the generation counter is atomic
 * so concurrent lifecycles never accept stale results. All Chrome
 * communication reuses the shared [CommandDispatcher] — no second transport or
 * dispatcher is created.
 */
class EmulationEngine(
    private val dispatcher: CommandDispatcher,
) {

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    /** The `Emulation` domain capability for the current session/target. */
    @Volatile
    var capability: EmulationCapabilityStatus? = null
        private set

    /** Whether a device metrics override is currently active. */
    @Volatile
    var deviceMetricsActive: Boolean = false
        private set

    /** Guards stale async results after detach / re-bind. */
    private val generation = AtomicInteger(0)

    // ================================================================
    // Lifecycle
    // ================================================================

    /** Binds to an attached session, resetting prior target state. */
    fun bindSession(newSession: SessionInfo) {
        generation.incrementAndGet()
        session = newSession
        resetState()
    }

    /** Detaches, clearing all target-scoped state. */
    fun detach() {
        generation.incrementAndGet()
        session = null
        resetState()
    }

    /** Handles connection loss — no commands are sent against the dead connection. */
    fun onConnectionLost() {
        detach()
    }

    /** Releases no event subscriptions (the domain is command-driven, not event-driven). */
    fun shutdown() {
        // no-op: the Emulation domain has no subscription state to release
    }

    // ================================================================
    // Commands
    // ================================================================

    /**
     * Applies a device metrics override via `Emulation.setDeviceMetricsOverride`.
     * Only the mandatory metrics plus genuinely-supplied optional fields are
     * serialized. On success [deviceMetricsActive] is set true.
     */
    fun setDeviceMetricsOverride(
        metrics: DeviceMetricsModel,
        callback: (EmulationActionResult<Unit>) -> Unit = {},
    ) {
        val sess = session
        if (sess == null) {
            capability = EmulationCapabilityStatus.UNAVAILABLE
            callback(EmulationActionResult.Failure(EmulationError.Unavailable))
            return
        }
        val params = deviceMetricsParams(metrics)
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Emulation.setDeviceMetricsOverride",
            params = params,
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = EmulationCapabilityStatus.SUPPORTED
                    deviceMetricsActive = true
                    callback(EmulationActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(EmulationActionResult.Failure(classifyFailure(result.error, "Emulation.setDeviceMetricsOverride")))
                }
            }
        }
    }

    /**
     * Clears the active device metrics override via
     * `Emulation.clearDeviceMetricsOverride`. On success [deviceMetricsActive]
     * is set false.
     */
    fun clearDeviceMetricsOverride(callback: (EmulationActionResult<Unit>) -> Unit = {}) {
        sendVoid("Emulation.clearDeviceMetricsOverride") { result ->
            when (result) {
                is EmulationActionResult.Success -> deviceMetricsActive = false
                is EmulationActionResult.Failure -> Unit
            }
            callback(result)
        }
    }

    /**
     * Applies a user-agent override via `Emulation.setUserAgentOverride`.
     * Only the mandatory `userAgent` plus genuinely-supplied optional
     * `acceptLanguage`/`platform` are serialized.
     */
    fun setUserAgentOverride(
        override: UserAgentOverrideModel,
        callback: (EmulationActionResult<Unit>) -> Unit = {},
    ) {
        val params = mutableMapOf<String, Any>()
        params["userAgent"] = override.userAgent
        override.acceptLanguage?.let { params["acceptLanguage"] = it }
        override.platform?.let { params["platform"] = it }
        sendVoid("Emulation.setUserAgentOverride", params, callback)
    }

    /**
     * Enables or disables touch emulation via
     * `Emulation.setTouchEmulationEnabled`. [maxTouchPoints] is serialized only
     * when supplied.
     */
    fun setTouchEmulationEnabled(
        enabled: Boolean,
        maxTouchPoints: Int? = null,
        callback: (EmulationActionResult<Unit>) -> Unit = {},
    ) {
        val params = mutableMapOf<String, Any>("enabled" to enabled)
        maxTouchPoints?.let { params["maxTouchPoints"] = it }
        sendVoid("Emulation.setTouchEmulationEnabled", params, callback)
    }

    /**
     * Sets the emulated media type and/or features via
     * `Emulation.setEmulatedMedia`. Only genuinely-supplied [media] and
     * [features] are serialized; features are emitted as `{name, value}` maps.
     */
    fun setEmulatedMedia(
        media: String? = null,
        features: List<MediaFeatureModel>? = null,
        callback: (EmulationActionResult<Unit>) -> Unit = {},
    ) {
        val params = mutableMapOf<String, Any>()
        media?.let { params["media"] = it }
        features?.let { params["features"] = it.map { f -> mapOf("name" to f.name, "value" to f.value) } }
        sendVoid("Emulation.setEmulatedMedia", params, callback)
    }

    /**
     * Sets the CPU throttling rate via `Emulation.setCPUThrottlingRate`. The
     * genuine [rate] is serialized as `rate`.
     */
    fun setCPUThrottlingRate(
        rate: Double,
        callback: (EmulationActionResult<Unit>) -> Unit = {},
    ) {
        sendVoid("Emulation.setCPUThrottlingRate", mapOf("rate" to rate), callback)
    }

    // ================================================================
    // Command plumbing & error mapping
    // ================================================================

    private fun sendVoid(
        method: String,
        params: Map<String, Any>? = null,
        callback: (EmulationActionResult<Unit>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            capability = EmulationCapabilityStatus.UNAVAILABLE
            callback(EmulationActionResult.Failure(EmulationError.Unavailable))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = method,
            params = params,
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = EmulationCapabilityStatus.SUPPORTED
                    callback(EmulationActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(EmulationActionResult.Failure(classifyFailure(result.error, method)))
                }
            }
        }
    }

    private fun deviceMetricsParams(metrics: DeviceMetricsModel): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        params["width"] = metrics.width
        params["height"] = metrics.height
        params["deviceScaleFactor"] = metrics.deviceScaleFactor
        params["mobile"] = metrics.mobile
        metrics.scale?.let { params["scale"] = it }
        metrics.screenWidth?.let { params["screenWidth"] = it }
        metrics.screenHeight?.let { params["screenHeight"] = it }
        metrics.positionX?.let { params["positionX"] = it }
        metrics.positionY?.let { params["positionY"] = it }
        metrics.dontSetVisibleSize?.let { params["dontSetVisibleSize"] = it }
        metrics.screenOrientation?.let { params["screenOrientation"] = screenOrientationParams(it) }
        return params
    }

    private fun screenOrientationParams(orientation: ScreenOrientationModel): Map<String, Any> =
        mapOf("type" to orientation.type, "angle" to orientation.angle)

    /**
     * Maps a CDP command failure to an explicit [EmulationError]. A
     * method-not-found error (`-32601`) is [EmulationError.Unsupported]; a
     * connection failure is [EmulationError.TargetDisconnected]; everything
     * else is [EmulationError.ExecutionFailed].
     */
    private fun classifyFailure(error: Throwable, method: String): EmulationError {
        val message = error.message.orEmpty()
        if (message.contains("-32601")) {
            capability = EmulationCapabilityStatus.UNSUPPORTED
            return EmulationError.Unsupported
        }
        if (error is ConnectionException) {
            capability = EmulationCapabilityStatus.UNAVAILABLE
            return EmulationError.TargetDisconnected
        }
        capability = EmulationCapabilityStatus.FAILED
        return EmulationError.ExecutionFailed(message.ifEmpty { "Unknown emulation error" })
    }

    private fun resetState() {
        capability = null
        deviceMetricsActive = false
    }
}
