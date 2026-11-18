package com.coppermux.cutools.core.browser

/**
 * Phase 19 — fault injection domain models.
 *
 * These are the UI-independent rules the [FaultInterceptorEngine] evaluates
 * against live WebView request interceptions, and the deterministic decisions
 * it produces. A rule is authored from the in-card `Inject Fault` dropdown and
 * targets an endpoint by glob [FaultRule.urlPattern] (`*` matches any run of
 * characters, `?` a single character), optionally restricted to a single HTTP
 * [FaultRule.method].
 *
 * A rule may combine any of the four supported fault surfaces:
 *  - [FaultRule.latencyMs]: artificial delay applied before the request is
 *    allowed to continue (simulates slow networks).
 *  - [FaultRule.statusCode]: forced HTTP response code served instead of the
 *    real response.
 *  - [FaultRule.mockBody]: mock response payload (e.g. JSON) served to the
 *    page in place of the real body.
 *  - [FaultRule.dropConnection]: simulated socket failure — the served stream
 *    throws while the page reads it, surfacing as a network error.
 */
data class FaultRule(
    val id: String,
    val urlPattern: String,
    val method: String? = null,
    val latencyMs: Int = 0,
    val statusCode: Int? = null,
    val dropConnection: Boolean = false,
    val mockBody: String? = null,
) {
    init {
        require(!(dropConnection && statusCode != null)) {
            "FaultRule cannot have both dropConnection=true and statusCode=$statusCode"
        }
        require(!(dropConnection && !mockBody.isNullOrBlank())) {
            "FaultRule cannot have both dropConnection=true and mockBody"
        }
    }

    /** True when the rule actually alters request behavior (non-empty fault). */
    val isActive: Boolean
        get() = latencyMs > 0 || statusCode != null || dropConnection || !mockBody.isNullOrBlank()

    /** Human-readable one-line summary used by the armed rules banner. */
    val summary: String
        get() = buildList {
            if (latencyMs > 0) add("+${latencyMs}ms")
            if (dropConnection) {
                add("drop")
            } else {
                if (statusCode != null) add("HTTP $statusCode")
                if (!mockBody.isNullOrBlank()) add("mock body")
            }
        }.joinToString(" · ")
}

/**
 * The kind of fault the interceptor applies to a matching request. Produced by
 * [FaultInterceptorEngine.decisionFor]:
 *  - [CONTINUE]: let WebView load the real resource (optionally after a
 *    latency delay).
 *  - [OVERRIDE]: serve a fabricated [FaultDecision.statusCode] /
 *    [FaultDecision.body] response instead of the real resource.
 *  - [DROP]: simulate a connection failure by serving a stream that throws on
 *    read.
 */
enum class FaultAction {
    CONTINUE,
    OVERRIDE,
    DROP,
}

/**
 * The deterministic outcome of evaluating all active fault rules against an
 * outgoing request. [delayMs] is always carried so latency combines with every
 * action (a mocked 500 can also arrive after +2s). [shouldDelay] excludes
 * [FaultAction.DROP], where the connection failure is immediate.
 */
data class FaultDecision(
    val action: FaultAction = FaultAction.CONTINUE,
    val delayMs: Int = 0,
    val statusCode: Int? = null,
    val body: String? = null,
) {
    /** Whether the interceptor must apply an artificial delay before acting. */
    val shouldDelay: Boolean get() = action != FaultAction.DROP && delayMs > 0
}