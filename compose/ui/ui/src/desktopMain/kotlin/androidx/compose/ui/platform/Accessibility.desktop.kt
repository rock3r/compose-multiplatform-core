/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.platform

import androidx.compose.ui.node.LayoutNode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextRange
import javax.accessibility.Accessible
import javax.accessibility.AccessibleComponent
import javax.accessibility.AccessibleContext.*
import javax.accessibility.AccessibleState
import kotlinx.coroutines.delay

/**
 * This class provides a mapping from compose tree of [owner] to tree of [ComposeAccessible],
 * so that each [SemanticsNode] has [ComposeAccessible].
 *
 * @param onFocusReceived a callback that will be called with [ComposeAccessible]
 * when a [SemanticsNode] from [owner] received a focus
 *
 * @see ComposeSceneAccessible
 * @see ComposeAccessible
 */
internal class AccessibilityControllerImpl(
    private val owner: SemanticsOwner,
    val desktopComponent: PlatformComponent,
    private val onFocusReceived: (ComposeAccessible) -> Unit
) : AccessibilityController {
    private var currentNodesInvalidated = true
    var _currentNodes: Map<Int, ComposeAccessible> = emptyMap()
    val currentNodes: Map<Int, ComposeAccessible>
        get() {
            if (currentNodesInvalidated) {
                syncNodes()
            }
            return _currentNodes
        }

    @Suppress("UNUSED_PARAMETER")
    private fun onNodeAdded(accessible: ComposeAccessible) {}

    private fun onNodeRemoved(accessible: ComposeAccessible) {
        accessible.removed = true
    }

    private fun onNodeChanged(
        component: ComposeAccessible,
        previousSemanticsNode: SemanticsNode,
        newSemanticsNode: SemanticsNode
    ) {
        for (entry in newSemanticsNode.config) {
            val prev = previousSemanticsNode.config.getOrNull(entry.key)
            if (entry.value != prev) {
                when (entry.key) {
                    SemanticsProperties.Text -> {
                        component.composeAccessibleContext.firePropertyChange(
                            ACCESSIBLE_TEXT_PROPERTY,
                            prev, entry.value
                        )
                    }

                    SemanticsProperties.EditableText -> {
                        component.composeAccessibleContext.firePropertyChange(
                            ACCESSIBLE_TEXT_PROPERTY,
                            prev, entry.value
                        )
                    }

                    SemanticsProperties.TextSelectionRange -> {
                        component.composeAccessibleContext.firePropertyChange(
                            ACCESSIBLE_CARET_PROPERTY,
                            prev, (entry.value as TextRange).start
                        )
                    }

                    SemanticsProperties.Focused ->
                        if (entry.value as Boolean) {
                            component.composeAccessibleContext.firePropertyChange(
                                ACCESSIBLE_STATE_PROPERTY,
                                null, AccessibleState.FOCUSED
                            )
                            onFocusReceived(component)
                        } else {
                            component.composeAccessibleContext.firePropertyChange(
                                ACCESSIBLE_STATE_PROPERTY,
                                AccessibleState.FOCUSED, null
                            )
                        }

                    SemanticsProperties.ToggleableState -> {
                        when (entry.value as ToggleableState) {
                            ToggleableState.On ->
                                component.composeAccessibleContext.firePropertyChange(
                                    ACCESSIBLE_STATE_PROPERTY,
                                    null, AccessibleState.CHECKED
                                )

                            ToggleableState.Off, ToggleableState.Indeterminate ->
                                component.composeAccessibleContext.firePropertyChange(
                                    ACCESSIBLE_STATE_PROPERTY,
                                    AccessibleState.CHECKED, null
                                )
                        }
                    }

                    SemanticsProperties.ProgressBarRangeInfo -> {
                        val value = entry.value as ProgressBarRangeInfo
                        component.composeAccessibleContext.firePropertyChange(
                            ACCESSIBLE_VALUE_PROPERTY,
                            prev,
                            value.current
                        )
                    }
                }
            }
        }
    }

    private object SyncLoopState {
        val maxIdleTimeMillis = 1000 * 60 * 5 // Stop syncing after 5 minutes of inactivity
        var lastAccessTimeMillis: Long = 0

        val shouldSync
            get() = System.currentTimeMillis() - lastAccessTimeMillis < maxIdleTimeMillis
    }

    /**
     * When called wakes up the sync loop, which may be stopped after
     * some period of inactivity
     */
    fun notifyIsInUse() {
        SyncLoopState.lastAccessTimeMillis = System.currentTimeMillis()
    }

    override suspend fun syncLoop() {
        while (true) {
            if (currentNodesInvalidated && SyncLoopState.shouldSync) {
                syncNodes()
            }
            delay(100)
        }
    }

    private fun syncNodes() {
        if (!rootSemanticNode.layoutNode.isPlaced) {
            return
        }

        val previous = _currentNodes
        val nodes = mutableMapOf<Int, ComposeAccessible>()
        fun findAllSemanticNodesRecursive(currentNode: SemanticsNode) {
            nodes[currentNode.id] = previous[currentNode.id]?.let {
                val prevSemanticsNode = it.semanticsNode
                it.semanticsNode = currentNode
                onNodeChanged(it, prevSemanticsNode, currentNode)
                it
            } ?: ComposeAccessible(currentNode, this).also {
                onNodeAdded(it)
            }

            // TODO fake nodes?
            // TODO find only visible nodes?

            val children = currentNode.replacedChildren
            for (i in children.size - 1 downTo 0) {
                findAllSemanticNodesRecursive(children[i])
            }
        }

        findAllSemanticNodesRecursive(rootSemanticNode)
        for ((id, prevNode) in previous.entries) {
            if (nodes[id] == null) {
                onNodeRemoved(prevNode)
            }
        }
        _currentNodes = nodes
        currentNodesInvalidated = false
    }

    override fun onLayoutChange(layoutNode: LayoutNode) {
        currentNodesInvalidated = true
    }

    override fun onSemanticsChange() {
        currentNodesInvalidated = true
    }

    val rootSemanticNode: SemanticsNode
        get() = owner.rootSemanticsNode

    val rootAccessible: ComposeAccessible
        get() = currentNodes[rootSemanticNode.id]!!
}

internal fun Accessible.print(level: Int = 0) {
    val context = accessibleContext
    val id = if (this is ComposeAccessible) {
        this.semanticsNode.id.toString()
    } else {
        "unknown"
    }
    val str = buildString {
        (1..level).forEach {
            append('\t')
        }
        append(
            "ID: $id Name: ${context.accessibleName} " +
                "Description: ${context.accessibleDescription} " +
                "Role: ${context.accessibleRole} " +
                "Bounds: ${(context as? AccessibleComponent)?.bounds}"
        )
    }
    println(str)

    (0 until context.accessibleChildrenCount).forEach { child ->
        context.getAccessibleChild(child).print(level + 1)
    }
}
