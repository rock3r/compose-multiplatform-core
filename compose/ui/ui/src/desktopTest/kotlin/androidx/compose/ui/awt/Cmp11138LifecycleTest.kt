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
package androidx.compose.ui.awt

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
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
import javax.swing.JFrame
import javax.swing.JPanel
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.skiko.MainUIDispatcher
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalComposeUiApi::class, ComposeToolingApi::class)
@RunWith(Parameterized::class)
class Cmp11138LifecycleTest(private val renderer: String) {
    @Test
    fun pausePreservesEffectsAndResumeState() {
        assumeFalse(GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance)
        runBlocking(MainUIDispatcher) {
            val targetState = mutableIntStateOf(0)
            val savedInput = mutableIntStateOf(7)
            var savedValue = -1
            var rememberCreations = 0
            var effectStarts = 0
            var disposeCalls = 0
            var frameResumes = 0
            var composedValue = -1
            var drawnValue = -1
            var frameEndCallbacks = 0
            val panel = ComposePanel(renderSettings = renderSettings()).apply {
                isDisposeOnRemove = false
                preferredSize = Dimension(160, 120)
                setContent {
                    val value = targetState.intValue
                    val saved = rememberSaveable { savedInput.intValue }
                    remember { rememberCreations++ }
                    SideEffect {
                        composedValue = value
                        savedValue = saved
                    }
                    LaunchedEffect(Unit) {
                        effectStarts++
                        while (true) {
                            withFrameNanos { frameResumes++ }
                        }
                    }
                    DisposableEffect(Unit) { onDispose { disposeCalls++ } }
                    Canvas(Modifier.size(80.dp)) {
                        drawnValue = value
                        drawRect(if (value % 2 == 0) Color.Red else Color.Blue)
                    }
                }
            }
            val host = JPanel(BorderLayout()).apply { add(panel, BorderLayout.CENTER) }
            val frame = frame(host)
            try {
                delay(400)
                panel.probeSetPauseCompositionWhenNotRendering(true)
                val recomposer = panel.probeFrameRecomposer().compositionContext as Recomposer
                assertEquals(1, rememberCreations)
                assertEquals(1, effectStarts)
                assertTrue(frameResumes > 0)

                host.remove(panel)
                delay(250)
                assertEquals(1, Recomposer.runningRecomposers.value.size)
                assertEquals(0, disposeCalls)
                assertEquals(1, effectStarts)
                val resumesBeforeStop = frameResumes
                recomposer.scheduleFrameEndCallback { frameEndCallbacks++ }
                withContext(Dispatchers.Default) {
                    targetState.intValue = 42
                    Snapshot.sendApplyNotifications()
                }
                delay(100)
                assertEquals(resumesBeforeStop, frameResumes)

                host.add(panel, BorderLayout.CENTER)
                host.revalidate()
                host.repaint()
                try {
                    withTimeout(5_000) {
                        while (composedValue != 42 || drawnValue != 42 || frameEndCallbacks != 1 ||
                            frameResumes == resumesBeforeStop || savedValue != 7 || savedInput.intValue != 7
                        ) {
                            delay(5)
                        }
                    }
                } catch (error: TimeoutCancellationException) {
                    println(
                        "CMP11138_LIFECYCLE_TIMEOUT renderer=$renderer composed=$composedValue " +
                            "drawn=$drawnValue saved=$savedValue " +
                            "frameEndCallbacks=$frameEndCallbacks resumesBefore=$resumesBeforeStop " +
                            "frameResumes=$frameResumes running=${Recomposer.runningRecomposers.value.size}"
                    )
                    throw error
                }
                assertEquals(1, Recomposer.runningRecomposers.value.size)
                assertEquals(1, rememberCreations)
                assertEquals(1, effectStarts)
                assertEquals(0, disposeCalls)
            } finally {
                frame.dispose()
                panel.dispose()
            }
            delay(100)
            assertEquals(1, disposeCalls)
            assertEquals(0, Recomposer.runningRecomposers.value.size)
        }
    }

    @Test
    fun rapidTransitionsAndCloseAreSafe() {
        assumeFalse(GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance)
        runBlocking(MainUIDispatcher) {
            var disposeCalls = 0
            val panel = ComposePanel(renderSettings = renderSettings()).apply {
                preferredSize = Dimension(160, 120)
                setContent { DisposableEffect(Unit) { onDispose { disposeCalls++ } } }
            }
            val host = JPanel(BorderLayout()).apply { add(panel, BorderLayout.CENTER) }
            val frame = frame(host)
            try {
                delay(300)
                panel.probeSetPauseCompositionWhenNotRendering(true)
                repeat(10) {
                    host.remove(panel)
                    delay(10)
                    host.add(panel, BorderLayout.CENTER)
                    host.revalidate()
                    host.repaint()
                    delay(20)
                }
                host.remove(panel)
                delay(10)
            } finally {
                frame.dispose()
                panel.dispose()
            }
            delay(100)
            assertEquals(11, disposeCalls)
            assertEquals(0, Recomposer.runningRecomposers.value.size)
        }
    }

