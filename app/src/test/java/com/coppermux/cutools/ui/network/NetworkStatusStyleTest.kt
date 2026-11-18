package com.coppermux.cutools.ui.network

import com.coppermux.cutools.core.network.NetworkRequestModel
import com.coppermux.cutools.core.network.NetworkRequestStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for Phase 5D status color-mapping and formatting
 * ([NetworkStatusStyle], [NetworkStatusCategory]).
 *
 * Colors are asserted in pure int/hex arithmetic — never via Android APIs, so
 * these run on any JVM without an emulator.
 */
class NetworkStatusStyleTest {

    private fun model(
        id: String,
        stage: NetworkRequestStage,
        status: Int? = null,
    ) = NetworkRequestModel(
        requestId = id,
        stage = stage,
        responseStatus = status,
        sessionId = "s1",
    )

    /** Parses an opaque `#RRGGBB` hex string to the full-ARGB int it represents. */
    private fun parseArgb(hex: String): Int =
        0xFF000000.toInt() or Integer.parseInt(hex.removePrefix("#"), 16)

    // ============ category mapping ============

    @Test
    fun success2xx_isGreen() {
        assertEquals(NetworkStatusCategory.SUCCESS, NetworkStatusStyle.category(model("a", NetworkRequestStage.FINISHED, 200)))
        assertEquals(NetworkStatusCategory.SUCCESS, NetworkStatusStyle.category(model("a", NetworkRequestStage.FINISHED, 204)))
        assertEquals(NetworkStatusCategory.SUCCESS, NetworkStatusStyle.category(model("a", NetworkRequestStage.RESPONSE_RECEIVED, 299)))
    }

    @Test
    fun redirect3xx_isBlue() {
        assertEquals(NetworkStatusCategory.REDIRECT, NetworkStatusStyle.category(model("a", NetworkRequestStage.FINISHED, 301)))
        assertEquals(NetworkStatusCategory.REDIRECT, NetworkStatusStyle.category(model("a", NetworkRequestStage.FINISHED, 304)))
    }

    @Test
    fun clientError4xx_isRed() {
        assertEquals(NetworkStatusCategory.CLIENT_ERROR, NetworkStatusStyle.category(model("a", NetworkRequestStage.FINISHED, 400)))
        assertEquals(NetworkStatusCategory.CLIENT_ERROR, NetworkStatusStyle.category(model("a", NetworkRequestStage.FINISHED, 404)))
    }

    @Test
    fun serverError5xx_isRed() {
        assertEquals(NetworkStatusCategory.SERVER_ERROR, NetworkStatusStyle.category(model("a", NetworkRequestStage.FINISHED, 500)))
        assertEquals(NetworkStatusCategory.SERVER_ERROR, NetworkStatusStyle.category(model("a", NetworkRequestStage.FINISHED, 503)))
    }

    @Test
    fun pending_isGrey() {
        assertEquals(NetworkStatusCategory.PENDING, NetworkStatusStyle.category(model("a", NetworkRequestStage.PENDING)))
    }

    @Test
    fun failed_isDarkRed() {
        assertEquals(NetworkStatusCategory.FAILED, NetworkStatusStyle.category(model("a", NetworkRequestStage.FAILED)))
    }

    @Test
    fun failedStage_winsOverStatusCode() {
        assertEquals(
            NetworkStatusCategory.FAILED,
            NetworkStatusStyle.category(model("a", NetworkRequestStage.FAILED, 200)),
        )
    }

    @Test
    fun missingStatus_isPending() {
        assertEquals(NetworkStatusCategory.PENDING, NetworkStatusStyle.category(model("a", NetworkRequestStage.FINISHED)))
        assertEquals(NetworkStatusCategory.PENDING, NetworkStatusStyle.category(model("a", NetworkRequestStage.RESPONSE_RECEIVED)))
    }

    // ============ colors ============

    @Test
    fun successColor_isGreenHex() {
        assertEquals("#2E7D32", NetworkStatusStyle.colorHex(NetworkStatusCategory.SUCCESS))
    }

    @Test
    fun redirectColor_isBlueHex() {
        assertEquals("#1565C0", NetworkStatusStyle.colorHex(NetworkStatusCategory.REDIRECT))
    }

    @Test
    fun clientErrorColor_isRedHex() {
        assertEquals("#C62828", NetworkStatusStyle.colorHex(NetworkStatusCategory.CLIENT_ERROR))
    }

