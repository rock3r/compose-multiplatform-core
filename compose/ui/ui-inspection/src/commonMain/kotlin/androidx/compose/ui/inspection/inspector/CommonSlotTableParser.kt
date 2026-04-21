package androidx.compose.ui.inspection.inspector

import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionGroup
import androidx.compose.runtime.tooling.CompositionInstance
import androidx.compose.runtime.tooling.findCompositionInstance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.inspection.util.NO_ANCHOR_ID
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.unit.IntRect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Slot-table parser shared between Android and JVM prototypes.
 *
 * This parser reconstructs each composition group tree and stitches child sub-compositions into
 * parent groups using [CompositionInstance.findContextGroup].
 */
internal class CommonSlotTableParser {
    private var nextId = -1L

    fun parse(compositions: Iterable<CompositionData>): List<InspectorNode> {
        nextId = -1L

        val compositionSet = compositions.toSet()
        val hierarchy = mutableMapOf<CompositionInstance, MutableList<CompositionInstance>>()
        val rootInstances = mutableSetOf<CompositionInstance>()
        val instanceByData = mutableMapOf<CompositionData, CompositionInstance>()

        compositionSet.forEach { composition ->
            composition.findCompositionInstance()?.let { instance ->
                instanceByData[instance.data] = instance
                buildCompositionParentHierarchy(instance, hierarchy, rootInstances)
            }
        }

        val parsedByInstance = mutableMapOf<CompositionInstance, List<InspectorNode>>()
        val stitchedRoots = rootInstances.flatMap { parseInstance(it, hierarchy, parsedByInstance) }

        // Fallback for compositions that do not expose CompositionInstance metadata.
        val unstitchedRoots =
            compositionSet
                .filter { composition -> composition !in instanceByData }
                .flatMap { composition -> parseComposition(composition, emptyMap()) }

        return stitchedRoots + unstitchedRoots
    }

    private fun parseInstance(
        instance: CompositionInstance,
        hierarchy: Map<CompositionInstance, List<CompositionInstance>>,
        parsedByInstance: MutableMap<CompositionInstance, List<InspectorNode>>,
    ): List<InspectorNode> {
        parsedByInstance[instance]?.let { return it }

        val childInstances = hierarchy[instance].orEmpty()
        val stitchedChildrenByGroup = mutableMapOf<CompositionGroup, MutableList<InspectorNode>>()

        childInstances.forEach { child ->
            val childNodes = parseInstance(child, hierarchy, parsedByInstance)
            val contextGroup = child.findContextGroup()
            if (contextGroup != null && childNodes.isNotEmpty()) {
                stitchedChildrenByGroup.getOrPut(contextGroup) { mutableListOf() }.addAll(childNodes)
            }
        }

        val parsed = parseComposition(instance.data, stitchedChildrenByGroup)
        parsedByInstance[instance] = parsed
        return parsed
    }

    private fun parseComposition(
        composition: CompositionData,
        stitchedChildrenByGroup: Map<CompositionGroup, List<InspectorNode>>,
    ): List<InspectorNode> {
        return composition.compositionGroups.map { group ->
            parseGroup(group, stitchedChildrenByGroup)
        }
    }

    private fun parseGroup(
        group: CompositionGroup,
        stitchedChildrenByGroup: Map<CompositionGroup, List<InspectorNode>>,
    ): InspectorNode {
        val groupChildren = group.compositionGroups.map { child -> parseGroup(child, stitchedChildrenByGroup) }
        val stitchedChildren = stitchedChildrenByGroup[group].orEmpty()
        val children = groupChildren + stitchedChildren

        val key = group.key as? Int ?: 0
        val name = extractName(group)
        val box = extractBox(group, children)

        return InspectorNode(
            id = nextGeneratedId(),
            key = key,
            anchorId = NO_ANCHOR_ID,
            name = name,
            fileName = "",
            packageHash = -1,
            lineNumber = 0,
            offset = 0,
            box = box,
            bounds = null,
            flags = 0,
            parameters = emptyList(),
            viewId = UNDEFINED_ID,
            mergedSemantics = emptyList(),
            unmergedSemantics = emptyList(),
            children = children,
        )
    }

    private fun buildCompositionParentHierarchy(
        instance: CompositionInstance,
        hierarchy: MutableMap<CompositionInstance, MutableList<CompositionInstance>>,
        rootInstances: MutableSet<CompositionInstance>,
    ) {
        var current = instance
        var parent = current.parent
        while (parent != null) {
            val children = hierarchy.getOrPut(parent) { mutableListOf() }
            if (children.contains(current)) {
                return
            }
            children.add(current)
            current = parent
            parent = current.parent
        }
        rootInstances.add(current)
    }

    private fun extractBox(group: CompositionGroup, children: List<InspectorNode>): IntRect {
        val childrenBox = unionChildrenBoxes(children)
        val layoutBox = extractLayoutInfoBox(group.node as? LayoutInfo)

        return when {
            layoutBox != emptyBox -> layoutBox
            childrenBox != emptyBox -> childrenBox
            else -> emptyBox
        }
    }

    private fun extractLayoutInfoBox(layoutInfo: LayoutInfo?): IntRect {
        if (layoutInfo == null || !layoutInfo.isAttached || !layoutInfo.isPlaced) {
            return emptyBox
        }

        return runCatching {
            val topLeft = layoutInfo.coordinates.localToRoot(Offset.Zero)
            val left = topLeft.x.roundToInt()
            val top = topLeft.y.roundToInt()
            val right = left + layoutInfo.width
            val bottom = top + layoutInfo.height
            IntRect(left, top, right, bottom)
        }.getOrDefault(emptyBox)
    }

    private fun unionChildrenBoxes(children: List<InspectorNode>): IntRect {
        var result = emptyBox
        children.forEach { child ->
            val childBox = child.box
            if (childBox != emptyBox) {
                result = if (result == emptyBox) childBox else result.union(childBox)
            }
        }
        return result
    }

    private fun IntRect.union(other: IntRect): IntRect {
        if (this == emptyBox) return other
        if (other == emptyBox) return this

        return IntRect(
            left = min(left, other.left),
            top = min(top, other.top),
            right = max(right, other.right),
            bottom = max(bottom, other.bottom),
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
