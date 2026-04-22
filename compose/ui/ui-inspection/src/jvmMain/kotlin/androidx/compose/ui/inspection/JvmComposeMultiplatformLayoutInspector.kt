package androidx.compose.ui.inspection

import androidx.compose.ui.inspection.jvm.JvmGetComposablesCommandHandler
import androidx.compose.ui.inspection.proto.StringTable
import androidx.compose.ui.inspection.proto.toComposableNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableNode

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
                val stringTable = StringTable()
                snapshot.panelId to snapshot.nodes.map { node -> node.toComposableNode(stringTable) }
            }

        return AttachedJvmLayoutInspector(initialSnapshot)
    }

    private class AttachedJvmLayoutInspector(
        initialComposableNodes: Map<Long, List<ComposableNode>>
    ) : ComposeMultiplatformLayoutInspector.LayoutInspector {
        private val state = MutableStateFlow(initialComposableNodes)

        override val composableNodes: StateFlow<Map<Long, List<ComposableNode>>> =
            state.asStateFlow()

        override fun detach() {
            // No refresh loop yet, so there is nothing to tear down.
        }
    }
}

/** Create a JVM layout inspector. */
fun ComposeMultiplatformLayoutInspector.Companion.createLayoutInspector():
    ComposeMultiplatformLayoutInspector = JvmComposeMultiplatformLayoutInspector()