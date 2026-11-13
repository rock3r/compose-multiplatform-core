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
    androidx.compose.runtime.InternalComposeApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
)

package androidx.compose.ui.awt

import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import java.awt.BorderLayout
import java.awt.GraphicsEnvironment
import java.util.concurrent.CountDownLatch
import javax.swing.JPanel
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skiko.MainUIDispatcher
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class Cmp11138SchedulerTest(private val renderer: String) {
    @Test
    fun legacyClockOrderingPreservesQueuedWorkAndFlags() = runBlocking(MainUIDispatcher) {
        assumeFalse(GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance)
        val targetState = mutableIntStateOf(0)
        var composedValue = -1
        var drawnValue = -1
        var rememberCreations = 0
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
            val recomposer = panel.enablePauseForTest()
            val sceneId = System.identityHashCode(panel.sceneForTest())
            host.remove(panel)
            delay(150)
            targetState.intValue = 42
            Snapshot.sendApplyNotifications()
            withTimeout(5000) {
                while (recomposer.compositionInvalidationsForTest() == 0) delay(5)
            }
            recomposer.resumeCompositionFrameClock()
            delay(50)
            assertEquals(true to false, recomposer.pauseFlagsForTest())
            assertEquals(0, composedValue)

            host.add(panel, BorderLayout.CENTER)
            host.revalidate()
            host.repaint()
            withTimeout(5000) {
                while (composedValue != 42 || drawnValue != 42) delay(5)
            }
            assertEquals(1, rememberCreations)
            assertEquals(sceneId, System.identityHashCode(panel.sceneForTest()))
            assertEquals(false to false, recomposer.pauseFlagsForTest())

            recomposer.pauseCompositionFrameClock()
            assertEquals(false to true, recomposer.pauseFlagsForTest())
            targetState.intValue = 43
            Snapshot.sendApplyNotifications()
            panel.repaint()
            withTimeout(5000) {
                while (composedValue != 43 || drawnValue != 43) delay(5)
            }
            recomposer.resumeCompositionFrameClock()
            assertEquals(false to false, recomposer.pauseFlagsForTest())
        } finally {
            frame.dispose()
            panel.dispose()
        }
    }

    @Test
    fun concurrentTransitionsPreserveQueuedWork() = runBlocking(MainUIDispatcher) {
        assumeFalse(GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance)
        val targetState = mutableIntStateOf(0)
        var composedValue = -1
        var drawnValue = -1
        var rememberCreations = 0
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
            val recomposer = panel.enablePauseForTest()
            val sceneId = System.identityHashCode(panel.sceneForTest())
            repeat(20) {
                host.remove(panel)
                delay(20)
                targetState.intValue++
                Snapshot.sendApplyNotifications()
                withTimeout(5000) {
                    while (recomposer.compositionInvalidationsForTest() == 0) delay(5)
                }
                val start = CountDownLatch(1)
                val pauser = thread(name = "cmp11138-pauser") {
                    start.await()
                    recomposer.pauseRecomposition()
                }
                val resumer = thread(name = "cmp11138-resumer") {
                    start.await()
                    recomposer.resumeRecomposition()
                }
                start.countDown()
                pauser.join()
                resumer.join()
                val (recompositionPaused, frameClockPaused) = recomposer.pauseFlagsForTest()
                assertEquals(recompositionPaused, frameClockPaused)
                host.add(panel, BorderLayout.CENTER)
                host.revalidate()
                host.repaint()
                withTimeout(5000) {
                    while (composedValue != targetState.intValue ||
                        drawnValue != targetState.intValue
                    ) {
                        delay(5)
                    }
                }
                assertEquals(1, rememberCreations)
                assertEquals(sceneId, System.identityHashCode(panel.sceneForTest()))
            }
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
