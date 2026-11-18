package com.coppermux.cutools.tools.application.engine

import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.tools.application.models.CookieActionResult
import com.coppermux.cutools.tools.application.models.CookieActionStatus
import com.coppermux.cutools.tools.application.models.CookieCapabilityStatus
import com.coppermux.cutools.tools.application.models.CookieModel
import com.coppermux.cutools.tools.application.models.CookiePriority
import com.coppermux.cutools.tools.application.models.SameSitePolicy
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates browser-backed cookie inspection, setting, deletion, and clearing
 * for the currently attached page target via the CDP `Network` / `Storage`
 * domains.
 *
 * Sub-Phase 6C:
 * - [getCookies] issues `Network.getCookies` and parses the genuine cookie
 *   attributes (name, value, domain, path, expires, size, httpOnly, secure,
 *   session, sameSite, priority) without assuming defaults (Exact Mapping).
 * - [setCookie] issues `Network.setCookie` with full safety-attribute parsing
 *   and rejects a `success=false` result explicitly.
 * - [deleteCookie] issues `Network.deleteCookies` for a named cookie.
 * - [clearCookies] issues `Storage.clearCookies` for the current context.
 *   CDP failures are mapped to explicit [CookieActionResult] statuses
 *   (`UNSUPPORTED`, `UNAVAILABLE`, `FAILED`) — no mock or hardcoded cookie
 *   values are ever returned (No-Fake-Capability).
 * - Session attach/detach lifecycle resets all target-scoped cookie state, and
 *   a generation counter guards stale async results delivered after detach.
 *
 * Threading: state fields are `@Volatile`; all Chrome communication reuses the
 * shared [CommandDispatcher] — no second transport or dispatcher is created.
 */
