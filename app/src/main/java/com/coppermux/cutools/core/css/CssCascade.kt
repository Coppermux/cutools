package com.coppermux.cutools.core.css

/**
 * Marks declaration provenance / cascade status from the data Chrome already
 * exposes in `CSS.getMatchedStylesForNode`:
 *
 * - `inherited` section → [CssDeclarationStatus.INHERITED]
 * - `inlineStyle` → [CssDeclarationStatus.INLINE]
 * - rules with `origin: user-agent` → [CssDeclarationStatus.USER_AGENT]
 * - remaining matches → [CssDeclarationStatus.ACTIVE] / [CssDeclarationStatus.OVERRIDDEN]
 *   derived from Chrome's cascade order (matched rule order, rule origin,
 *   `!important`, inline style).
 *
 * Chrome does not emit an explicit "active vs overridden" marker; the ranking
 * here is a faithful interpretation of the precedence information Chrome DOES
 * provide. It is a display/derivation only — the authoritative resolved outcome
 * remains `CSS.getComputedStyleForNode`. CuTools never reconstructs full browser
 * CSS behavior locally.
 */
object CssCascade {

    enum class Scope { ATTRIBUTES, MATCHED, INLINE, INHERITED }

    private data class Candidate(
        val name: String,
        val precedence: Int,
        val sequence: Int,
        val scope: Scope,
        val userAgent: Boolean,
    )

    /**
     * Precedence rank (higher wins) derived from data Chrome provides, following
     * the CSS cascade: user-agent < author normal < inline normal <
     * author !important < inline !important.
     */
    private fun precedence(scope: Scope, important: Boolean, userAgent: Boolean): Int = when {
        scope == Scope.INLINE && important -> 4
        important -> 3
        scope == Scope.INLINE -> 2
        userAgent -> 0
        else -> 1
    }

    /** Returns a copy of [styles] with declaration statuses populated. */
    fun resolve(styles: CssNodeStyles): CssNodeStyles {
        val candidates = ArrayList<Candidate>()
        var sequence = 0

        fun add(name: String, precedence: Int, scope: Scope, userAgent: Boolean) {
            candidates += Candidate(name, precedence, sequence, scope, userAgent)
            sequence++
        }

        // Inherited declarations are tracked (for display) but never win the
        // per-property cascade for the node itself.
        styles.inherited.forEach { entry ->
            entry.matchedRules.forEach { rule ->
                val ua = rule.origin == CssStyleSheetOrigin.USER_AGENT
                rule.declarations.forEach { d -> add(d.name, Int.MIN_VALUE, Scope.INHERITED, ua) }
            }
            entry.inlineDeclarations.forEach { d -> add(d.name, Int.MIN_VALUE, Scope.INHERITED, false) }
        }
        styles.attributesDeclarations.forEach { d ->
            add(d.name, precedence(Scope.ATTRIBUTES, d.important, false), Scope.ATTRIBUTES, false)
        }
        styles.matchedRules.forEach { rule ->
            val ua = rule.origin == CssStyleSheetOrigin.USER_AGENT
            rule.declarations.forEach { d ->
                add(d.name, precedence(Scope.MATCHED, d.important, ua), Scope.MATCHED, ua)
            }
        }
        styles.inlineDeclarations.forEach { d ->
            add(d.name, precedence(Scope.INLINE, d.important, false), Scope.INLINE, false)
        }

        // Winner per property: highest precedence; later occurrence wins ties.
        val winner = HashMap<String, Candidate>()
        candidates.forEach { c ->
            if (c.scope == Scope.INHERITED) return@forEach
            val cur = winner[c.name]
            if (cur == null ||
                c.precedence > cur.precedence ||
                (c.precedence == cur.precedence && c.sequence > cur.sequence)
            ) {
                winner[c.name] = c
            }
        }

        fun statusOf(c: Candidate): CssDeclarationStatus = when {
            c.scope == Scope.INHERITED -> CssDeclarationStatus.INHERITED
            c.userAgent -> CssDeclarationStatus.USER_AGENT
            winner[c.name] == c -> if (c.scope == Scope.INLINE) CssDeclarationStatus.INLINE else CssDeclarationStatus.ACTIVE
            else -> CssDeclarationStatus.OVERRIDDEN
        }

        // Consume statuses in the SAME order the candidates were built.
        var idx = 0
        fun statusNext(): CssDeclarationStatus = statusOf(candidates[idx++])

        fun Statused(nodeStyles: CssNodeStyles): CssNodeStyles =
            nodeStyles.copy(
                inherited = nodeStyles.inherited.map { entry ->
                    entry.copy(
                        matchedRules = entry.matchedRules.map { rule ->
                            rule.copy(declarations = rule.declarations.map { it.copy(status = statusNext()) })
                        },
                        inlineDeclarations = entry.inlineDeclarations.map { it.copy(status = statusNext()) },
                    )
                },
                attributesDeclarations = nodeStyles.attributesDeclarations.map { it.copy(status = statusNext()) },
                matchedRules = nodeStyles.matchedRules.map { rule ->
                    rule.copy(declarations = rule.declarations.map { it.copy(status = statusNext()) })
                },
                inlineDeclarations = nodeStyles.inlineDeclarations.map { it.copy(status = statusNext()) },
            )

        return Statused(styles)
    }
}