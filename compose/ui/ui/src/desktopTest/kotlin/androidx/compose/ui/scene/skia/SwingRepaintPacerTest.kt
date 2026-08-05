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

package androidx.compose.ui.scene.skia

import androidx.compose.ui.ComposeFeatureFlags
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test

class SwingRepaintPacerTest {
    private lateinit var frame: JFrame
    private lateinit var panel: RepaintCountingPanel
    private var pacer: SwingRepaintPacer? = null

    @Before
    fun setUp() {
        assumeFalse(GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance)
        onEdt {
            frame = JFrame()
            panel = RepaintCountingPanel()
        }
    }

    /**
     * Adds the panel to the frame and makes it displayable. Kept out of [setUp] so that tests can
     * create the pacer while the panel has no graphics configuration yet (a parentless component).
     */
    private fun attachAndPack() {
        frame.contentPane.add(panel)
        frame.pack()
    }

    @After
    fun tearDown() {
        if (::frame.isInitialized) {
            onEdt {
                pacer?.dispose()
                frame.dispose()
            }
        }
    }

    @Test
    fun subscribesOnceWhenComponentBecomesDisplayable() {
        val service = FakeFramePacingService()
        onEdt {
            pacer = SwingRepaintPacer(panel, service)
            // No graphics configuration yet (parentless component), so no subscription.
            assertEquals(0, service.subscribeCount)
            attachAndPack()
        }
        onEdt {
            // pack() fires both the displayability and the graphicsConfiguration events, but an
            // unchanged display must not cause subscription churn.
            assertEquals(1, service.subscribeCount)
            assertEquals(listOf(DISPLAY_A), service.subscribedDisplayIds)
            assertEquals(0, service.closeCount)
        }
    }

    @Test
    fun closesSubscriptionOnDisposeAndPassesThroughAfterwards() {
        val service = FakeFramePacingService()
        onEdt {
            pacer = SwingRepaintPacer(panel, service)
            attachAndPack()
        }
        onEdt {
            pacer?.dispose()
            assertEquals(1, service.closeCount)
        }
        onEdt {
            val before = panel.repaintCount.get()
            pacer?.requestRepaint()
            assertEquals(before + 1, panel.repaintCount.get())
        }
    }

    @Test
    fun passesThroughWhenServiceIsAbsent() {
        onEdt {
            pacer = SwingRepaintPacer(panel, service = null)
            attachAndPack()
        }
        onEdt {
            val before = panel.repaintCount.get()
            pacer?.requestRepaint()
            assertEquals(before + 1, panel.repaintCount.get())
        }
    }

    @Test
    fun passesThroughWhenDisplayCannotBePaced() {
        val service = FakeFramePacingService().apply { refuseSubscriptions = true }
        onEdt {
            pacer = SwingRepaintPacer(panel, service)
            attachAndPack()
        }
        onEdt {
            assertTrue(service.subscribeAttempts > 0)
            assertEquals(0, service.subscribeCount)
            val before = panel.repaintCount.get()
            pacer?.requestRepaint()
            assertEquals(before + 1, panel.repaintCount.get())
        }
    }

    @Test
    fun coalescesRepaintsOntoTicks() {
        // A huge refresh period keeps the stale-tick watchdog out of this test.
        val service = FakeFramePacingService().apply { periodNanos = 1_000_000_000L }
        onEdt {
            pacer = SwingRepaintPacer(panel, service)
            attachAndPack()
        }
        val baseline = onEdtGet { panel.repaintCount.get() }

        // Multiple requests while paced: no direct repaint.
        onEdt { repeat(3) { pacer?.requestRepaint() } }
        drainEdt()
        assertEquals(baseline, onEdtGet { panel.repaintCount.get() })

        // One tick releases exactly one repaint.
        service.tick()
        drainEdt()
        assertEquals(baseline + 1, onEdtGet { panel.repaintCount.get() })

        // A tick with nothing pending releases nothing.
        service.tick()
        drainEdt()
        assertEquals(baseline + 1, onEdtGet { panel.repaintCount.get() })

        // The next request waits for the next tick again.
        onEdt { pacer?.requestRepaint() }
        drainEdt()
        assertEquals(baseline + 1, onEdtGet { panel.repaintCount.get() })
        service.tick()
        drainEdt()
        assertEquals(baseline + 2, onEdtGet { panel.repaintCount.get() })
    }

