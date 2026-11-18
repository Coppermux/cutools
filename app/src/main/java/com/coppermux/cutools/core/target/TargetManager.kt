package com.coppermux.cutools.core.target

import android.util.Log
import com.coppermux.cutools.core.error.TargetException
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.model.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "CuTools/TargetManager"

private fun logTrace(tag: String, message: String) {
    try {
        Log.d(tag, message)
    } catch (_: Throwable) {
        // Fallback for JVM unit tests where android.util.Log is not mocked
    }
    println("[$tag] $message")
}

/**
 * Manages Chrome debugging targets: discovery, selection, attachment, and session tracking.
 *
 * Thread-safe: all public operations are safe to call from any thread.
 */
class TargetManager(
    private var dispatcher: CommandDispatcher,
    private var eventRouter: CdpEventRouter,
) {

    // Distinguishes repeated or concurrent attachment attempts
    private val attachAttemptCounter = AtomicInteger(0)

    // All known targets, keyed by target ID
    private val targets = ConcurrentHashMap<String, TargetInfo>()

    // Active session by target ID
    private val sessions = ConcurrentHashMap<String, SessionInfo>()

    // Currently selected target ID
    @Volatile
    private var selectedTargetId: String? = null

    // Listeners for target state changes
    private val listeners = CopyOnWriteArrayList<TargetManagerListener>()

    /** Optional HTTP `/json/list` fetcher injected by ConnectionManager to resolve target metadata. */
    @Volatile
    var jsonListFetcher: (((List<Map<String, Any?>>) -> Unit) -> Unit)? = null

    /** All discovered targets. */
    val discoveredTargets: List<TargetInfo> get() = targets.values.toList()

    /** Currently selected target, or null. */
    val selectedTarget: TargetInfo? get() = selectedTargetId?.let { targets[it] }

    /** Currently active session info, or null. */
    val activeSession: SessionInfo? get() = selectedTargetId?.let { sessions[it] }

    /** Whether the selected target is currently attached. */
    val isAttached: Boolean get() = activeSession != null

    /**
     * Register for events from the Target domain. Must be called after
     * the connection is established. Subscribes to Target.* events for
     * target discovery and lifecycle tracking.
     */
    fun registerTargetDomainEvents() {
        eventRouter.subscribe("Target.targetCreated") { event ->
            val info = parseTargetInfoFromEvent(event)
            if (info != null) {
                val wasNew = targets.put(info.targetId, info) == null
                logTrace(
                    TAG,
                    "target discovered via Target.targetCreated targetId=${info.targetId} type=${info.type} title=${info.title.orEmpty()} isPage=${info.isPage} wasNew=$wasNew"
                )
                if (wasNew) {
                    listeners.forEach { it.onTargetCreated(info) }
                }
            }
        }

        eventRouter.subscribe("Target.targetDestroyed") { event ->
            val targetId = getStringParam(event, "targetId")
            logTrace(TAG, "Target.targetDestroyed event targetId=$targetId")
            if (targetId != null) {
                handleTargetDestroyed(targetId)
            }
        }

        eventRouter.subscribe("Target.targetInfoUpdated") { event ->
            val info = parseTargetInfoFromEvent(event)
            if (info != null) {
                val existing = targets[info.targetId] ?: return@subscribe
                val updated = existing.copy(
                    title = info.title ?: existing.title,
                    url = info.url ?: existing.url,
                    webSocketDebuggerUrl = info.webSocketDebuggerUrl ?: existing.webSocketDebuggerUrl,
                    faviconUrl = info.faviconUrl ?: existing.faviconUrl,
                    attached = info.attached,
                    browserContextId = info.browserContextId ?: existing.browserContextId,
                    canSession = info.canSession ?: existing.canSession,
                    canDisconnect = info.canDisconnect ?: existing.canDisconnect,
                )
                targets[info.targetId] = updated
                logTrace(
                    TAG,
                    "target updated via Target.targetInfoUpdated targetId=${updated.targetId} type=${updated.type} title=${updated.title.orEmpty()} attached=${updated.attached}"
                )
                listeners.forEach { it.onTargetUpdated(updated) }
            }
        }

        eventRouter.subscribe("Target.attachedToTarget") { event ->
            val sessionId = getStringParam(event, "sessionId") ?: return@subscribe
            val targetInfo = parseTargetInfoFromEvent(event) ?: return@subscribe
            targets[targetInfo.targetId] = targetInfo
            val session = SessionInfo(
                sessionId = sessionId,
                targetId = targetInfo.targetId,
                webSocketDebuggerUrl = targetInfo.webSocketDebuggerUrl,
            )
            val existingSession = sessions[targetInfo.targetId]
            val wasAlreadyAttached = sessions.put(targetInfo.targetId, session) != null
            logTrace(
                TAG,
                "Target.attachedToTarget targetId=${targetInfo.targetId} sessionId=$sessionId existingSessionPresent=$wasAlreadyAttached previousSessionId=${existingSession?.sessionId}"
            )
            if (selectedTargetId == null || selectedTargetId == targetInfo.targetId || targetInfo.isPage) {
                selectedTargetId = targetInfo.targetId
            }
            updateTargetAttached(targetInfo.targetId, true)
            if (!wasAlreadyAttached) {
                listeners.forEach { it.onAttached(session) }
            }
        }

        eventRouter.subscribe("Target.detachedFromTarget") { event ->
            val targetIdParam = getStringParam(event, "targetId")
            val sessionIdParam = getStringParam(event, "sessionId")
            val resolvedTargetId = targetIdParam
                ?: sessionIdParam?.let { sessId ->
                    sessions.entries.find { it.value.sessionId == sessId }?.key
                }
            logTrace(
                TAG,
                "Target.detachedFromTarget event targetIdParam=$targetIdParam sessionIdParam=$sessionIdParam resolvedTargetId=$resolvedTargetId"
            )
            if (resolvedTargetId != null) {
                handleTargetDetached(resolvedTargetId)
            }
        }
    }

    /**
     * Register a listener for target lifecycle events.
     */
    fun addListener(listener: TargetManagerListener) {
        listeners.add(listener)
        activeSession?.let { listener.onAttached(it) }
    }

    /**
     * Remove a previously registered listener.
     */
    fun removeListener(listener: TargetManagerListener) {
        listeners.remove(listener)
    }

    /**
     * Rebind this manager to a fresh [CommandDispatcher] and [CdpEventRouter]
     * (used when the [com.coppermux.cutools.core.connection.ConnectionManager]
     * re-initializes core components for a new connection). Registered
     * listeners are preserved, so application lifecycle observers keep
     * receiving target events across reconnects.
     */
    fun rebind(newDispatcher: CommandDispatcher, newEventRouter: CdpEventRouter) {
        dispatcher = newDispatcher
        eventRouter = newEventRouter
        val hadState = targets.isNotEmpty() || sessions.isNotEmpty() || selectedTargetId != null
        targets.clear()
        sessions.clear()
        selectedTargetId = null
        if (hadState) {
            listeners.forEach { it.onCleared() }
        }
        registerTargetDomainEvents()
    }

    private fun handleTargetDestroyed(targetId: String) {
        targets.remove(targetId)
        val removedSession = sessions.remove(targetId)
        val wasSelected = (targetId == selectedTargetId)
        if (wasSelected) {
            selectedTargetId = null
        }
        logTrace(
            TAG,
            "handleTargetDestroyed targetId=$targetId hadSession=${removedSession != null} removedSessionId=${removedSession?.sessionId} wasSelected=$wasSelected"
        )
        if (wasSelected) {
            listeners.forEach { it.onDetached(targetId) }
        }
        listeners.forEach { it.onTargetDestroyed(targetId) }
    }

    private fun handleTargetDetached(targetId: String) {
        val removedSession = sessions.remove(targetId)
        val wasSelected = (targetId == selectedTargetId)
        if (wasSelected) {
            selectedTargetId = null
        }
        logTrace(
            TAG,
            "handleTargetDetached targetId=$targetId hadSession=${removedSession != null} removedSessionId=${removedSession?.sessionId} wasSelected=$wasSelected"
        )
        updateTargetAttached(targetId, false)
        if (wasSelected) {
            listeners.forEach { it.onDetached(targetId) }
        }
    }

    /**
     * Select a target by ID from discovered targets.
     */
    fun selectTarget(targetId: String) {
        val target = targets[targetId]
        if (target == null) {
            logTrace(TAG, "target selection failed targetId=$targetId error=target not found")
            val error = TargetException("Target not found: $targetId")
            listeners.forEach { it.onError(error) }
            return
        }
        selectedTargetId = targetId
        logTrace(TAG, "target selected targetId=$targetId type=${target.type} title=${target.title.orEmpty()}")
        listeners.forEach { it.onTargetSelected(target) }
    }

    /**
     * Attach to the currently selected target via CDP Target.attachToTarget.
     * Tracks the returned session ID.
     */
    fun attachToSelectedTarget(callback: (Result<SessionInfo>) -> Unit) {
        val attemptId = attachAttemptCounter.incrementAndGet()
        val target = selectedTarget
        if (target == null) {
            logTrace(
                TAG,
                "attach requested (attempt #$attemptId) target=null error=no target selected existingSessionPresent=false sessionId=null"
            )
            val error = TargetException("No target selected")
            callback(Result.failure(error))
            listeners.forEach { it.onError(error) }
            return
        }

        val existingSession = sessions[target.targetId]
        if (existingSession != null) {
            logTrace(
                TAG,
                "attach requested (attempt #$attemptId) targetId=${target.targetId} existingSessionPresent=true sessionId=${existingSession.sessionId}"
            )
            selectedTargetId = target.targetId
            updateTargetAttached(target.targetId, true)
            listeners.forEach { it.onAttached(existingSession) }
            callback(Result.success(existingSession))
            return
        }

        logTrace(
            TAG,
            "attach requested (attempt #$attemptId) targetId=${target.targetId} existingSessionPresent=false sessionId=null"
        )
        val params = mapOf("targetId" to target.targetId, "flatten" to true)
        dispatcher.sendCommand("Target.attachToTarget", params) { result ->
            when (result) {
                is CommandResult.Success -> {
                    val sessionId = extractSessionId(result.response)
                    if (sessionId != null) {
                        val session = SessionInfo(
                            sessionId = sessionId,
                            targetId = target.targetId,
                            webSocketDebuggerUrl = target.webSocketDebuggerUrl,
                        )
                        val wasAlreadyAttached = sessions.put(target.targetId, session) != null
                        logTrace(
                            TAG,
                            "attach success (attempt #$attemptId) targetId=${target.targetId} sessionId=$sessionId existingSessionPresent=$wasAlreadyAttached"
                        )
                        updateTargetAttached(target.targetId, true)
                        if (!wasAlreadyAttached) {
                            listeners.forEach { it.onAttached(session) }
                        }
                        callback(Result.success(session))
                    } else {
                        logTrace(
                            TAG,
                            "attach failed (attempt #$attemptId) targetId=${target.targetId} error=missing sessionId"
                        )
                        val error = TargetException(
                            "attachToTarget response missing sessionId: ${result.response.result}"
                        )
                        callback(Result.failure(error))
                        listeners.forEach { it.onError(error) }
                    }
                }
                is CommandResult.Failure -> {
                    logTrace(
                        TAG,
                        "attach failed (attempt #$attemptId) targetId=${target.targetId} error=${result.error.message}"
                    )
                    listeners.forEach { it.onError(result.error) }
                    callback(Result.failure(result.error))
                }
            }
        }
    }

    /**
     * Detach from the currently attached target via CDP Target.detachFromTarget.
     */
    fun detachFromSelectedTarget(callback: (Result<Unit>) -> Unit) {
        val session = activeSession
        if (session == null) {
            logTrace(TAG, "detach requested target=none activeSession=null")
            // Nothing to detach
            callback(Result.success(Unit))
            return
        }

        logTrace(TAG, "detach requested targetId=${session.targetId} sessionId=${session.sessionId}")
        val params = mapOf(
            "targetId" to session.targetId,
            "sessionId" to session.sessionId,
        )
        dispatcher.sendCommand("Target.detachFromTarget", params) { result ->
            sessions.remove(session.targetId)
            updateTargetAttached(session.targetId, false)
            when (result) {
                is CommandResult.Success -> {
                    logTrace(TAG, "detach success targetId=${session.targetId} sessionId=${session.sessionId}")
                    listeners.forEach { it.onDetached(session.targetId) }
                    callback(Result.success(Unit))
                }
                is CommandResult.Failure -> {
                    logTrace(TAG, "detach failed targetId=${session.targetId} sessionId=${session.sessionId} error=${result.error.message}")
                    listeners.forEach { it.onError(result.error) }
                    callback(Result.failure(result.error))
                }
            }
        }
    }

    /**
     * Discover all current targets via CDP Target.getTargets.
     */
    fun discoverTargets(callback: (List<TargetInfo>) -> Unit) {
        logTrace(TAG, "target discovery requested via Target.getTargets")
        dispatcher.sendCommand("Target.getTargets") { result ->
            when (result) {
                is CommandResult.Success -> {
                    val parsed = parseTargetInfosFromResponse(result.response)
                    logTrace(TAG, "target discovery completed count=${parsed.size} targetIds=${parsed.map { it.targetId }}")
                    // Update our target map — add new, update existing
                    parsed.forEach { info ->
                        logTrace(
                            TAG,
                            "target discovered via Target.getTargets targetId=${info.targetId} type=${info.type} title=${info.title.orEmpty()} isPage=${info.isPage} attached=${info.attached}"
                        )
                        val existing = targets[info.targetId]
                        val merged = if (existing != null) {
                            info.copy(
                                description = existing.description ?: info.description,
                                isWindowAttached = existing.isWindowAttached ?: info.isWindowAttached,
                                isVisible = existing.isVisible ?: info.isVisible,
                            )
                        } else {
                            info
                        }
                        targets.put(info.targetId, merged)
                    }
                    val fetcher = jsonListFetcher
                    if (fetcher != null) {
                        fetcher { jsonList ->
                            for (item in jsonList) {
                                val id = item["id"] as? String ?: item["targetId"] as? String
                                val desc = item["description"] as? String
                                if (id != null && desc != null) {
                                    updateTargetMetadataInternal(id, desc, notify = false)
                                }
                            }
                            val discovered = targets.values.toList()
                            callback(discovered)
                            listeners.forEach { it.onTargetsUpdated(discovered) }
                        }
                    } else {
                        val discovered = targets.values.toList()
                        callback(discovered)
                        listeners.forEach { it.onTargetsUpdated(discovered) }
                    }
                }
                is CommandResult.Failure -> {
                    logTrace(TAG, "target discovery failed error=${result.error.message}")
                    listeners.forEach { it.onError(result.error) }
                    callback(emptyList())
                }
            }
        }
    }

    /**
     * Updates target metadata (such as window attachment and visibility) derived from
     * the Chromium `/json/list` DevTools HTTP endpoint.
     */
    fun updateTargetMetadata(targetId: String, description: String?) {
        updateTargetMetadataInternal(targetId, description, notify = true)
    }

    private fun updateTargetMetadataInternal(targetId: String, description: String?, notify: Boolean) {
        val existing = targets[targetId] ?: return
        val (windowAttached, visible) = parseDescription(description)
        val updated = existing.copy(
            description = description ?: existing.description,
            isWindowAttached = windowAttached ?: existing.isWindowAttached,
            isVisible = visible ?: existing.isVisible,
        )
        targets[targetId] = updated
        if (notify) {
            listeners.forEach { it.onTargetUpdated(updated) }
        }
    }

    /**
     * Resolves the actual visible browser WebView page target among discovered targets,
     * discarding dormant or detached background targets.
     */
    fun findBestPageTarget(preferredUrl: String? = null): TargetInfo? {
        val pageTargets = targets.values.filter { it.isPage }
        if (pageTargets.isEmpty()) return null
        if (pageTargets.size == 1) return pageTargets.first()

        // 1. Targets explicitly verified as attached to window / visible
        val windowAttached = pageTargets.filter { it.isWindowAttached == true || it.isVisible == true }
        if (windowAttached.isNotEmpty()) {
            if (!preferredUrl.isNullOrBlank() && preferredUrl != "about:blank") {
                windowAttached.firstOrNull { urlsMatch(it.url, preferredUrl) }?.let { return it }
            }
            return windowAttached.first()
        }

        // 2. Targets not known to be unattached (exclude isWindowAttached == false)
        val notUnattached = pageTargets.filter { it.isWindowAttached != false }
        val candidates = if (notUnattached.isNotEmpty()) notUnattached else pageTargets

        // 3. Match preferred URL if available
        if (!preferredUrl.isNullOrBlank() && preferredUrl != "about:blank") {
            candidates.firstOrNull { urlsMatch(it.url, preferredUrl) }?.let { return it }
        }

        return candidates.firstOrNull()
    }

    private fun urlsMatch(u1: String?, u2: String?): Boolean {
        if (u1.isNullOrBlank() || u2.isNullOrBlank()) return false
        val s1 = u1.trimEnd('/')
        val s2 = u2.trimEnd('/')
        return s1.equals(s2, ignoreCase = true)
    }

    /**
     * Clear all target and session state. Called on disconnect.
     */
    fun clearAllState() {
        logTrace(
            TAG,
            "clearAllState targetsCount=${targets.size} sessionsCount=${sessions.size} selectedTargetId=$selectedTargetId"
        )
        targets.clear()
        sessions.clear()
        selectedTargetId = null
        listeners.forEach { it.onCleared() }
    }

    // ============== Parsing helpers ==============

    private fun updateTargetAttached(targetId: String, attached: Boolean) {
        targets[targetId]?.let { existing ->
            val updated = existing.copy(attached = attached)
            targets[targetId] = updated
            listeners.forEach { it.onTargetUpdated(updated) }
        }
    }

    private fun extractSessionId(response: Response): String? {
        val result = response.result
        if (result is Map<*, *>) {
            return result["sessionId"] as? String
        }
        return null
    }

    private fun parseDescription(desc: String?): Pair<Boolean?, Boolean?> {
        if (desc.isNullOrBlank()) return Pair(null, null)
        val attached = Regex(""""attached"\s*:\s*(true|false)""").find(desc)?.groupValues?.get(1)?.toBoolean()
        val visible = Regex(""""visible"\s*:\s*(true|false)""").find(desc)?.groupValues?.get(1)?.toBoolean()
        return Pair(attached, visible)
    }

    private fun parseTargetInfoFromEvent(event: Event): TargetInfo? {
        val params = event.params
        if (params !is Map<*, *>) return null

        // Events like targetCreated/targetInfoUpdated contain a "targetInfo" object
        val targetInfo = params["targetInfo"] as? Map<*, *>
        if (targetInfo != null) {
            val targetId = targetInfo["targetId"] as? String ?: return null
            val type = targetInfo["type"] as? String ?: return null
            val desc = targetInfo["description"] as? String
            val (windowAttached, visible) = parseDescription(desc)
            return TargetInfo(
                targetId = targetId,
                type = type,
                title = targetInfo["title"] as? String,
                url = targetInfo["url"] as? String,
                webSocketDebuggerUrl = targetInfo["webSocketDebuggerUrl"] as? String,
                faviconUrl = targetInfo["faviconUrl"] as? String,
                attached = targetInfo["attached"] as? Boolean ?: false,
                browserContextId = targetInfo["browserContextId"] as? String,
                canSession = targetInfo["canSession"] as? Boolean,
                canDisconnect = targetInfo["canDisconnect"] as? Boolean,
                description = desc,
                isWindowAttached = windowAttached,
                isVisible = visible,
            )
        }

        // Some events (e.g., targetDestroyed) may have top-level fields
        val targetId = params["targetId"] as? String ?: return null
        val type = params["type"] as? String ?: "unknown"
        val desc = params["description"] as? String
        val (windowAttached, visible) = parseDescription(desc)
        return TargetInfo(
            targetId = targetId,
            type = type,
            title = params["title"] as? String,
            url = params["url"] as? String,
            webSocketDebuggerUrl = params["webSocketDebuggerUrl"] as? String,
            faviconUrl = params["faviconUrl"] as? String,
            attached = params["attached"] as? Boolean ?: false,
            description = desc,
            isWindowAttached = windowAttached,
            isVisible = visible,
        )
    }

    private fun parseTargetInfosFromResponse(response: Response): List<TargetInfo> {
        val result = response.result
        if (result !is Map<*, *>) return emptyList()

        val targetInfos = result["targetInfos"] as? List<*> ?: return emptyList()
        return targetInfos.mapNotNull { item ->
            if (item !is Map<*, *>) return@mapNotNull null
            val targetId = item["targetId"] as? String ?: return@mapNotNull null
            val type = item["type"] as? String ?: return@mapNotNull null
            val desc = item["description"] as? String
            val (windowAttached, visible) = parseDescription(desc)
            TargetInfo(
                targetId = targetId,
                type = type,
                title = item["title"] as? String,
                url = item["url"] as? String,
                webSocketDebuggerUrl = item["webSocketDebuggerUrl"] as? String,
                faviconUrl = item["faviconUrl"] as? String,
                attached = item["attached"] as? Boolean ?: false,
                browserContextId = item["browserContextId"] as? String,
                canSession = item["canSession"] as? Boolean,
                canDisconnect = item["canDisconnect"] as? Boolean,
                description = desc,
                isWindowAttached = windowAttached,
                isVisible = visible,
            )
        }
    }

    private fun getStringParam(event: Event, key: String): String? {
        val params = event.params
        if (params is Map<*, *>) {
            return params[key] as? String
        }
        return null
    }
}

/**
 * Listener for target manager events.
 */
interface TargetManagerListener {
    fun onTargetCreated(target: TargetInfo)
    fun onTargetUpdated(target: TargetInfo)
    fun onTargetDestroyed(targetId: String)
    fun onTargetSelected(target: TargetInfo)
    fun onTargetsUpdated(targets: List<TargetInfo>)
    fun onAttached(session: SessionInfo)
    fun onDetached(targetId: String)
    fun onCleared()
    fun onError(error: Throwable)
}
