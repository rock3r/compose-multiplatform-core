package androidx.compose.ui.inspection

import androidx.compose.ui.inspection.inspector.InspectorNode
import androidx.compose.ui.inspection.jvm.JvmGetComposablesCommandHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JVM prototype implementation for [ComposeMultiplatformLayoutInspector].
 *
 * It captures a single snapshot at attach-time and exposes it via [StateFlow].
 */
internal class JvmComposeMultiplatformLayoutInspector(
    private val commandHandler: JvmGetComposablesCommandHandler = JvmGetComposablesCommandHandler()
) : ComposeMultiplatformLayoutInspector {

    override fun attachToCurrentProcess(): ComposeMultiplatformLayoutInspector.LayoutInspector {
        val initialSnapshot =
            commandHandler.handleGetComposablesCommand().associate { snapshot ->
                snapshot.panelId to snapshot.nodes
            }

        return AttachedJvmLayoutInspector(initialSnapshot)
    }

    private class AttachedJvmLayoutInspector(initialLayoutInfos: Map<Long, List<InspectorNode>>) :
        ComposeMultiplatformLayoutInspector.LayoutInspector {
        private val state = MutableStateFlow(initialLayoutInfos)

        override val layoutInfos: StateFlow<Map<Long, List<InspectorNode>>> = state.asStateFlow()

        override fun detach() {
            // No refresh loop yet, so there is nothing to tear down.
        }
    }
}

/** Create a JVM layout inspector. */
fun ComposeMultiplatformLayoutInspector.Companion.createLayoutInspector():
    ComposeMultiplatformLayoutInspector = JvmComposeMultiplatformLayoutInspector()