    @Test
    fun reparentFromMinimizedToNormalWindowResumes() {
        assumeFalse(GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance)
        runBlocking(MainUIDispatcher) {
            val targetState = mutableIntStateOf(0)
            var composedValue = -1
            val panel = ComposePanel(renderSettings = renderSettings()).apply {
                isDisposeOnRemove = false
                preferredSize = Dimension(160, 120)
                setContent {
                    composedValue = targetState.intValue
                    SideEffect { composedValue = targetState.intValue }
                }
            }
            val hostA = JPanel(BorderLayout()).apply { add(panel, BorderLayout.CENTER) }
            val hostB = JPanel(BorderLayout())
            val frameA = frame(hostA)
            val frameB = frame(hostB)
            try {
                delay(300)
                panel.probeSetPauseCompositionWhenNotRendering(true)
                frameA.extendedState = Frame.ICONIFIED
                delay(400)
                hostA.remove(panel)
                targetState.intValue = 9
                Snapshot.sendApplyNotifications()
                hostB.add(panel, BorderLayout.CENTER)
                hostB.revalidate()
                hostB.repaint()
                withTimeout(5_000) { while (composedValue != 9) delay(5) }
                assertEquals(1, Recomposer.runningRecomposers.value.size)
            } finally {
                frameA.dispose()
                frameB.dispose()
                panel.dispose()
            }
        }
    }

