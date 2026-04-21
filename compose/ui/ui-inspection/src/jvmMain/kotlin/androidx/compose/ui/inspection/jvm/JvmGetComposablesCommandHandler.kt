package androidx.compose.ui.inspection.jvm

import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.inspection.inspector.CommonSlotTableParser
import androidx.compose.ui.inspection.inspector.InspectorNode
import java.awt.Component
import java.awt.Container
import java.awt.Window

internal data class JvmComposablePanelSnapshot(
    val panelId: Long,
    val slotTables: Set<CompositionData>,
    val nodes: List<InspectorNode>,
)

/**
 * Prototype JVM-side equivalent of `handleGetComposablesCommand` without transport concerns.
 */
internal class JvmGetComposablesCommandHandler(
    private val parser: CommonSlotTableParser = CommonSlotTableParser(),
) {
    fun handleGetComposablesCommand(rootViewId: Long = -1L): List<JvmComposablePanelSnapshot> {
        val panels = enumerateComposePanels()
        log("enumerated panels=${panels.size}")

        val snapshots =
            panels.map { panel ->
                val panelId = panel.uniquePanelId()
                val slotTables = ComposePanelInspectionTables.get(panel)
                val nodes = parser.parse(slotTables)
                log(
                    "snapshot panel=$panelId tables=${slotTables.size} " +
                        "roots=${nodes.size}"
                )
                JvmComposablePanelSnapshot(
                    panelId = panelId,
                    slotTables = slotTables,
                    nodes = nodes,
                )
            }

        val result =
            if (rootViewId == -1L) {
                snapshots
            } else {
                snapshots.filter { it.panelId == rootViewId }
            }
        log("result snapshots=${result.size} filter=$rootViewId")
        return result
    }

    private fun enumerateComposePanels(): List<ComposePanel> {
        val result = mutableListOf<ComposePanel>()
        Window.getWindows().forEach { window ->
            if (!window.isDisplayable) return@forEach
            collectComposePanels(window, result)
        }
        return result
    }

    private fun collectComposePanels(component: Component, result: MutableList<ComposePanel>) {
        if (component is ComposePanel) {
            result += component
        }
        if (component is Container) {
            component.components.forEach { child -> collectComposePanels(child, result) }
        }
    }

    private fun ComposePanel.uniquePanelId(): Long {
        return System.identityHashCode(this).toLong()
    }

    private fun log(message: String) {
        println("[ui-inspection-jvm] $message")
    }
}
