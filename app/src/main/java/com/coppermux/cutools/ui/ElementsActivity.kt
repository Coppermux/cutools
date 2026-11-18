package com.coppermux.cutools.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.coppermux.cutools.CuToolsApp
import com.coppermux.cutools.databinding.ActivityElementsBinding
import com.coppermux.cutools.core.css.CssCapabilityStatus
import com.coppermux.cutools.core.css.CssDeclaration
import com.coppermux.cutools.core.css.CssInspectionPhase
import com.coppermux.cutools.core.css.CssInspectionState
import com.coppermux.cutools.core.css.CssInspector
import com.coppermux.cutools.core.css.CssInspectorListener
import com.coppermux.cutools.core.css.CssOverlayPhase
import com.coppermux.cutools.core.css.CssOverlayType
import com.coppermux.cutools.core.css.CssPseudoClass
import com.coppermux.cutools.core.css.CssPseudoStatePhase
import com.coppermux.cutools.core.dom.DomAttribute
import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.dom.DomInspectorListener
import com.coppermux.cutools.core.dom.DomInspectionState
import com.coppermux.cutools.core.dom.DomMutationPhase
import com.coppermux.cutools.core.dom.DomMutationState
import com.coppermux.cutools.core.dom.DomNode
import com.coppermux.cutools.core.dom.DomSearchState
import com.coppermux.cutools.core.dom.DomState
import com.coppermux.cutools.core.dom.DomTree

/**
 * Elements / DOM inspector UI (Phase 2C — DOM Live Editing; Phase 3A — CSS Styles).
 *
 * Extends the Phase 2B read-only inspector with genuine Chrome-backed editing
 * and a CSS Styles panel backed by the [CssInspector] (real `CSS.getMatchedStylesForNode`).
 *
 * All mutations run through [DomInspector] (real CDP). Destructive actions are
 * explicit (confirm dialog for delete). Mutation controls are hidden/disabled
 * when there is no valid selected node, the session is unavailable, a mutation
 * is already running, or the operation is invalid for the selected node.
 */
class ElementsActivity : AppCompatActivity(), DomInspectorListener, CssInspectorListener {

    private lateinit var binding: ActivityElementsBinding
    private lateinit var inspector: DomInspector
    private lateinit var cssInspector: CssInspector
    private lateinit var treeAdapter: DomTreeAdapter
    private lateinit var attrAdapter: DomAttributeAdapter
    private lateinit var cssAdapter: CssStyleAdapter

    // True when the outer-HTML edit box is open.
    private var htmlEditMode: Boolean = false

    // Local filter for already-retrieved computed styles (Phase 3B).
    private var computedStyleFilter: String = ""

    // Guards against re-entrant toggle updates while renderCss reflects state.
    private var updatingPseudoUi: Boolean = false
    private var updatingOverlayUi: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityElementsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        inspector = (application as CuToolsApp).domInspector
        cssInspector = (application as CuToolsApp).cssInspector
        inspector.addListener(this)
        cssInspector.addListener(this)

        treeAdapter = DomTreeAdapter(
            onNodeClick = { node -> inspector.selectNode(node.nodeId) },
            onExpandToggle = { node -> inspector.toggleExpand(node.nodeId) },
        )
        binding.elementsTreeRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.elementsTreeRecyclerView.adapter = treeAdapter

        attrAdapter = DomAttributeAdapter(
            onEdit = { attr -> promptEditAttribute(attr) },
            onRemove = { attr -> confirmRemoveAttribute(attr) },
        )
        binding.elementsAttributesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.elementsAttributesRecyclerView.adapter = attrAdapter

        cssAdapter = CssStyleAdapter()
        binding.elementsCssRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.elementsCssRecyclerView.adapter = cssAdapter

