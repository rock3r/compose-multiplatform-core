/*
 * Copyright 2026 The Android Open Source Project
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
@file:OptIn(
    androidx.compose.runtime.tooling.ComposeToolingApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
)

package androidx.compose.ui.awt

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsEnvironment
import javax.swing.JPanel
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.skiko.MainUIDispatcher
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class Cmp11138LifecycleTest(private val renderer: String) {
    @Test
    fun backgroundWritesAndMovableContentSurvivePause() = runBlocking(MainUIDispatcher) {
        assumeFalse(GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance)
        val targetState = mutableIntStateOf(0)
        val moveState = mutableIntStateOf(0)
        var composedTarget = -1
        var composedMove = -1
        var markerIdentity = 0
        var effectStarts = 0
        var disposeCalls = 0
        val panel = ComposePanel(renderSettings = renderSettings(renderer)).apply {
            isDisposeOnRemove = false
            preferredSize = Dimension(160, 120)
            setContent {
                val target = targetState.intValue
                val move = moveState.intValue
                val content = remember {
                    movableContentOf {
                        val marker = remember { Any() }
                        SideEffect { markerIdentity = System.identityHashCode(marker) }
                        DisposableEffect(Unit) {
                            effectStarts++
                            onDispose { disposeCalls++ }
                        }
                    }
                }
                if (move == 0) {
                    Row { content() }
                } else {
                    Column {
                        Box(Modifier.size(10.dp))
                        content()
                    }
                }
                SideEffect {
                    composedTarget = target
                    composedMove = move
                }
            }
        }
        val host = JPanel(BorderLayout()).apply { add(panel, BorderLayout.CENTER) }
        val frame = frameForTest(host, 60)
        try {
            delay(300)
            val recomposer = panel.enablePauseForTest()
            val sceneId = System.identityHashCode(panel.sceneForTest())
            val initialMarker = markerIdentity
            host.remove(panel)
            delay(150)
            withContext(Dispatchers.Default) {
                targetState.intValue = 42
                moveState.intValue = 1
                Snapshot.sendApplyNotifications()
            }
            withTimeout(5000) {
                while (recomposer.compositionInvalidationsForTest() == 0) delay(5)
            }
            assertEquals(0, composedTarget)
            assertEquals(0, composedMove)
            assertEquals(1, effectStarts)
            assertEquals(0, disposeCalls)
            host.add(panel, BorderLayout.CENTER)
            host.revalidate()
            host.repaint()
            withTimeout(5000) {
                while (composedTarget != 42 || composedMove != 1) delay(5)
            }
            assertEquals(initialMarker, markerIdentity)
            assertEquals(sceneId, System.identityHashCode(panel.sceneForTest()))
            assertEquals(1, effectStarts)
            assertEquals(0, disposeCalls)
        } finally {
            frame.dispose()
            panel.dispose()
        }
        delay(50)
        assertEquals(1, disposeCalls)
    }

    @Test
    fun disposeWhilePausedRunsEffectsExactlyOnce() = runBlocking(MainUIDispatcher) {
        assumeFalse(GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance)
        var disposeCalls = 0
        val panel = ComposePanel(renderSettings = renderSettings(renderer)).apply {
            isDisposeOnRemove = false
            preferredSize = Dimension(160, 120)
            setContent { DisposableEffect(Unit) { onDispose { disposeCalls++ } } }
        }
        val host = JPanel(BorderLayout()).apply { add(panel, BorderLayout.CENTER) }
        val frame = frameForTest(host, 60)
        try {
            delay(300)
            panel.enablePauseForTest()
            host.remove(panel)
            delay(150)
            assertEquals(0, disposeCalls)
            assertEquals(1, Recomposer.runningRecomposers.value.size)
            panel.dispose()
            delay(100)
            assertEquals(1, disposeCalls)
            assertEquals(0, Recomposer.runningRecomposers.value.size)
        } finally {
            frame.dispose()
            panel.dispose()
        }
    }

    @Test
    fun layoutDrawAndSemanticsCatchUpWithoutRecomposition() = runBlocking(MainUIDispatcher) {
        assumeFalse(GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance)
        val layoutState = mutableIntStateOf(40)
        val drawState = mutableIntStateOf(0)
        val semanticsState = mutableIntStateOf(0)
        var compositions = 0
        var measuredValue = -1
        var drawnValue = -1
        val panel = ComposePanel(renderSettings = renderSettings(renderer)).apply {
            isDisposeOnRemove = false
            preferredSize = Dimension(160, 120)
            setContent {
                SideEffect { compositions++ }
                Column {
                    Box(
                        Modifier.layout { measurable, constraints ->
                            val side = layoutState.intValue
                            val placeable = measurable.measure(constraints)
                            layout(side, side) {
                                measuredValue = side
                                placeable.place(0, 0)
                            }
                        }.semantics {
                            contentDescription = "value-${semanticsState.intValue}"
                        }
                    )
                    Canvas(Modifier.size(80.dp)) {
                        drawnValue = drawState.intValue
                        drawRect(if (drawnValue % 2 == 0) Color.Red else Color.Blue)
                    }
                }
            }
        }
        val host = JPanel(BorderLayout()).apply { add(panel, BorderLayout.CENTER) }
        val frame = frameForTest(host, 60)
        fun semanticsDescription(): String? =
            panel.semanticsOwners
                .flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                .flatMap { node ->
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                }
                .firstOrNull { it.startsWith("value-") }
        try {
            delay(400)
            panel.enablePauseForTest()
            assertEquals(40, measuredValue)
            assertEquals(0, drawnValue)
            assertEquals("value-0", semanticsDescription())
            host.remove(panel)
            delay(150)
            val compositionsBefore = compositions
            val measuredBefore = measuredValue
            val drawnBefore = drawnValue
            withContext(Dispatchers.Default) {
                layoutState.intValue = 60
                drawState.intValue = 43
                semanticsState.intValue = 9
                Snapshot.sendApplyNotifications()
            }
            delay(100)
            assertEquals(compositionsBefore, compositions)
            assertEquals(measuredBefore, measuredValue)
            assertEquals(drawnBefore, drawnValue)
            host.add(panel, BorderLayout.CENTER)
            host.revalidate()
            host.repaint()
            withTimeout(5000) {
                while (measuredValue != 60 || drawnValue != 43 ||
                    semanticsDescription() != "value-9"
                ) {
                    delay(5)
                }
            }
            assertEquals(compositionsBefore, compositions)
        } finally {
            frame.dispose()
            panel.dispose()
        }
    }

    @Test
    fun reparentFromMinimizedToNormalWindowResumes() = runBlocking(MainUIDispatcher) {
        assumeFalse(GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance)
        val targetState = mutableIntStateOf(0)
        var composedValue = -1
        val panel = ComposePanel(renderSettings = renderSettings(renderer)).apply {
            isDisposeOnRemove = false
            preferredSize = Dimension(160, 120)
            setContent {
                composedValue = targetState.intValue
                SideEffect { composedValue = targetState.intValue }
            }
        }
        val hostA = JPanel(BorderLayout()).apply { add(panel, BorderLayout.CENTER) }
        val hostB = JPanel(BorderLayout())
        val frameA = frameForTest(hostA, 60)
        val frameB = frameForTest(hostB, 60)
        try {
            delay(300)
            val recomposer = panel.enablePauseForTest()
            frameA.extendedState = Frame.ICONIFIED
            withTimeout(5000) {
                while (recomposer.pauseFlagsForTest() != (true to true)) delay(10)
            }
            targetState.intValue = 9
            Snapshot.sendApplyNotifications()
            withTimeout(5000) {
                while (recomposer.compositionInvalidationsForTest() == 0) delay(5)
            }
            assertEquals(0, composedValue)
            hostA.remove(panel)
            hostB.add(panel, BorderLayout.CENTER)
            hostB.revalidate()
            hostB.repaint()
            withTimeout(5000) { while (composedValue != 9) delay(5) }
            assertEquals(1, Recomposer.runningRecomposers.value.size)
        } finally {
            frameA.dispose()
            frameB.dispose()
            panel.dispose()
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters() = listOf("SkiaSurface", "SwingGraphics").map { arrayOf(it) }
    }
}
