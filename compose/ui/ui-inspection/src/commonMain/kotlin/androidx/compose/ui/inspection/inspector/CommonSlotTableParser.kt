package androidx.compose.ui.inspection.inspector

import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionGroup
import androidx.compose.ui.inspection.util.NO_ANCHOR_ID

/**
 * Minimal slot-table parser shared between Android and JVM prototypes.
 *
 * This parser intentionally keeps the output lightweight for now: it reconstructs a tree structure
 * and basic node identity/name information from [CompositionGroup]s.
 */
internal class CommonSlotTableParser {
    private var nextId = -1L

    fun parse(compositions: Iterable<CompositionData>): List<InspectorNode> {
        nextId = -1L
        return compositions.flatMap { composition ->
            composition.compositionGroups.map { group -> parseGroup(group) }
        }
    }

    private fun parseGroup(group: CompositionGroup): InspectorNode {
        val children = group.compositionGroups.map { child -> parseGroup(child) }

        val key = group.key as? Int ?: 0
        val name = extractName(group)

        return InspectorNode(
            id = nextGeneratedId(),
            key = key,
            anchorId = NO_ANCHOR_ID,
            name = name,
            fileName = "",
            packageHash = -1,
            lineNumber = 0,
            offset = 0,
            box = emptyBox,
            bounds = null,
            flags = 0,
            parameters = emptyList(),
            viewId = UNDEFINED_ID,
            mergedSemantics = emptyList(),
            unmergedSemantics = emptyList(),
            children = children,
        )
    }

    private fun nextGeneratedId(): Long = nextId--

    private fun extractName(group: CompositionGroup): String {
        val sourceInfo = group.sourceInfo
        if (sourceInfo != null) {
            val start = sourceInfo.indexOf("C(")
            val end = if (start >= 0) sourceInfo.indexOf(')', start + 2) else -1
            if (start >= 0 && end > start + 2) {
                return sourceInfo.substring(start + 2, end)
            }
        }

        return group.key.toString()
    }
}
