package com.coppermux.cutools.ui.network

import com.coppermux.cutools.core.network.NetworkRequestModel
import com.coppermux.cutools.core.network.NetworkRequestStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for Phase 5D request-list filter and search
 * predicates ([NetworkRequestFilter], [NetworkRequestFiltering]).
 */
class NetworkRequestFilterTest {

    private fun model(
        id: String,
        resourceType: String? = null,
        initiatorType: String? = null,
        url: String? = null,
        method: String? = null,
        status: Int? = null,
    ) = NetworkRequestModel(
        requestId = id,
        stage = NetworkRequestStage.FINISHED,
        url = url,
        method = method,
        resourceType = resourceType,
        initiatorType = initiatorType,
        responseStatus = status,
        sessionId = "s1",
    )

    // ============ resource type classification ============

    @Test
    fun document_classifiesAsDoc() {
        assertEquals(NetworkResourceType.DOC, NetworkResourceType.classify(model("a", resourceType = "document")))
    }

    @Test
    fun script_classifiesAsJs() {
        assertEquals(NetworkResourceType.JS, NetworkResourceType.classify(model("a", resourceType = "script")))
    }

    @Test
    fun stylesheet_classifiesAsCss() {
        assertEquals(NetworkResourceType.CSS, NetworkResourceType.classify(model("a", resourceType = "stylesheet")))
    }

    @Test
    fun image_classifiesAsImg() {
        assertEquals(NetworkResourceType.IMG, NetworkResourceType.classify(model("a", resourceType = "image")))
    }

    @Test
    fun mediaAndFont_classifyAsMedia() {
        assertEquals(NetworkResourceType.MEDIA, NetworkResourceType.classify(model("a", resourceType = "media")))
        assertEquals(NetworkResourceType.MEDIA, NetworkResourceType.classify(model("a", resourceType = "font")))
    }

    @Test
    fun xhrFetchAndXhrInitiator_classifyAsFetchXhr() {
        assertEquals(NetworkResourceType.FETCH_XHR, NetworkResourceType.classify(model("a", resourceType = "xhr")))
        assertEquals(NetworkResourceType.FETCH_XHR, NetworkResourceType.classify(model("a", resourceType = "fetch")))
        assertEquals(
            NetworkResourceType.FETCH_XHR,
            NetworkResourceType.classify(model("a", initiatorType = "xmlhttprequest")),
        )
    }

    @Test
    fun unknownResourceType_classifiesAsOther() {
        assertEquals(NetworkResourceType.OTHER, NetworkResourceType.classify(model("a", resourceType = "weird")))
    }

    @Test
    fun nullFields_classifyAsOther() {
        assertEquals(NetworkResourceType.OTHER, NetworkResourceType.classify(model("a")))
    }

    @Test
    fun classification_isCaseInsensitive() {
        assertEquals(NetworkResourceType.JS, NetworkResourceType.classify(model("a", resourceType = "SCRIPT")))
        assertEquals(NetworkResourceType.CSS, NetworkResourceType.classify(model("a", resourceType = "StyleSheet")))
    }

    @Test
    fun fromLabel_roundTripsAllTypes() {
        NetworkResourceType.values().forEach { type ->
            assertEquals(type, NetworkResourceType.fromLabel(type.displayLabel))
        }
    }

    @Test
    fun fromLabel_unknownFallsBackToAll() {
        assertEquals(NetworkResourceType.ALL, NetworkResourceType.fromLabel("nope"))
        assertEquals(NetworkResourceType.ALL, NetworkResourceType.fromLabel(null))
    }

    // ============ type filter predicate ============

    @Test
    fun allType_passesEveryRequest() {
        val filter = NetworkRequestFilter()
        assertTrue(filter.matches(model("a", resourceType = "image")))
        assertTrue(filter.matches(model("b", resourceType = "script")))
        assertTrue(filter.matches(model("c")))
    }

    @Test
    fun typePasses_separatesSelectedType() {
        val js = model("a", resourceType = "script")
        val img = model("b", resourceType = "image")
        assertTrue(NetworkRequestFiltering.typePasses(js, NetworkResourceType.JS))
        assertFalse(NetworkRequestFiltering.typePasses(img, NetworkResourceType.JS))
        assertTrue(NetworkRequestFiltering.typePasses(img, NetworkResourceType.ALL))
    }

    @Test
    fun matches_respectsSelectedType() {
        val filter = NetworkRequestFilter(resourceType = NetworkResourceType.IMG)
        assertTrue(filter.matches(model("a", resourceType = "image")))
        assertFalse(filter.matches(model("b", resourceType = "script")))
    }

