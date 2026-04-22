package androidx.compose.ui.inspection.harness

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.inspection.JvmComposeMultiplatformLayoutInspector
import androidx.compose.ui.inspection.jvm.JvmGetComposablesCommandHandler
import androidx.compose.ui.inspection.jvm.setInspectableContent
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableNode
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.debugInspectorInfo
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.compose.ui.semantics.SemanticsModifier
import androidx.compose.ui.unit.dp
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Small desktop harness for future slot table connection work. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    SwingUtilities.invokeLater {
        isDebugInspectorInfoEnabled = true

        val generation = mutableIntStateOf(0)
        val inspector = JvmComposeMultiplatformLayoutInspector()
        val rawHandler = JvmGetComposablesCommandHandler()

        val composePanel = ComposePanel().apply { preferredSize = Dimension(480, 320) }

        fun installContent() {
            composePanel.setInspectableContent {
                if (generation.value % 2 == 0) {
                    ProbeTree("even")
                } else {
                    ProbeTree("odd")
                }
            }
        }

        installContent()

        val controls =
            JPanel(BorderLayout()).apply {
                add(
                    JButton("Reset content").apply {
                        addActionListener {
                            generation.value += 1
                            installContent()
                        }
                    },
                    BorderLayout.WEST,
                )
                add(
                    JButton("Snapshot slot tables").apply {
                        addActionListener {
                            val layoutInspector = inspector.attachToCurrentProcess()
                            val snapshots = layoutInspector.composableNodes.value
                            println(
                                "[slot-table-snapshot] panels=${snapshots.size} " +
                                    "roots=${snapshots.values.sumOf { it.size }}"
                            )
                            println(
                                "[slot-table-snapshot] hint: look for SubcomposeProbe -> " +
                                    "SubcomposeHeader/SubcomposeBody"
                            )
                            snapshots.forEach { (panelId, roots) ->
                                println("panel=$panelId")
                                if (roots.isEmpty()) {
                                    println("  <empty>")
                                } else {
                                    roots.forEachIndexed { index, root ->
                                        root.printTree(prefix = "", isLast = index == roots.lastIndex)
                                    }
                                }
                            }

                            rawHandler.handleGetComposablesCommand().forEach { snapshot ->
                                val debugLines = collectModifierAndSemantics(snapshot.slotTables)
                                println(
                                    "[slot-table-debug] panel=${snapshot.panelId} " +
                                        "groupsWithInfo=${debugLines.size}"
                                )
                                debugLines.take(8).forEach { block ->
                                    block.lineSequence().forEach { line -> println("  $line") }
                                }
                            }

                            layoutInspector.detach()
                        }
                    },
                    BorderLayout.EAST,
                )
            }

        JFrame("ComposePanel Slot Table Harness").apply {
            defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            layout = BorderLayout()
            add(composePanel, BorderLayout.CENTER)
            add(controls, BorderLayout.SOUTH)
            pack()
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
}

@Composable
private fun ProbeTree(label: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ProbeLeaf("root-marker-$label")
        SubcomposeProbe(seed = label)
        ProbeBranch(seed = "root-$label", depth = 0)
    }
}

@Composable
private fun SubcomposeProbe(seed: String) {
    SubcomposeLayout { constraints ->
        val header =
            subcompose("header-$seed") {
                SubcomposeHeader(seed)
            }.map { measurable -> measurable.measure(constraints) }

        val body =
            subcompose("body-$seed") {
                SubcomposeBody(seed)
            }.map { measurable -> measurable.measure(constraints) }

        val placeables = header + body
        val width = placeables.maxOfOrNull { it.width } ?: 0
        val height = placeables.sumOf { it.height }
        layout(width, height) {
            var y = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(0, y)
                y += placeable.height
            }
        }
    }
}

@Composable
private fun SubcomposeHeader(seed: String) {
    ProbeLeaf("sub-header-$seed")
}

@Composable
private fun SubcomposeBody(seed: String) {
    ProbeLeaf("sub-body-a-$seed")
    ProbeLeaf("sub-body-b-$seed")
}

@Composable
private fun ProbeBranch(seed: String, depth: Int) {
    val branchMarker = "$seed:$depth"
    Column {
        ProbeLeaf("$branchMarker-a")
        ProbeLeaf("$branchMarker-b")
        if (depth < 3) {
            ProbeBranch(seed = "$seed-left", depth = depth + 1)
            ProbeBranch(seed = "$seed-right", depth = depth + 1)
        } else {
            ProbeLeaf("$branchMarker-terminal")
        }
    }
}

@Composable
private fun ProbeLeaf(seed: String) {
    if (seed.length % 2 == 0) {
        BasicText("Leaf even", Modifier.background(Color.Gray).harnessDebugTag(seed))
    } else {
        BasicText("Leaf odd", Modifier.border(1.dp, Color.Gray).harnessDebugTag(seed))
    }
}

