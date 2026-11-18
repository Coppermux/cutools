package com.coppermux.cutools.ui.network

/**
 * A labelled key/value row rendered by the detail inspector.
 *
 * Pure presentation value: the label is human-readable text and the value is
 * the formatted content of a structured field, never fabricated browser state.
 */
data class NameValue(
    val label: String,
    val value: String,
)