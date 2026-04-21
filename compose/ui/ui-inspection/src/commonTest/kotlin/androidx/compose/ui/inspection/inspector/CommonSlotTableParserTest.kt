package androidx.compose.ui.inspection.inspector

import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionGroup
import androidx.compose.runtime.tooling.CompositionInstance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.ModifierInfo
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class CommonSlotTableParserTest {
    private val parser = CommonSlotTableParser()

    @Test
    fun parseBuildsTreeAndExtractsComposableNames() {
        val composition =
            FakeComposition(
                groups =
                    listOf(
                        FakeGroup(
                            key = 1,
                            sourceInfo = "C(Root)",
                            children =
                                listOf(
                                    FakeGroup(key = 2, sourceInfo = "C(Child)", children = emptyList())
                                ),
                        )
                    )
            )

        val roots = parser.parse(listOf(composition))

        assertEquals(1, roots.size)
        assertEquals("Root", roots.single().name)
        assertEquals(1, roots.single().key)
        assertEquals(1, roots.single().children.size)
        assertEquals("Child", roots.single().children.single().name)
        assertEquals(2, roots.single().children.single().key)
    }

    @Test
    fun parseFallsBackToKeyStringWhenSourceInfoIsMissing() {
        val composition =
            FakeComposition(
                groups = listOf(FakeGroup(key = 77, sourceInfo = null, children = emptyList()))
            )

        val roots = parser.parse(listOf(composition))

        assertEquals(1, roots.size)
        assertEquals("77", roots.single().name)
    }

    @Test
    fun parseFallsBackToKeyStringWhenSourceInfoDoesNotContainComposableName() {
        val composition =
            FakeComposition(
                groups =
                    listOf(
                        FakeGroup(key = 42, sourceInfo = "something else", children = emptyList())
                    )
            )

        val roots = parser.parse(listOf(composition))

        assertEquals(1, roots.size)
        assertEquals("42", roots.single().name)
    }

    @Test
    fun parseResetsGeneratedIdsBetweenCalls() {
        val composition =
            FakeComposition(
                groups =
                    listOf(
                        FakeGroup(key = 1, sourceInfo = "C(First)", children = emptyList()),
                        FakeGroup(key = 2, sourceInfo = "C(Second)", children = emptyList()),
                    )
            )

        val first = parser.parse(listOf(composition))
        val second = parser.parse(listOf(composition))

        assertEquals(listOf(-1L, -2L), first.map { it.id })
        assertEquals(listOf(-1L, -2L), second.map { it.id })
    }

    @Test
    fun parseStitchesChildCompositionIntoContextGroup() {
        val contextGroup = FakeGroup(key = 2, sourceInfo = "C(Context)", children = emptyList())
        val parentRoot = FakeGroup(key = 1, sourceInfo = "C(Parent)", children = listOf(contextGroup))
        val parentComposition =
            FakeCompositionInstance(parent = null, groups = listOf(parentRoot), contextGroup = null)

        val childRoot = FakeGroup(key = 3, sourceInfo = "C(SubRoot)", children = emptyList())
        val childComposition =
            FakeCompositionInstance(
                parent = parentComposition,
                groups = listOf(childRoot),
                contextGroup = contextGroup,
            )

        val roots = parser.parse(listOf(parentComposition, childComposition))

        assertEquals(1, roots.size)
        assertEquals("Parent", roots.single().name)
        assertEquals(1, roots.single().children.size)
        assertEquals("Context", roots.single().children.single().name)
        assertEquals(1, roots.single().children.single().children.size)
        assertEquals("SubRoot", roots.single().children.single().children.single().name)
    }

    @Test
    fun parseStitchesMultipleChildCompositionsIntoSameContextGroup() {
        val contextGroup = FakeGroup(key = 2, sourceInfo = "C(Context)", children = emptyList())
        val parentRoot = FakeGroup(key = 1, sourceInfo = "C(Parent)", children = listOf(contextGroup))
        val parentComposition =
            FakeCompositionInstance(parent = null, groups = listOf(parentRoot), contextGroup = null)

        val firstChildComposition =
            FakeCompositionInstance(
                parent = parentComposition,
                groups = listOf(FakeGroup(key = 3, sourceInfo = "C(SubA)", children = emptyList())),
                contextGroup = contextGroup,
            )
        val secondChildComposition =
            FakeCompositionInstance(
                parent = parentComposition,
                groups = listOf(FakeGroup(key = 4, sourceInfo = "C(SubB)", children = emptyList())),
                contextGroup = contextGroup,
            )

        val roots = parser.parse(listOf(parentComposition, firstChildComposition, secondChildComposition))

        val stitchedChildren = roots.single().children.single().children
        assertEquals(listOf("SubA", "SubB"), stitchedChildren.map { it.name })
    }

    @Test
    fun parseSupportsNestedSubcompositionStitching() {
        val childContext = FakeGroup(key = 2, sourceInfo = "C(ChildContext)", children = emptyList())
        val parentRoot =
            FakeGroup(key = 1, sourceInfo = "C(Parent)", children = listOf(childContext))
        val parentComposition =
            FakeCompositionInstance(parent = null, groups = listOf(parentRoot), contextGroup = null)

        val grandchildContext =
            FakeGroup(key = 3, sourceInfo = "C(GrandchildContext)", children = emptyList())
        val childRoot =
            FakeGroup(key = 4, sourceInfo = "C(ChildRoot)", children = listOf(grandchildContext))
        val childComposition =
            FakeCompositionInstance(
                parent = parentComposition,
                groups = listOf(childRoot),
                contextGroup = childContext,
            )

        val grandchildComposition =
            FakeCompositionInstance(
                parent = childComposition,
                groups =
                    listOf(
                        FakeGroup(key = 5, sourceInfo = "C(GrandchildRoot)", children = emptyList())
                    ),
                contextGroup = grandchildContext,
            )

        val roots = parser.parse(listOf(parentComposition, childComposition, grandchildComposition))

        val grandchildNode =
            roots
                .single()
                .children
                .single()
                .children
                .single()
                .children
                .single()
                .children
                .single()
        assertEquals("GrandchildRoot", grandchildNode.name)
    }

    @Test
    fun parseKeepsRootsWithoutCompositionInstanceMetadata() {
        val plainComposition =
            FakeComposition(
                groups = listOf(FakeGroup(key = 10, sourceInfo = "C(Plain)", children = emptyList()))
            )

        val rootWithInstance =
            FakeCompositionInstance(
                parent = null,
                groups =
                    listOf(FakeGroup(key = 11, sourceInfo = "C(WithInstance)", children = emptyList())),
                contextGroup = null,
            )

        val roots = parser.parse(listOf(plainComposition, rootWithInstance))

        assertEquals(listOf("WithInstance", "Plain"), roots.map { it.name })
    }

    @Test
    fun parseUsesLayoutInfoBoundsForLayoutNodes() {
        val layoutInfo = FakeLayoutInfo(left = 10f, top = 20f, width = 30, height = 40)
        val composition =
            FakeComposition(
                groups =
                    listOf(
                        FakeGroup(
                            key = 1,
                            sourceInfo = "C(LayoutNode)",
                            children = emptyList(),
                            groupNode = layoutInfo,
                        )
                    )
            )

        val roots = parser.parse(listOf(composition))
        val root = roots.single()

        assertEquals(10, root.left)
        assertEquals(20, root.top)
        assertEquals(30, root.width)
        assertEquals(40, root.height)
    }

    @Test
    fun parseFallsBackToUnionOfChildBoundsWhenParentHasNoLayoutInfo() {
        val childA =
            FakeGroup(
                key = 2,
                sourceInfo = "C(ChildA)",
                children = emptyList(),
                groupNode = FakeLayoutInfo(left = 5f, top = 7f, width = 11, height = 13),
            )
        val childB =
            FakeGroup(
                key = 3,
                sourceInfo = "C(ChildB)",
                children = emptyList(),
                groupNode = FakeLayoutInfo(left = 20f, top = 22f, width = 5, height = 7),
            )
        val composition =
            FakeComposition(
                groups =
                    listOf(FakeGroup(key = 1, sourceInfo = "C(Parent)", children = listOf(childA, childB)))
            )

        val roots = parser.parse(listOf(composition))
        val root = roots.single()

        assertEquals(5, root.left)
        assertEquals(7, root.top)
        assertEquals(20, root.width)
        assertEquals(22, root.height)
    }

    private class FakeComposition(
        private val groups: List<CompositionGroup>,
    ) : CompositionData {
        override val compositionGroups: Iterable<CompositionGroup>
            get() = groups

        override val isEmpty: Boolean
            get() = groups.isEmpty()
    }

    private class FakeCompositionInstance(
        override val parent: CompositionInstance?,
        private val groups: List<CompositionGroup>,
        private val contextGroup: CompositionGroup?,
    ) : CompositionData, CompositionInstance {
        override val data: CompositionData
            get() = this

        override val compositionGroups: Iterable<CompositionGroup>
            get() = groups

        override val isEmpty: Boolean
            get() = groups.isEmpty()

        override fun findContextGroup(): CompositionGroup? = contextGroup
    }

    private class FakeGroup(
        override val key: Any,
        override val sourceInfo: String?,
        children: List<CompositionGroup>,
        private val groupNode: Any? = null,
    ) : CompositionGroup {
        private val childGroups = children

        override val compositionGroups: Iterable<CompositionGroup>
            get() = childGroups

        override val isEmpty: Boolean
            get() = childGroups.isEmpty()

        override val node: Any?
            get() = groupNode

        override val data: Iterable<Any?>
            get() = emptyList()
    }

    private class FakeLayoutInfo(
        private val left: Float,
        private val top: Float,
        override val width: Int,
        override val height: Int,
    ) : LayoutInfo {
        override fun getModifierInfo(): List<ModifierInfo> = emptyList()

        override val coordinates: LayoutCoordinates =
            FakeLayoutCoordinates(left = left, top = top, size = IntSize(width, height))

        override val isPlaced: Boolean
            get() = true

        override val parentInfo: LayoutInfo?
            get() = null

        override val density: Density
            get() = Density(1f)

        override val layoutDirection: LayoutDirection
            get() = LayoutDirection.Ltr

        override val viewConfiguration: ViewConfiguration
            get() =
                object : ViewConfiguration {
                    override val longPressTimeoutMillis: Long = 500L
                    override val doubleTapTimeoutMillis: Long = 300L
                    override val doubleTapMinTimeMillis: Long = 40L
                    override val touchSlop: Float = 0f
                }

        override val isAttached: Boolean
            get() = true

        override val semanticsId: Int
            get() = 0
    }

    private class FakeLayoutCoordinates(
        private val left: Float,
        private val top: Float,
        override val size: IntSize,
    ) : LayoutCoordinates {
        override val providedAlignmentLines: Set<AlignmentLine>
            get() = emptySet()

        override val parentLayoutCoordinates: LayoutCoordinates?
            get() = null

        override val parentCoordinates: LayoutCoordinates?
            get() = null

        override val isAttached: Boolean
            get() = true

        override fun windowToLocal(relativeToWindow: Offset): Offset {
            return Offset(relativeToWindow.x - left, relativeToWindow.y - top)
        }

        override fun localToWindow(relativeToLocal: Offset): Offset {
            return Offset(left + relativeToLocal.x, top + relativeToLocal.y)
        }

        override fun localToRoot(relativeToLocal: Offset): Offset {
            return Offset(left + relativeToLocal.x, top + relativeToLocal.y)
        }

        override fun localPositionOf(sourceCoordinates: LayoutCoordinates, relativeToSource: Offset): Offset {
            return relativeToSource
        }

        override fun localBoundingBoxOf(sourceCoordinates: LayoutCoordinates, clipBounds: Boolean): Rect {
            return Rect.Zero
        }

        override fun get(alignmentLine: AlignmentLine): Int {
            return AlignmentLine.Unspecified
        }
    }
}
