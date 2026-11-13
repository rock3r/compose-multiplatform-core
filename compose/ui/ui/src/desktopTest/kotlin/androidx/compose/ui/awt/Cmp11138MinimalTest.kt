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
import androidx.compose.runtime.MutableIntState
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

internal class RetentionPayload(size: Int = 1024) {
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
        var sourceReads = -1
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
                SideEffect { sourceReads = sourceTrigger.intValue }
            }
        }
        val hostA = JPanel(BorderLayout()).apply { add(panelA, BorderLayout.CENTER) }
        val hostB = JPanel(BorderLayout()).apply { add(panelB, BorderLayout.CENTER) }
        val frameA = frameForTest(hostA)
        val frameB = frameForTest(hostB, 360)
        val baselineControl = transition.endsWith("-baseline")
        val actualTransition = transition.removeSuffix("-baseline")
        val pauseExpected = !baselineControl && actualTransition != "visible" &&
            !(renderer == "SkiaSurface" && actualTransition == "zeroSize")
        fun setNonRendering(nonRendering: Boolean) {
            when (actualTransition) {
                "visible" -> Unit
                "detached" -> if (nonRendering) {
                    hostA.remove(panelA)
                } else {
                    hostA.add(panelA, BorderLayout.CENTER)
                }
                "isVisible=false" -> panelA.isVisible = !nonRendering
                "zeroSize" -> panelA.size = if (nonRendering) Dimension(0, 0) else Dimension(200, 140)
                "windowHidden" -> frameA.isVisible = !nonRendering
                else -> error("Unknown transition $actualTransition")
            }
            if (actualTransition != "zeroSize") {
                hostA.revalidate()
                hostA.repaint()
            }
        }
        suspend fun awaitSourceConsumption() {
            val expected = sourceTrigger.intValue
            withTimeout(5000) { while (sourceReads != expected) delay(5) }
        }
        suspend fun awaitSchedulerProgress(recomposer: Recomposer) {
            if (pauseExpected) {
                withTimeout(5000) {
                    while (recomposer.snapshotInvalidationsForTest() != 0 ||
                        recomposer.compositionInvalidationsForTest() == 0
                    ) {
                        delay(5)
                    }
                }
            } else {
                delay(100)
            }
        }
        try {
            delay(300)
            val frameRecomposer = panelA.frameRecomposerForTest()
            val recomposer = if (baselineControl) {
                frameRecomposer.compositionContext as Recomposer
            } else {
                panelA.enablePauseForTest()
            }
            val sceneId = System.identityHashCode(panelA.sceneForTest())
            targetState.intValue++
            Snapshot.sendApplyNotifications()
            frameRecomposer.performFrameDispatch()
            assertTrue(frameRecomposer.hasParentFrameAwaiterForTest())
            setNonRendering(true)
            delay(150)
            val pausedRecompositions = recompositions
            val pausedDrawCalls = drawCalls
            repeat(3) { batch ->
                repeat(1000) { sourceStates.add(mutableStateOf(null)) }
                sourceTrigger.intValue++
                Snapshot.sendApplyNotifications()
                awaitSourceConsumption()
                val weakPayloads = sourceStates.map { state ->
                    RetentionPayload().also { state.value = it }.let(::WeakReference)
                }
                sourceStates.clear()
                sourceTrigger.intValue++
                repeat(4) {
                    targetState.intValue++
                    Snapshot.sendApplyNotifications()
                    awaitSchedulerProgress(recomposer)
                }
                awaitSourceConsumption()
                repeat(4) {
                    System.gc()
                    delay(25)
                }
                val retained = weakPayloads.count { it.get() != null }
                if (baselineControl) {
                    assertTrue(retained > 0, "baseline must retain in $renderer/$transition")
                    assertTrue(recomposer.snapshotInvalidationsForTest() > 0,
                        "baseline invalidations must be non-empty in $renderer/$transition")
                } else {
                    assertEquals(0, retained, "$renderer/$transition batch ${batch + 1}")
                    assertEquals(0, recomposer.snapshotInvalidationsForTest(),
                        "$renderer/$transition batch ${batch + 1}")
                }
                assertTrue(ownPayload.get() != null, "target-owned live state must survive")
                if (pauseExpected) {
                    assertEquals(pausedRecompositions, recompositions,
                        "no invisible recomposition in $renderer/$transition")
                    assertEquals(pausedDrawCalls, drawCalls,
                        "no invisible drawing in $renderer/$transition")
                }
            }
            setNonRendering(false)
            withTimeout(5000) {
                while (composedValue != targetState.intValue || drawnValue != targetState.intValue) {
                    delay(5)
                }
            }
            assertEquals(sceneId, System.identityHashCode(panelA.sceneForTest()))
            assertEquals(1, rememberCreations)
            assertEquals(1, effectStarts)
            assertEquals(0, disposeCalls)
        } finally {
            frameA.dispose()
            frameB.dispose()
            panelA.dispose()
            panelB.dispose()
        }
        delay(50)
        assertEquals(1, disposeCalls)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}-{1}")
        fun parameters() = listOf(
            "visible", "detached", "isVisible=false", "zeroSize", "windowHidden",
            "detached-baseline", "windowHidden-baseline",
        ).flatMap { transition ->
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
        val panel = valuePanelForTest(
            renderer,
            targetState,
            onCompose = { composedValue = it },
            onDraw = { drawnValue = it },
            onRemember = { rememberCreations++ },
        )
        val host = JPanel(BorderLayout()).apply { add(panel, BorderLayout.CENTER) }
        val frame = frameForTest(host)
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
            assertTrue(frameRecomposer.hasParentFrameAwaiterForTest())
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

internal fun renderSettings(renderer: String) = when (renderer) {
    "SkiaSurface" -> RenderSettings.SkiaSurface()
    "SwingGraphics" -> RenderSettings.SwingGraphics()
    else -> error("Unknown renderer $renderer")
}

internal fun valuePanelForTest(
    renderer: String,
    targetState: MutableIntState,
    onCompose: (Int) -> Unit,
    onDraw: (Int) -> Unit,
    onRemember: () -> Unit,
) = ComposePanel(renderSettings = renderSettings(renderer)).apply {
    isDisposeOnRemove = false
    preferredSize = Dimension(160, 120)
    setContent {
        val value = targetState.intValue
        remember { onRemember() }
        SideEffect { onCompose(value) }
        Canvas(Modifier.size(80.dp)) {
            onDraw(value)
            drawRect(if (value % 2 == 0) Color.Red else Color.Blue)
        }
    }
}

internal fun frameForTest(host: JPanel, x: Int = 40) = JFrame().apply {
    isUndecorated = true
    contentPane.add(host)
    pack()
    setLocation(x, 80)
    isVisible = true
}

internal fun ComposePanel.frameRecomposerForTest(): FrameRecomposer {
    val container = readFieldForTest(this, "_composeContainer")
    return readFieldForTest(container, "mediator").let { mediator ->
        readFieldForTest(mediator, "frameRecomposer") as FrameRecomposer
    }
}

internal fun ComposePanel.enablePauseForTest(): Recomposer {
    isPauseCompositionWhenNotRendering = true
    return frameRecomposerForTest().compositionContext as Recomposer
}

internal fun Recomposer.snapshotInvalidationsForTest(): Int {
    val invalidations = readFieldForTest(this, "snapshotInvalidations")
    return invalidations.javaClass.getMethod("getSize").invoke(invalidations) as Int
}

internal fun Recomposer.compositionInvalidationsForTest(): Int {
    val invalidations = readFieldForTest(this, "compositionInvalidations")
    return invalidations.javaClass.getMethod("getSize").invoke(invalidations) as Int
}

internal fun ComposePanel.sceneForTest(): Any {
    val container = readFieldForTest(this, "_composeContainer")
    val mediator = readFieldForTest(container, "mediator")
    val method = mediator.javaClass.getDeclaredMethod("getScene")
    method.isAccessible = true
    return method.invoke(mediator)
}

internal fun FrameRecomposer.hasParentFrameAwaiterForTest(): Boolean {
    val frameClock = readFieldForTest(this, "frameClock")
    return frameClock.javaClass.getMethod("getHasAwaiters").invoke(frameClock) as Boolean
}

internal fun Recomposer.pauseFlagsForTest(): Pair<Boolean, Boolean> {
    val lock = readFieldForTest(this, "stateLock")
    return synchronized(lock) {
        readFieldForTest(this, "recompositionPaused") as Boolean to
            (readFieldForTest(this, "frameClockPaused") as Boolean)
    }
}

internal fun readFieldForTest(target: Any, name: String): Any {
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
