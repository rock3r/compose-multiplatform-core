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

import androidx.compose.ui.scene.skia.SwingRepaintPacer.Companion.WATCHDOG_PERIODS
import java.awt.Component
import java.awt.EventQueue
import java.awt.GraphicsConfiguration
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.beans.PropertyChangeListener
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Timer
import kotlin.math.ceil

/**
 * Paces Compose invalidation-driven repaints of a Swing component to the display refresh, using the
 * JBR `FramePacing` service (available in JetBrains Runtime builds that provide
 * `com.jetbrains.FramePacing`).
 *
 * Without pacing, a continuously invalidating scene (e.g., a draw-phase animation) calls
 * [Component.repaint] as fast as the EDT can complete paint cycles, rendering far above the display
 * refresh rate and burning GPU on frames that are never shown. With pacing, repaint requests only
 * set a pending flag; the actual [Component.repaint] happens on the next FramePacing tick (roughly
 * once per display refresh), coalescing to at most one repaint per tick.
 *
 * Behavior:
 * - If the JBR API or the FramePacing service is unavailable, or the component's display cannot be
 *   paced, requests pass through to [Component.repaint] unchanged.
 * - The display id is resolved from the component's [GraphicsConfiguration] and re-resolved when
 *   the graphics configuration changes (window moved to another display) or the component's
 *   displayability changes.
 * - Stale-tick watchdog: if a pending repaint sees no tick within ~3 refresh periods, the repaint
 *   is issued directly and the subscription is re-created. If re-subscription fails, the pacer
 *   deactivates and passes requests through.
 *
 * Note: this only gates the [Component.repaint] path (Compose invalidation). Paints initiated by
 * Swing itself (resize, expose, `paintImmediately`) are not affected.
 *
 * [requestRepaint], [dispose] and [refreshSubscription] must be called on the event dispatch
 * thread.
 */
internal class SwingRepaintPacer(
    private val component: Component,
    private val service: FramePacingService? = JbrFramePacingApi.instance,
) {
    /** Set on the EDT when a repaint is requested; cleared when the repaint is issued. */
    private val pendingRepaint = AtomicBoolean(false)

    /** Limits the tick thread to one queued EDT flush at a time. */
    private val flushScheduled = AtomicBoolean(false)

    /** True while a live subscription exists. Written on the EDT, read from the tick thread. */
    @Volatile private var active = false

    private var disposed = false
    private var subscription: AutoCloseable? = null
    private var subscribedDisplayId = UNKNOWN_DISPLAY_ID

    /**
     * Issues the pending repaint directly and re-subscribes if no tick arrived within
     * ~[WATCHDOG_PERIODS] refresh periods of a request.
     */
    private val watchdog =
        Timer(DEFAULT_WATCHDOG_DELAY_MILLIS, { onWatchdogTimeout() }).apply {
            isRepeats = false
        }

    private val graphicsConfigurationListener = PropertyChangeListener {
        refreshSubscription()
    }

    private val hierarchyListener = HierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L) {
            refreshSubscription()
        }
    }

    init {
        if (service != null) {
            component.addPropertyChangeListener(
                "graphicsConfiguration",
                graphicsConfigurationListener,
            )
            component.addHierarchyListener(hierarchyListener)
            refreshSubscription()
        }
    }

    /**
     * Requests a repaint of the component. If pacing is active, the repaint is deferred to the next
     * FramePacing tick (at most one repaint per tick); otherwise it is issued immediately.
     */
    fun requestRepaint() {
        if (!active || disposed) {
            component.repaint()
            return
        }

        pendingRepaint.set(true)

        // No-op if the watchdog is already running, so the deadline of the earliest
        // still-pending request is kept.
        watchdog.start()
    }

    fun dispose() {
        if (disposed) return

        disposed = true
        closeSubscription()
        watchdog.stop()
        pendingRepaint.set(false)

        if (service != null) {
            component.removePropertyChangeListener(
                "graphicsConfiguration",
                graphicsConfigurationListener,
            )

            component.removeHierarchyListener(hierarchyListener)
        }
    }

    /**
     * Re-resolves the component's display and re-creates the tick subscription if the display
     * changed. If the display cannot be resolved or paced, the pacer deactivates, issuing any
     * pending repaint directly.
     *
     * @param force when true, the subscription is re-created even for an unchanged display (used
     *   when ticks are suspected stale)
     */
    internal fun refreshSubscription(force: Boolean = false) {
        if (disposed) return

        val displayId = resolveDisplayId()
        val displayUnchanged = displayId != UNKNOWN_DISPLAY_ID && displayId == subscribedDisplayId
        if (!force && active && displayUnchanged) {
            return
        }

        closeSubscription()

        val service = service
        if (service == null || displayId == UNKNOWN_DISPLAY_ID) {
            flushPendingRepaint()
            return
        }

        watchdog.initialDelay = watchdogDelayMillisFor(service.refreshPeriodNanos(displayId))

        val subscription = service.subscribe(displayId) { _, _ -> onTick() }
        if (subscription == null) {
            flushPendingRepaint()
            return
        }

        this.subscription = subscription
        this.subscribedDisplayId = displayId
        active = true

        if (pendingRepaint.get()) {
            watchdog.start()
        }
    }

    /** Called by the FramePacing service on a non-EDT thread; must return immediately. */
    private fun onTick() {
        if (!pendingRepaint.get()) return

        if (flushScheduled.compareAndSet(false, true)) {
            EventQueue.invokeLater {
                flushScheduled.set(false)

                if (!disposed) {
                    flushPendingRepaint()
                }
            }
        }
    }

    /** Issues the pending repaint, if any. Called on the EDT. */
    private fun flushPendingRepaint() {
        if (pendingRepaint.compareAndSet(true, false)) {
            watchdog.stop()
            component.repaint()
        }
    }

    private fun onWatchdogTimeout() {
        if (disposed) return

        if (pendingRepaint.compareAndSet(true, false)) {
            component.repaint()

            // Ticks are stale: the clock should beat a ~3-period deadline whenever it is
            // running. Recreate the subscription; on failure the pacer deactivates and
            // requests pass through.
            refreshSubscription(force = true)
        }
    }

    private fun resolveDisplayId(): Long {
        val service = service ?: return UNKNOWN_DISPLAY_ID
        val graphicsConfiguration = component.graphicsConfiguration ?: return UNKNOWN_DISPLAY_ID
        return service.displayId(graphicsConfiguration)
    }

    private fun closeSubscription() {
        active = false
        subscription?.close()
        subscription = null
        subscribedDisplayId = UNKNOWN_DISPLAY_ID
    }

    private fun watchdogDelayMillisFor(periodNanos: Long): Int =
        if (periodNanos > 0) {
            ceil(WATCHDOG_PERIODS * periodNanos / 1_000_000.0)
                .toInt()
                .coerceAtLeast(MIN_WATCHDOG_DELAY_MILLIS)
        } else {
            DEFAULT_WATCHDOG_DELAY_MILLIS
        }

    companion object {
        private const val UNKNOWN_DISPLAY_ID = -1L
        private const val WATCHDOG_PERIODS = 3

        /** Watchdog delay when the refresh period is unknown: 3 periods at 60 Hz. */
        private const val DEFAULT_WATCHDOG_DELAY_MILLIS = 50
        private const val MIN_WATCHDOG_DELAY_MILLIS = 10
    }
}

