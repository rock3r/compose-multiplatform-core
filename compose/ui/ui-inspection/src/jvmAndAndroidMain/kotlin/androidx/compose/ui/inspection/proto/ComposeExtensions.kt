/*
 * Copyright 2021 The Android Open Source Project
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

@file:JvmName("ComposeProtoNodeExtensions")

package androidx.compose.ui.inspection.proto

import androidx.compose.ui.inspection.inspector.InspectorNode
import androidx.compose.ui.inspection.inspector.systemPackages
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Bounds
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableNode
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Quad
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Rect

internal data class RecomposeCount(val count: Int, val skips: Int)

/**
 * Convert an [InspectorNode] to protobuf [ComposableNode].
 *
 * If [reduceChildNesting] is enabled, a subtree with single-child nodes is collapsed as nested
 * children and marked with [ComposableNode.Flags.NESTED_SINGLE_CHILDREN].
 */
internal fun InspectorNode.toComposableNode(
    stringTable: StringTable,
    windowX: Int = 0,
    windowY: Int = 0,
    reduceChildNesting: Boolean = false,
    recomposeCountLookup: ((anchorId: Int) -> RecomposeCount?)? = null,
): ComposableNode =
    toNodeBuilder(
            stringTable = stringTable,
            windowX = windowX,
            windowY = windowY,
            reduceChildNesting = reduceChildNesting,
            recomposeCountLookup = recomposeCountLookup,
        )
        .build()

private fun InspectorNode.toNodeBuilder(
    stringTable: StringTable,
    windowX: Int,
    windowY: Int,
    reduceChildNesting: Boolean,
    recomposeCountLookup: ((anchorId: Int) -> RecomposeCount?)?,
): ComposableNode.Builder {
    val builder =
        toFlatNode(
            stringTable = stringTable,
            windowX = windowX,
            windowY = windowY,
            recomposeCountLookup = recomposeCountLookup,
        )

    if (!reduceChildNesting || children.size != 1) {
        children.forEach { child ->
            builder.addChildren(
                child.toNodeBuilder(
                    stringTable = stringTable,
                    windowX = windowX,
                    windowY = windowY,
                    reduceChildNesting = reduceChildNesting,
                    recomposeCountLookup = recomposeCountLookup,
                )
            )
        }
    } else {
        var nested = children.single()

        while (nested.children.size == 1) {
            builder.addChildren(
                nested.toFlatNode(
                    stringTable = stringTable,
                    windowX = windowX,
                    windowY = windowY,
                    recomposeCountLookup = recomposeCountLookup,
                )
            )
            builder.flags = builder.flags or ComposableNode.Flags.NESTED_SINGLE_CHILDREN_VALUE
            nested = nested.children.single()
        }
        builder.addChildren(
            nested.toNodeBuilder(
                stringTable = stringTable,
                windowX = windowX,
                windowY = windowY,
                reduceChildNesting = reduceChildNesting,
                recomposeCountLookup = recomposeCountLookup,
            )
        )
    }
    return builder
}

/** Convert an [InspectorNode] to protobuf [ComposableNode] without child nesting. */
private fun InspectorNode.toFlatNode(
    stringTable: StringTable,
    windowX: Int,
    windowY: Int,
    recomposeCountLookup: ((anchorId: Int) -> RecomposeCount?)?,
): ComposableNode.Builder {
    val inspectorNode = this
    return ComposableNode.newBuilder().apply {
        id = inspectorNode.id

        packageHash = inspectorNode.packageHash
        filename = stringTable.put(inspectorNode.fileName)
        lineNumber = inspectorNode.lineNumber
        offset = inspectorNode.offset

        name = stringTable.put(inspectorNode.name)

        bounds =
            Bounds.newBuilder()
                .apply {
                    layout =
                        Rect.newBuilder()
                            .apply {
                                x = inspectorNode.left + windowX
                                y = inspectorNode.top + windowY
                                w = inspectorNode.width
                                h = inspectorNode.height
                            }
                            .build()
                    if (inspectorNode.bounds != null) {
                        render =
                            Quad.newBuilder()
                                .apply {
                                    x0 = inspectorNode.bounds.x0
                                    y0 = inspectorNode.bounds.y0
                                    x1 = inspectorNode.bounds.x1
                                    y1 = inspectorNode.bounds.y1
                                    x2 = inspectorNode.bounds.x2
                                    y2 = inspectorNode.bounds.y2
                                    x3 = inspectorNode.bounds.x3
                                    y3 = inspectorNode.bounds.y3
                                }
                                .build()
                    }
                }
                .build()

        flags = inspectorNode.composableNodeFlags()
        viewId = inspectorNode.viewId

        recomposeCountLookup?.invoke(inspectorNode.anchorId)?.let {
            recomposeCount = it.count
            recomposeSkips = it.skips
        }

        anchorHash = inspectorNode.anchorId
    }
}

private fun InspectorNode.composableNodeFlags(): Int {
    var flags = 0
    if (packageHash in systemPackages) {
        flags = flags or ComposableNode.Flags.SYSTEM_CREATED_VALUE
    }
    if (mergedSemantics.isNotEmpty()) {
        flags = flags or ComposableNode.Flags.HAS_MERGED_SEMANTICS_VALUE
    }
    if (unmergedSemantics.isNotEmpty()) {
        flags = flags or ComposableNode.Flags.HAS_UNMERGED_SEMANTICS_VALUE
    }
    if (inlined) {
        flags = flags or ComposableNode.Flags.INLINED_VALUE
    }
    if (hasDrawModifier) {
        flags = flags or ComposableNode.Flags.HAS_DRAW_MODIFIER_VALUE
    }
    if (hasChildDrawModifier) {
        flags = flags or ComposableNode.Flags.HAS_CHILD_DRAW_MODIFIER_VALUE
    }
    return flags
}