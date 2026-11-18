package com.coppermux.cutools.core.capability

import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.error.CommandException

/**
 * Retrieves browser information from Chrome via CDP.
 *
 * Uses "Browser.getVersion" to obtain:
 * - Browser name/version
 * - Protocol version
 * - User agent
 * - Product identification
 */
class BrowserInfoRetriever(private val dispatcher: CommandDispatcher) {

    /**
     * Query Chrome for browser version information.
     *
     * @param callback Called with the result: Success(BrowserInfo) or Failure(CdpException).
     */
    fun getBrowserVersion(callback: (Result<BrowserInfo>) -> Unit) {
        dispatcher.sendCommand("Browser.getVersion") { result ->
            when (result) {
                is CommandResult.Success -> {
                    try {
                        val info = parseBrowserInfo(result.response.result)
                        callback(Result.success(info))
                    } catch (e: Exception) {
                        callback(Result.failure(
                            CommandException("Failed to parse Browser.getVersion response: ${e.message}", cause = e)
                        ))
                    }
                }
                is CommandResult.Failure -> {
                    callback(Result.failure(result.error))
                }
            }
        }
    }

    private fun parseBrowserInfo(result: Any?): BrowserInfo {
        if (result !is Map<*, *>) {
            throw CommandException("Unexpected response format for Browser.getVersion")
        }
        @Suppress("UNCHECKED_CAST")
        return BrowserInfo(
            browser = result["browser"] as? String,
            protocolVersion = result["protocolVersion"] as? String,
            userAgent = result["userAgent"] as? String,
            product = result["product"] as? String,
        )
    }
}