    @Test
    fun watchdogRepaintsDirectlyAndResubscribesWhenTicksStall() {
        // 5 ms period puts the watchdog deadline at ~15 ms.
        val service = FakeFramePacingService().apply { periodNanos = 5_000_000L }
        onEdt {
            pacer = SwingRepaintPacer(panel, service)
            attachAndPack()
        }
        val baseline = onEdtGet { panel.repaintCount.get() }

        onEdt { pacer?.requestRepaint() }
        // No ticks arrive: the watchdog must issue the repaint directly...
        waitUntil { onEdtGet { panel.repaintCount.get() } == baseline + 1 }
        // ...and recreate the suspected-stale subscription.
        waitUntil { onEdtGet { service.subscribeCount } == 2 }
        onEdt { assertEquals(1, service.closeCount) }
    }

    @Test
    fun resubscribesWhenTheDisplayChanges() {
        val service = FakeFramePacingService()
        onEdt {
            pacer = SwingRepaintPacer(panel, service)
            attachAndPack()
        }
        onEdt {
            service.displayIdToReturn = DISPLAY_B
            // Simulates what the graphicsConfiguration/hierarchy listeners invoke on a change.
            pacer?.refreshSubscription()
            assertEquals(listOf(DISPLAY_A, DISPLAY_B), service.subscribedDisplayIds)
            assertEquals(1, service.closeCount)

            // Unchanged display: refresh must not churn the subscription.
            pacer?.refreshSubscription()
            assertEquals(2, service.subscribeCount)
            assertEquals(1, service.closeCount)
        }
    }

    @Test
    fun featureFlagDefaultsToFalse() {
        assertFalse(ComposeFeatureFlags.useSwingFramePacing.value)
    }

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private fun <T> onEdtGet(block: () -> T): T {
        var result: T? = null
        SwingUtilities.invokeAndWait { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** Waits for all events queued on the EDT so far (including tick-scheduled flushes). */
    private fun drainEdt() = SwingUtilities.invokeAndWait {}

    private fun waitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), "Condition not met within $timeoutMillis ms")
    }

    /** Counts the no-argument [java.awt.Component.repaint] calls the pacer issues. */
    private class RepaintCountingPanel : JPanel() {
        val repaintCount = AtomicInteger()

        override fun repaint() {
            // JPanel's constructor calls repaint() before this class's fields are initialized.
            @Suppress("UNNECESSARY_SAFE_CALL", "SENSELESS_COMPARISON")
            repaintCount?.incrementAndGet()
            super.repaint()
        }
    }

    private class FakeFramePacingService : FramePacingService {
        var displayIdToReturn = DISPLAY_A
        var periodNanos = 1_000_000_000L
        var refuseSubscriptions = false

        @Volatile var subscribeAttempts = 0

        @Volatile var subscribeCount = 0

        @Volatile var closeCount = 0

        val subscribedDisplayIds = mutableListOf<Long>()

        private var listener: ((Long, Long) -> Unit)? = null

        override fun displayId(graphicsConfiguration: GraphicsConfiguration): Long =
            displayIdToReturn

        override fun refreshPeriodNanos(displayId: Long): Long = periodNanos

        override fun subscribe(
            displayId: Long,
            onTick: (displayId: Long, timeNanos: Long) -> Unit,
        ): AutoCloseable? {
            subscribeAttempts++
            if (refuseSubscriptions) return null
            subscribedDisplayIds += displayId
            listener = onTick
            subscribeCount++
            return AutoCloseable {
                if (listener === onTick) {
                    listener = null
                }
                closeCount++
            }
        }

        /** Delivers a tick the way the real service does: from a non-EDT thread. */
        fun tick() {
            check(!SwingUtilities.isEventDispatchThread())
            listener?.invoke(displayIdToReturn, System.nanoTime())
        }
    }

    private companion object {
        const val DISPLAY_A = 1L
        const val DISPLAY_B = 2L
    }
}