    @Test
    fun serverErrorColor_isRedHex() {
        assertEquals("#B71C1C", NetworkStatusStyle.colorHex(NetworkStatusCategory.SERVER_ERROR))
    }

    @Test
    fun pendingColor_isGreyHex() {
        assertEquals("#9E9E9E", NetworkStatusStyle.colorHex(NetworkStatusCategory.PENDING))
    }

    @Test
    fun failedColor_isDarkRedHex() {
        assertEquals("#B71C1C", NetworkStatusStyle.colorHex(NetworkStatusCategory.FAILED))
    }

    @Test
    fun modelHexColor_followsCategory() {
        assertEquals("#2E7D32", NetworkStatusStyle.colorHex(model("a", NetworkRequestStage.FINISHED, 200)))
        assertEquals("#9E9E9E", NetworkStatusStyle.colorHex(model("a", NetworkRequestStage.PENDING)))
        assertEquals("#B71C1C", NetworkStatusStyle.colorHex(model("a", NetworkRequestStage.FAILED)))
        assertEquals("#C62828", NetworkStatusStyle.colorHex(model("a", NetworkRequestStage.FINISHED, 404)))
    }

    @Test
    fun argbIsConsistentWithHex() {
        NetworkStatusCategory.values().forEach { category ->
            val hex = NetworkStatusStyle.colorHex(category)
            val expected = parseArgb(hex)
            assertEquals("ARGB mismatch for $category", expected, NetworkStatusStyle.colorArgb(category))
        }
    }

    @Test
    fun modelArgbColor_matchesCategoryColor() {
        val model = model("a", NetworkRequestStage.FINISHED, 404)
        assertEquals(
            NetworkStatusStyle.colorArgb(NetworkStatusCategory.CLIENT_ERROR),
            NetworkStatusStyle.colorArgb(model),
        )
        assertNotEquals(
            NetworkStatusStyle.colorArgb(NetworkStatusCategory.SUCCESS),
            NetworkStatusStyle.colorArgb(model),
        )
    }

    @Test
    fun hexColor_isAlwaysSixHexDigits() {
        val pattern = Regex("#[0-9A-F]{6}")
        NetworkStatusCategory.values().forEach { category ->
            assertTrue(pattern.matches(NetworkStatusStyle.colorHex(category)))
        }
    }

    @Test
    fun colorCategory_isDeterministic() {
        assertEquals(
            NetworkStatusStyle.colorHex(model("x", NetworkRequestStage.FINISHED, 500)),
            NetworkStatusStyle.colorHex(model("y", NetworkRequestStage.FINISHED, 500)),
        )
        assertNotEquals(
            NetworkStatusStyle.colorHex(model("z", NetworkRequestStage.FINISHED, 500)),
            NetworkStatusStyle.colorHex(model("w", NetworkRequestStage.FINISHED, 200)),
        )
    }

    // ============ labels & formatting ============

    @Test
    fun statusLabel_usesStatusCodeWhenPresent() {
        assertEquals("200", NetworkStatusStyle.statusLabel(model("a", NetworkRequestStage.FINISHED, 200)))
        assertEquals("404", NetworkStatusStyle.statusLabel(model("a", NetworkRequestStage.RESPONSE_RECEIVED, 404)))
    }

    @Test
    fun statusLabel_pendingAndFailedMarkers() {
        assertEquals("…", NetworkStatusStyle.statusLabel(model("a", NetworkRequestStage.PENDING)))
        assertEquals("failed", NetworkStatusStyle.statusLabel(model("a", NetworkRequestStage.FAILED)))
    }

    @Test
    fun statusLabel_responseWithoutCode() {
        assertEquals("response", NetworkStatusStyle.statusLabel(model("a", NetworkRequestStage.RESPONSE_RECEIVED)))
        assertEquals("response", NetworkStatusStyle.statusLabel(model("a", NetworkRequestStage.FINISHED)))
    }

    @Test
    fun stageLabel_usesModelStage() {
        assertEquals("pending", NetworkStatusStyle.stageLabel(model("a", NetworkRequestStage.PENDING)))
        assertEquals("failed", NetworkStatusStyle.stageLabel(model("a", NetworkRequestStage.FAILED)))
        assertEquals("finished", NetworkStatusStyle.stageLabel(model("a", NetworkRequestStage.FINISHED)))
    }
}