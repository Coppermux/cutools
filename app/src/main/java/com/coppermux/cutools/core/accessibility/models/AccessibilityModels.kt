package com.coppermux.cutools.core.accessibility.models

/**
 * Well-known accessibility property names reported by the CDP `Accessibility`
 * domain on `AXNode.properties` / `ignoredReasons`.
 *
 * The wire name for each property is provided via [wireValue]; unrecognized
 * property names remain available as raw [AXPropertyModel.name] strings and are
 * never coerced into a fabricated enum value (Exact Mapping rule).
 */
enum class AXPropertyType(val wireValue: String) {
    BUSY("busy"),
    DISABLED("disabled"),
    EXPANDED("expanded"),
    FOCUSABLE("focusable"),
    FOCUSED("focused"),
    HIDDEN("hidden"),
    HIDDEN_ROOT("hiddenRoot"),
    INVALID("invalid"),
    KEYS_SHORTCUTS("keys"),
    SETSIZE("setsize"),
    POSINSET("posinset"),
    READONLY("readonly"),
    REQUIRED("required"),
    SELECTED("selected"),
    CHECKED("checked"),
    PRESSED("pressed"),
    LEVEL("level"),
    VALUEMIN("valuemin"),
    VALUEMAX("valuemax"),
    VALUENOW("valuenow"),
    VALUETEXT("valuetext");

    companion object {
        /**
         * Maps a CDP accessibility-property wire name to a [AXPropertyType], or
         * null when the name is not one of the known property types.
         */
        fun fromWire(value: String?): AXPropertyType? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Immutable wrapper for a single `AXValueSource` entry on a CDP `AXValue`,
 * mirroring the genuine `type`, `value`, `attribute`, and `attributeValue`
 * fields plus the optional `superseded` / `invalid` flags Chrome reports.
 *
 * No source is ever fabricated; absent optional fields default safely.
 */
data class AXValueSourceModel(
    val type: String,
    val value: String?,
    val attribute: String?,
    val attributeValue: String?,
    val superseded: Boolean = false,
    val invalid: Boolean = false,
)

/**
 * Immutable wrapper for a CDP `AXValue`, capturing the genuine `type` and
 * `value` Chrome reported together with any structured [sources]. Missing or
 * malformed optional fields default safely (No-Fake-Capability rule).
 */
data class AXValueModel(
    val type: String,
    val value: String?,
    val sources: List<AXValueSourceModel> = emptyList(),
)

/**
 * Immutable wrapper for a CDP `AXProperty` (an `AXValue` paired with its name),
 * used for both accessibility properties and ignored-reason entries on an
 * `AXNode`. Never fabricated.
 */
data class AXPropertyModel(
    val name: String,
    val value: AXValueModel,
)

/**
 * Immutable wrapper for a CDP `Accessibility.AXNode`, capturing the mandatory
 * [nodeId] and [ignored] flags, optional [role] / [name] / [description] /
 * [value] `AXValue`s, [properties] and [ignoredReasons] lists, [childIds]
 * references, and the optional [backendDOMNodeId] integer. All fields map
 * strictly from a genuine Chrome payload — none are fabricated.
 */
data class AXNodeModel(
    val nodeId: String,
    val ignored: Boolean,
    val ignoredReasons: List<AXPropertyModel> = emptyList(),
    val role: AXValueModel? = null,
    val name: AXValueModel? = null,
    val description: AXValueModel? = null,
    val value: AXValueModel? = null,
    val properties: List<AXPropertyModel> = emptyList(),
    val childIds: List<String> = emptyList(),
    val backendDOMNodeId: Int? = null,
)

/**
 * Immutable wrapper for the result of `Accessibility.getFullAXTree` /
 * `Accessibility.getPartialAXTree`: the parsed [nodes] plus the derived
 * [rootNodeId] (the single node not referenced by any child) when it can be
 * determined. Nodes come strictly from the genuine Chrome payload.
 */
data class AXTreeModel(
    val nodes: List<AXNodeModel>,
    val rootNodeId: String?,
)

/**
 * Outcome of an `Accessibility` domain operation.
 *
 * Mirrors the CDP error-mapping contract: an operation either succeeds with an
 * [AXTreeModel] value or resolves to an explicit [AccessibilityError]
 * (`Unsupported`, `Unavailable`, `ExecutionFailed`, `TargetDisconnected`). No
 * mock or hardcoded AX tree state is ever produced (No-Fake-Capability rule).
 *
 * @param T The value carried on success.
 */
sealed class AccessibilityActionResult<out T> {

    /** The operation succeeded, carrying [data]. */
    data class Success<out T>(val data: T) : AccessibilityActionResult<T>()

    /** The operation failed, carrying an explicit [error]. */
    data class Failure(val error: AccessibilityError) : AccessibilityActionResult<Nothing>()
}

/**
 * Explicit, non-fabricated error for a failed `Accessibility` operation.
 */
sealed class AccessibilityError {

    /** An `Accessibility` method is not available on the attached target. */
    object Unsupported : AccessibilityError()

    /** The `Accessibility` domain cannot be reached (e.g., no bound session). */
    object Unavailable : AccessibilityError()

    /** The attached target disconnected while the operation was in flight. */
    object TargetDisconnected : AccessibilityError()

    /** The command failed (protocol error, parsing error, ...). */
    data class ExecutionFailed(val message: String) : AccessibilityError()
}