/**
 * The subset of the JBR `com.jetbrains.FramePacing` service that [SwingRepaintPacer] uses.
 * Abstracted so that tests can drive the pacer with a controllable tick source; production code
 * uses [JbrFramePacingApi], which adapts the real service reflectively until the JBR API catches up
 * and deploys the real FramePacing service accessor.
 */
internal interface FramePacingService {
    /** Returns the stable id of the display showing [graphicsConfiguration], or -1 if unknown. */
    fun displayId(graphicsConfiguration: GraphicsConfiguration): Long

    /** Returns the nominal refresh period of the display in nanoseconds, or 0 if unknown. */
    fun refreshPeriodNanos(displayId: Long): Long

    /**
     * Subscribes [onTick] to refresh ticks of [displayId]. [onTick] is invoked on an arbitrary
     * non-EDT thread. Returns a handle that closes the subscription, or null if the display cannot
     * be paced.
     */
    fun subscribe(
        displayId: Long,
        onTick: (displayId: Long, timeNanos: Long) -> Unit,
    ): AutoCloseable?
}

/**
 * Reflection-based access to the JBR `com.jetbrains.FramePacing` service, so that this module does
 * not need a compile-time dependency on a jbr-api version that provides it. Resolved once;
 * [instance] is null when the API classes are absent from the classpath or the runtime does not
 * provide the service.
 *
 * TODO replace with direct FramePacing service accessor once JBR-API 1.11.0+ ships.
 */
private class JbrFramePacingApi private constructor(
    private val service: Any,
    private val displayIdMethod: Method,
    private val refreshPeriodNanosMethod: Method,
    private val subscribeMethod: Method,
    private val listenerClass: Class<*>,
    private val subscriptionCloseMethod: Method,
) : FramePacingService {

    override fun displayId(graphicsConfiguration: GraphicsConfiguration): Long =
        displayIdMethod.invoke(service, graphicsConfiguration) as Long

    override fun refreshPeriodNanos(displayId: Long): Long =
        refreshPeriodNanosMethod.invoke(service, displayId) as Long

    override fun subscribe(
        displayId: Long,
        onTick: (displayId: Long, timeNanos: Long) -> Unit,
    ): AutoCloseable? {
        val listener =
            Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { proxy, method, args ->
                when (method.name) {
                    "onTick" -> {
                        onTick(args[0] as Long, args[1] as Long)
                        null
                    }
                    "equals" -> proxy === args[0]
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "SwingRepaintPacer.Listener"
                    else -> null
                }
            }
        val subscription = subscribeMethod.invoke(service, displayId, listener) ?: return null
        return AutoCloseable {
            subscriptionCloseMethod.invoke(subscription)
        }
    }

    companion object {
        val instance: FramePacingService? by lazy {
            try {
                val jbrClass = Class.forName("com.jetbrains.JBR")
                val service = jbrClass.getMethod("getFramePacing").invoke(null)
                    ?: return@lazy null

                val serviceClass = Class.forName("com.jetbrains.FramePacing")
                val listenerClass = Class.forName($$"com.jetbrains.FramePacing$Listener")
                val subscriptionClass = Class.forName($$"com.jetbrains.FramePacing$Subscription")

                JbrFramePacingApi(
                    service = service,
                    displayIdMethod = serviceClass.getMethod(
                        "displayId",
                        GraphicsConfiguration::class.java,
                    ),
                    refreshPeriodNanosMethod = serviceClass.getMethod(
                        "refreshPeriodNanos",
                        Long::class.javaPrimitiveType,
                    ),
                    subscribeMethod = serviceClass.getMethod(
                        "subscribe",
                        Long::class.javaPrimitiveType,
                        listenerClass,
                    ),
                    listenerClass = listenerClass,
                    subscriptionCloseMethod = subscriptionClass.getMethod("close"),
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}
