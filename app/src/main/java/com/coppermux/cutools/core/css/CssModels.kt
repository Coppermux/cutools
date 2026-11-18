package com.coppermux.cutools.core.css

/**
 * Stylesheet origin, matching the Chrome DevTools `StyleSheetOrigin` enum.
 * Unknown/absent values are preserved in [UNKNOWN].
 */
enum class CssStyleSheetOrigin(val code: String) {
    /** Stylesheets injected via extension. */
    INJECTED("injected"),

    /** User-agent stylesheets. */
    USER_AGENT("user-agent"),

    /** Stylesheets created by the inspector (rules added "via inspector"). */
    INSPECTOR("inspector"),

    /** Regular author stylesheets (page / style attributes). */
    REGULAR("regular"),

    UNKNOWN("unknown");

    /** A short human-readable origin label for UI display. */
    val displayLabel: String
        get() = when (this) {
            INJECTED -> "injected"
            USER_AGENT -> "user agent"
            INSPECTOR -> "inspector"
            REGULAR -> "author"
            UNKNOWN -> ""
        }

    companion object {
        fun fromCode(code: String?): CssStyleSheetOrigin =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * Zero-based source range within a resource, matching Chrome's `SourceRange`.
 */
data class CssSourceRange(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
)

/**
 * CSS stylesheet metainformation, matching Chrome's `CSSStyleSheetHeader`.
 *
 * @property displayName A short readable name derived from [sourceUrl] / origin.
 */
data class CssStyleSheet(
    val styleSheetId: String,
    val origin: CssStyleSheetOrigin = CssStyleSheetOrigin.UNKNOWN,
    val sourceUrl: String? = null,
    val title: String? = null,
    val disabled: Boolean = false,
    val isInline: Boolean = false,
    val isConstructed: Boolean = false,
    val frameId: String? = null,
    val startLine: Int = 0,
    val startColumn: Int = 0,
    val endLine: Int = 0,
    val endColumn: Int = 0,
) {
    /** Whether this is a user-agent stylesheet. */
    val isUserAgent: Boolean get() = origin == CssStyleSheetOrigin.USER_AGENT

    val displayName: String
        get() = when {
            isUserAgent -> "user agent stylesheet"
            isInline && sourceUrl.isNullOrBlank() -> "(inline style)"
            sourceUrl.isNullOrBlank() -> "(embedded)"
            else -> sourceUrl.substringAfterLast('/')
        }
}

/**
 * A single simple selector (matching Chrome's `Value` within a selector list).
 */
data class CssSelector(
    val text: String,
    val range: CssSourceRange? = null,
)

/**
 * A CSS cascade layer reference attached to a rule (matching Chrome's `CSSLayer`).
 */
data class CssLayer(
    val text: String,
    val range: CssSourceRange? = null,
    val styleSheetId: String? = null,
)

/**
 * A CSS container-query descriptor attached to a rule (matching Chrome's
 * `CSSContainerQuery`).
 */
data class CssContainerQuery(
    val text: String,
    val conditionText: String,
    val name: String? = null,
    val physicalAxes: String? = null,
    val logicalAxes: String? = null,
    val styleSheetId: String? = null,
    val range: CssSourceRange? = null,
)

/**
 * A cascade layer tree (matching Chrome's `CSSLayerData`, returned by
 * `CSS.getLayersForNode`).
 */
data class CssLayerData(
    val name: String,
    val order: Int,
    val subLayers: List<CssLayerData> = emptyList(),
)

/**
 * Provenance / cascade status of a declaration.
 *
 * Chrome does not emit an explicit "active vs overridden" marker on CSS
 * declarations; CuTools marks [ACTIVE]/[OVERRIDDEN] from the cascade order
 * Chrome DOES provide (matched rule order, origin, !important, inline). The
 * inherited / inline / user-agent designations are directly exposed by Chrome.
 */
enum class CssDeclarationStatus {
    /** No status determined. */
    UNKNOWN,

    /** Winning declaration for its property (derived from Chrome's cascade order). */
    ACTIVE,

    /** Beaten by a higher-precedence declaration for the same property. */
    OVERRIDDEN,

    /** Belongs to an inherited rule (Chrome's `inherited` section). */
    INHERITED,

    /** Belongs to the inline `style` attribute. */
    INLINE,

    /** Belongs to a user-agent stylesheet rule. */
    USER_AGENT,
}

/**
 * A CSS declaration (matching Chrome's `CSSProperty`).
 */
data class CssDeclaration(
    val name: String,
    val value: String,
    val important: Boolean = false,
    val disabled: Boolean = false,
    val implicit: Boolean = false,
    val parsedOk: Boolean? = null,
    val range: CssSourceRange? = null,
    val status: CssDeclarationStatus = CssDeclarationStatus.UNKNOWN,
) {
    /** "name: value [!important]" display text. */
    val displayText: String get() = "$name: $value${if (important) " !important" else ""}"
}

/**
 * A CSS rule (matching Chrome's `CSSRule`), with its matched-selector indices.
 */
data class CssRule(
    val selectorText: String,
    val selectors: List<CssSelector> = emptyList(),
    val origin: CssStyleSheetOrigin = CssStyleSheetOrigin.UNKNOWN,
    val styleSheetId: String? = null,
    val sourceRange: CssSourceRange? = null,
    val declarations: List<CssDeclaration> = emptyList(),
    val matchingSelectors: List<Int> = emptyList(),
    val mediaText: List<String> = emptyList(),
    val layers: List<CssLayer> = emptyList(),
    val containerQueries: List<CssContainerQuery> = emptyList(),
)

/**
 * A style block (matching Chrome's `CSSStyle`): a set of declarations that
 * optionally belong to a stylesheet (used for inline / attribute styles).
 */
data class CssStyleBlock(
    val styleSheetId: String? = null,
    val declarations: List<CssDeclaration> = emptyList(),
)

/**
 * Inherited CSS rules collected from an ancestor node (matching Chrome's
 * `InheritedStyleEntry`).
 */
data class CssInheritedStyles(
    val matchedRules: List<CssRule> = emptyList(),
    val inlineDeclarations: List<CssDeclaration> = emptyList(),
)

/**
 * The full style result for a single inspected node, derived from
 * `CSS.getMatchedStylesForNode`.
 */
data class CssNodeStyles(
    val nodeId: Int,
    val inlineDeclarations: List<CssDeclaration> = emptyList(),
    val attributesDeclarations: List<CssDeclaration> = emptyList(),
    val matchedRules: List<CssRule> = emptyList(),
    val inherited: List<CssInheritedStyles> = emptyList(),
) {
    /** Whether Chrome reported any meaningful style information for this node. */
    val isEmpty: Boolean
        get() = inlineDeclarations.isEmpty() &&
            attributesDeclarations.isEmpty() &&
            matchedRules.isEmpty() &&
            inherited.none { it.matchedRules.isNotEmpty() || it.inlineDeclarations.isNotEmpty() }
}

/**
 * A single computed-style property (matching Chrome's `CSSComputedStyleProperty`).
 */
data class CssComputedStyleProperty(
    val name: String,
    val value: String,
)

/**
 * An 8-number box-model quad (x/y pairs for each corner, counter-clockwise),
 * matching Chrome's `DOM.Quad`.
 */
data class CssQuad(
    val values: List<Double>,
) {
    val isEmpty: Boolean get() = values.size != 8

    /** Minimum x (leftmost) across the quad corners. */
    val left: Double? get() = if (isEmpty) null else values.withIndex().minByOrNull { it.value }?.value

    /** Maximum x (rightmost) across the quad corners. */
    val right: Double? get() = if (isEmpty) null else values.withIndex().maxByOrNull { it.value }?.value

    /** Minimum y (topmost) across the quad corners. */
    val top: Double? get() = if (isEmpty) null else values.filterIndexed { i, _ -> i % 2 == 1 }.minOrNull()

    /** Maximum y (bottommost) across the quad corners. */
    val bottom: Double? get() = if (isEmpty) null else values.filterIndexed { i, _ -> i % 2 == 1 }.maxOrNull()

    /** Rendered "x, y / w×h" summary derived purely from Chrome's quad geometry. */
    val summary: String
        get() {
            if (isEmpty) return "n/a"
            val l = left ?: return "n/a"
            val r = right ?: return "n/a"
            val t = top ?: return "n/a"
            val b = bottom ?: return "n/a"
            return "(${"%.0f".format(l)}, ${"%.0f".format(t)}) ${"%.0f".format(r - l)}×${"%.0f".format(b - t)}"
        }
}

/**
 * Chrome-backed box model for a node (matching `DOM.BoxModel`). Geometry is
 * authoritative from Chrome — never reconstructed locally.
 */
data class CssBoxModel(
    val content: CssQuad? = null,
    val padding: CssQuad? = null,
    val border: CssQuad? = null,
    val margin: CssQuad? = null,
    val width: Int? = null,
    val height: Int? = null,
) {
    val isEmpty: Boolean
        get() = content == null && padding == null && border == null && margin == null && width == null && height == null
}

/**
 * Node-level layout information derived from Chrome (`DOM.getContentQuads`,
 * `DOM.getRelayoutBoundary`). Geometry originates from Chrome.
 */
data class CssLayoutInfo(
    val nodeId: Int,
    val contentQuads: List<CssQuad> = emptyList(),
    val relayoutBoundaryNodeId: Int? = null,
) {
    val isEmpty: Boolean get() = contentQuads.isEmpty() && relayoutBoundaryNodeId == null
}

/**
 * Container-query information for a node (`DOM.getContainerForNode`,
 * `DOM.getQueryingDescendantsForContainer`).
 */
data class CssContainerInfo(
    val nodeId: Int,
    val containerNodeId: Int? = null,
    val queryingDescendants: List<Int> = emptyList(),
) {
    val isEmpty: Boolean get() = containerNodeId == null && queryingDescendants.isEmpty()
}

/**
 * Lifecycle phase of the CSS inspection request for the currently selected node.
 */
enum class CssInspectionPhase {
    /** No node is selected / no inspection requested. */
    IDLE,

    /** A `CSS.getMatchedStylesForNode` request is in flight. */
    LOADING,

    /** Styles were retrieved and are available. */
    LOADED,

    /** The request completed but Chrome reported no styles for the node. */
    EMPTY,

    /** The request failed with a protocol/domain error. */
    FAILED,

    /** The CSS domain is unavailable for the current target. */
    UNSUPPORTED,
}

/**
 * Availability of the CSS domain on the active target.
 */
enum class CssCapabilityStatus {
    /** Not yet probed. */
    UNKNOWN,

    /** CSS domain is enabled and functional. */
    SUPPORTED,

    /** CSS domain is not available (method not found). */
    UNSUPPORTED,

    /** CSS domain enablement failed with an error. */
    FAILED,
}

/**
 * A CSS pseudo-class that can be forced on the selected node via
 * `CSS.forcePseudoState`.
 */
enum class CssPseudoClass(val cdpValue: String, val displayLabel: String) {
    HOVER("hover", ":hover"),
    ACTIVE("active", ":active"),
    FOCUS("focus", ":focus"),
    UNKNOWN("", "");

    companion object {
        fun fromCdp(value: String?): CssPseudoClass =
            entries.firstOrNull { it.cdpValue == value } ?: UNKNOWN
    }
}

/**
 * Lifecycle phase of a pseudo-class force/remove operation.
 */
enum class CssPseudoStatePhase {
    /** No node selected / no operation pending. */
    IDLE,

    /** A `CSS.forcePseudoState` request to apply a class is in flight. */
    APPLYING,

    /** A `CSS.forcePseudoState` request to remove a class is in flight. */
    REMOVING,

    /** The last operation succeeded; [CssPseudoState.forcedClasses] is confirmed by Chrome. */
    APPLIED,

    /** The last operation failed; [CssPseudoState.forcedClasses] still reflects the confirmed set. */
    FAILED,

    /** Pseudo-state forcing is unavailable for this target. */
    UNSUPPORTED,
}

/**
 * A type of layout overlay that can be shown on the live page via the `Overlay`
 * CDP domain. This is INDEPENDENT of structured Grid/Flex inspection (Phase 3D),
 * which this protocol version does not expose; overlay support is its own
 * capability.
 */
enum class CssOverlayType(val displayLabel: String) {
    GRID("Grid"),
    FLEX("Flexbox"),
    CONTAINER_QUERY("Container query"),
}

/**
 * Lifecycle phase of a layout-overlay operation.
 */
enum class CssOverlayPhase {
    /** No overlay is requested for the current selection. */
    DISABLED,

    /** A show/clear request is in flight. */
    REQUESTED,

    /** Chrome confirmed the overlay is active on the live page. */
    ACTIVE,

    /** Chrome rejected the operation. */
    FAILED,

    /** The Overlay domain is unavailable for this target. */
    UNSUPPORTED,
}

/**
 * Explicit state of the layout overlay for the selected node. [phase] is only
 * moved to [CssOverlayPhase.ACTIVE] after Chrome confirms; a local toggle is
 * never treated as proof the browser shows an overlay.
 */
data class CssOverlayState(
    val type: CssOverlayType? = null,
    val phase: CssOverlayPhase = CssOverlayPhase.DISABLED,
    val capability: CssCapabilityStatus = CssCapabilityStatus.UNKNOWN,
    val error: Throwable? = null,
) {
    val isActive: Boolean get() = phase == CssOverlayPhase.ACTIVE
    val isRequested: Boolean get() = phase == CssOverlayPhase.REQUESTED
    val isUnsupported: Boolean get() = phase == CssOverlayPhase.UNSUPPORTED

    companion object {
        fun empty(): CssOverlayState = CssOverlayState()
    }
}

/**
 * Explicit state of forced pseudo-classes for the selected node.
 *
 * [forcedClasses] only ever reflects what Chrome confirmed — it is updated only
 * after a successful `CSS.forcePseudoState`. Never presented as active when the
 * browser rejected the operation.
 */
data class CssPseudoState(
    val nodeId: Int? = null,
    val forcedClasses: Set<CssPseudoClass> = emptySet(),
    val phase: CssPseudoStatePhase = CssPseudoStatePhase.IDLE,
    val capability: CssCapabilityStatus = CssCapabilityStatus.UNKNOWN,
    val error: Throwable? = null,
) {
    val isForcing: Boolean
        get() = phase == CssPseudoStatePhase.APPLYING || phase == CssPseudoStatePhase.REMOVING

    fun isForced(pseudoClass: CssPseudoClass): Boolean = forcedClasses.contains(pseudoClass)

    companion object {
        fun empty(): CssPseudoState = CssPseudoState()
    }
}

/**
 * Explicit read-only CSS inspection state.
 *
 * Owned by the [CssInspector]. Tracks the selected node, the loading phase, the
 * parsed styles, the known stylesheets, the capability status, and any error.
 * Also carries the Phase 3B computed-style and box-model sub-states for the same
 * node. Invalidate on selection change / target change / connection loss so an
 * older response never appears as the current selection's data.
 */
data class CssInspectionState(
    val nodeId: Int? = null,
    val phase: CssInspectionPhase = CssInspectionPhase.IDLE,
    val styles: CssNodeStyles? = null,
    val stylesheets: List<CssStyleSheet> = emptyList(),
    val capability: CssCapabilityStatus = CssCapabilityStatus.UNKNOWN,
    val error: Throwable? = null,
    // Phase 3B — computed styles
    val computedStylePhase: CssInspectionPhase = CssInspectionPhase.IDLE,
    val computedStyle: List<CssComputedStyleProperty>? = null,
    val computedStyleError: Throwable? = null,
    // Phase 3B — cascade layers for the selected node
    val layerPhase: CssInspectionPhase = CssInspectionPhase.IDLE,
    val rootLayer: CssLayerData? = null,
    val layerError: Throwable? = null,
    // Phase 3B — box model
    val boxModelPhase: CssInspectionPhase = CssInspectionPhase.IDLE,
    val boxModel: CssBoxModel? = null,
    val boxModelError: Throwable? = null,
    // Phase 3C — forced pseudo-classes for the selected node
    val pseudoState: CssPseudoState = CssPseudoState.empty(),
    // Phase 3D — node-level layout
    val layoutPhase: CssInspectionPhase = CssInspectionPhase.IDLE,
    val layoutInfo: CssLayoutInfo? = null,
    val layoutError: Throwable? = null,
    // Phase 3D — container queries
    val containerPhase: CssInspectionPhase = CssInspectionPhase.IDLE,
    val containerInfo: CssContainerInfo? = null,
    val containerError: Throwable? = null,
    // Phase 3D — structured Grid / Flexbox availability (this CDP version exposes
    // grid/flex only through Overlay highlight APIs, which belong to Phase 3E).
    val gridCapability: CssCapabilityStatus = CssCapabilityStatus.UNSUPPORTED,
    val flexCapability: CssCapabilityStatus = CssCapabilityStatus.UNSUPPORTED,
    val layoutCapabilityNote: String? = null,
    // Phase 3E — layout overlay for the selected node (independent capability)
    val overlayState: CssOverlayState = CssOverlayState.empty(),
) {
    val isLoading: Boolean get() = phase == CssInspectionPhase.LOADING
    val isLoaded: Boolean get() = phase == CssInspectionPhase.LOADED
    val hasError: Boolean get() = error != null

    val isComputedStyleLoading: Boolean get() = computedStylePhase == CssInspectionPhase.LOADING
    val isComputedStyleLoaded: Boolean get() = computedStylePhase == CssInspectionPhase.LOADED
    val hasComputedStyleError: Boolean get() = computedStyleError != null

    val isBoxModelLoading: Boolean get() = boxModelPhase == CssInspectionPhase.LOADING
    val isBoxModelLoaded: Boolean get() = boxModelPhase == CssInspectionPhase.LOADED
    val hasBoxModelError: Boolean get() = boxModelError != null

    val isLayoutLoading: Boolean get() = layoutPhase == CssInspectionPhase.LOADING
    val isLayoutLoaded: Boolean get() = layoutPhase == CssInspectionPhase.LOADED
    val hasLayoutError: Boolean get() = layoutError != null

    val isContainerLoading: Boolean get() = containerPhase == CssInspectionPhase.LOADING
    val isContainerLoaded: Boolean get() = containerPhase == CssInspectionPhase.LOADED
    val hasContainerError: Boolean get() = containerError != null

    /** Whether structured Grid inspection is available (unavailable in this protocol version). */
    val isGridSupported: Boolean get() = gridCapability == CssCapabilityStatus.SUPPORTED

    /** Whether structured Flexbox inspection is available (unavailable in this protocol version). */
    val isFlexSupported: Boolean get() = flexCapability == CssCapabilityStatus.SUPPORTED

    /** The number of matched (non-inherited) rules in the current result. */
    val matchedRuleCount: Int get() = styles?.matchedRules?.size ?: 0

    companion object {
        fun empty(): CssInspectionState = CssInspectionState()
    }
}