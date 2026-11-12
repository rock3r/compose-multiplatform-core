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
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.lang.ref.WeakReference
import javax.swing.JFrame
import javax.swing.JPanel
import kotlin.test.assertNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skiko.MainUIDispatcher
import org.junit.Assume.assumeFalse
import org.junit.Test

@OptIn(ExperimentalComposeUiApi::class)
class Cmp11138UnlinkTest {
    @Test
    fun explicitDisposeUnlinksAfterPause() {
        assumeFalse(GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance)
        runBlocking(MainUIDispatcher) {
            val variant = probeVariant()
            listOf("SkiaSurface", "SwingGraphics").forEach { renderer ->
                val case = detachPanel(renderer)
                delay(750)
                forceProbeGc()
                record(variant, renderer, "after-detach", case)
                disposeForgottenPanel(case)
                forceProbeGc()
                record(variant, renderer, "after-explicit-dispose", case)
                assertNull(case.container.get(), "container must be released by explicit dispose")
                assertNull(case.mediator.get(), "mediator must be released by explicit dispose")
                assertNull(case.scene.get(), "scene must be released by explicit dispose")
                assertNull(case.recomposer.get(), "frame recomposer must be released by explicit dispose")
                assertNull(case.payload.get(), "content payload must be released by explicit dispose")
                case.frame.dispose()
            }
        }
    }

    private suspend fun detachPanel(renderer: String): DetachedCase {
        var ownPayload = WeakReference<RetentionPayload>(null)
        val panel = ComposePanel(renderSettings = renderSettings(renderer)).apply {
            isDisposeOnRemove = false
            preferredSize = Dimension(160, 120)
            setContent {
                val necessary = remember {
                    RetentionPayload(32 * 1024).also { ownPayload = WeakReference(it) }
                }
                Canvas(Modifier.size(80.dp)) {
                    necessary.bytes.size
                    drawRect(Color.Red)
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
        delay(500)
        panel.probeSetPauseCompositionWhenNotRendering(true)
        val case = DetachedCase(
            frame = frame,
            panel = WeakReference(panel),
            container = WeakReference(panel.probeContainer()),
            mediator = WeakReference(panel.probeMediator()),
            scene = WeakReference(panel.probeScene()),
            recomposer = WeakReference(panel.probeFrameRecomposer()),
            payload = ownPayload,
        )
        host.remove(panel)
        host.revalidate()
        host.repaint()
        return case
    }

    private fun disposeForgottenPanel(case: DetachedCase) {
        case.panel.get()?.dispose()
    }

    private fun record(variant: String, renderer: String, phase: String, case: DetachedCase) {
        appendProbeCsv(
            "unlink.csv",
            "variant,renderer,phase,panelAlive,containerAlive,mediatorAlive,sceneAlive," +
                "frameRecomposerAlive,payloadAlive",
            listOf(
                variant,
                renderer,
                phase,
                case.panel.get() != null,
                case.container.get() != null,
                case.mediator.get() != null,
                case.scene.get() != null,
                case.recomposer.get() != null,
                case.payload.get() != null,
            ).joinToString(",") { csvValue(it) },
        )
    }

    private fun renderSettings(renderer: String) =
        when (renderer) {
            "SkiaSurface" -> RenderSettings.SkiaSurface()
            "SwingGraphics" -> RenderSettings.SwingGraphics()
            else -> error("Unknown renderer $renderer")
        }

    private data class DetachedCase(
        val frame: JFrame,
        val panel: WeakReference<ComposePanel>,
        val container: WeakReference<Any>,
        val mediator: WeakReference<Any>,
        val scene: WeakReference<Any>,
        val recomposer: WeakReference<Any>,
        val payload: WeakReference<RetentionPayload>,
    )
}
