/*
 * Copyright 2022 The Android Open Source Project
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

package androidx.compose.ui.node

import androidx.compose.ui.MockOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InspectorInfo
import com.google.common.base.Objects
import org.junit.Assert

internal fun chainTester() = NodeChainTester()

class DiffLog {
    private val oplog = mutableListOf<DiffOp>()
    fun op(op: DiffOp) = oplog.add(op)
    fun clear() = oplog.clear()

    fun assertElementDiff(expected: String) {
        Assert.assertEquals(
            expected,
            oplog.joinToString("\n") {
                it.elementDiffString()
            }
        )
    }

    fun debug(): String = buildString {
        for (op in oplog) {
            appendLine(op.debug())
        }
    }
}

internal class NodeChainTester : NodeChain.Logger {
    private val layoutNode = LayoutNode()
    val chain = layoutNode.nodes.also { it.useLogger(this) }
    private val log = DiffLog()
    val nodes: List<Modifier.Node>
        get() {
            val result = mutableListOf<Modifier.Node>()
            chain.headToTailExclusive {
                result.add(it)
            }
            return result
        }

    val aggregateChildMasks: List<Int> get() = nodes.map { it.aggregateChildKindSet }

    fun attach(): NodeChainTester {
        check(!layoutNode.isAttached)
        layoutNode.attach(MockOwner())
        return this
    }

    fun detach(): NodeChainTester {
        check(layoutNode.isAttached)
        layoutNode.detach()
        return this
    }

    fun clearLog(): NodeChainTester {
        log.clear()
        return this
    }

    fun debug(): NodeChainTester {
        if (true) error(log.debug())
        return this
    }

    fun validateAttached(): NodeChainTester {
        chain.head.visitSubtree(Nodes.Any) {
            check(it.isAttached)
        }
        return this
    }

    fun withModifiers(vararg modifiers: Modifier): NodeChainTester {
        chain.updateFrom(modifierOf(*modifiers))
        return this
    }
    fun withModifierNodes(vararg nodes: Modifier.Node): NodeChainTester {
        chain.updateFrom(modifierOf(*nodes))
        return this
    }

    fun assertStringEquals(expected: String): NodeChainTester {
        Assert.assertEquals(expected, chain.toString())
        return this
    }

    fun assertElementDiff(expected: String): NodeChainTester {
        log.assertElementDiff(expected)
        return this
    }

    override fun linearDiffAborted(
        index: Int,
        prev: Modifier.Element,
        next: Modifier.Element,
        node: Modifier.Node
    ) {
        // TODO
    }

    override fun nodeUpdated(
        oldIndex: Int,
        newIndex: Int,
        prev: Modifier.Element,
        next: Modifier.Element,
        node: Modifier.Node,
    ) {
        log.op(DiffOp.Same(oldIndex, newIndex, prev, next, node, true))
    }

    override fun nodeReused(
        oldIndex: Int,
        newIndex: Int,
        prev: Modifier.Element,
        next: Modifier.Element,
        node: Modifier.Node
    ) {
        log.op(DiffOp.Same(oldIndex, newIndex, prev, next, node, false))
    }

    override fun nodeInserted(
        atIndex: Int,
        newIndex: Int,
        element: Modifier.Element,
        child: Modifier.Node,
        inserted: Modifier.Node
    ) {
        log.op(DiffOp.Insert(atIndex, newIndex, element, child, inserted))
    }

    override fun nodeRemoved(oldIndex: Int, element: Modifier.Element, node: Modifier.Node) {
        log.op(DiffOp.Remove(oldIndex, element, node))
    }
}

sealed class DiffOp(
    private val element: Modifier.Element,
    private val opChar: String,
    val opString: String,
) {
    fun elementDiffString(): String {
        return "$opChar$element"
    }

    abstract fun debug(): String
    class Same(
        private val oldIndex: Int,
        private val newIndex: Int,
        private val beforeEl: Modifier.Element,
        private val afterEl: Modifier.Element,
        private val node: Modifier.Node,
        val updated: Boolean,
    ) : DiffOp(beforeEl, if (updated) "*" else " ", "Same") {
        override fun debug() = """
            <$opString>
                $beforeEl @ $oldIndex = $afterEl @ $newIndex
                node: $node
                updated? = $updated
            </$opString>
        """.trimIndent()
    }

    class Insert(
        private val oldIndex: Int,
        private val newIndex: Int,
        private val afterEl: Modifier.Element,
        val child: Modifier.Node,
        private val inserted: Modifier.Node,
    ) : DiffOp(afterEl, "+", "Insert") {
        override fun debug() = """
            <$opString>
                $afterEl @ $newIndex (inserted at $oldIndex)
                child = $child
                inserted = $inserted
            </$opString>
        """.trimIndent()
    }

    class Remove(
        private val oldIndex: Int,
        private val beforeEl: Modifier.Element,
        private val beforeEntity: Modifier.Node,
    ) : DiffOp(beforeEl, "-", "Remove") {
        override fun debug() = """
            <$opString>
                $beforeEl @ $oldIndex
                beforeEntity = $beforeEntity
            </$opString>
        """.trimIndent()
    }
}

fun modifierOf(vararg modifiers: Modifier): Modifier {
    var result: Modifier = Modifier
    for (m in modifiers) {
        result = result.then(m)
    }
    return result
}

fun modifierOf(vararg nodes: Modifier.Node): Modifier {
    var result: Modifier = Modifier
    for (n in nodes) {
        result = result.then(NodeModifierElementNode(n))
    }
    return result
}

internal open class NodeModifierElementNode(val node: Modifier.Node) :
    ModifierNodeElement<Modifier.Node>() {
    override fun create(): Modifier.Node = node
    override fun update(node: Modifier.Node) { }
    override fun hashCode(): Int = node.hashCode()
    override fun equals(other: Any?): Boolean {
        if (other !is NodeModifierElementNode) return false
        return other.node === node
    }
}

fun reusableModifier(name: String): Modifier.Element = object : Modifier.Element {
    override fun toString(): String = name
}

fun reusableModifiers(vararg names: String): List<Modifier.Element> {
    return names.map { reusableModifier(it) }
}

class A : Modifier.Node() {
    override fun toString(): String = "a"
}

fun modifierA(params: Any? = null): Modifier.Element {
    return object : TestElement<A>("a", params, A()) {}
}

class B : Modifier.Node() {
    override fun toString(): String = "b"
}

fun modifierB(params: Any? = null): Modifier.Element {
    return object : TestElement<B>("b", params, B()) {}
}

class C : Modifier.Node() {
    override fun toString(): String = "c"
}

fun modifierC(params: Any? = null): Modifier.Element {
    return object : TestElement<C>("c", params, C()) {}
}

fun modifierD(params: Any? = null): Modifier.Element {
    return object : TestElement<Modifier.Node>("d", params,
        object : Modifier.Node() {}
    ) {}
}

fun managedModifier(
    name: String,
    params: Any? = null
): ModifierNodeElement<*> = object : TestElement<Modifier.Node>(name, params,
    object : Modifier.Node() {}
) {}

private abstract class TestElement<T : Modifier.Node>(
    val modifierName: String,
    val param: Any? = null,
    val node: T
) : ModifierNodeElement<T>() {
    override fun create(): T = node
    override fun update(node: T) {}
    override fun InspectorInfo.inspectableProperties() {
        name = modifierName
    }

    override fun hashCode() = Objects.hashCode(modifierName, param)

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        return other is TestElement<*> &&
            javaClass == other.javaClass &&
            modifierName == other.modifierName &&
            param == other.param
    }

    override fun toString() = modifierName
}

fun entityModifiers(vararg names: String): List<ModifierNodeElement<*>> {
    return names.map { managedModifier(it, null) }
}

fun assertReused(before: Modifier.Element, after: Modifier.Element) {
    with(chainTester()) {
        withModifiers(before)
        val beforeEntity = chain.tail
        withModifiers(after)
        val afterEntity = chain.tail
        assert(beforeEntity === afterEntity)
    }
}