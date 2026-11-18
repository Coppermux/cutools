package com.coppermux.cutools.core.browser

/**
 * Phase 19 — cURL command generator.
 *
 * Pure, Android-free formatter that transforms an intercepted network event
 * ([DashboardEvent]) — or an explicit method/URL/header/body tuple — into a
 * valid, executable `curl` command string. Escaping rules:
 *  - URL, header names, and header values are double-quoted with `\` and `"`
 *    escaped, so values containing spaces or shell metacharacters are safe.
 *  - Request bodies use single-quoted `--data-raw '...'` with `'` escaped as
 *    `'\''` (the POSIX-safe single-quote escape).
 *  - `-X <METHOD>` is emitted whenever curl's defaults would otherwise change
 *    the method (any non-GET, or any request carrying a body), so an explicit
 *    `-X GET --data-raw '...'` preserves the intended method instead of letting
 *    curl silently switch to POST.
 *  - Query parameters are preserved verbatim from the URL — the generator
 *    never re-encodes them.
 *
 * The output is deterministic and JVM-testable; it only reflects the genuine
 * data captured by the interceptor (WebView exposes request headers but not
 * raw POST payloads for pass-through loads, so a body is only emitted when one
 * was captured on the event).
 */
object CurlGenerator {

    /** Builds a curl command from the event's method, URL, headers, and body. */
    fun fromEvent(event: DashboardEvent): String =
        generate(
            url = event.url ?: "",
            method = event.method ?: "GET",
            headers = event.requestHeaders,
            body = event.requestBody,
        )

    /**
     * Builds a valid, executable curl command. [url] is required; a blank URL
     * yields the bare `curl` command (never a fabricated target).
     */
    fun generate(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): String {
        val parts = mutableListOf<String>()
        val methodUp = method.trim().uppercase().ifBlank { "GET" }

        parts.add("curl")
        if (url.isNotBlank()) {
            parts.add(quoteDouble(url))
            // -X pins the method whenever curl's defaults would otherwise
            // change it (non-GET, or any body which would switch to POST).
            if (methodUp != "GET" || !body.isNullOrEmpty()) {
                parts.add("-X ${quoteDouble(methodUp)}")
            }
        } else if (methodUp != "GET") {
            parts.add("-X ${quoteDouble(methodUp)}")
        }

        headers.forEach { (name, value) ->
            parts.add("-H ${quoteDouble("$name: $value")}")
        }

        if (!body.isNullOrEmpty()) {
            parts.add("--data-raw ${quoteSingle(body)}")
        }

        return parts.joinToString(" ")
    }

    /** Wraps [value] in double quotes, escaping backslashes and quotes. */
    internal fun quoteDouble(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Wraps [value] in single quotes using the POSIX-safe `'\''` escape. */
    internal fun quoteSingle(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}