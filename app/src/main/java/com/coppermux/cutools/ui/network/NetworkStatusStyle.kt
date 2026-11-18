package com.coppermux.cutools.ui.network

import com.coppermux.cutools.core.network.NetworkRequestModel

/**
 * Color-coded visual status categories for the Network monitor's request list.
 *
 * The mapping follows the Chrome DevTools convention and is fully
 * deterministic: 2xx → green, 3xx → blue, 4xx/5xx → red, pending → grey,
 * failed → dark red. Nothing is guessed — the category is derived only from
 * [NetworkRequestModel.stage] and [NetworkRequestModel.responseStatus] as
 * reported by Chrome.
 */
enum class NetworkStatusCategory {
    /** Response with an HTTP 2xx status. */
    SUCCESS,

    /** Response with an HTTP 3xx status. */
    REDIRECT,

    /** Response with an HTTP 4xx status. */
    CLIENT_ERROR,

    /** Response with an HTTP 5xx status. */
    SERVER_ERROR,

    /** Request still awaiting a terminal state. */
    PENDING,

    /** Request that failed to load. */
    FAILED,
}

/**
 * Maps [NetworkRequestModel]s to [NetworkStatusCategory]s and human-readable
 * labels, and supplies the ARGB color used to render the status indicator.
 *
 * Colors are exposed both as raw ARGB ints (for `TextView.setTextColor`) and as
 * `#RRGGBB` strings (for parsing / tests), so the mapping is testable without a
 * running Android activity.
 */
object NetworkStatusStyle {

    /** The status category for [model], derived from genuine Chrome state. */
    fun category(model: NetworkRequestModel): NetworkStatusCategory = when {
        model.isFailed -> NetworkStatusCategory.FAILED
        model.isPending -> NetworkStatusCategory.PENDING
        else -> when (val status = model.responseStatus) {
            null -> NetworkStatusCategory.PENDING
            in 200..299 -> NetworkStatusCategory.SUCCESS
            in 300..399 -> NetworkStatusCategory.REDIRECT
            in 400..499 -> NetworkStatusCategory.CLIENT_ERROR
            else -> NetworkStatusCategory.SERVER_ERROR
        }
    }

    /**
     * The short status label for [model]: the HTTP status code when Chrome
     * reported one, a stage-derived marker otherwise.
     */
    fun statusLabel(model: NetworkRequestModel): String = when {
        model.isFailed -> "failed"
        model.responseStatus != null -> model.responseStatus.toString()
        model.isPending -> "…"
        else -> "response"
    }

    /** The stage display label for [model]. */
    fun stageLabel(model: NetworkRequestModel): String = model.stage.displayLabel

    /** The ARGB color used to render the status indicator for [model]. */
    fun colorArgb(model: NetworkRequestModel): Int = colorArgb(category(model))

    /** The ARGB color for [category]. */
    fun colorArgb(category: NetworkStatusCategory): Int = colorValue(category)

    /** The `#RRGGBB` string for the status indicator of [model]. */
    fun colorHex(model: NetworkRequestModel): String = colorHex(category(model))

    /** The `#RRGGBB` string for [category]. */
    fun colorHex(category: NetworkStatusCategory): String = "#%06X".format(colorValue(category) and 0xFFFFFF)

    private fun colorValue(category: NetworkStatusCategory): Int = when (category) {
        NetworkStatusCategory.SUCCESS -> 0xFF2E7D32.toInt()
        NetworkStatusCategory.REDIRECT -> 0xFF1565C0.toInt()
        NetworkStatusCategory.CLIENT_ERROR -> 0xFFC62828.toInt()
        NetworkStatusCategory.SERVER_ERROR -> 0xFFB71C1C.toInt()
        NetworkStatusCategory.PENDING -> 0xFF9E9E9E.toInt()
        NetworkStatusCategory.FAILED -> 0xFFB71C1C.toInt()
    }
}