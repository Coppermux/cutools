package com.coppermux.cutools.ui.network

import com.coppermux.cutools.core.network.NetworkRequestModel

/**
 * The resource categories offered by the Network monitor's filter controls.
 *
 * Classification is deterministic and driven only by the structured fields a
 * genuine Chrome `Network` event carries on its [NetworkRequestModel]
 * ([NetworkRequestModel.resourceType] / [NetworkRequestModel.initiatorType] /
 * [NetworkRequestModel.url]). A request is never re-classified by guesswork:
 * anything that does not clearly fall into a named category is [OTHER].
 */
enum class NetworkResourceType(val displayLabel: String) {
    /** No filtering — every request is shown. */
    ALL("All"),

    /** XMLHttpRequest / fetch fetches. */
    FETCH_XHR("Fetch/XHR"),

    /** JavaScript scripts. */
    JS("JS"),

    /** Stylesheets. */
    CSS("CSS"),

    /** Images. */
    IMG("Img"),

    /** Audio/video media. */
    MEDIA("Media"),

    /** Top-level / iframe documents. */
    DOC("Doc"),

    /** Everything that does not match a named category. */
    OTHER("Other");

    companion object {
        /** Maps a filter label back to its [NetworkResourceType], defaulting to [ALL]. */
        fun fromLabel(label: String?): NetworkResourceType =
            values().find { it.displayLabel.equals(label, ignoreCase = true) } ?: ALL

        /** Classifies [model] into a filter category using its genuine CDP fields. */
        fun classify(model: NetworkRequestModel): NetworkResourceType {
            val resource = model.resourceType?.lowercase()
            val initiator = model.initiatorType?.lowercase()
            val url = model.url?.lowercase()
            if (resource == "document" || initiator == "document") return DOC
            if (resource == "stylesheet" || initiator == "stylesheet" || resource == "css") return CSS
            if (resource == "script" || initiator == "script") return JS
            if (resource == "image" || initiator == "image") return IMG
            if (resource == "media" || resource == "font" || initiator == "media") return MEDIA
            if (resource == "xhr" || resource == "fetch" || initiator == "xhr" || initiator == "fetch") {
                return FETCH_XHR
            }
            if (url != null && (url.startsWith("data:", true))) return OTHER
            if (initiator == "xmlhttprequest") return FETCH_XHR
            return OTHER
        }
    }
}