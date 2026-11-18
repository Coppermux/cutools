package com.coppermux.cutools.core.integration

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the `integrationTest` task: fails with a clear message when headless
 * Chrome is not reachable on port 9224, so a missing environment never
 * silently passes or skips.
 */
class ChromeEnvironmentIntegrationTest {

    @Test
    fun headlessChrome_reachableOnPort9224() {
        val env = ChromeEnvironment()
        if (!env.isReachable) {
            env.startHeadlessChrome()
        }
        assertTrue(
            "Headless Chrome is not reachable at ${env.versionUrl()}. " +
                "Start it with: chromium-browser --remote-debugging-port=9224 --headless --no-sandbox",
            env.isReachable
        )
    }
}