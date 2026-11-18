package com.coppermux.cutools.core.network

import java.util.Base64
import java.util.LinkedHashMap

/**
 * A CDP `Network.ErrorReason` value, used by `Fetch.failRequest` to simulate a
 * network failure for a paused request. The subset below matches the explicit
 * failure categories CuTools exposes to its users; the CDP wire value is
 * preserved verbatim in [cdpValue].
 */
enum class NetworkFailureReason(val cdpValue: String) {
    Failed("Failed"),
    Aborted("Aborted"),
    TimedOut("TimedOut"),
    AccessDenied("AccessDenied"),
    ConnectionRefused("ConnectionRefused"),
    ConnectionReset("ConnectionReset");

    companion object {
        /** Maps an unknown CDP string to [Failed] rather than throwing. */
        fun fromCdp(value: String): NetworkFailureReason =
            values().find { it.cdpValue == value } ?: Failed
    }
}

/** The action a [MockResponseRuleSpec] applies to a matching paused request. */
enum class MockAction {
    /** Serve a mock HTTP response via `Fetch.fulfillRequest`. */
    FULFILL,

    /** Simulate a network failure via `Fetch.failRequest`. */
    FAIL,

    /** Pass the request through untouched via `Fetch.continueRequest`. */
    CONTINUE,
}

/**
 * A CDP `Fetch.RequestPattern` used by `Fetch.enable` to limit which requests
 * Chrome pauses. Mirrors the real protocol shape: `urlPattern`, `resourceType`,
 * and `requestStage` are supported (Chrome defines no method field here).
 */
data class RequestPattern(
    val urlPattern: String? = null,
    val resourceType: String? = null,
    val requestStage: String? = null,
) {
    /** Serializes to the CDP `Fetch.enable` `patterns` entry shape. */
    fun toParams(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        if (urlPattern != null) map["urlPattern"] = urlPattern
        if (resourceType != null) map["resourceType"] = resourceType
        if (requestStage != null) map["requestStage"] = requestStage
        return map
    }
}

/**
 * An explicit, user-authored interception rule held by the
 * [MockResponseRuleManager].
 *
 * [urlPattern] is a glob pattern (`*` matches any run of characters, `?`
 * matches a single character) evaluated against the paused request URL.
 * [priority] orders competing rules — higher wins; ties fall back to insertion
 * order (earliest added wins). Fields that do not apply to a rule's [action]
 * are ignored by the engine.
 */
data class MockResponseRuleSpec(
    val id: String,
    val urlPattern: String,
    val method: String? = null,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val action: MockAction = MockAction.FULFILL,
    val statusCode: Int = 200,
    val statusText: String? = null,
    val responseHeaders: Map<String, String> = emptyMap(),
    val body: String? = null,
    val bodyIsBase64: Boolean = false,
    val failureReason: NetworkFailureReason = NetworkFailureReason.Failed,
)

/**
 * A mock HTTP response to serve via `Fetch.fulfillRequest`.
 *
 * [body] carries the payload to deliver. When [bodyIsBase64] is false the body
 * is plain text and is base64-encoded here (Chrome requires the `Fetch`
 * fulfillment body to be base64). When true the body is already base64 and is
 * passed through verbatim.
 */
data class MockHttpResponse(
    val code: Int,
    val statusText: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val bodyIsBase64: Boolean = false,
) {
    /** Builds the CDP `Fetch.fulfillRequest` params for [requestId]. */
    fun toFulfillParams(requestId: String): Map<String, Any?> {
        val response = LinkedHashMap<String, Any?>()
        response["code"] = code
        if (statusText != null) response["statusText"] = statusText
        if (headers.isNotEmpty()) {
            response["headers"] = headers.map { (name, value) -> mapOf("name" to name, "value" to value) }
        }
        if (body != null) {
            response["body"] = if (bodyIsBase64) body else MockHttpResponse.base64Encode(body)
        }
        return mapOf("requestId" to requestId, "response" to response)
    }

    companion object {
        /** Encodes [text] as UTF-8 base64 for the CDP `Fetch` body field. */
        fun base64Encode(text: String): String =
            Base64.getEncoder().encodeToString(text.toByteArray())

        /** Derives a fulfillment response from an authored [MockResponseRuleSpec]. */
        fun fromRule(rule: MockResponseRuleSpec): MockHttpResponse = MockHttpResponse(
            code = rule.statusCode,
            statusText = rule.statusText,
            headers = rule.responseHeaders,
            body = rule.body,
            bodyIsBase64 = rule.bodyIsBase64,
        )
    }
}