    @Test
    fun layoutDrawAndSemanticsStateCatchUpWithoutRecomposition() {
        assumeFalse(GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance)
        runBlocking(MainUIDispatcher) {
            val layoutState = mutableIntStateOf(40)
            val drawState = mutableIntStateOf(0)
            val semanticsState = mutableIntStateOf(0)
            var compositions = 0
            var measuredValue = -1
            var drawnValue = -1
            val panel = ComposePanel(renderSettings = renderSettings()).apply {
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
            val frame = frame(host)
            fun semanticsDescription(): String? =
                panel.semanticsOwners
                    .flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                    .flatMap { node ->
                        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    }
                    .firstOrNull { it.startsWith("value-") }
            try {
                delay(400)
                panel.probeSetPauseCompositionWhenNotRendering(true)
                assertEquals(40, measuredValue)
                assertEquals(0, drawnValue)
                assertEquals("value-0", semanticsDescription())
                host.remove(panel)
                delay(250)
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
                withTimeout(5_000) {
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
    }

    @Test
    fun movableContentSurvivesPauseAndResume() {
        assumeFalse(GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance)
        runBlocking(MainUIDispatcher) {
            val moveState = mutableIntStateOf(0)
            var composedMove = -1
            var markerIdentity = 0
            var effectStarts = 0
            var disposeCalls = 0
            val panel = ComposePanel(renderSettings = renderSettings()).apply {
                isDisposeOnRemove = false
                preferredSize = Dimension(160, 120)
                setContent {
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
                    SideEffect { composedMove = move }
                }
            }
            val host = JPanel(BorderLayout()).apply { add(panel, BorderLayout.CENTER) }
            val frame = frame(host)
            try {
                delay(400)
                panel.probeSetPauseCompositionWhenNotRendering(true)
                val initialMarker = markerIdentity
                assertTrue(initialMarker != 0)
                assertEquals(1, effectStarts)
                host.remove(panel)
                delay(250)
                moveState.intValue = 1
                Snapshot.sendApplyNotifications()
                delay(100)
                assertEquals(0, composedMove)
                assertEquals(1, effectStarts)
                assertEquals(0, disposeCalls)

                host.add(panel, BorderLayout.CENTER)
                host.revalidate()
                host.repaint()
                withTimeout(5_000) { while (composedMove != 1) delay(5) }
                assertEquals(initialMarker, markerIdentity)
                assertEquals(1, effectStarts)
                assertEquals(0, disposeCalls)
            } finally {
                frame.dispose()
                panel.dispose()
            }
            delay(100)
            assertEquals(1, disposeCalls)
        }
    }

    @Test
    fun immediateReattachCompletesFrameEndAfterQueuedRecomposition() {
        assumeFalse(GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance)
        runBlocking(MainUIDispatcher) {
            val targetState = mutableIntStateOf(0)
            var composedValue = -1
            var drawnValue = -1
            var rememberCreations = 0
            var frameEndCallbacks = 0
            var composedAtFrameEnd = -1
            val panel = ComposePanel(renderSettings = renderSettings()).apply {
                isDisposeOnRemove = false
                preferredSize = Dimension(160, 120)
                setContent {
                    val value = targetState.intValue
                    remember { rememberCreations++ }
                    SideEffect { composedValue = value }
                    Canvas(Modifier.size(80.dp)) {
                        drawnValue = value
                        drawRect(if (value % 2 == 0) Color.Red else Color.Blue)
                    }
                }
            }
            val host = JPanel(BorderLayout()).apply { add(panel, BorderLayout.CENTER) }
            val frame = frame(host)
            try {
                delay(400)
                panel.probeSetPauseCompositionWhenNotRendering(true)
                val frameRecomposer = panel.probeFrameRecomposer()
                val recomposer = frameRecomposer.compositionContext as Recomposer
                targetState.intValue = 1
                Snapshot.sendApplyNotifications()
                recomposer.scheduleFrameEndCallback {
                    frameEndCallbacks++
                    composedAtFrameEnd = composedValue
                }
                frameRecomposer.performFrameDispatch()
                val frameClock = readProbeField(frameRecomposer, "frameClock")
                assertTrue(
                    frameClock.javaClass.getMethod("getHasAwaiters").invoke(frameClock) as Boolean
                )
                assertEquals(0, composedValue)
                host.remove(panel)
                host.add(panel, BorderLayout.CENTER)
                host.revalidate()
                host.repaint()

                withTimeout(5_000) {
                    while (composedValue != 1 || drawnValue != 1 || frameEndCallbacks != 1) {
                        delay(5)
                    }
                }
                assertEquals(1, composedAtFrameEnd)
                assertEquals(1, rememberCreations)
            } finally {
                frame.dispose()
                panel.dispose()
            }
        }
    }

    @Test
    fun rapidRetainedTransitionsPreserveOneComposition() {
        assumeFalse(GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance)
        runBlocking(MainUIDispatcher) {
            val targetState = mutableIntStateOf(0)
            var composedValue = -1
            var rememberCreations = 0
            var disposeCalls = 0
            val panel = ComposePanel(renderSettings = renderSettings()).apply {
                isDisposeOnRemove = false
                preferredSize = Dimension(160, 120)
                setContent {
                    val value = targetState.intValue
                    remember { rememberCreations++ }
                    SideEffect { composedValue = value }
                    DisposableEffect(Unit) { onDispose { disposeCalls++ } }
                }
            }
            val host = JPanel(BorderLayout()).apply { add(panel, BorderLayout.CENTER) }
            val frame = frame(host)
            try {
                delay(300)
                panel.probeSetPauseCompositionWhenNotRendering(true)
                repeat(10) {
                    host.remove(panel)
                    targetState.intValue++
                    Snapshot.sendApplyNotifications()
                    delay(10)
                    host.add(panel, BorderLayout.CENTER)
                    host.revalidate()
                    host.repaint()
                    delay(20)
                }
                withTimeout(5_000) { while (composedValue != 10) delay(5) }
                assertEquals(1, rememberCreations)
                assertEquals(0, disposeCalls)
                assertEquals(1, Recomposer.runningRecomposers.value.size)
            } finally {
                frame.dispose()
                panel.dispose()
            }
            delay(100)
            assertEquals(1, disposeCalls)
            assertEquals(0, Recomposer.runningRecomposers.value.size)
        }
    }

    @Test
    fun optInCanBeChangedWhileDetached() {
        assumeFalse(GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance)
        runBlocking(MainUIDispatcher) {
            val targetState = mutableIntStateOf(0)
            var composedValue = -1
            var rememberCreations = 0
            val panel = ComposePanel(renderSettings = renderSettings()).apply {
                isDisposeOnRemove = false
                preferredSize = Dimension(160, 120)
                setContent {
                    val value = targetState.intValue
                    remember { rememberCreations++ }
                    SideEffect { composedValue = value }
                }
            }
            val host = JPanel(BorderLayout()).apply { add(panel, BorderLayout.CENTER) }
            val frame = frame(host)
            try {
                delay(300)
                val recomposer = panel.probeFrameRecomposer().compositionContext as Recomposer
                host.remove(panel)
                delay(500)
                assertEquals(false, recomposer.probeSample().paused)
                panel.probeSetPauseCompositionWhenNotRendering(true)
                delay(50)
                assertEquals(true, recomposer.probeSample().paused)
                withContext(Dispatchers.Default) {
                    targetState.intValue = 9
                    Snapshot.sendApplyNotifications()
                }
                delay(100)
                assertEquals(0, composedValue)
                panel.probeSetPauseCompositionWhenNotRendering(false)
                delay(50)
                assertEquals(false, recomposer.probeSample().paused)
                panel.probeSetPauseCompositionWhenNotRendering(true)
                delay(50)
                assertEquals(true, recomposer.probeSample().paused)

                host.add(panel, BorderLayout.CENTER)
                host.revalidate()
                host.repaint()
                withTimeout(5_000) { while (composedValue != 9) delay(5) }
                assertEquals(1, rememberCreations)
            } finally {
                frame.dispose()
                panel.dispose()
            }
        }
    }

    private fun renderSettings() = when (renderer) {
        "SkiaSurface" -> RenderSettings.SkiaSurface()
        "SwingGraphics" -> RenderSettings.SwingGraphics()
        else -> error("Unknown renderer $renderer")
    }

    private fun frame(host: JPanel) = JFrame().apply {
        isUndecorated = true
        contentPane.add(host)
        pack()
        setLocation(60, 80)
        isVisible = true
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters() = listOf("SkiaSurface", "SwingGraphics").map { arrayOf(it) }
    }
}
