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

package androidx.compose.ui.inspection.proto

import android.view.inspector.WindowInspector
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.inspection.ComposeLayoutInspector.CacheTree
import androidx.compose.ui.inspection.LambdaLocation
import androidx.compose.ui.inspection.inspector.InspectorNode
import androidx.compose.ui.inspection.inspector.LayoutInspectorTree
import androidx.compose.ui.inspection.inspector.NodeParameter
import androidx.compose.ui.inspection.inspector.NodeParameterReference
import androidx.compose.ui.inspection.inspector.ParameterKind
import androidx.compose.ui.inspection.inspector.ParameterType
import androidx.compose.ui.inspection.recompositions.ObservedReadResult
import androidx.compose.ui.inspection.recompositions.StateReadRecord
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableNode
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableRoot
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.LambdaValue
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Parameter
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ParameterReference
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StackTraceLine
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StateRead
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StateReadGroup

internal fun InspectorNode.toComposableNode(context: ConversionContext): ComposableNode =
    toComposableNode(
        stringTable = context.stringTable,
        windowX = context.windowPos.x,
        windowY = context.windowPos.y,
        reduceChildNesting = context.reduceChildNesting,
        recomposeCountLookup = { anchorId ->
            context.recompositionHandler.getCounts(anchorId)?.let {
                RecomposeCount(it.count, it.skips)
            }
        },
    )

fun ParameterType.convert(): Parameter.Type {
    return when (this) {
        ParameterType.String -> Parameter.Type.STRING
        ParameterType.Boolean -> Parameter.Type.BOOLEAN
        ParameterType.Double -> Parameter.Type.DOUBLE
        ParameterType.Float -> Parameter.Type.FLOAT
        ParameterType.Int32 -> Parameter.Type.INT32
        ParameterType.Int64 -> Parameter.Type.INT64
        ParameterType.Color -> Parameter.Type.COLOR
        ParameterType.Resource -> Parameter.Type.RESOURCE
        ParameterType.DimensionDp -> Parameter.Type.DIMENSION_DP
        ParameterType.DimensionSp -> Parameter.Type.DIMENSION_SP
        ParameterType.DimensionEm -> Parameter.Type.DIMENSION_EM
        ParameterType.Lambda -> Parameter.Type.LAMBDA
        ParameterType.FunctionReference -> Parameter.Type.FUNCTION_REFERENCE
        ParameterType.Iterable -> Parameter.Type.ITERABLE
    }
}

fun ParameterKind.convert(): ParameterReference.Kind {
    return when (this) {
        ParameterKind.Normal -> ParameterReference.Kind.NORMAL
        ParameterKind.MergedSemantics -> ParameterReference.Kind.MERGED_SEMANTICS
        ParameterKind.UnmergedSemantics -> ParameterReference.Kind.UNMERGED_SEMANTICS
    }
}

fun ParameterReference.Kind.convert(): ParameterKind {
    return when (this) {
        ParameterReference.Kind.NORMAL -> ParameterKind.Normal
        ParameterReference.Kind.MERGED_SEMANTICS -> ParameterKind.MergedSemantics
        ParameterReference.Kind.UNMERGED_SEMANTICS -> ParameterKind.UnmergedSemantics
        else -> ParameterKind.Normal
    }
}

private fun Parameter.Builder.setValue(stringTable: StringTable, value: Any?) {
    when (type) {
        Parameter.Type.ITERABLE,
        Parameter.Type.STRING -> {
            int32Value = stringTable.put(value as String)
        }
        Parameter.Type.BOOLEAN -> {
            int32Value = if (value as Boolean) 1 else 0
        }
        Parameter.Type.DOUBLE -> {
            doubleValue = value as Double
        }
        Parameter.Type.FLOAT,
        Parameter.Type.DIMENSION_DP,
        Parameter.Type.DIMENSION_SP,
        Parameter.Type.DIMENSION_EM -> {
            floatValue = value as Float
        }
        Parameter.Type.INT32,
        Parameter.Type.COLOR -> {
            int32Value = value as Int
        }
        Parameter.Type.INT64 -> {
            int64Value = value as Long
        }
        Parameter.Type.RESOURCE -> setResourceType(value, stringTable)
        Parameter.Type.LAMBDA -> setFunctionType(value, stringTable)
        Parameter.Type.FUNCTION_REFERENCE -> setFunctionType(value, stringTable)
        else -> error("Unknown Composable parameter type: $type")
    }
}