class CookieEngine(
    private val dispatcher: CommandDispatcher,
) {

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    /** The cookie domain capability for the current session/target. */
    @Volatile
    var capability: CookieCapabilityStatus? = null
        private set

    /** The most recent genuine `Network.getCookies` result, or null. */
    @Volatile
    var latestCookies: List<CookieModel>? = null
        private set

    /** Guards stale async results after detach / re-bind. */
    private val generation = AtomicInteger(0)

    // ================================================================
    // Lifecycle
    // ================================================================

    /** Binds to an attached session and resets any prior target's cookie state. */
    fun bindSession(newSession: SessionInfo) {
        generation.incrementAndGet()
        session = newSession
        capability = null
        latestCookies = null
    }

    /** Detaches, clearing all target-scoped cookie state. */
    fun detach() {
        generation.incrementAndGet()
        session = null
        capability = null
        latestCookies = null
    }

    /** Handles connection loss — no commands are sent against the dead connection. */
    fun onConnectionLost() {
        detach()
    }

    // ================================================================
    // Inspection
    // ================================================================

    /**
     * Asynchronously retrieves the active cookies for the given [urls] via
     * `Network.getCookies`, caching the parsed list in [latestCookies].
     *
     * Protocol failures resolve to an explicit [CookieActionResult] status
     * (`UNSUPPORTED`, `UNAVAILABLE`, `FAILED`) — never to fabricated values.
     */
    fun getCookies(urls: List<String>, callback: (CookieActionResult<List<CookieModel>>) -> Unit) {
        val sess = session
        if (sess == null) {
            capability = CookieCapabilityStatus.UNAVAILABLE
            callback(CookieActionResult.unavailable("Cookie domain is not bound to an active session"))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Network.getCookies",
            params = mapOf("urls" to urls),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    val payload = result.response.result
                    if (payload !is Map<*, *>) {
                        capability = CookieCapabilityStatus.FAILED
                        callback(CookieActionResult.failed("Network.getCookies result is not an object"))
                        return@sendCommand
                    }
                    val cookies = parseCookies(payload)
                    latestCookies = cookies
                    capability = CookieCapabilityStatus.SUPPORTED
                    callback(CookieActionResult.success(cookies))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "Network.getCookies"))
                }
            }
        }
    }

    // ================================================================
    // Mutation
    // ================================================================

    /**
     * Asynchronously sets/updates a cookie for [originUrl] via
     * `Network.setCookie`, serializing the full safety attributes
     * (domain, path, secure, httpOnly, sameSite, expires, priority).
     *
     * A genuine `success=false` result or a protocol failure resolves to an
     * explicit [CookieActionResult] status.
     */
    fun setCookie(
        cookie: CookieModel,
        originUrl: String,
        callback: (CookieActionResult<Unit>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            capability = CookieCapabilityStatus.UNAVAILABLE
            callback(CookieActionResult.unavailable("Cookie domain is not bound to an active session"))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Network.setCookie",
            params = setCookieParams(cookie, originUrl),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    val payload = result.response.result
                    if (payload is Map<*, *> && payload["success"] == false) {
                        capability = CookieCapabilityStatus.FAILED
                        callback(CookieActionResult.failed("Network.setCookie was rejected by the browser"))
                        return@sendCommand
                    }
                    capability = CookieCapabilityStatus.SUPPORTED
                    callback(CookieActionResult.success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "Network.setCookie"))
                }
            }
        }
    }

    /**
     * Asynchronously deletes a named cookie via `Network.deleteCookies`.
     */
    fun deleteCookie(
        name: String,
        domain: String,
        path: String,
        url: String,
        callback: (CookieActionResult<Unit>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            capability = CookieCapabilityStatus.UNAVAILABLE
            callback(CookieActionResult.unavailable("Cookie domain is not bound to an active session"))
            return
        }
        val gen = generation.get()
        val params = mutableMapOf<String, Any?>("name" to name)
        if (url.isNotBlank()) params["url"] = url
        if (domain.isNotBlank()) params["domain"] = domain
        if (path.isNotBlank()) params["path"] = path
        dispatcher.sendCommand(
            method = "Network.deleteCookies",
            params = params,
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = CookieCapabilityStatus.SUPPORTED
                    callback(CookieActionResult.success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "Network.deleteCookies"))
                }
            }
        }
    }

    /**
     * Asynchronously clears all cookies for the current browser context via
     * `Storage.clearCookies`, optionally scoped to [browserContextId].
     */
    fun clearCookies(
        browserContextId: String? = null,
        callback: (CookieActionResult<Unit>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            capability = CookieCapabilityStatus.UNAVAILABLE
            callback(CookieActionResult.unavailable("Cookie domain is not bound to an active session"))
            return
        }
        val gen = generation.get()
        val params = if (browserContextId != null) mapOf("browserContextId" to browserContextId) else emptyMap()
        dispatcher.sendCommand(
            method = "Storage.clearCookies",
            params = params,
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = CookieCapabilityStatus.SUPPORTED
                    callback(CookieActionResult.success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "Storage.clearCookies"))
                }
            }
        }
    }

    // ================================================================
    // Parsing & error mapping
    // ================================================================

    /**
     * Serializes a [CookieModel] plus [originUrl] into the CDP `Network.setCookie`
     * parameter object. Session cookies omit `expires`; only present, non-default
     * safety attributes are emitted (Exact Mapping — nothing is assumed).
     */
    private fun setCookieParams(cookie: CookieModel, originUrl: String): Map<String, Any?> {
        val params = mutableMapOf<String, Any?>(
            "name" to cookie.name,
            "value" to cookie.value,
            "url" to originUrl,
            "secure" to cookie.secure,
            "httpOnly" to cookie.httpOnly,
        )
        if (cookie.domain.isNotBlank()) params["domain"] = cookie.domain
        if (cookie.path.isNotBlank()) params["path"] = cookie.path
        cookie.sameSite?.let { params["sameSite"] = it.wireValue }
        cookie.priority?.let { params["priority"] = it.wireValue }
        val expires = cookie.expires
        if (!cookie.session && expires != null) params["expires"] = expires
        return params
    }

    /**
     * Parses the genuine `cookies` array of a `Network.getCookies` result into
     * [CookieModel]s. Cookie attributes are mapped exactly; entries without a
     * name are skipped rather than fabricated.
     */
    private fun parseCookies(result: Map<*, *>): List<CookieModel> {
        val cookies = result["cookies"] as? List<*> ?: return emptyList()
        return cookies.mapNotNull { entry ->
            if (entry !is Map<*, *>) return@mapNotNull null
            val name = entry["name"] as? String ?: return@mapNotNull null
            val expiresRaw = (entry["expires"] as? Number)?.toLong()
            val session = (entry["session"] as? Boolean)
                ?: (expiresRaw != null && expiresRaw <= 0L)
            CookieModel(
                name = name,
                value = (entry["value"] as? String) ?: "",
                domain = (entry["domain"] as? String) ?: "",
                path = (entry["path"] as? String) ?: "",
                expires = if (session) null else expiresRaw,
                size = (entry["size"] as? Number)?.toInt() ?: 0,
                httpOnly = (entry["httpOnly"] as? Boolean) ?: false,
                secure = (entry["secure"] as? Boolean) ?: false,
                session = session,
                sameSite = SameSitePolicy.fromWire(entry["sameSite"] as? String),
                priority = CookiePriority.fromWire(entry["priority"] as? String),
            )
        }
    }

    /**
     * Maps a CDP command failure to an explicit [CookieActionResult] status.
     * A method-not-found error (`-32601`) is [CookieActionStatus.UNSUPPORTED];
     * everything else is [CookieActionStatus.FAILED].
     */
    private fun classifyFailure(error: Throwable, method: String): CookieActionResult<Nothing> {
        val message = error.message.orEmpty()
        if (message.contains("-32601")) {
            capability = CookieCapabilityStatus.UNSUPPORTED
            return CookieActionResult.unsupported("$method is not available on the attached target", error)
        }
        capability = CookieCapabilityStatus.FAILED
        return CookieActionResult.failed("$method failed: ${error.message}", error)
    }
}