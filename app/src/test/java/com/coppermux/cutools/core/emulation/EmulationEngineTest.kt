package com.coppermux.cutools.core.emulation

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit & protocol test suite for Phase 9B Emulation & Device Metrics Engine.
 *
 * Raw CDP JSON messages are delivered via the fake transport and flow through
 * the classifier → router → dispatcher → engine, proving
 * `Emulation.setDeviceMetricsOverride` / `clearDeviceMetricsOverride` /
 * `setUserAgentOverride` / `setTouchEmulationEnabled` / `setEmulatedMedia` /
 * `setCPUThrottlingRate` serialization, override reset, error mapping, and
 * lifecycle safety in the same pipeline the app uses against Chrome.
 * No-Fake-Capability is enforced: protocol failures, missing sessions,
 * unavailable methods, and connection loss map to explicit UNSUPPORTED /
 * UNAVAILABLE / FAILED / TARGET_DISCONNECTED outcomes, never fabricated
 * emulation state.
 */
class EmulationEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: EmulationEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        dispatcher = CommandDispatcher(transport, null, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = EmulationEngine(dispatcher)
    }

    private fun bind() {
        engine.bindSession(session)
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

    private val metrics = DeviceMetricsModel(
        width = 390,
        height = 844,
        deviceScaleFactor = 3.0,
        mobile = true,
        scale = 1.0,
        screenWidth = 390,
        screenHeight = 844,
        positionX = 0,
        positionY = 0,
        dontSetVisibleSize = false,
        screenOrientation = ScreenOrientationModel(type = "portraitPrimary", angle = 0),
    )

    // ============ payload generation ============

    @Test
    fun setDeviceMetricsOverride_serializesMethodSessionAndAllParams() {
        bind()
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        engine.setDeviceMetricsOverride(metrics) { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Emulation.setDeviceMetricsOverride") }
        assertTrue(raw.contains("\"method\":\"Emulation.setDeviceMetricsOverride\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
        assertTrue(raw.contains("\"width\":390"))
        assertTrue(raw.contains("\"height\":844"))
        assertTrue(raw.contains("\"deviceScaleFactor\":3.0"))
        assertTrue(raw.contains("\"mobile\":true"))
        assertTrue(raw.contains("\"screenOrientation\":{\"type\":\"portraitPrimary\",\"angle\":0}"))
    }

    @Test
    fun setUserAgentOverride_serializesMethodAndParams() {
        bind()
        engine.setUserAgentOverride(
            UserAgentOverrideModel(userAgent = "UA-1", acceptLanguage = "en-US", platform = "macOS")
        ) { }
        val raw = transport.sentMessages.last { it.contains("Emulation.setUserAgentOverride") }
        assertTrue(raw.contains("\"method\":\"Emulation.setUserAgentOverride\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
        assertTrue(raw.contains("\"userAgent\":\"UA-1\""))
        assertTrue(raw.contains("\"acceptLanguage\":\"en-US\""))
        assertTrue(raw.contains("\"platform\":\"macOS\""))
    }

    @Test
    fun setUserAgentOverride_onlyUserAgent_omitsOptionalParams() {
        bind()
        engine.setUserAgentOverride(UserAgentOverrideModel(userAgent = "UA-1")) { }
        val raw = transport.sentMessages.last { it.contains("Emulation.setUserAgentOverride") }
        assertTrue(raw.contains("\"userAgent\":\"UA-1\""))
        assertTrue(!raw.contains("\"acceptLanguage\""))
        assertTrue(!raw.contains("\"platform\""))
    }

    @Test
    fun setTouchEmulationEnabled_serializesEnabledAndMaxTouchPoints() {
        bind()
        engine.setTouchEmulationEnabled(true, maxTouchPoints = 5) { }
        val raw = transport.sentMessages.last { it.contains("Emulation.setTouchEmulationEnabled") }
        assertTrue(raw.contains("\"method\":\"Emulation.setTouchEmulationEnabled\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
        assertTrue(raw.contains("\"enabled\":true"))
        assertTrue(raw.contains("\"maxTouchPoints\":5"))
    }

    @Test
    fun setTouchEmulationEnabled_withoutMaxTouchPoints_omitsKey() {
        bind()
        engine.setTouchEmulationEnabled(false) { }
        val raw = transport.sentMessages.last { it.contains("Emulation.setTouchEmulationEnabled") }
        assertTrue(raw.contains("\"enabled\":false"))
        assertTrue(!raw.contains("\"maxTouchPoints\""))
    }

    @Test
    fun setEmulatedMedia_serializesMediaAndFeatures() {
        bind()
        engine.setEmulatedMedia(
            media = "screen",
            features = listOf(
                MediaFeatureModel("prefers-color-scheme", "dark"),
                MediaFeatureModel("prefers-reduced-motion", "reduce"),
            ),
        ) { }
        val raw = transport.sentMessages.last { it.contains("Emulation.setEmulatedMedia") }
        assertTrue(raw.contains("\"method\":\"Emulation.setEmulatedMedia\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
        assertTrue(raw.contains("\"media\":\"screen\""))
        assertTrue(raw.contains("\"name\":\"prefers-color-scheme\""))
        assertTrue(raw.contains("\"value\":\"dark\""))
        assertTrue(raw.contains("\"name\":\"prefers-reduced-motion\""))
    }

    @Test
    fun setEmulatedMedia_noParams_omitsMediaAndFeatures() {
        bind()
        engine.setEmulatedMedia() { }
        val raw = transport.sentMessages.last { it.contains("Emulation.setEmulatedMedia") }
        assertTrue(raw.contains("\"method\":\"Emulation.setEmulatedMedia\""))
        assertTrue(!raw.contains("\"media\""))
        assertTrue(!raw.contains("\"features\""))
    }

    @Test
    fun setCPUThrottlingRate_serializesRate() {
        bind()
        engine.setCPUThrottlingRate(6.0) { }
        val raw = transport.sentMessages.last { it.contains("Emulation.setCPUThrottlingRate") }
        assertTrue(raw.contains("\"method\":\"Emulation.setCPUThrottlingRate\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
        assertTrue(raw.contains("\"rate\":6.0"))
    }

    // ============ override reset ============

    @Test
    fun clearDeviceMetricsOverride_serializesMethodAndSessionOnly() {
        bind()
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        engine.clearDeviceMetricsOverride { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Emulation.clearDeviceMetricsOverride") }
        assertTrue(raw.contains("\"method\":\"Emulation.clearDeviceMetricsOverride\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun clearDeviceMetricsOverride_success_clearsActiveFlag() {
        bind()
        val setResults = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        engine.setDeviceMetricsOverride(metrics) { setResults.add(it) }
        val setId = parseIdFor("Emulation.setDeviceMetricsOverride")
        transport.deliverMessage("""{"id":$setId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.deviceMetricsActive })

        val clearResults = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        engine.clearDeviceMetricsOverride { clearResults.add(it) }
        val clearId = parseIdFor("Emulation.clearDeviceMetricsOverride")
        transport.deliverMessage("""{"id":$clearId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { clearResults.size == 1 })
        assertTrue(clearResults[0] is EmulationActionResult.Success<*>)
        assertTrue(waitFor { !engine.deviceMetricsActive })
    }

    // ============ domain success ============

    @Test
    fun setDeviceMetricsOverride_success_setsCapabilityAndActive() {
        bind()
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        engine.setDeviceMetricsOverride(metrics) { results.add(it) }
        val id = parseIdFor("Emulation.setDeviceMetricsOverride")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is EmulationActionResult.Success<*>)
        assertEquals(EmulationCapabilityStatus.SUPPORTED, engine.capability)
        assertTrue(engine.deviceMetricsActive)
    }

    @Test
    fun setCPUThrottlingRate_success_setsCapability() {
        bind()
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        engine.setCPUThrottlingRate(4.0) { results.add(it) }
        val id = parseIdFor("Emulation.setCPUThrottlingRate")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is EmulationActionResult.Success<*>)
        assertEquals(EmulationCapabilityStatus.SUPPORTED, engine.capability)
    }

    // ============ error mapping ============

    @Test
    fun setDeviceMetricsOverride_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        engine.setDeviceMetricsOverride(metrics) { results.add(it) }
        assertEquals(1, results.size)
        assertTrue(results[0] is EmulationActionResult.Failure)
        assertTrue((results[0] as EmulationActionResult.Failure).error === EmulationError.Unavailable)
        assertEquals(EmulationCapabilityStatus.UNAVAILABLE, engine.capability)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun clearDeviceMetricsOverride_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        engine.clearDeviceMetricsOverride { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as EmulationActionResult.Failure).error === EmulationError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun setUserAgentOverride_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        engine.setUserAgentOverride(UserAgentOverrideModel("UA")) { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as EmulationActionResult.Failure).error === EmulationError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun setTouchEmulationEnabled_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        engine.setTouchEmulationEnabled(true) { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as EmulationActionResult.Failure).error === EmulationError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun setEmulatedMedia_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        engine.setEmulatedMedia(media = "screen") { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as EmulationActionResult.Failure).error === EmulationError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun setCPUThrottlingRate_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        engine.setCPUThrottlingRate(2.0) { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as EmulationActionResult.Failure).error === EmulationError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun setCPUThrottlingRate_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        engine.setCPUThrottlingRate(3.0) { results.add(it) }
        val id = parseIdFor("Emulation.setCPUThrottlingRate")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as EmulationActionResult.Failure).error === EmulationError.Unsupported)
        assertEquals(EmulationCapabilityStatus.UNSUPPORTED, engine.capability)
    }

    @Test
    fun setEmulatedMedia_protocolFailure_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        engine.setEmulatedMedia(media = "print") { results.add(it) }
        val id = parseIdFor("Emulation.setEmulatedMedia")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"media broke"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as EmulationActionResult.Failure
        assertTrue(failure.error is EmulationError.ExecutionFailed)
        assertTrue((failure.error as EmulationError.ExecutionFailed).message.contains("media broke"))
        assertEquals(EmulationCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun setTouchEmulationEnabled_afterConnectionLost_mapsToTargetDisconnected() {
        bind()
        transport.disconnect()
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        engine.setTouchEmulationEnabled(true) { results.add(it) }
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as EmulationActionResult.Failure).error === EmulationError.TargetDisconnected)
    }

    // ============ lifecycle / detach safety ============

    @Test
    fun detach_clearsStateAndCapability() {
        bind()
        engine.setDeviceMetricsOverride(metrics) { }
        val id = parseIdFor("Emulation.setDeviceMetricsOverride")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.deviceMetricsActive })
        engine.detach()
        assertNull(engine.session)
        assertNull(engine.capability)
        assertTrue(!engine.deviceMetricsActive)
    }

    @Test
    fun bindSession_resetsPreviousTargetState() {
        bind()
        val other = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(other)
        assertEquals(other, engine.session)
        assertNull(engine.capability)
        assertTrue(!engine.deviceMetricsActive)
    }

    @Test
    fun onConnectionLost_detachesAndClearsState() {
        bind()
        engine.onConnectionLost()
        assertNull(engine.session)
        assertNull(engine.capability)
        assertTrue(!engine.deviceMetricsActive)
    }

    @Test
    fun staleResponseAfterDetach_isIgnored() {
        bind()
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        engine.setCPUThrottlingRate(5.0) { results.add(it) }
        val id = parseIdFor("Emulation.setCPUThrottlingRate")
        engine.detach()
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        Thread.sleep(100)
        assertTrue(results.isEmpty())
        assertNull(engine.session)
    }

    @Test
    fun setCPUThrottlingRate_afterDetach_isUnavailable() {
        bind()
        engine.detach()
        val results = CopyOnWriteArrayList<EmulationActionResult<Unit>>()
        engine.setCPUThrottlingRate(2.0) { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as EmulationActionResult.Failure).error === EmulationError.Unavailable)
    }

    @Test
    fun shutdown_isSafeNoop() {
        bind()
        engine.shutdown()
        assertTrue(engine.session == session)
    }

    // ============ parser & model-level mapping ============

    @Test
    fun parser_parseDeviceMetrics_parsesAllFields() {
        val model = EmulationParser.parseDeviceMetrics(
            mapOf<String, Any?>(
                "width" to 390, "height" to 844, "deviceScaleFactor" to 3.0, "mobile" to true,
                "scale" to 1.0, "screenWidth" to 390, "screenHeight" to 844,
                "positionX" to 0, "positionY" to 0, "dontSetVisibleSize" to false,
                "screenOrientation" to mapOf("type" to "portraitPrimary", "angle" to 0),
            )
        )
        assertEquals(390, model!!.width)
        assertEquals(844, model!!.height)
        assertEquals(3.0, model!!.deviceScaleFactor, 0.0)
        assertTrue(model!!.mobile)
        assertEquals("portraitPrimary", model!!.screenOrientation!!.type)
        assertEquals(0, model!!.screenOrientation!!.angle)
    }

    @Test
    fun parser_parseDeviceMetrics_missingMandatory_returnsNull() {
        assertNull(EmulationParser.parseDeviceMetrics(mapOf<String, Any?>("width" to 1)))
        assertNull(EmulationParser.parseDeviceMetrics("not-a-map"))
        assertNull(EmulationParser.parseDeviceMetrics(null))
    }

    @Test
    fun parser_parseDeviceMetrics_missingOptional_safeDefaults() {
        val model = EmulationParser.parseDeviceMetrics(
            mapOf<String, Any?>("width" to 100, "height" to 200, "deviceScaleFactor" to 1.0, "mobile" to false)
        )
        assertNull(model!!.scale)
        assertNull(model!!.screenWidth)
        assertNull(model!!.screenHeight)
        assertNull(model!!.positionX)
        assertNull(model!!.positionY)
        assertNull(model!!.dontSetVisibleSize)
        assertNull(model!!.screenOrientation)
    }

    @Test
    fun parser_parseScreenOrientation_safe() {
        val o = EmulationParser.parseScreenOrientation(mapOf<String, Any?>("type" to "landscapePrimary", "angle" to 90))
        assertEquals("landscapePrimary", o!!.type)
        assertEquals(90, o!!.angle)
        assertNull(EmulationParser.parseScreenOrientation(mapOf<String, Any?>("type" to "x")))
        assertNull(EmulationParser.parseScreenOrientation("not-a-map"))
        assertNull(EmulationParser.parseScreenOrientation(null))
    }

    @Test
    fun parser_parseMediaFeatures_safe() {
        val features = EmulationParser.parseMediaFeatures(
            mapOf<String, Any?>(
                "features" to listOf(
                    mapOf("name" to "prefers-color-scheme", "value" to "dark"),
                    mapOf("name" to "prefers-reduced-motion"),
                    "not-a-map",
                )
            )
        )
        assertEquals(1, features.size)
        assertEquals("prefers-color-scheme", features[0].name)
        assertEquals("dark", features[0].value)
        assertTrue(EmulationParser.parseMediaFeatures(mapOf<String, Any?>()).isEmpty())
        assertTrue(EmulationParser.parseMediaFeatures("not-a-map").isEmpty())
        assertTrue(EmulationParser.parseMediaFeatures(null).isEmpty())
    }

    @Test
    fun parser_parseCpuThrottlingRate_safe() {
        assertEquals(6.0, EmulationParser.parseCpuThrottlingRate(mapOf<String, Any?>("rate" to 6.0))!!, 0.0)
        assertNull(EmulationParser.parseCpuThrottlingRate(mapOf<String, Any?>()))
        assertNull(EmulationParser.parseCpuThrottlingRate("not-a-map"))
        assertNull(EmulationParser.parseCpuThrottlingRate(null))
    }

    @Test
    fun parser_parseBoolean_safeFallback() {
        assertTrue(EmulationParser.parseBoolean(mapOf<String, Any?>("mobile" to true), "mobile", false))
        assertTrue(!EmulationParser.parseBoolean(mapOf<String, Any?>("mobile" to false), "mobile", true))
        assertTrue(EmulationParser.parseBoolean(mapOf<String, Any?>(), "mobile", true))
        assertTrue(EmulationParser.parseBoolean("not-a-map", "mobile", true))
    }

    @Test
    fun emulationActionResult_and_error_factories_produceExpectedValues() {
        val ok = EmulationActionResult.Success(42)
        assertTrue((ok as EmulationActionResult.Success<Int>).data == 42)

        val failure = EmulationActionResult.Failure(EmulationError.Unsupported)
        assertTrue(failure.error === EmulationError.Unsupported)

        val exec = EmulationError.ExecutionFailed("boom")
        assertEquals("boom", exec.message)
        assertTrue(EmulationError.Unavailable === EmulationError.Unavailable)
        assertTrue(EmulationError.TargetDisconnected === EmulationError.TargetDisconnected)
    }
}
