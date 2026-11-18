package com.coppermux.cutools.ui.accessibility

import com.coppermux.cutools.core.accessibility.models.AXNodeModel
import com.coppermux.cutools.core.accessibility.models.AXTreeModel
import com.coppermux.cutools.core.accessibility.models.AccessibilityCapabilityStatus

/**
 * Immutable UI snapshot for the Accessibility panel.
 *
 * Mirrors genuine engine truth only — [status] is derived from the
 * [com.coppermux.cutools.core.accessibility.AccessibilityEngine] capability,
 * [axTree] holds the parsed tree from a real `Accessibility.getFullAXTree`
 * result, and [errorMessage] carries an explicit renderable error resolved from
 * an engine failure. No mock or synthetic tree state is ever produced
 * (No-Fake-Capability rule).
 *
 * @property status The explicit `Accessibility` capability (SUPPORTED /
 *   UNSUPPORTED / UNAVAILABLE / FAILED).
 * @property isLoading True while a tree load command is in flight.
 * @property axTree The genuine parsed AX tree, or null when not loaded.
 * @property selectedNode The node selected for the detail view, or null.
 * @property searchQuery The live filter query applied to the tree.
 * @property errorMessage A renderable banner string, or null when no error.
 */
data class AccessibilityUiState(
    val status: AccessibilityCapabilityStatus = AccessibilityCapabilityStatus.UNAVAILABLE,
    val isLoading: Boolean = false,
    val axTree: AXTreeModel? = null,
    val selectedNode: AXNodeModel? = null,
    val searchQuery: String = "",
    val errorMessage: String? = null,
)