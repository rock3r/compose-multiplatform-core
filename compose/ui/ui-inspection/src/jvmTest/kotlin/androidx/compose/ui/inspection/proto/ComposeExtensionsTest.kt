package androidx.compose.ui.inspection.proto

import androidx.compose.ui.inspection.inspector.MutableInspectorNode
import androidx.compose.ui.inspection.inspector.RawParameter
import androidx.compose.ui.unit.IntRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableNode

class ComposeExtensionsTest {

    @Test
    fun toComposableNodeCopiesInspectorNodeData() {
        val inspectorNode =
            MutableInspectorNode()
                .apply {
                    id = 42L
                    anchorId = 7
                    viewId = 99L
                    name = "Root"
                    fileName = "Sample.kt"
                    packageHash = 1234
                    lineNumber = 56
                    offset = 78
                    box = IntRect(10, 20, 40, 70)
                    inlined = true
                    hasDrawModifier = true
                    mergedSemantics += RawParameter("semantics", "value")
                }
                .build()

        val stringTable = StringTable()
        val converted = inspectorNode.toComposableNode(stringTable, windowX = 3, windowY = 5)

        assertEquals(42L, converted.id)
        assertEquals(1234, converted.packageHash)
        assertEquals(56, converted.lineNumber)
        assertEquals(78, converted.offset)
        assertEquals(99L, converted.viewId)
        assertEquals(7, converted.anchorHash)

        assertEquals(13, converted.bounds.layout.x)
        assertEquals(25, converted.bounds.layout.y)
        assertEquals(30, converted.bounds.layout.w)
        assertEquals(50, converted.bounds.layout.h)

        val strings = stringTable.toStringEntries().associate { it.id to it.str }
        assertEquals("Root", strings[converted.name])
        assertEquals("Sample.kt", strings[converted.filename])

        assertTrue(converted.flags and ComposableNode.Flags.INLINED_VALUE != 0)
        assertTrue(converted.flags and ComposableNode.Flags.HAS_DRAW_MODIFIER_VALUE != 0)
        assertTrue(converted.flags and ComposableNode.Flags.HAS_MERGED_SEMANTICS_VALUE != 0)
    }

    @Test
    fun toComposableNodeSupportsNestedSingleChildReduction() {
        val leaf =
            MutableInspectorNode().apply {
                id = 3L
                name = "Leaf"
                fileName = "Tree.kt"
            }.build()
        val middle =
            MutableInspectorNode().apply {
                id = 2L
                name = "Middle"
                fileName = "Tree.kt"
                children += leaf
            }.build()
        val root =
            MutableInspectorNode().apply {
                id = 1L
                name = "Root"
                fileName = "Tree.kt"
                children += middle
            }.build()

        val converted = root.toComposableNode(StringTable(), reduceChildNesting = true)

        assertTrue(converted.flags and ComposableNode.Flags.NESTED_SINGLE_CHILDREN_VALUE != 0)
        assertEquals(2, converted.childrenCount)
        assertEquals(2L, converted.getChildren(0).id)
        assertEquals(3L, converted.getChildren(1).id)
    }
}
