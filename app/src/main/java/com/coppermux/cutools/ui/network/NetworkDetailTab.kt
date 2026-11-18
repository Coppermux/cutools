package com.coppermux.cutools.ui.network

/**
 * The detail inspector's navigation tabs, matching the inspector's three
 * functional panes: headers, response body, and timing breakdown.
 */
enum class NetworkDetailTab(val displayLabel: String) {
    /** General / request headers / response headers. */
    HEADERS("Headers"),

    /** Pretty-printed JSON/text body (or binary/evicted indicators). */
    BODY("Response Body"),

    /** Graphical / metric DNS, SSL, TTFB and content-download breakdown. */
    TIMING("Timing"),
}