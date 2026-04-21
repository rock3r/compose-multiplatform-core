package androidx.compose.ui.inspection.harness

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.inspection.JvmComposeMultiplatformLayoutInspector
import androidx.compose.ui.inspection.inspector.InspectorNode
import androidx.compose.ui.inspection.jvm.setInspectableContent
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
        val generation = mutableIntStateOf(0)
        val inspector = JvmComposeMultiplatformLayoutInspector()

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
                            val snapshots = layoutInspector.layoutInfos.value
                            val names =
                                snapshots.values
                                    .flatMap { roots -> roots.flatMap { it.flattenedNames() } }
                                    .take(12)
                            println(
                                "[slot-table-snapshot] panels=${snapshots.size} " +
                                    "nodes=${snapshots.values.sumOf { it.size }} " +
                                    "sample=$names"
                            )
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
    ProbeBranch(seed = "root-$label", depth = 0)
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
        BasicText("Stupid agent")
    } else {
        BasicText("Idiot agent")
    }
}

private fun InspectorNode.flattenedNames(): List<String> {
    return listOf(name) + children.flatMap { child -> child.flattenedNames() }
}