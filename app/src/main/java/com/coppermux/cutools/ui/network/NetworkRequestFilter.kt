package com.coppermux.cutools.ui.network

import com.coppermux.cutools.core.network.NetworkRequestModel

/**
 * The Network monitor's presentation filter: a resource-type selection plus a
 * live, case-insensitive search text.
 *
 * Filtering is a CuTools presentation convenience composed deterministically
 * over the structured [NetworkRequestModel]s already owned by the Network
 * engine. It never mutates or deletes the underlying request collection and
 * never talks to Chrome.
 */
data class NetworkRequestFilter(
    val resourceType: NetworkResourceType = NetworkResourceType.ALL,
    val searchText: String = "",
) {
    /** Whether no filtering is applied (ALL types and an empty search). */
    val isAll: Boolean get() = resourceType == NetworkResourceType.ALL && searchText.isBlank()

    /** Whether any filtering is active. */
    val isActive: Boolean get() = !isAll

    /** A copy with the resource-type selection updated. */
    fun withResourceType(type: NetworkResourceType): NetworkRequestFilter = copy(resourceType = type)

    /** A copy with the search text updated. */
    fun withSearchText(text: String): NetworkRequestFilter = copy(searchText = text)

    /** Clears both filters. */
    fun clear(): NetworkRequestFilter = NetworkRequestFilter()

    /** Whether [model] passes the active filter. */
    fun matches(model: NetworkRequestModel): Boolean =
        NetworkRequestFiltering.matches(model, resourceType, searchText)

    /** The subset of [requests] that passes the filter, preserving order. */
    fun apply(requests: List<NetworkRequestModel>): List<NetworkRequestModel> =
        NetworkRequestFiltering.apply(requests, resourceType, searchText)
}

/**
 * Pure, deterministic predicate evaluation for the Network monitor's filter.
 */
object NetworkRequestFiltering {

    /** The meaningful, searchable text of [model], derived from structured fields. */
    fun searchableText(model: NetworkRequestModel): String = buildString {
        model.url?.let { append(it).append(' ') }
        model.method?.let { append(it).append(' ') }
        model.responseStatus?.let { append(it).append(' ') }
        model.responseStatusText?.let { append(it).append(' ') }
        model.resourceType?.let { append(it).append(' ') }
        model.initiatorType?.let { append(it).append(' ') }
        model.responseMimeType?.let { append(it).append(' ') }
    }

    /** Whether [model] matches a resource type (ALL always passes). */
    fun typePasses(model: NetworkRequestModel, type: NetworkResourceType): Boolean =
        type == NetworkResourceType.ALL || type == NetworkResourceType.classify(model)

    /** Whether [model] matches the search [query] (case-insensitive substring). */
    fun searchPasses(model: NetworkRequestModel, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return searchableText(model).contains(q, ignoreCase = true)
    }

    /** Whether [model] satisfies both the [type] and the search [query]. */
    fun matches(model: NetworkRequestModel, type: NetworkResourceType, query: String): Boolean =
        typePasses(model, type) && searchPasses(model, query)

    /** Applies the filter to [requests], preserving order and never mutating input. */
    fun apply(
        requests: List<NetworkRequestModel>,
        type: NetworkResourceType,
        query: String,
    ): List<NetworkRequestModel> = requests.filter { matches(it, type, query) }
}