/**
 * The `authChallengeResponse` payload for `Fetch.continueWithAuth`.
 *
 * [response] must be one of `Default`, `CancelAuth`, or `ProvideCredentials`;
 * [username]/[password] are only meaningful for `ProvideCredentials`.
 */
data class ContinueWithAuth(
    val response: String,
    val username: String? = null,
    val password: String? = null,
) {
    /** Builds the CDP `Fetch.continueWithAuth` params for [requestId]. */
    fun toParams(requestId: String): Map<String, Any?> {
        val auth = LinkedHashMap<String, Any?>()
        auth["response"] = response
        if (username != null) auth["username"] = username
        if (password != null) auth["password"] = password
        return mapOf("requestId" to requestId, "authChallengeResponse" to auth)
    }
}

/** Outcome of a user-invoked interception action against a paused request. */
enum class FetchActionResult {
    /** The CDP command was dispatched to Chrome. */
    SENT,

    /** No session is bound; the action was not dispatched. */
    NO_SESSION,

    /** The requestId is not paused on the active session (stale/unknown). */
    UNKNOWN_REQUEST,

    /** Chrome returned an error for the dispatched command. */
    ERROR,
}

/** Availability of the `Fetch` CDP domain on the active target. */
enum class FetchCapabilityStatus {
    /** Not yet probed. */
    UNKNOWN,

    /** `Fetch.enable` succeeded; interception is functional. */
    SUPPORTED,

    /** The `Fetch.enable` method is not available. */
    UNSUPPORTED,

    /** `Fetch.enable` failed with an error. */
    FAILED,
}

/** Lifecycle phase of the Fetch interception subsystem. */
enum class FetchLifecyclePhase {
    /** Not bound to any target/session. */
    INACTIVE,

    /** Binding and enabling the `Fetch` domain. */
    ENABLING,

    /** Bound, `Fetch.enable` succeeded, and paused requests are routed. */
    READY,

    /** Explicitly disabled via `Fetch.disable`. */
    DISABLED,

    /** The `Fetch` capability is unavailable for this target. */
    UNSUPPORTED,

    /** Binding/enablement failed with an error. */
    FAILED,
}

/**
 * Explicit, UI-independent interception state owned by the
 * [FetchInterceptionEngine]. Paused-request counters and session identity are
 * reset on target switch / detach / connection loss so a previous target's
 * interception hooks never leak into a new one.
 */
data class FetchInterceptionState(
    val phase: FetchLifecyclePhase = FetchLifecyclePhase.INACTIVE,
    val sessionId: String? = null,
    val targetId: String? = null,
    val capability: FetchCapabilityStatus = FetchCapabilityStatus.UNKNOWN,
    val capabilityError: Throwable? = null,
    val error: Throwable? = null,
    val enabledPatternCount: Int = 0,
    val authRequestsEnabled: Boolean = false,
    val interceptedCount: Int = 0,
    val pendingCount: Int = 0,
) {
    val isReady: Boolean get() = phase == FetchLifecyclePhase.READY
    val isInactive: Boolean get() = phase == FetchLifecyclePhase.INACTIVE
    val isEnabling: Boolean get() = phase == FetchLifecyclePhase.ENABLING
    val hasPending: Boolean get() = pendingCount > 0
}