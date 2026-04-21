package androidx.compose.ui.inspection.jvm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.awt.ComposePanel
import java.util.Collections
import java.util.WeakHashMap

internal object ComposePanelInspectionTables {
    private val key = Any()

    @Suppress("UNCHECKED_CAST")
    fun install(panel: ComposePanel): MutableSet<CompositionData> {
        val existing = panel.getClientProperty(key) as? MutableSet<CompositionData>
        if (existing != null) {
            log(
                "reusing store panel=${panel.panelId()} size=${existing.size} " +
                    "tables=${existing.formatIds()}"
            )
            return existing
        }

        val store = Collections.newSetFromMap(WeakHashMap<CompositionData, Boolean>())
        panel.putClientProperty(key, store)
        log("created store panel=${panel.panelId()}")
        return store
    }

    @Suppress("UNCHECKED_CAST")
    fun get(panel: ComposePanel): Set<CompositionData> {
        val store = panel.getClientProperty(key) as? Set<CompositionData> ?: emptySet()
        log(
            "read store panel=${panel.panelId()} size=${store.size} " +
                "tables=${store.formatIds()}"
        )
        return store
    }

    fun logCapture(panel: ComposePanel, compositionData: CompositionData, added: Boolean, size: Int) {
        log(
            "captured composition panel=${panel.panelId()} " +
                "table=${compositionData.debugId()} added=$added storeSize=$size"
        )
    }

    private fun log(message: String) {
        println("[ui-inspection-jvm] $message")
    }
}

@OptIn(InternalComposeApi::class)
internal fun ComposePanel.setInspectableContent(content: @Composable () -> Unit) {
    val store = ComposePanelInspectionTables.install(this)

    setContent {
        currentComposer.collectParameterInformation()
        val compositionData = currentComposer.compositionData
        val added = store.add(compositionData)
        ComposePanelInspectionTables.logCapture(this@setInspectableContent, compositionData, added, store.size)
        CompositionLocalProvider(LocalInspectionTables provides store) { content() }
    }
}

private fun ComposePanel.panelId(): Long = System.identityHashCode(this).toLong()

private fun CompositionData.debugId(): String = "0x" + System.identityHashCode(this).toString(16)

private fun Set<CompositionData>.formatIds(): String =
    if (isEmpty()) {
        "[]"
    } else {
        take(8).joinToString(prefix = "[", postfix = if (size > 8) ", ...]" else "]") {
            it.debugId()
        }
    }