private fun Modifier.harnessDebugTag(tag: String): Modifier =
    composed(
        inspectorInfo =
            debugInspectorInfo {
                name = "harnessDebugTag"
                properties["tag"] = tag
            }
    ) {
        this
    }

private fun ComposableNode.printTree(prefix: String, isLast: Boolean) {
    val branch = if (isLast) "└─" else "├─"
    val layoutBounds = bounds.layout
    println(
        "$prefix$branch<name#${name}> [id=$id bounds=[${layoutBounds.x},${layoutBounds.y} " +
            "${layoutBounds.w}x${layoutBounds.h}] children=${childrenCount}]"
    )
    val childPrefix = prefix + if (isLast) "  " else "│ "
    childrenList.forEachIndexed { index, child ->
        child.printTree(prefix = childPrefix, isLast = index == childrenList.lastIndex)
    }
}

private fun collectModifierAndSemantics(slotTables: Set<CompositionData>): List<String> {
    val result = mutableListOf<String>()
    slotTables.forEach { composition ->
        composition.compositionGroups.forEach { group ->
            collectModifierAndSemantics(group, result)
        }
    }
    return result
}

private fun collectModifierAndSemantics(group: CompositionGroup, result: MutableList<String>) {
    val layoutInfo = group.node as? LayoutInfo
    if (layoutInfo != null) {
        val modifierInfo = layoutInfo.getModifierInfo()
        val modifiers = modifierInfo.map { info -> describeModifierLines(info.modifier) }
        val semantics =
            modifierInfo.mapNotNull { info ->
                (info.modifier as? SemanticsModifier)?.semanticsConfiguration
            }
                .flatMap { configuration ->
                    configuration.map { entry -> "${entry.key.name}=${entry.value}" }
                }

        val name = extractComposableName(group)
        result +=
            buildString {
                append("$name semanticsId=${layoutInfo.semanticsId} ${layoutBoundsSummary(layoutInfo)}")
                append("\n    modifiers:")
                if (modifiers.isEmpty()) {
                    append(" <none>")
                } else {
                    modifiers.forEachIndexed { index, lines ->
                        append("\n      ${index + 1}. ${lines.firstOrNull() ?: "<modifier>"}")
                        lines.drop(1).forEach { detail -> append("\n         - $detail") }
                    }
                }

                append("\n    semantics:")
                if (semantics.isEmpty()) {
                    append(" <none>")
                } else {
                    semantics.forEach { semantic -> append("\n      - $semantic") }
                }
            }
    }

    group.compositionGroups.forEach { child -> collectModifierAndSemantics(child, result) }
}

private fun layoutBoundsSummary(layoutInfo: LayoutInfo): String {
    if (!layoutInfo.isAttached || !layoutInfo.isPlaced) {
        return "bounds=<unplaced> size=${layoutInfo.width}x${layoutInfo.height} " +
            "attached=${layoutInfo.isAttached} placed=${layoutInfo.isPlaced}"
    }

    return runCatching {
        val bounds = layoutInfo.coordinates.boundsInRoot()
        val left = bounds.left.toInt()
        val top = bounds.top.toInt()
        val right = bounds.right.toInt()
        val bottom = bounds.bottom.toInt()
        "bounds=[$left,$top - $right,$bottom] size=${layoutInfo.width}x${layoutInfo.height}"
    }
        .getOrElse { throwable ->
            "bounds=<unavailable:${throwable::class.simpleName}> size=${layoutInfo.width}x${layoutInfo.height}"
        }
}

private fun describeModifierLines(modifier: Modifier): List<String> {
    val inspectable = modifier as? InspectableValue
    val name = inspectable?.nameFallback ?: modifier::class.simpleName ?: "<anonymous-modifier>"
    if (inspectable == null) {
        return listOf(name)
    }

    return buildList {
        add(name)
        inspectable.valueOverride
            ?.takeUnless { it is InspectableValue }
            ?.let { value -> add("value=${formatInspectorValue(value)}") }
        inspectable.inspectableElements.forEach { element ->
            add("${element.name}=${formatInspectorValue(element.value)}")
        }
    }
}

private fun formatInspectorValue(value: Any?): String {
    return when (value) {
        null -> "null"
        is Function<*> -> "<lambda>"
        else -> {
            val text = value.toString()
            if (text.length > 120) text.take(117) + "..." else text
        }
    }
}

private fun extractComposableName(group: CompositionGroup): String {
    val sourceInfo = group.sourceInfo ?: return group.key.toString()
    val start = sourceInfo.indexOf("C(")
    val end = if (start >= 0) sourceInfo.indexOf(')', start + 2) else -1
    return if (start >= 0 && end > start + 2) {
        sourceInfo.substring(start + 2, end)
    } else {
        group.key.toString()
    }
}
