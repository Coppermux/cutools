package com.coppermux.cutools.core.network

/**
 * Lifecycle stage of a single network request, advanced as genuine Chrome CDP
 * `Network` events arrive. The stage is monotonic: a request that has reached a
 * terminal stage (FINISHED / FAILED) is never regressed by a later event.
 */
enum class NetworkRequestStage(val displayLabel: String) {
    /** `Network.requestWillBeSent` received; awaiting response. */
    PENDING("pending"),

    /** `Network.responseReceived` received. */
    RESPONSE_RECEIVED("response"),

    /** `Network.loadingFinished` received (terminal). */
    FINISHED("finished"),

    /** `Network.loadingFailed` received (terminal). */
    FAILED("failed");

    companion object {
        /** Maps a CDP Network event method to the stage it introduces, or null. */
        fun fromCdpMethod(method: String): NetworkRequestStage? = when (method) {
            "Network.requestWillBeSent" -> PENDING
            "Network.responseReceived" -> RESPONSE_RECEIVED
            "Network.loadingFinished" -> FINISHED
            "Network.loadingFailed" -> FAILED
            else -> null
        }
    }
}

/**
 * Availability of the `Network` CDP domain on the active target.
 *
 * Network event reception requires `Network.enable`. If the method is not
 * available on the browser, the domain is UNSUPPORTED; if enablement errors,
 * it is FAILED. Nothing is fabricated.
 */
enum class NetworkCapabilityStatus {
    /** Not yet probed. */
    UNKNOWN,

    /** `Network.enable` succeeded; the domain is functional. */
    SUPPORTED,

    /** The `Network.enable` method is not available. */
    UNSUPPORTED,

    /** `Network.enable` failed with an error. */
    FAILED,
}

/**
 * Lifecycle phase of the Network subsystem relative to the active target/session.
 */
enum class NetworkLifecyclePhase {
    /** Not bound to any target/session. */
    INACTIVE,

    /** Binding and enabling the `Network` domain. */
    ENABLING,

    /** Bound, `Network.enable` succeeded, and events are being received. */
    READY,

    /** The `Network` capability is unavailable for this target. */
    UNSUPPORTED,

    /** Binding/enablement failed with an error. */
    FAILED,
}

/**
 * A single network request tracked across its full Chrome lifecycle.
 *
 * All fields originate from genuine Chrome `Network` events. Missing browser
 * information remains null/empty — CuTools never fabricates network data.
 *
 * [timestamp] preserves the browser-provided CDP `time` value (a Number, as
 * reported by Chrome) — it is NEVER replaced with local event-reception time.
 *
 * [requestId] is Chrome's own request identifier, which keys the in-memory
 * store and stitches the four lifecycle events together.
 */
data class NetworkRequestModel(
    val requestId: String,
    val stage: NetworkRequestStage,
    val url: String? = null,
    val method: String? = null,
    val timestamp: Double? = null,
    val resourceType: String? = null,
    val initiatorType: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val postData: String? = null,
    val responseStatus: Int? = null,
    val responseStatusText: String? = null,
    val responseMimeType: String? = null,
    val responseHeaders: Map<String, String> = emptyMap(),
    val loadingError: String? = null,
    val canceled: Boolean = false,
    val timing: ResourceTiming? = null,
    val sessionId: String,
    val targetId: String? = null,
    val redirectStatus: Int? = null,
    val redirectUrl: String? = null,
) {
    val isPending: Boolean get() = stage == NetworkRequestStage.PENDING
    val hasResponse: Boolean get() =
        stage == NetworkRequestStage.RESPONSE_RECEIVED || stage == NetworkRequestStage.FINISHED
    val isFinished: Boolean get() = stage == NetworkRequestStage.FINISHED
    val isFailed: Boolean get() = stage == NetworkRequestStage.FAILED
    val isTerminal: Boolean get() = isFinished || isFailed

    /** A copy promoted to the given stage (callers guard terminal monotonicity). */
    fun withStage(stage: NetworkRequestStage): NetworkRequestModel = copy(stage = stage)
}

/**
 * Explicit, UI-independent Network state, owned by the [NetworkEngine].
 *
 * [requests] are structured [NetworkRequestModel]s originating from genuine
 * Chrome `Network` events — never UI-generated or fabricated. State is
 * invalidated (emptied) on target switch, detach, and connection loss so a
 * previous target's requests never appear as belonging to a new target.
 *
 * Deterministic and testable: the UI renders this state and never owns browser
 * truth.
 */
data class NetworkState(
    val requests: List<NetworkRequestModel> = emptyList(),
    val sessionId: String? = null,
    val targetId: String? = null,
    val phase: NetworkLifecyclePhase = NetworkLifecyclePhase.INACTIVE,
    val capability: NetworkCapabilityStatus = NetworkCapabilityStatus.UNKNOWN,
    val capabilityError: Throwable? = null,
    val error: Throwable? = null,
) {
    val isReady: Boolean get() = phase == NetworkLifecyclePhase.READY
    val isEnabling: Boolean get() = phase == NetworkLifecyclePhase.ENABLING
    val isInactive: Boolean get() = phase == NetworkLifecyclePhase.INACTIVE
    val hasRequests: Boolean get() = requests.isNotEmpty()

    /** Requests still awaiting a terminal state. */
    val pendingCount: Int get() = requests.count { it.stage == NetworkRequestStage.PENDING }

    /** Requests that received a response (but not yet terminal). */
    val responseCount: Int get() = requests.count { it.stage == NetworkRequestStage.RESPONSE_RECEIVED }

    /** Requests that completed successfully. */
    val finishedCount: Int get() = requests.count { it.isFinished }

    /** Requests that failed to load. */
    val failedCount: Int get() = requests.count { it.isFailed }

    companion object {
        fun empty(): NetworkState = NetworkState()
    }
}