    @Test
    fun matches_classifiesNullTypeViaUrlAndInitiator() {
        val filter = NetworkRequestFilter(resourceType = NetworkResourceType.FETCH_XHR)
        assertTrue(filter.matches(model("a", initiatorType = "fetch")))
        assertFalse(filter.matches(model("b", resourceType = "document")))
    }

    // ============ search predicate ============

    @Test
    fun emptySearch_passesEverything() {
        assertTrue(NetworkRequestFiltering.searchPasses(model("a", url = "https://x.com/y"), ""))
        assertTrue(NetworkRequestFiltering.searchPasses(model("a"), "   "))
    }

    @Test
    fun search_matchesUrlSubstring() {
        val model = model("a", url = "https://api.example.com/users")
        assertTrue(NetworkRequestFiltering.searchPasses(model, "users"))
        assertFalse(NetworkRequestFiltering.searchPasses(model, "orders"))
    }

    @Test
    fun search_isCaseInsensitive() {
        assertTrue(
            NetworkRequestFiltering.searchPasses(
                model("a", url = "https://api.Example.com/Users"),
                "users",
            ),
        )
    }

    @Test
    fun search_matchesMethodAndStatus() {
        assertTrue(NetworkRequestFiltering.searchPasses(model("a", method = "GET", status = 404), "get"))
        assertTrue(NetworkRequestFiltering.searchPasses(model("a", method = "POST", status = 404), "post"))
        assertTrue(NetworkRequestFiltering.searchPasses(model("a", method = "POST", status = 404), "404"))
    }

    @Test
    fun search_matchesResourceTypeField() {
        assertTrue(
            NetworkRequestFiltering.searchPasses(
                model("a", resourceType = "script", url = "https://x/app.js"),
                "script",
            ),
        )
    }

    // ============ combined filter ============

    @Test
    fun matches_requiresBothTypeAndSearch() {
        val filter = NetworkRequestFilter(resourceType = NetworkResourceType.JS, searchText = "app")
        assertTrue(filter.matches(model("a", url = "https://x/app.js", resourceType = "script")))
        assertFalse(filter.matches(model("b", url = "https://x/logo.png", resourceType = "script")))
        assertFalse(filter.matches(model("c", url = "https://x/app.js", resourceType = "image")))
    }

    @Test
    fun apply_filtersAndPreservesOrder() {
        val requests = listOf(
            model("a", resourceType = "script", url = "https://x/1.js"),
            model("b", resourceType = "image", url = "https://x/2.png"),
            model("c", resourceType = "script", url = "https://x/3.js"),
        )
        val filter = NetworkRequestFilter(resourceType = NetworkResourceType.JS)
        assertEquals(listOf("a", "c"), filter.apply(requests).map { it.requestId })
    }

    @Test
    fun apply_withSearchText() {
        val requests = listOf(
            model("a", url = "https://x/env.js"),
            model("b", url = "https://x/main.js"),
            model("c", url = "https://x/worker.js"),
        )
        val filter = NetworkRequestFilter(searchText = "worker")
        assertEquals(listOf("c"), filter.apply(requests).map { it.requestId })
    }

    @Test
    fun apply_doesNotMutateInput() {
        val requests = listOf(model("a", resourceType = "image"), model("b", resourceType = "script"))
        val filter = NetworkRequestFilter(resourceType = NetworkResourceType.IMG)
        val result = filter.apply(requests)
        assertEquals(1, result.size)
        assertEquals(2, requests.size)
    }

    @Test
    fun filterCopies_preserveOtherFields() {
        val filter = NetworkRequestFilter(resourceType = NetworkResourceType.JS, searchText = "app")
        assertEquals(NetworkResourceType.JS, filter.withSearchText("").resourceType)
        assertEquals("app", filter.withResourceType(NetworkResourceType.ALL).searchText)
        assertEquals(NetworkRequestFilter(), filter.clear())
    }

    // ============ derived filter flags ============

    @Test
    fun isActive_isTrueWhenSearchOrTypeSet() {
        assertFalse(NetworkRequestFilter().isActive)
        assertTrue(NetworkRequestFilter(resourceType = NetworkResourceType.CSS).isActive)
        assertTrue(NetworkRequestFilter(searchText = "x").isActive)
    }

    @Test
    fun isAll_isTrueOnlyWhenUnfiltered() {
        assertTrue(NetworkRequestFilter().isAll)
        assertFalse(NetworkRequestFilter(resourceType = NetworkResourceType.CSS).isAll)
        assertFalse(NetworkRequestFilter(searchText = "x").isAll)
        assertFalse(NetworkRequestFilter(resourceType = NetworkResourceType.CSS, searchText = "x").isAll)
    }
}