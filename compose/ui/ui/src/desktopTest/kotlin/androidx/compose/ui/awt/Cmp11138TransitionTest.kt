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
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.lang.ref.WeakReference
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JPanel
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skiko.MainUIDispatcher
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import jdk.jfr.Configuration
import jdk.jfr.Recording

@OptIn(ExperimentalComposeUiApi::class, ComposeToolingApi::class)
@RunWith(Parameterized::class)
class Cmp11138TransitionTest(
    private val renderer: String,
    private val transition: String,
) {
    @Test
    fun profileTransition() {
        assumeFalse(GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance)
        runBlocking(MainUIDispatcher) {
            val variant = probeVariant()
            val runId = "$variant-$renderer-$transition"
            val recording = Recording(Configuration.getConfiguration("profile").settings)
            recording.name = "CMP-11138 $runId"
            recording.start()
            val targetState = mutableIntStateOf(0)
            val sourceTrigger = mutableIntStateOf(0)
            val sourceStates = ArrayList<MutableState<RetentionPayload?>>()
            val repeatedState = mutableStateOf<RetentionPayload?>(null)
            val uniquePayloads = ArrayList<WeakReference<RetentionPayload>>()
            var ownPayload = WeakReference<RetentionPayload>(null)
            var recompositions = 0
            var rememberCreations = 0
            var composedValue = -1
            var drawCalls = 0
            var drawnValue = -1
            var lastDrawNanos = 0L
            val panelA = ComposePanel(renderSettings = renderSettings()).apply {
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
                    Canvas(Modifier.size(80.dp)) {
                        drawCalls++
                        drawnValue = value
                        lastDrawNanos = System.nanoTime()
                        drawRect(if (value % 2 == 0) Color.Red else Color.Blue)
                    }
                    LaunchedEffect(necessary) { necessary.bytes[0] = 1 }
                }
            }
            val panelB = ComposePanel(renderSettings = renderSettings()).apply {
                preferredSize = Dimension(240, 140)
                setContent {
                    sourceTrigger.intValue
                    repeatedState.value
                    for (state in sourceStates) state.value
                }
            }
            val hostA = JPanel(BorderLayout()).apply { add(panelA, BorderLayout.CENTER) }
            val hostB = JPanel(BorderLayout()).apply { add(panelB, BorderLayout.CENTER) }
            val frameA = frame(hostA, 40)
            val frameB = frame(hostB, 360)
            try {
                delay(500)
                panelA.probeSetPauseCompositionWhenNotRendering(true)
                val recomposer = panelA.probeFrameRecomposer().compositionContext as androidx.compose.runtime.Recomposer
                val sceneId = System.identityHashCode(panelA.probeScene())
                val weakRepeated = ArrayList<WeakReference<RetentionPayload>>()

                fun sample(phase: String, batch: Int) {
                    val stats = recomposer.probeSample()
                    appendProbeCsv(
                        TRANSITION_CSV,
                        TRANSITION_HEADER,
                        listOf(
                            runId,
                            variant,
                            renderer,
                            transition,
                            phase,
                            batch,
                            stats.snapshotInvalidations,
                            stats.compositionInvalidations,
                            stats.paused,
                            stats.state,
                            stats.changeCount,
                            stats.runningRecomposers,
                            stats.usedHeap,
                            stats.cpuMs,
                            uniquePayloads.count { it.get() != null },
                            weakRepeated.count { it.get() != null },
                            ownPayload.get() != null,
                            recompositions,
                            drawCalls,
                            composedValue,
                            drawnValue,
                            System.currentTimeMillis(),
                        ).joinToString(",") { csvValue(it) },
                    )
                }

                transitionNonRendering(frameA, hostA, panelA)
                delay(300)
                forceProbeGc()
                sample("non-rendering-start", 0)
                val workloadStartMs = System.currentTimeMillis()
                val workloadStartCpuMs = processCpuMs()
                repeat(3) { batchIndex ->
                    repeat(1_000) { sourceStates.add(mutableStateOf(null)) }
                    sourceTrigger.intValue++
                    Snapshot.sendApplyNotifications()
                    delay(250)
                    uniquePayloads += sourceStates.map { state ->
                        RetentionPayload(1_024).also { state.value = it }.let(::WeakReference)
                    }
                    sourceStates.clear()
                    targetState.intValue++
                    sourceTrigger.intValue++
                    Snapshot.sendApplyNotifications()
                    delay(250)
                    forceProbeGc()
                    sample("unique-batch-${batchIndex + 1}", batchIndex + 1)
                    if (System.getenv("CMP11138_EXPECT_FIX") == "true" && transition != "visible") {
                        assertEquals(0, uniquePayloads.count { it.get() != null },
                            "unrelated payloads must be released after ${batchIndex + 1}")
                        assertEquals(0, recomposer.probeSample().snapshotInvalidations)
                        if (renderer != "SkiaSurface" || transition != "zeroSize") {
                            assertEquals(2, recomposer.probeSample().runningRecomposers,
                                "paused target must remain registered without cancelling its runner")
                        }
                    }
                }
                val workloadCpuMs = processCpuMs() - workloadStartCpuMs
                val workloadWallMs = System.currentTimeMillis() - workloadStartMs
                writeProbeHistogram("$runId-hidden-histogram.txt")
                appendProbeCsv(
                    WORKLOAD_CSV,
                    WORKLOAD_HEADER,
                    "$runId,$variant,$renderer,$transition,$workloadWallMs,$workloadCpuMs",
                )

                repeat(1_000) {
                    val payload = RetentionPayload(1_024)
                    repeatedState.value = payload
                    weakRepeated.add(WeakReference(payload))
                }
                Snapshot.sendApplyNotifications()
                delay(250)
                forceProbeGc()
                sample("same-state-control", 4)
                delay(300)
                forceProbeGc()
                sample("no-mutation-control", 5)
                assertTrue(ownPayload.get() != null, "target-owned live state must survive")
                if (transition == "visible") {
                    assertEquals(0, uniquePayloads.count { it.get() != null })
                    assertTrue(weakRepeated.count { it.get() != null } <= 1)
                }

                if (transition != "visible") {
                    repeat(5) { repetition ->
                        val beforeRecompositions = recompositions
                        val beforeDrawCalls = drawCalls
                        targetState.intValue++
                        Snapshot.sendApplyNotifications()
                        delay(100)
                        val resumeStart = System.nanoTime()
                        val expected = targetState.intValue
                        val neededResumeWork = drawnValue != expected || composedValue != expected
                        transitionRendering(frameA, hostA, panelA)
                        var resumeCompleted = true
                        try {
                            withTimeout(5_000) {
                                while (drawnValue != expected || composedValue != expected) delay(2)
                            }
                        } catch (_: TimeoutCancellationException) {
                            resumeCompleted = false
                        }
                        val latency = when {
                            !resumeCompleted -> -1L
                            neededResumeWork -> lastDrawNanos - resumeStart
                            else -> 0L
                        }
                        var actualColor: Any = ""
                        if (resumeCompleted) {
                            val image = assertNotNull(panelA.captureContentToImage())
                            val expectedColor = (if (expected % 2 == 0) Color.Red else Color.Blue).toArgb()
                            actualColor = image.getRGB(5, 5)
                            assertEquals(expectedColor, actualColor, "latest-state first draw pixel")
                            if (repetition == 0) {
                                ImageIO.write(
                                    image,
                                    "png",
                                    probeOutputDir().resolve("$runId-resumed.png").toFile(),
                                )
                            }
                        }
                        assertEquals(sceneId, System.identityHashCode(panelA.probeScene()))
                        assertEquals(1, rememberCreations)
                        if (System.getenv("CMP11138_EXPECT_FIX") == "true" && resumeCompleted &&
                            (renderer != "SkiaSurface" || transition != "zeroSize")
                        ) {
                            assertEquals(2, recomposer.probeSample().runningRecomposers,
                                "visible target and source must each have one runner")
                        }
                        var recoveredWithRepaint = false
                        if (!resumeCompleted) {
                            frameA.repaint()
                            panelA.repaint()
                            try {
                                withTimeout(5_000) {
                                    while (drawnValue != expected || composedValue != expected) delay(2)
                                }
                                recoveredWithRepaint = true
                            } catch (_: TimeoutCancellationException) {
                                // Record the failed recovery and continue the remaining cycles.
                            }
                            println(
                                "CMP11138_RESUME_STALL run=$runId repetition=${repetition + 1} " +
                                    "expected=$expected composed=$composedValue drawn=$drawnValue " +
                                    "frameState=${frameA.extendedState} panelShowing=${panelA.isShowing} " +
                                    "panelDisplayable=${panelA.isDisplayable} panelSize=${panelA.size} " +
                                    "recomposer=${recomposer.probeSample()}"
                            )
                        }
                        appendProbeCsv(
                            RESUME_CSV,
                            RESUME_HEADER,
                            listOf(
                                runId,
                                variant,
                                renderer,
                                transition,
                                repetition + 1,
                                latency,
                                neededResumeWork,
                                resumeCompleted,
                                recoveredWithRepaint,
                                recompositions - beforeRecompositions,
                                drawCalls - beforeDrawCalls,
                                sceneId,
                                rememberCreations,
                                actualColor,
                            ).joinToString(",") { csvValue(it) },
                        )
                        val nativeRestoreStalled = transition == "minimized" &&
                            frameA.extendedState and Frame.ICONIFIED != 0
                        if (System.getenv("CMP11138_EXPECT_FIX") == "true" &&
                            !nativeRestoreStalled
                        ) {
                            assertTrue(resumeCompleted, "resume must complete without a repaint")
                        }
                        delay(100)
                        transitionNonRendering(frameA, hostA, panelA)
                        delay(150)
                    }
                }
            } finally {
                recording.dump(probeOutputDir().resolve("$runId.jfr"))
                recording.stop()
                recording.close()
                frameA.dispose()
                frameB.dispose()
                panelA.dispose()
                panelB.dispose()
            }
        }
    }

    private fun renderSettings(): RenderSettings =
        when (renderer) {
            "SkiaSurface" -> RenderSettings.SkiaSurface()
            "SwingGraphics" -> RenderSettings.SwingGraphics()
            else -> error("Unknown renderer $renderer")
        }

    private fun frame(host: JPanel, x: Int) = JFrame().apply {
        isUndecorated = true
        contentPane.add(host)
        pack()
        setLocation(x, 80)
        isVisible = true
    }

    private suspend fun transitionNonRendering(frame: JFrame, host: JPanel, panel: ComposePanel) {
        when (transition) {
            "visible" -> Unit
            "detached" -> {
                host.remove(panel)
                host.revalidate()
                host.repaint()
            }
            "isVisible=false" -> panel.isVisible = false
            "zeroSize" -> panel.size = Dimension(0, 0)
            "minimized" -> {
                frame.extendedState = Frame.ICONIFIED
                withTimeout(5_000) {
                    while (frame.extendedState and Frame.ICONIFIED == 0) delay(10)
                }
            }
            "windowHidden" -> frame.isVisible = false
            else -> error("Unknown transition $transition")
        }
    }

    private suspend fun transitionRendering(frame: JFrame, host: JPanel, panel: ComposePanel) {
        when (transition) {
            "detached" -> {
                host.add(panel, BorderLayout.CENTER)
                host.revalidate()
                host.repaint()
            }
            "isVisible=false" -> panel.isVisible = true
            "zeroSize" -> panel.size = Dimension(200, 140)
            "minimized" -> {
                frame.extendedState = Frame.NORMAL
                withTimeout(5_000) {
                    while (frame.extendedState and Frame.ICONIFIED != 0) delay(10)
                }
            }
            "windowHidden" -> frame.isVisible = true
            else -> error("Cannot resume transition $transition")
        }
    }

    companion object {
        private const val TRANSITION_CSV = "transition-samples.csv"
        private const val TRANSITION_HEADER =
            "run,variant,renderer,transition,phase,batch,snapshotInvalidations," +
                "compositionInvalidations,paused,state,changeCount,runningRecomposers,usedHeap," +
                "processCpuMs,uniquePayloadsAlive,repeatedPayloadsAlive,ownPayloadAlive," +
                "recompositions,drawCalls,composedValue,drawnValue,wallTimeMs"
        private const val WORKLOAD_CSV = "workload.csv"
        private const val WORKLOAD_HEADER =
            "run,variant,renderer,transition,wallMs,processCpuMs"
        private const val RESUME_CSV = "resume-latencies.csv"
        private const val RESUME_HEADER =
            "run,variant,renderer,transition,repetition,resumeToLatestDrawNanos,neededResumeWork," +
                "resumeCompleted,recoveredWithRepaint,recompositions,drawCalls,sceneId,rememberCreations,pixel"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}-{1}")
        fun parameters(): List<Array<Any>> =
            listOf("SkiaSurface", "SwingGraphics").flatMap { renderer ->
                listOf("visible", "detached", "isVisible=false", "zeroSize", "minimized", "windowHidden")
                    .map { transition -> arrayOf(renderer, transition) }
            }
    }
}
