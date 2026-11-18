package com.coppermux.cutools.tools.application.models

/**
 * Cross-site same-site policy of a cookie, mirroring the CDP `Network.Cookie`
 * `sameSite` wire value.
 *
 * Values are mapped strictly from Chrome; an unrecognized or absent wire value
 * resolves to `null` on [CookieModel.sameSite] — no default policy is assumed
 * (Exact Mapping rule).
 */
enum class SameSitePolicy(val wireValue: String) {
    STRICT("Strict"),
    LAX("Lax"),
    NONE("None");

    companion object {
        /** Maps a CDP `sameSite` wire string to a [SameSitePolicy], or null when unrecognized. */
        fun fromWire(value: String?): SameSitePolicy? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Priority of a cookie as reported by the CDP `Network.Cookie` `priority`
 * field and accepted by `Network.setCookie`.
 */
enum class CookiePriority(val wireValue: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    NONE("None");

    companion object {
        /** Maps a CDP `priority` wire string to a [CookiePriority], or null when unrecognized. */
        fun fromWire(value: String?): CookiePriority? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * A single browser cookie, parsed strictly from a genuine CDP `Network.getCookies`
 * (or `Storage.getCookies`) response.
 *
 * Attribute mapping is exact: each field is taken from the corresponding CDP
 * cookie attribute without assuming defaults. [expires] is the epoch-seconds
 * expiry for persistent cookies (null for session cookies per the `session`
 * flag), [size] the cookie byte size reported by Chrome, and [sameSite] /
 * [priority] the raw policies only when Chrome reported them.
 */
data class CookieModel(
    val name: String,
    val value: String,
    val domain: String = "",
    val path: String = "",
    val expires: Long? = null,
    val size: Int = 0,
    val httpOnly: Boolean = false,
    val secure: Boolean = false,
    val session: Boolean = false,
    val sameSite: SameSitePolicy? = null,
    val priority: CookiePriority? = null,
)

/**
 * Availability of the `Network` / `Storage` cookie domains for the bound target.
 *
 * No capability is ever fabricated: a genuine domain failure or an unavailable
 * method resolves to [UNSUPPORTED] / [UNAVAILABLE] / [FAILED] explicitly
 * (No-Fake-Capability rule).
 */
enum class CookieCapabilityStatus {
    /** Cookie commands are functional on the attached target. */
    SUPPORTED,

    /** A cookie CDP method is not available on the connected target. */
    UNSUPPORTED,

    /** The cookie domain cannot be reached (e.g., no bound session). */
    UNAVAILABLE,

    /** A cookie command failed with an error. */
    FAILED,
}

/** Outcome status for a [CookieActionResult]. */
enum class CookieActionStatus {
    SUCCESS,
    UNSUPPORTED,
    UNAVAILABLE,
    FAILED,
}

/**
 * Outcome of a cookie operation.
 *
 * Mirrors the CDP error mapping contract: an operation either succeeds or
 * resolves to an explicit [CookieActionStatus] (`UNSUPPORTED`, `UNAVAILABLE`,
 * `FAILED`) with an optional diagnostic message and throwable. No mock or
 * hardcoded cookie values are ever returned.
 *
 * @param T The value carried on success (e.g. `List<CookieModel>` or `Unit`).
 */
data class CookieActionResult<out T>(
    val status: CookieActionStatus,
    val value: T? = null,
    val message: String? = null,
    val throwable: Throwable? = null,
) {
    val isSuccess: Boolean get() = status == CookieActionStatus.SUCCESS

    companion object {
        fun <T> success(value: T): CookieActionResult<T> =
            CookieActionResult(CookieActionStatus.SUCCESS, value = value)

        fun <T> unsupported(message: String?, throwable: Throwable? = null): CookieActionResult<T> =
            CookieActionResult(CookieActionStatus.UNSUPPORTED, message = message, throwable = throwable)

        fun <T> unavailable(message: String?, throwable: Throwable? = null): CookieActionResult<T> =
            CookieActionResult(CookieActionStatus.UNAVAILABLE, message = message, throwable = throwable)

        fun <T> failed(message: String?, throwable: Throwable? = null): CookieActionResult<T> =
            CookieActionResult(CookieActionStatus.FAILED, message = message, throwable = throwable)
    }
}