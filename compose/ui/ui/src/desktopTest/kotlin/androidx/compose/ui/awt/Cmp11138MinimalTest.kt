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
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package androidx.compose.ui.awt

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.ComposeSceneMediator
import androidx.compose.ui.unit.dp
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.lang.ref.WeakReference
import javax.swing.JFrame
import javax.swing.JPanel
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skiko.MainUIDispatcher
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private class RetentionPayload(size: Int = 1024) {
    val bytes = ByteArray(size)
}

@RunWith(Parameterized::class)
class Cmp11138MinimalTest(private val renderer: String, private val transition: String) {
    @Test
    fun nonRenderingTransitionsReleaseUnrelatedStateAndResumeLatest() = runBlocking(MainUIDispatcher) {
        assumeFalse(GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance)
        val targetState = mutableIntStateOf(0)
        val sourceTrigger = mutableIntStateOf(0)
        val sourceStates = ArrayList<MutableState<RetentionPayload?>>()
        var ownPayload = WeakReference<RetentionPayload>(null)
        var rememberCreations = 0
        var effectStarts = 0
        var disposeCalls = 0
        var recompositions = 0
        var drawCalls = 0
        var composedValue = -1
        var drawnValue = -1
        val panelA = ComposePanel(renderSettings = renderSettings(renderer)).apply {
            isDisposeOnRemove = false
            preferredSize = Dimension(200, 140)
            setContent {
                val value = targetState.intValue
                val necessary = remember {
                    rememberCreations++
                    RetentionPayload(16 * 1024).also { ownPayload = WeakReference(it) }
                }
                SideEffect {
                    recompositions++
                    composedValue = value
                    necessary.bytes.size
                }
                LaunchedEffect(Unit) { effectStarts++ }
                DisposableEffect(Unit) { onDispose { disposeCalls++ } }
                Canvas(Modifier.size(80.dp)) {
                    drawCalls++
                    drawnValue = value
                    drawRect(if (value % 2 == 0) Color.Red else Color.Blue)
                }
            }
        }
        val panelB = ComposePanel(renderSettings = renderSettings(renderer)).apply {
            preferredSize = Dimension(200, 100)
            setContent {
                sourceTrigger.intValue
                for (state in sourceStates) state.value
            }
        }
        val host = JPanel(BorderLayout()).apply {
            add(panelA, BorderLayout.NORTH)
            add(panelB, BorderLayout.SOUTH)
        }
        val frame = JFrame().apply {
            isUndecorated = true
            contentPane.add(host)
            pack()
            isVisible = true
        }
        fun setNonRendering(nonRendering: Boolean) {
            when (transition) {
                "visible" -> Unit
                "detached" -> if (nonRendering) {
                    host.remove(panelA)
                } else {
                    host.add(panelA, BorderLayout.NORTH)
                }
                "isVisible=false" -> panelA.isVisible = !nonRendering
                "zeroSize" -> panelA.size = if (nonRendering) Dimension(0, 0) else Dimension(200, 140)
                "windowHidden" -> frame.isVisible = !nonRendering
                else -> error("Unknown transition $transition")
            }
            host.revalidate()
            host.repaint()
        }
        try {
            delay(300)
            val recomposer = panelA.enablePauseForTest()
            setNonRendering(true)
            delay(150)
            val pausedRecompositions = recompositions
            val pausedDrawCalls = drawCalls
            repeat(3) { batch ->
                repeat(1000) { sourceStates.add(mutableStateOf(null)) }
                sourceTrigger.intValue++
                Snapshot.sendApplyNotifications()
                delay(100)
                val weakPayloads = sourceStates.map { state ->
                    RetentionPayload().also { state.value = it }.let(::WeakReference)
                }
                sourceStates.clear()
                sourceTrigger.intValue++
                Snapshot.sendApplyNotifications()
                delay(100)
                repeat(4) {
                    System.gc()
                    delay(25)
                }
                assertEquals(0, weakPayloads.count { it.get() != null },
                    "$renderer/$transition batch ${batch + 1}")
                assertEquals(0, recomposer.snapshotInvalidationsForTest(),
                    "$renderer/$transition batch ${batch + 1}")
                assertTrue(ownPayload.get() != null, "target-owned live state must survive")
                if (transition != "visible" && !(renderer == "SkiaSurface" && transition == "zeroSize")) {
                    assertEquals(pausedRecompositions, recompositions,
                        "no invisible recomposition in $renderer/$transition")
                    assertEquals(pausedDrawCalls, drawCalls,
                        "no invisible drawing in $renderer/$transition")
                }
            }
            targetState.intValue++
            Snapshot.sendApplyNotifications()
            setNonRendering(false)
            withTimeout(5000) {
                while (composedValue != targetState.intValue || drawnValue != targetState.intValue) {
                    delay(5)
                }
            }
            assertEquals(1, rememberCreations)
            assertEquals(1, effectStarts)
            assertEquals(0, disposeCalls)
        } finally {
            frame.dispose()
            panelA.dispose()
            panelB.dispose()
        }
        delay(50)
        assertEquals(1, disposeCalls)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}-{1}")
        fun parameters() = listOf("visible", "detached", "isVisible=false", "zeroSize", "windowHidden")
            .flatMap { transition ->
                listOf("SkiaSurface", "SwingGraphics").map { renderer -> arrayOf(renderer, transition) }
            }
    }
}