private fun Parameter.Builder.setResourceType(value: Any?, stringTable: StringTable) {
    // A Resource is passed by resource id for Compose
    val resourceId = (value as? Int) ?: return
    resourceValue =
        WindowInspector.getGlobalWindowViews()
            .firstOrNull()
            ?.createResource(stringTable, resourceId) ?: return
}

private fun Parameter.Builder.setFunctionType(value: Any?, stringTable: StringTable) {
    if (value !is Array<*> || value.size > 2 || value.size == 0) {
        return
    }
    val lambdaInstance = value[0] ?: return
    val location = LambdaLocation.resolve(lambdaInstance) ?: return
    val function = value.getOrNull(1) as? String
    lambdaValue =
        LambdaValue.newBuilder()
            .apply {
                packageName = stringTable.put(location.packageName)
                functionName = function?.let { stringTable.put(it) } ?: 0
                lambdaName = stringTable.put(location.lambdaName)
                fileName = stringTable.put(location.fileName)
                startLineNumber = location.startLine
                endLineNumber = location.endLine
            }
            .build()
}

fun NodeParameter.convert(stringTable: StringTable): Parameter {
    val nodeParam = this
    return Parameter.newBuilder()
        .apply {
            name = stringTable.put(nodeParam.name)
            type = nodeParam.type.convert()
            setValue(stringTable, nodeParam.value)
            index = nodeParam.index
            nodeParam.reference?.let { reference = it.convert() }
            if (nodeParam.elements.isNotEmpty()) {
                addAllElements(nodeParam.elements.map { it.convert(stringTable) })
            }
        }
        .build()
}

fun NodeParameterReference.convert(): ParameterReference {
    val reference = this
    return ParameterReference.newBuilder()
        .apply {
            kind = reference.kind.convert()
            composableId = reference.nodeId
            anchorHash = reference.anchorId
            parameterIndex = reference.parameterIndex
            reference.indices.forEach { addCompositeIndex(it) }
        }
        .build()
}

internal fun CacheTree.toComposableRoot(context: ConversionContext): ComposableRoot =
    ComposableRoot.newBuilder()
        .also { root ->
            root.viewId = viewParent.uniqueDrawingId
            root.addAllNodes(nodes.map { it.toComposableNode(context) })
            viewsToSkip.forEach { root.addViewsToSkip(it) }
        }
        .build()

fun Iterable<NodeParameter>.convertAll(stringTable: StringTable): List<Parameter> {
    return this.map { it.convert(stringTable) }
}

// Omit stacktrace lines from these classes.
// This helps with filtering out stacktraces with very similar nature.
private val ignoreStackTraceFromClasses =
    listOf(
        SnapshotStateList::class.java.name,
        "androidx.compose.runtime.snapshots.StateListIterator",
    )

private fun StateReadRecord.convert(
    stringTable: StringTable,
    layoutInspectorTree: LayoutInspectorTree,
): StateRead {
    val value = layoutInspectorTree.convertStateValue(this.value)
    val elements = this.trace.stackTrace
    val builder = StateRead.newBuilder()
    builder.value = value?.convert(stringTable) ?: Parameter.getDefaultInstance()
    builder.valueInstanceHash = this.valueInstanceHash
    builder.invalidated = this.invalidated
    for (index in 1 until elements.size) {
        val element = elements[index]
        if (element.className in ignoreStackTraceFromClasses) {
            continue
        }
        builder.addStackTraceLine(
            StackTraceLine.newBuilder().apply {
                declaringClass = stringTable.put(element.className.orEmpty())
                methodName = stringTable.put(element.methodName.orEmpty())
                fileName = stringTable.put(element.fileName.orEmpty())
                lineNumber = element.lineNumber
            }
        )
    }
    return builder.build()
}

fun ObservedReadResult.convert(
    stringTable: StringTable,
    layoutInspectorTree: LayoutInspectorTree,
): StateReadGroup {
    val builder = StateReadGroup.newBuilder()
    builder.recompositionNumber = recomposition

    // Collapse state reads that are identical:
    val convertedReads = mutableSetOf<StateRead>()
    reads.mapTo(convertedReads) { it.convert(stringTable, layoutInspectorTree) }
    builder.addAllRead(convertedReads)
    return builder.build()
}