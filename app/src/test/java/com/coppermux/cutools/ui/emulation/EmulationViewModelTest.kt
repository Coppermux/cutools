package com.coppermux.cutools.ui.emulation

import com.coppermux.cutools.core.emulation.EmulationEngine
import com.coppermux.cutools.core.emulation.models.DeviceMetricsModel
import com.coppermux.cutools.core.emulation.models.EmulationActionResult
import com.coppermux.cutools.core.emulation.models.EmulationCapabilityStatus
import com.coppermux.cutools.core.emulation.models.EmulationError
import com.coppermux.cutools.core.emulation.models.MediaFeatureModel
import com.coppermux.cutools.core.emulation.models.ScreenOrientationModel
import com.coppermux.cutools.core.emulation.models.UserAgentOverrideModel
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit & protocol test suite for the Phase 9C Emulation ViewModel.
 *
 * The real [EmulationEngine] (Phase 9B) is constructed against the fake
 * transport and driven through raw CDP JSON, then the ViewModel's state
 * transitions, command dispatching, override state recording, error
 * propagation into explicit banners, and attach / detach / connection-loss
 * coordination are verified in the same pipeline the app uses against Chrome.
 * No-Fake-Capability is enforced: engine failures surface as explicit
 * `errorMessage` banners with matching status, never synthetic override state.
 */
class EmulationViewModelTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: EmulationEngine
    private lateinit var viewModel: EmulationViewModel

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        dispatcher = CommandDispatcher(transport, null, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = EmulationEngine(dispatcher)
        viewModel = EmulationViewModel(engine)
    }

    private fun parseIdFor(method: String): Long {
        val raw = transport.sentMessages.last { it.contains(method) }
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    private fun waitFor(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return condition()
    }

    private val mobilePreset = DeviceMetricsModel(
        width = 390,
        height = 844,
        deviceScaleFactor = 3.0,
        mobile = true,
        screenOrientation = ScreenOrientationModel(type = "portraitPrimary", angle = 0),
    )

    // ============ target attach / detach coordination ============

    @Test
    fun attachTarget_bindsEngineAndPublishesState() {
        viewModel.attachTarget(session)
        assertEquals(session, engine.session)
        assertEquals(session, viewModel.attachedSession)
        assertTrue(viewModel.isAttached)
        assertEquals(EmulationCapabilityStatus.UNAVAILABLE, viewModel.state.value.status)
    }

    @Test
    fun detachTarget_clearsEngineAndResetsAllOverrideState() {
        viewModel.attachTarget(session)
        applyMobilePreset()

        viewModel.detachTarget()
        assertNull(engine.session)
        assertNull(viewModel.attachedSession)
        assertFalse(viewModel.isAttached)
        val state = viewModel.state.value
        assertEquals(EmulationCapabilityStatus.UNAVAILABLE, state.status)
        assertNull(state.activeMetrics)
        assertNull(state.activeUserAgent)
        assertFalse(state.touchEmulationEnabled)
        assertNull(state.emulatedMedia)
        assertTrue(state.activeMediaFeatures.isEmpty())
        assertTrue(state.cpuThrottlingRate == 1.0)
        assertNull(state.errorMessage)
    }

    @Test
    fun onConnectionLost_clearsEngineAndResetsState() {
        viewModel.attachTarget(session)
        applyMobilePreset()
        viewModel.onConnectionLost()
        assertNull(engine.session)
        assertNull(viewModel.attachedSession)
        assertEquals(EmulationCapabilityStatus.UNAVAILABLE, viewModel.state.value.status)
        assertNull(viewModel.state.value.activeMetrics)
    }

    // ============ command dispatching & state transitions ============

    private fun applyMobilePreset() {
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        viewModel.applyDevicePreset(mobilePreset) { results.add(it) }
        val id = parseIdFor("Emulation.setDeviceMetricsOverride")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is EmulationActionResult.Success<*>)
        assertTrue(waitFor { viewModel.state.value.activeMetrics == mobilePreset })
    }

    @Test
    fun applyDevicePreset_success_recordsMetricsAndStatus() {
        viewModel.attachTarget(session)
        applyMobilePreset()
        val state = viewModel.state.value
        assertEquals(EmulationCapabilityStatus.SUPPORTED, state.status)
        assertEquals(mobilePreset, state.activeMetrics)
        assertNull(state.errorMessage)
        assertTrue(engine.deviceMetricsActive)
    }

    @Test
    fun applyDevicePreset_dispatchesMethodWithSessionAndParams() {
        viewModel.attachTarget(session)
        transport.resetSentMessages()
        viewModel.applyDevicePreset(mobilePreset) {}
        val raw = transport.sentMessages.last { it.contains("Emulation.setDeviceMetricsOverride") }
        assertTrue(raw.contains("\"method\":\"Emulation.setDeviceMetricsOverride\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
        assertTrue(raw.contains("\"width\":390"))
        assertTrue(raw.contains("\"height\":844"))
        assertTrue(raw.contains("\"mobile\":true"))
        assertTrue(raw.contains("\"screenOrientation\":{\"type\":\"portraitPrimary\",\"angle\":0}"))
    }

    @Test
    fun clearDeviceMetrics_success_restoresNativeMetrics() {
        viewModel.attachTarget(session)
        applyMobilePreset()
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        viewModel.clearDeviceMetrics { results.add(it) }
        val id = parseIdFor("Emulation.clearDeviceMetricsOverride")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(waitFor { viewModel.state.value.activeMetrics == null })
        assertFalse(engine.deviceMetricsActive)
        assertEquals(EmulationCapabilityStatus.SUPPORTED, viewModel.state.value.status)
    }

    @Test
    fun setUserAgent_success_recordsUAAndDispatchesPlatform() {
        viewModel.attachTarget(session)
        val ua = UserAgentOverrideModel(userAgent = "UA-1", platform = "Linux")
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        viewModel.setUserAgent(ua) { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Emulation.setUserAgentOverride") }
        assertTrue(raw.contains("\"userAgent\":\"UA-1\""))
        assertTrue(raw.contains("\"platform\":\"Linux\""))
        val id = parseIdFor("Emulation.setUserAgentOverride")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(waitFor { viewModel.state.value.activeUserAgent == ua })
        assertEquals(EmulationCapabilityStatus.SUPPORTED, viewModel.state.value.status)
    }

    @Test
    fun toggleTouchEmulation_success_recordsEnabledState() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        viewModel.toggleTouchEmulation(true) { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Emulation.setTouchEmulationEnabled") }
        assertTrue(raw.contains("\"enabled\":true"))
        val id = parseIdFor("Emulation.setTouchEmulationEnabled")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(waitFor { viewModel.state.value.touchEmulationEnabled })

        viewModel.toggleTouchEmulation(false) {}
        val offId = parseIdFor("Emulation.setTouchEmulationEnabled")
        transport.deliverMessage("""{"id":$offId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { !viewModel.state.value.touchEmulationEnabled })
    }

    @Test
    fun setMediaOverrides_success_recordsMediaAndFeatures() {
        viewModel.attachTarget(session)
        val features = listOf(
            MediaFeatureModel(name = "prefers-color-scheme", value = "dark"),
            MediaFeatureModel(name = "prefers-reduced-motion", value = "reduce"),
        )
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        viewModel.setMediaOverrides("screen", features) { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Emulation.setEmulatedMedia") }
        assertTrue(raw.contains("\"media\":\"screen\""))
        assertTrue(raw.contains("\"features\":[{\"name\":\"prefers-color-scheme\",\"value\":\"dark\"},{\"name\":\"prefers-reduced-motion\",\"value\":\"reduce\"}]"))
        val id = parseIdFor("Emulation.setEmulatedMedia")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(waitFor { viewModel.state.value.emulatedMedia == "screen" && viewModel.state.value.activeMediaFeatures == features })
    }

    @Test
    fun setCPUThrottling_success_recordsRateAndDispatches() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        viewModel.setCPUThrottling(4.0) { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Emulation.setCPUThrottlingRate") }
        assertTrue(raw.contains("\"rate\":4.0"))
        val id = parseIdFor("Emulation.setCPUThrottlingRate")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(waitFor { viewModel.state.value.cpuThrottlingRate == 4.0 })
        assertEquals(EmulationCapabilityStatus.SUPPORTED, viewModel.state.value.status)
    }

    // ============ error handling (No-Fake-Capability) ============

    @Test
    fun applyDevicePreset_withoutSession_resolvesUnavailableBannerWithoutFallback() {
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        viewModel.applyDevicePreset(mobilePreset) { results.add(it) }
        assertEquals(1, results.size)
        assertTrue(results[0] is EmulationActionResult.Failure)
        val state = viewModel.state.value
        assertEquals(EmulationCapabilityStatus.UNAVAILABLE, state.status)
        assertTrue(state.errorMessage.orEmpty().contains("Unavailable"))
        assertNull(state.activeMetrics)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun applyDevicePreset_methodUnavailable_resolvesUnsupportedBannerWithoutFallback() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        viewModel.applyDevicePreset(mobilePreset) { results.add(it) }
        val id = parseIdFor("Emulation.setDeviceMetricsOverride")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is EmulationActionResult.Failure)
        assertTrue(waitFor { viewModel.state.value.errorMessage != null })
        val state = viewModel.state.value
        assertEquals(EmulationCapabilityStatus.UNSUPPORTED, state.status)
        assertTrue(state.errorMessage.orEmpty().contains("Unsupported"))
        assertNull(state.activeMetrics)
    }

    @Test
    fun setUserAgent_protocolFailure_resolvesFailedBannerWithoutFallback() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        viewModel.setUserAgent(UserAgentOverrideModel(userAgent = "UA-1")) { results.add(it) }
        val id = parseIdFor("Emulation.setUserAgentOverride")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"ua broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(waitFor { viewModel.state.value.errorMessage != null })
        assertNull(viewModel.state.value.activeUserAgent)
        assertTrue(viewModel.state.value.errorMessage.orEmpty().contains("ua broke"))
    }

    @Test
    fun setCPUThrottling_protocolFailure_keepsPreviousRate() {
        viewModel.attachTarget(session)
        viewModel.setCPUThrottling(2.0) {}
        val okId = parseIdFor("Emulation.setCPUThrottlingRate")
        transport.deliverMessage("""{"id":$okId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { viewModel.state.value.cpuThrottlingRate == 2.0 })

        viewModel.setCPUThrottling(6.0) {}
        val failId = parseIdFor("Emulation.setCPUThrottlingRate")
        transport.deliverMessage("""{"id":$failId,"sessionId":"session-1","error":{"code":-32000,"message":"throttle broke"}}""")
        assertTrue(waitFor { viewModel.state.value.errorMessage != null })
        assertTrue(viewModel.state.value.cpuThrottlingRate == 2.0)
        assertTrue(viewModel.state.value.errorMessage.orEmpty().contains("throttle broke"))
    }

    @Test
    fun successAfterError_clearsBannerAndRecordsState() {
        viewModel.attachTarget(session)
        viewModel.applyDevicePreset(mobilePreset) {}
        val failId = parseIdFor("Emulation.setDeviceMetricsOverride")
        transport.deliverMessage("""{"id":$failId,"sessionId":"session-1","error":{"code":-32000,"message":"broke"}}""")
        assertTrue(waitFor { viewModel.state.value.errorMessage != null })

        viewModel.applyDevicePreset(mobilePreset) {}
        val okId = parseIdFor("Emulation.setDeviceMetricsOverride")
        transport.deliverMessage("""{"id":$okId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { viewModel.state.value.activeMetrics == mobilePreset })
        assertNull(viewModel.state.value.errorMessage)
        assertEquals(EmulationCapabilityStatus.SUPPORTED, viewModel.state.value.status)
    }

    // ============ error description helpers & presets ============

    @Test
    fun describeError_mapsEveryErrorVariantToRenderableBanner() {
        assertTrue(viewModel.describeError(EmulationError.Unsupported).contains("Unsupported"))
        assertTrue(viewModel.describeError(EmulationError.Unavailable).contains("Unavailable"))
        assertTrue(viewModel.describeError(EmulationError.TargetDisconnected).contains("disconnected"))
        assertTrue(viewModel.describeError(EmulationError.ExecutionFailed("boom")).contains("boom"))
        assertTrue(viewModel.describeError(EmulationError.ExecutionFailed("")).contains("execution failed"))
    }

    @Test
    fun devicePresets_coverMobileTabletDesktop() {
        assertEquals(3, EmulationViewModel.DEVICE_PRESETS.size)
        val labels = EmulationViewModel.DEVICE_PRESETS.map { it.first }
        assertTrue(labels.any { it.contains("Mobile") })
        assertTrue(labels.any { it.contains("Tablet") })
        assertTrue(labels.any { it.contains("Desktop") })
        val desktop = EmulationViewModel.DEVICE_PRESETS.first { it.first.contains("Desktop") }.second
        assertEquals(1280, desktop.width)
        assertEquals(720, desktop.height)
        assertFalse(desktop.mobile)
    }

    @Test
    fun userAgentPresets_coverMobileAndDesktop() {
        assertEquals(2, EmulationViewModel.USER_AGENT_PRESETS.size)
        val labels = EmulationViewModel.USER_AGENT_PRESETS.map { it.first }
        assertTrue(labels.any { it.contains("Mobile") })
        assertTrue(labels.any { it.contains("Desktop") })
        assertTrue(EmulationViewModel.USER_AGENT_PRESETS.any { it.second.platform == "Android" })
        assertTrue(EmulationViewModel.USER_AGENT_PRESETS.any { it.second.platform == "Linux" })
    }

    @Test
    fun cpuThrottleRates_expose1x2x4x6x() {
        assertEquals(listOf(1.0, 2.0, 4.0, 6.0), EmulationViewModel.CPU_THROTTLE_RATES)
    }
}