@RunWith(Parameterized::class)
class Cmp11138FrameRaceTest(private val renderer: String) {
    @Test
    fun immediateReattachCompletesFrameEndAfterQueuedRecomposition() = runBlocking(MainUIDispatcher) {
        assumeFalse(GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance)
        val targetState = mutableIntStateOf(0)
        var composedValue = -1
        var drawnValue = -1
        var rememberCreations = 0
        var frameEndCallbacks = 0
        var composedAtFrameEnd = -1
        val panel = ComposePanel(renderSettings = renderSettings(renderer)).apply {
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
        val frame = JFrame().apply {
            isUndecorated = true
            contentPane.add(host)
            pack()
            isVisible = true
        }
        try {
            delay(300)
            val frameRecomposer = panel.frameRecomposerForTest()
            val recomposer = panel.enablePauseForTest()
            targetState.intValue = 1
            Snapshot.sendApplyNotifications()
            recomposer.scheduleFrameEndCallback {
                frameEndCallbacks++
                composedAtFrameEnd = composedValue
            }
            frameRecomposer.performFrameDispatch()
            val frameClock = readFieldForTest(frameRecomposer, "frameClock")
            assertTrue(frameClock.javaClass.getMethod("getHasAwaiters").invoke(frameClock) as Boolean)
            assertEquals(0, composedValue)
            host.remove(panel)
            host.add(panel, BorderLayout.CENTER)
            host.revalidate()
            host.repaint()
            withTimeout(5000) {
                while (composedValue != 1 || drawnValue != 1 || frameEndCallbacks != 1) delay(5)
            }
            assertEquals(1, composedAtFrameEnd)
            assertEquals(1, rememberCreations)
        } finally {
            frame.dispose()
            panel.dispose()
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters() = listOf("SkiaSurface", "SwingGraphics").map { arrayOf(it) }
    }
}

private fun renderSettings(renderer: String) = when (renderer) {
    "SkiaSurface" -> RenderSettings.SkiaSurface()
    "SwingGraphics" -> RenderSettings.SwingGraphics()
    else -> error("Unknown renderer $renderer")
}

private fun ComposePanel.frameRecomposerForTest(): FrameRecomposer {
    val container = readFieldForTest(this, "_composeContainer")
    return readFieldForTest(container, "mediator").let { mediator ->
        readFieldForTest(mediator, "frameRecomposer") as FrameRecomposer
    }
}

private fun ComposePanel.enablePauseForTest(): Recomposer {
    val container = readFieldForTest(this, "_composeContainer")
    val mediator = readFieldForTest(container, "mediator") as ComposeSceneMediator
    mediator.pauseCompositionWhenNotRendering = true
    return frameRecomposerForTest().compositionContext as Recomposer
}

private fun Recomposer.snapshotInvalidationsForTest(): Int {
    val invalidations = readFieldForTest(this, "snapshotInvalidations")
    return invalidations.javaClass.getMethod("getSize").invoke(invalidations) as Int
}

private fun readFieldForTest(target: Any, name: String): Any {
    var type: Class<*>? = target.javaClass
    while (type != null) {
        try {
            val field = type.getDeclaredField(name)
            field.isAccessible = true
            return field.get(target)
        } catch (_: NoSuchFieldException) {
            type = type.superclass
        }
    }
    error("Field $name not found on ${target.javaClass.name}")
}