        binding.elementsCssFilterClearButton.setOnClickListener {
            binding.elementsCssFilterInput.text?.clear()
            computedStyleFilter = ""
            renderCss(cssInspector.inspectionState)
        }
        binding.elementsCssFilterInput.setOnEditorActionListener { _, _, _ ->
            computedStyleFilter = binding.elementsCssFilterInput.text?.toString().orEmpty()
            renderCss(cssInspector.inspectionState)
            false
        }
        binding.elementsCssFilterInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                computedStyleFilter = binding.elementsCssFilterInput.text?.toString().orEmpty()
                renderCss(cssInspector.inspectionState)
            }
        }

        binding.elementsPseudoHover.setOnCheckedChangeListener { _, checked -> togglePseudo(CssPseudoClass.HOVER, checked) }
        binding.elementsPseudoActive.setOnCheckedChangeListener { _, checked -> togglePseudo(CssPseudoClass.ACTIVE, checked) }
        binding.elementsPseudoFocus.setOnCheckedChangeListener { _, checked -> togglePseudo(CssPseudoClass.FOCUS, checked) }

        binding.elementsOverlayGrid.setOnCheckedChangeListener { _, checked -> toggleOverlay(CssOverlayType.GRID, checked) }
        binding.elementsOverlayFlex.setOnCheckedChangeListener { _, checked -> toggleOverlay(CssOverlayType.FLEX, checked) }
        binding.elementsOverlayContainer.setOnCheckedChangeListener { _, checked -> toggleOverlay(CssOverlayType.CONTAINER_QUERY, checked) }

        wiringClicks()

        // Render the current state.
        renderTree()
        renderDetail(inspector.inspectionState)
        renderSearch(inspector.searchState)
        renderMutation(inspector.mutationState)
        renderCss(cssInspector.inspectionState)
        renderState()
    }

    private fun wiringClicks() {
        binding.elementsSearchButton.setOnClickListener { doSearch() }
        binding.elementsSearchPrevButton.setOnClickListener { inspector.searchPrevious() }
        binding.elementsSearchNextButton.setOnClickListener { inspector.searchNext() }
        binding.elementsSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }
        binding.elementsSearchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.elementsSearchInput.selectAll()
        }
        binding.elementsRefreshButton.setOnClickListener { inspector.refresh() }

        // Attribute creation.
        binding.elementsAddAttrButton.setOnClickListener { addAttribute() }
        binding.elementsNewAttrValue.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addAttribute()
                true
            } else false
        }

        // Text-node editing.
        binding.elementsApplyTextButton.setOnClickListener { applyText() }

        // Outer HTML editing.
        binding.elementsEditHtmlButton.setOnClickListener { enterHtmlEditMode() }
        binding.elementsApplyHtmlButton.setOnClickListener { applyOuterHtml() }
        binding.elementsCancelHtmlButton.setOnClickListener { exitHtmlEditMode() }

        // Node deletion (explicit confirmation).
        binding.elementsDeleteNodeButton.setOnClickListener { confirmDeleteNode() }
    }

    private fun doSearch() {
        val query = binding.elementsSearchInput.text?.toString().orEmpty()
        inspector.performSearch(query)
    }

    /** Sends a pseudo-class force/remove request to the real Chrome target. */
    private fun togglePseudo(pseudoClass: CssPseudoClass, checked: Boolean) {
        if (updatingPseudoUi) return
        val node = inspector.selectedNode() ?: return
        cssInspector.setPseudoClass(node.nodeId, pseudoClass, checked) { }
    }

    /** Sends a layout-overlay show/hide request to the real Chrome target. */
    private fun toggleOverlay(type: CssOverlayType, checked: Boolean) {
        if (updatingOverlayUi) return
        val node = inspector.selectedNode() ?: return
        cssInspector.setOverlay(type, node.nodeId, checked) { }
    }

    // ============ Mutation actions ============

    private fun addAttribute() {
        val node = inspector.selectedNode() ?: return
        val name = binding.elementsNewAttrName.text?.toString()?.trim().orEmpty()
        val value = binding.elementsNewAttrValue.text?.toString().orEmpty()
        if (name.isEmpty()) {
            binding.elementsMutationStatus.text = "Attribute name is required"
            return
        }
        inspector.addAttribute(node.nodeId, name, value) { }
        binding.elementsNewAttrName.text?.clear()
        binding.elementsNewAttrValue.text?.clear()
    }

    private fun promptEditAttribute(attr: DomAttribute) {
        val node = inspector.selectedNode() ?: return
        val input = EditText(this).apply {
            setText(attr.value.orEmpty())
            hint = "attribute value"
        }
        AlertDialog.Builder(this)
            .setTitle("Edit attribute \"${attr.name}\"")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                inspector.setAttributeValue(node.nodeId, attr.name, input.text.toString()) { }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemoveAttribute(attr: DomAttribute) {
        val node = inspector.selectedNode() ?: return
        AlertDialog.Builder(this)
            .setTitle("Remove attribute?")
            .setMessage("Remove \"${attr.name}\" from ${node.displayLabel}?")
            .setPositiveButton("Remove") { _, _ ->
                inspector.removeAttribute(node.nodeId, attr.name) { }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyText() {
        val node = inspector.selectedNode() ?: return
        if (!node.isText && !node.isComment) return
        val value = binding.elementsTextValueInput.text?.toString().orEmpty()
        inspector.setTextNodeValue(node.nodeId, value) { }
    }

    private fun enterHtmlEditMode() {
        val node = inspector.selectedNode() ?: return
        if (!node.isElement) return
        htmlEditMode = true
        renderDetail(inspector.inspectionState)
    }

    private fun exitHtmlEditMode() {
        htmlEditMode = false
        renderDetail(inspector.inspectionState)
    }

    private fun applyOuterHtml() {
        val node = inspector.selectedNode() ?: return
        val html = binding.elementsOuterHtmlEdit.text?.toString().orEmpty()
        inspector.setOuterHTML(node.nodeId, html) { }
        htmlEditMode = false
        renderDetail(inspector.inspectionState)
    }

    private fun confirmDeleteNode() {
        val node = inspector.selectedNode() ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete node?")
            .setMessage("Delete ${node.displayLabel} from the page? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                inspector.removeNode(node.nodeId) { }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ============ Inspector listeners ============

    override fun onNodesChanged(changedNodeIds: Set<Int>) {
        runOnUiThread { renderTree(); renderDetail(inspector.inspectionState) }
    }

    override fun onSelectionChanged(node: DomNode?) {
        runOnUiThread { renderDetail(inspector.inspectionState) }
    }

    override fun onInspectionStateChanged(state: DomInspectionState) {
        runOnUiThread { renderDetail(state) }
    }

    override fun onSearchStateChanged(state: DomSearchState) {
        runOnUiThread { renderSearch(state) }
    }

    override fun onMutationStateChanged(state: DomMutationState) {
        runOnUiThread { renderMutation(state) }
    }

    override fun onDocumentLoaded(document: DomNode?) {
        runOnUiThread {
            renderTree()
            renderDetail(inspector.inspectionState)
            renderSearch(inspector.searchState)
            renderMutation(inspector.mutationState)
            renderCss(cssInspector.inspectionState)
        }
    }

    override fun onStateChanged(state: DomState, error: Throwable?) {
        runOnUiThread {
            renderState()
            if (error != null) binding.elementsSearchStatus.text = "Error: ${error.message}"
        }
    }

    override fun onError(error: Throwable) {
        runOnUiThread {
            if (inspector.searchState.hasError) return@runOnUiThread
            binding.elementsSearchStatus.text = "Error: ${error.message}"
        }
    }

    // ============ CSS inspector listeners ============

    override fun onCssStateChanged(state: CssInspectionState) {
        runOnUiThread { renderCss(state) }
    }

    override fun onCssCapabilityChanged(capability: CssCapabilityStatus) {
        runOnUiThread { renderCss(cssInspector.inspectionState) }
    }

    // ============ Rendering ============

    private fun renderState() {
        val target = inspector.targetId()?.let { "Target: $it" } ?: "Target: —"
        binding.elementsTargetText.text = target
        binding.elementsStateText.text = "State: ${inspector.state().name}"
        binding.elementsRefreshButton.isEnabled = inspector.state() == DomState.READY
    }

    private fun renderTree() {
        val flat = flattenTree(inspector.tree, inspector.expandedIds())
        treeAdapter.submit(flat)
    }

    /** Flattens the DOM tree into visible rows, honoring expansion state. */
    private fun flattenTree(tree: DomTree, expanded: Set<Int>): List<DomTreeAdapter.FlatNode> {
        val result = mutableListOf<DomTreeAdapter.FlatNode>()
        val root = tree.document ?: return result
        fun walk(node: DomNode, depth: Int) {
            result.add(DomTreeAdapter.FlatNode(node, depth))
            if (expanded.contains(node.nodeId)) {
                node.children.forEach { walk(it, depth + 1) }
            }
        }
        walk(root, 0)
        return result
    }

    /** Renders selected-node inspection (metadata + attributes + HTML + mutation controls). */
    private fun renderDetail(state: DomInspectionState) {
        val node = inspector.selectedNode()
        if (node == null) {
            binding.elementsSelectedLabel.text = "Nothing selected"
            binding.elementsSelectedDetails.text = ""
            attrAdapter.submit(emptyList())
            binding.elementsOuterHtmlText.text = ""
            showMutationControls(false)
            return
        }
        binding.elementsSelectedLabel.text = node.displayLabel
        val editable = canEdit(node)
        attrAdapter.submit(node.attributes, editable)
        binding.elementsSelectedDetails.text = buildString {
            append("Type: ${node.nodeType.name}")
            append('\n')
            append("nodeId: ${node.nodeId}")
            node.backendNodeId?.let { append("\nbackendNodeId: $it") }
            if (node.isElement) {
                node.localName?.let { append("\nlocalName: $it") }
            }
            if (node.isText || node.isComment) {
                append("\nvalue: ${node.nodeValue.orEmpty()}")
            }
            append("\nchildNodeCount: ${node.childNodeCount}")
            append("\nchildren loaded: ${node.children.size}")
        }
        renderOuterHtml(node, state)
        showMutationControls(editable, node)
    }

    private fun canEdit(node: DomNode): Boolean =
        inspector.session != null &&
            inspector.state() == DomState.READY &&
            !inspector.mutationState.isSubmitting &&
            (node.isElement || node.isText || node.isComment)

    private fun showMutationControls(editable: Boolean, node: DomNode? = null) {
        val enabled = editable
        binding.elementsAddAttributeRow.visibility = if (enabled && node?.isElement == true) View.VISIBLE else View.GONE
        binding.elementsTextEditTitle.visibility = if (enabled && (node?.isText == true || node?.isComment == true)) View.VISIBLE else View.GONE
        binding.elementsTextEditRow.visibility = if (enabled && (node?.isText == true || node?.isComment == true)) View.VISIBLE else View.GONE
        binding.elementsEditHtmlButton.visibility = if (enabled && node?.isElement == true) View.VISIBLE else View.GONE
        binding.elementsDeleteNodeButton.visibility = if (enabled && node?.isElement == true) View.VISIBLE else View.GONE
    }

    private fun renderOuterHtml(node: DomNode, state: DomInspectionState) {
        // Only elements support outer-HTML replacement.
        val elementEdit = node.isElement
        binding.elementsHtmlTitle.visibility = if (elementEdit) View.VISIBLE else View.GONE
        binding.elementsOuterHtmlScroll.visibility = if (elementEdit && !htmlEditMode) View.VISIBLE else View.GONE
        binding.elementsOuterHtmlEdit.visibility = if (elementEdit && htmlEditMode) View.VISIBLE else View.GONE
        binding.elementsEditHtmlButton.visibility = if (elementEdit && !htmlEditMode) View.VISIBLE else View.GONE
        binding.elementsApplyHtmlButton.visibility = if (elementEdit && htmlEditMode) View.VISIBLE else View.GONE
        binding.elementsCancelHtmlButton.visibility = if (elementEdit && htmlEditMode) View.VISIBLE else View.GONE

        if (htmlEditMode) {
            binding.elementsOuterHtmlEdit.setText(state.outerHtml ?: "")
            return
        }
        binding.elementsOuterHtmlText.text = when {
            state.outerHtmlLoading -> "Loading outer HTML…"
            state.outerHtmlError != null -> "Outer HTML error: ${state.outerHtmlError.message}"
            state.outerHtml != null -> state.outerHtml
            else -> "(not loaded — select a node)"
        }
    }

    /** Renders search result count + navigation buttons. */
    private fun renderSearch(state: DomSearchState) {
        binding.elementsSearchPrevButton.visibility = if (state.resultCount > 1) android.view.View.VISIBLE else android.view.View.GONE
        binding.elementsSearchNextButton.visibility = if (state.resultCount > 1) android.view.View.VISIBLE else android.view.View.GONE
        binding.elementsSearchStatus.text = when {
            state.loading -> "Searching for '${state.query}'…"
            state.hasError -> "Search error: ${state.error?.message}"
            state.resultCount == 0 -> "No matches for '${state.query}'"
            state.resultCount == 1 -> "1 match for '${state.query}'"
            else -> "${state.activeIndex + 1}/${state.resultCount} matches for '${state.query}'"
        }
    }

    /** Renders the mutation status (submitting / success / failure). */
    private fun renderMutation(state: DomMutationState) {
        binding.elementsMutationStatus.text = when (state.phase) {
            DomMutationPhase.SUBMITTING -> "Mutating… (${state.operation?.name ?: "operation"})"
            DomMutationPhase.SUCCESS -> "Mutation succeeded (${state.operation?.name ?: "operation"})"
            DomMutationPhase.FAILURE -> "Mutation failed: ${state.error?.message}"
            DomMutationPhase.IDLE -> ""
        }
        // Re-evaluate control availability once submission completes.
        renderDetail(inspector.inspectionState)
    }

    /** Renders the CSS Styles panel from the current CSS inspection state. */
    private fun renderPseudoControls(state: CssInspectionState) {
        val pseudo = state.pseudoState
        val cssCap = state.capability
        val pending = pseudo.isForcing
        val disabled = cssCap == CssCapabilityStatus.UNSUPPORTED ||
            cssCap == CssCapabilityStatus.FAILED ||
            pseudo.capability == CssCapabilityStatus.UNSUPPORTED ||
            pending ||
            inspector.selectedNode() == null

        updatingPseudoUi = true
        binding.elementsPseudoHover.isChecked = pseudo.isForced(CssPseudoClass.HOVER)
        binding.elementsPseudoActive.isChecked = pseudo.isForced(CssPseudoClass.ACTIVE)
        binding.elementsPseudoFocus.isChecked = pseudo.isForced(CssPseudoClass.FOCUS)
        binding.elementsPseudoHover.isEnabled = !disabled
        binding.elementsPseudoActive.isEnabled = !disabled
        binding.elementsPseudoFocus.isEnabled = !disabled
        updatingPseudoUi = false

        binding.elementsPseudoStatus.text = when {
            inspector.selectedNode() == null -> ""
            pseudo.capability == CssCapabilityStatus.UNSUPPORTED || cssCap == CssCapabilityStatus.UNSUPPORTED ->
                "Pseudo-states unsupported"
            pseudo.phase == CssPseudoStatePhase.APPLYING -> "Applying pseudo-state…"
            pseudo.phase == CssPseudoStatePhase.REMOVING -> "Removing pseudo-state…"
            pseudo.phase == CssPseudoStatePhase.FAILED -> "Pseudo-state error: ${pseudo.error?.message}"
            pseudo.phase == CssPseudoStatePhase.UNSUPPORTED -> "Pseudo-states unsupported"
            else -> ""
        }
    }

    /** Renders the layout-overlay controls from the current inspection state. */
    private fun renderOverlayControls(state: CssInspectionState) {
        val overlay = state.overlayState
        val cssCap = state.capability
        val pending = overlay.isRequested
        val unsupported = overlay.isUnsupported ||
            overlay.capability == CssCapabilityStatus.UNSUPPORTED ||
            cssCap == CssCapabilityStatus.UNSUPPORTED ||
            cssCap == CssCapabilityStatus.FAILED
        val enabled = inspector.selectedNode() != null && !pending && !unsupported

        fun isOn(type: CssOverlayType): Boolean =
            overlay.type == type && overlay.isActive

        updatingOverlayUi = true
        binding.elementsOverlayGrid.isChecked = isOn(CssOverlayType.GRID)
        binding.elementsOverlayFlex.isChecked = isOn(CssOverlayType.FLEX)
        binding.elementsOverlayContainer.isChecked = isOn(CssOverlayType.CONTAINER_QUERY)
        binding.elementsOverlayGrid.isEnabled = enabled
        binding.elementsOverlayFlex.isEnabled = enabled
        binding.elementsOverlayContainer.isEnabled = enabled
        updatingOverlayUi = false

        binding.elementsOverlayStatus.text = when {
            inspector.selectedNode() == null -> ""
            unsupported -> "Layout overlays unsupported for this target."
            overlay.phase == CssOverlayPhase.REQUESTED -> "Applying ${overlay.type?.displayLabel ?: ""} overlay…"
            overlay.phase == CssOverlayPhase.FAILED -> "Overlay error: ${overlay.error?.message}"
            overlay.phase == CssOverlayPhase.ACTIVE -> "${overlay.type?.displayLabel ?: ""} overlay active"
            else -> ""
        }
    }

    /** Renders the CSS Styles panel from the current CSS inspection state. */
    private fun renderCss(state: CssInspectionState) {
        val node = inspector.selectedNode()
        renderPseudoControls(state)
        renderOverlayControls(state)
        val rows = mutableListOf<CssStyleAdapter.Row>()
        val status = StringBuilder()
        val filter = computedStyleFilter.trim().lowercase()

        fun addRules(rules: List<com.coppermux.cutools.core.css.CssRule>) {
            rules.forEach { rule ->
                val sheet = state.stylesheets.firstOrNull { it.styleSheetId == rule.styleSheetId }
                rows.add(CssStyleAdapter.Row.Rule(rule, sheet))
                if (rule.declarations.isEmpty()) {
                    rows.add(CssStyleAdapter.Row.Text("(no declarations)"))
                } else {
                    rule.declarations.forEach { decl ->
                        rows.add(CssStyleAdapter.Row.Declaration(decl, indent = true))
                    }
                }
            }
        }

        fun addComputedSection() {
            when (state.computedStylePhase) {
                CssInspectionPhase.IDLE -> {}
                CssInspectionPhase.LOADING -> rows.add(CssStyleAdapter.Row.Text("Loading computed styles…"))
                CssInspectionPhase.FAILED -> rows.add(
                    CssStyleAdapter.Row.Text("Computed style error: ${state.computedStyleError?.message}")
                )
                CssInspectionPhase.UNSUPPORTED -> rows.add(
                    CssStyleAdapter.Row.Text("Computed styles are unsupported for this target.")
                )
                CssInspectionPhase.EMPTY -> rows.add(CssStyleAdapter.Row.Section("Computed styles (0)"))
                CssInspectionPhase.LOADED -> {
                    val computed = state.computedStyle.orEmpty()
                    if (computed.isEmpty()) {
                        rows.add(CssStyleAdapter.Row.Section("Computed styles (0)"))
                    } else {
                        rows.add(CssStyleAdapter.Row.Section("Computed styles (${computed.size})"))
                        computed.forEach { rows.add(CssStyleAdapter.Row.Computed(it)) }
                    }
                }
            }
        }

        fun addCascadeLayers() {
            when (state.layerPhase) {
                CssInspectionPhase.LOADED -> {
                    state.rootLayer?.let { root ->
                        rows.add(CssStyleAdapter.Row.Section("Cascade layers"))
                        rows.add(CssStyleAdapter.Row.BoxLine("root", root.name))
                        root.subLayers.forEach { rows.add(CssStyleAdapter.Row.BoxLine("layer", "${it.name} (order ${it.order})")) }
                    }
                }
                CssInspectionPhase.EMPTY -> rows.add(CssStyleAdapter.Row.Section("Cascade layers: none"))
                CssInspectionPhase.FAILED -> rows.add(
                    CssStyleAdapter.Row.Text("Cascade layers error: ${state.layerError?.message}")
                )
                CssInspectionPhase.UNSUPPORTED -> rows.add(
                    CssStyleAdapter.Row.Text("Cascade layers not supported by this protocol version.")
                )
                else -> {}
            }
        }

        fun addBoxModel() {
            when (state.boxModelPhase) {
                CssInspectionPhase.LOADED -> {
                    val box = state.boxModel
                    if (box != null && !box.isEmpty) {
                        rows.add(CssStyleAdapter.Row.Section("Box model"))
                        box.width?.let { rows.add(CssStyleAdapter.Row.BoxLine("width", "${it}px")) }
                        box.height?.let { rows.add(CssStyleAdapter.Row.BoxLine("height", "${it}px")) }
                        rows.add(CssStyleAdapter.Row.BoxLine("content", box.content?.summary ?: "n/a"))
                        rows.add(CssStyleAdapter.Row.BoxLine("padding", box.padding?.summary ?: "n/a"))
                        rows.add(CssStyleAdapter.Row.BoxLine("border", box.border?.summary ?: "n/a"))
                        rows.add(CssStyleAdapter.Row.BoxLine("margin", box.margin?.summary ?: "n/a"))
                    } else {
                        rows.add(CssStyleAdapter.Row.Text("Box model: none"))
                    }
                }
                CssInspectionPhase.LOADING -> rows.add(CssStyleAdapter.Row.Text("Loading box model…"))
                CssInspectionPhase.FAILED -> rows.add(
                    CssStyleAdapter.Row.Text("Box model error: ${state.boxModelError?.message}")
                )
                CssInspectionPhase.EMPTY -> rows.add(CssStyleAdapter.Row.Text("Box model: none"))
                CssInspectionPhase.UNSUPPORTED -> rows.add(
                    CssStyleAdapter.Row.Text("Box model not supported by this protocol version.")
                )
                else -> {}
            }
        }

        fun addLayoutSection() {
            when (state.layoutPhase) {
                CssInspectionPhase.LOADED -> {
                    val info = state.layoutInfo ?: return
                    rows.add(CssStyleAdapter.Row.Section("Layout"))
                    if (info.contentQuads.isEmpty()) {
                        rows.add(CssStyleAdapter.Row.BoxLine("content quads", "none"))
                    } else {
                        rows.add(CssStyleAdapter.Row.BoxLine("content quads", info.contentQuads.size.toString()))
                        info.contentQuads.forEachIndexed { i, quad ->
                            rows.add(CssStyleAdapter.Row.BoxLine("  quad ${i + 1}", quad.summary))
                        }
                    }
                    rows.add(
                        CssStyleAdapter.Row.BoxLine(
                            "relayout boundary",
                            info.relayoutBoundaryNodeId?.let { "node $it" } ?: "n/a",
                        )
                    )
                }
                CssInspectionPhase.LOADING -> rows.add(CssStyleAdapter.Row.Text("Loading layout…"))
                CssInspectionPhase.EMPTY -> rows.add(CssStyleAdapter.Row.Text("Layout: no geometry"))
                CssInspectionPhase.FAILED -> rows.add(
                    CssStyleAdapter.Row.Text("Layout error: ${state.layoutError?.message}")
                )
                CssInspectionPhase.UNSUPPORTED -> rows.add(
                    CssStyleAdapter.Row.Text("Layout inspection unsupported by this protocol version.")
                )
                else -> {}
            }
        }

        fun addGridNotice() {
            // Structured Grid data is NOT exposed by this CDP version (only Overlay
            // highlight APIs). Never fabricate tracks/areas/coordinates.
            if (state.gridCapability != CssCapabilityStatus.SUPPORTED) {
                rows.add(
                    CssStyleAdapter.Row.Text(
                        "CSS Grid: structured inspection unsupported (grid layout data is not exposed by this protocol version)."
                    )
                )
            }
        }

        fun addFlexNotice() {
            if (state.flexCapability != CssCapabilityStatus.SUPPORTED) {
                rows.add(
                    CssStyleAdapter.Row.Text(
                        "Flexbox: structured inspection unsupported (flex layout data is not exposed by this protocol version)."
                    )
                )
            }
        }

        fun addContainerSection() {
            when (state.containerPhase) {
                CssInspectionPhase.LOADED -> {
                    val info = state.containerInfo ?: return
                    rows.add(CssStyleAdapter.Row.Section("Container queries"))
                    info.containerNodeId?.let {
                        rows.add(CssStyleAdapter.Row.BoxLine("container", "node $it"))
                    } ?: rows.add(CssStyleAdapter.Row.BoxLine("container", "none"))
                    if (info.queryingDescendants.isEmpty()) {
                        rows.add(CssStyleAdapter.Row.BoxLine("querying descendants", "none"))
                    } else {
                        rows.add(
                            CssStyleAdapter.Row.BoxLine("querying descendants", "node " + info.queryingDescendants.sorted().joinToString(", "))
                        )
                    }
                }
                CssInspectionPhase.LOADING -> rows.add(CssStyleAdapter.Row.Text("Loading container queries…"))
                CssInspectionPhase.FAILED -> rows.add(
                    CssStyleAdapter.Row.Text("Container query error: ${state.containerError?.message}")
                )
                CssInspectionPhase.EMPTY -> rows.add(CssStyleAdapter.Row.Section("Container queries: none"))
                CssInspectionPhase.UNSUPPORTED -> rows.add(
                    CssStyleAdapter.Row.Text("Container queries not supported by this protocol version.")
                )
                else -> {}
            }
        }

        // When a computed-style filter is active, show ONLY the matching computed
        // properties (filtering operates locally over already-retrieved state).
        if (filter.isNotEmpty()) {
            val computed = state.computedStyle.orEmpty()
            val matches = computed.filter { it.name.lowercase().contains(filter) || it.value.lowercase().contains(filter) }
            rows.add(CssStyleAdapter.Row.Section("Computed styles"))
            if (matches.isEmpty()) {
                rows.add(CssStyleAdapter.Row.Text("No computed styles match \"${computedStyleFilter.trim()}\"."))
            } else {
                matches.forEach { rows.add(CssStyleAdapter.Row.Computed(it)) }
            }
            status.append("${matches.size} matches")
            binding.elementsCssStatus.text = status.toString()
            cssAdapter.submit(rows)
            return
        }

        when (state.phase) {
            CssInspectionPhase.IDLE -> {
                rows.add(CssStyleAdapter.Row.Text(
                    if (node == null) "Select an element to inspect its styles." else "Styles not loaded."
                ))
                status.append("Idle")
            }
            CssInspectionPhase.LOADING -> {
                rows.add(CssStyleAdapter.Row.Text("Loading styles…"))
                status.append("Loading…")
            }
            CssInspectionPhase.FAILED -> {
                rows.add(CssStyleAdapter.Row.Text("Styles error: ${state.error?.message}"))
                status.append("Failed")
            }
            CssInspectionPhase.UNSUPPORTED -> {
                rows.add(CssStyleAdapter.Row.Text(
                    "CSS inspection unavailable${state.error?.message?.let { ": $it" } ?: "."}"
                ))
                status.append("Unsupported")
            }
            CssInspectionPhase.EMPTY -> {
                rows.add(CssStyleAdapter.Row.Text("No styles found for this node."))
                status.append("No styles")
            }
            CssInspectionPhase.LOADED -> {
                val styles = state.styles
                if (styles != null) {
                    if (styles.inlineDeclarations.isNotEmpty()) {
                        rows.add(CssStyleAdapter.Row.Section("Inline style"))
                        styles.inlineDeclarations.forEach { rows.add(CssStyleAdapter.Row.Declaration(it, indent = false)) }
                    }
                    if (styles.attributesDeclarations.isNotEmpty()) {
                        rows.add(CssStyleAdapter.Row.Section("Attributes style"))
                        styles.attributesDeclarations.forEach { rows.add(CssStyleAdapter.Row.Declaration(it, indent = false)) }
                    }
                    rows.add(CssStyleAdapter.Row.Section("Matched CSS rules (${styles.matchedRules.size})"))
                    addRules(styles.matchedRules)
                    if (styles.matchedRules.isEmpty() &&
                        styles.inlineDeclarations.isEmpty() &&
                        styles.attributesDeclarations.isEmpty()
                    ) {
                        rows.add(CssStyleAdapter.Row.Text("Only inherited styles apply to this node."))
                    }
                    styles.inherited.forEachIndexed { index, entry ->
                        rows.add(CssStyleAdapter.Row.Section("Inherited from ancestor ${index + 1}"))
                        addRules(entry.matchedRules)
                        entry.inlineDeclarations.forEach { rows.add(CssStyleAdapter.Row.Declaration(it, indent = true)) }
                    }
                }
                status.append("${state.matchedRuleCount} matched rules")
            }
        }
        addCascadeLayers()
        addLayoutSection()
        addGridNotice()
        addFlexNotice()
        addContainerSection()
        addComputedSection()
        addBoxModel()

        val cap = when (state.capability) {
            CssCapabilityStatus.SUPPORTED -> ""
            CssCapabilityStatus.UNKNOWN -> "CSS: probing…"
            CssCapabilityStatus.UNSUPPORTED -> "CSS unsupported"
            CssCapabilityStatus.FAILED -> "CSS failed"
        }
        binding.elementsCssStatus.text = listOf(status.toString(), cap).filter { it.isNotEmpty() }.joinToString("  ·  ")
        cssAdapter.submit(rows)
    }

    override fun onDestroy() {
        inspector.removeListener(this)
        cssInspector.removeListener(this)
        super.onDestroy()
    }
}
