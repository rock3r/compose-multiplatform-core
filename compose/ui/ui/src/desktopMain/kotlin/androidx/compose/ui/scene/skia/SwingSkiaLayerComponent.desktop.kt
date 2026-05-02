/*
 * Copyright 2023 The Android Open Source Project
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
import androidx.compose.ui.scene.ComposeSceneMediator
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.FocusEvent
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import javax.accessibility.AccessibleContext
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.SkiaLayerAnalytics
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.swing.SkiaSwingLayer

/**
 * Provides a lightweight Swing [hierarchyRoot] used to render content
 * (provided by [SkikoRenderDelegate]) on-screen with Skia.
 *
 * [SwingSkiaLayerComponent] provides smooth integration with Swing, so z-ordering, double-buffering etc. from Swing will be taken into account.
 *
 * However, if smooth interop with Swing is not needed, consider using [WindowSkiaLayerComponent]
 */
@OptIn(ExperimentalSkikoApi::class)
internal class SwingSkiaLayerComponent(
    mediator: ComposeSceneMediator,
    renderDelegate: SkikoRenderDelegate,
    skiaLayerAnalytics: SkiaLayerAnalytics,
) : SkiaLayerComponent {
    /**
     * See also backendLayer for standalone Compose in [WindowSkiaLayerComponent]
     */
    override val hierarchyRoot: SkiaSwingLayer =
        createHierarchyRoot(mediator, renderDelegate, skiaLayerAnalytics)

    private fun createHierarchyRoot(
        mediator: ComposeSceneMediator,
        renderDelegate: SkikoRenderDelegate,
        skiaLayerAnalytics: SkiaLayerAnalytics,
    ): SkiaSwingLayer {
        if (ComposeFeatureFlags.useJbrSkiaInteropInComposePanel.value) {
            val delegateWithDensityRefresh = JbrSkiaInteropRuntime.createCommandRenderDelegateOrNull(
                mediator = mediator,
                renderDelegate = renderDelegate,
            ) ?: createDensityRefreshingRenderDelegate(mediator, renderDelegate)
            JbrSkiaInteropRuntime.createSwingLayerOrNull(
                renderDelegate = delegateWithDensityRefresh,
                analytics = skiaLayerAnalytics,
                accessibleContextProvider = mediator.accessibility.accessibleContextProvider
            )?.let { return it }
        }

        return createDefaultHierarchyRoot(mediator, renderDelegate, skiaLayerAnalytics)
    }

    private fun createDensityRefreshingRenderDelegate(
        mediator: ComposeSceneMediator,
        renderDelegate: SkikoRenderDelegate,
    ): SkikoRenderDelegate = object : SkikoRenderDelegate {
        override fun onRender(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, nanoTime: Long) {
            mediator.onChangeDensity()
            renderDelegate.onRender(canvas, width, height, nanoTime)
        }
    }

    private fun createDefaultHierarchyRoot(
        mediator: ComposeSceneMediator,
        renderDelegate: SkikoRenderDelegate,
        skiaLayerAnalytics: SkiaLayerAnalytics,
    ): SkiaSwingLayer =
        object : SkiaSwingLayer(
            renderDelegate = renderDelegate,
            analytics = skiaLayerAnalytics,
            accessibleContextProvider = mediator.accessibility.accessibleContextProvider
        ) {
            private var endCompositionWorkaround: InputMethodEndCompositionWorkaround? = null

            override fun getInputContext() =
                endCompositionWorkaround?.inputContext ?: super.getInputContext()

            override fun addNotify() {
                super.addNotify()

                endCompositionWorkaround = InputMethodEndCompositionWorkaround.forCurrentEnvironment(
                    componentInputContext = { super.getInputContext() }
                )
            }

            override fun paint(g: Graphics) {
                mediator.onChangeDensity()
                if (ComposeFeatureFlags.useJbrSkiaInteropInComposePanel.value) {
                    JbrSkiaInteropRuntime.acquireCanvasOrNull(g)?.close()
                }
                super.paint(g)
            }

            override fun getInputMethodRequests() = mediator.currentInputMethodRequests

            override fun doLayout() {
                super.doLayout()
                mediator.onContainerSizeChanged()
            }

            override fun getPreferredSize(): Dimension = if (isPreferredSizeSet) {
                super.getPreferredSize()
            } else {
                mediator.preferredSize
            }

            // Workaround for enableInputMethods being ignored until the component is actually focused.
            // This also controls the default state, without needing it to be set from the outside.
            private var inputMethodsEnabled = false

            override fun processFocusEvent(e: FocusEvent?) {
                super.processFocusEvent(e)

                // enableInputMethods is idempotent (and quick when applying the same value),
                // so it's ok to call it on every event
                super.enableInputMethods(inputMethodsEnabled)
            }

            override fun enableInputMethods(enable: Boolean) {
                inputMethodsEnabled = enable
                super.enableInputMethods(enable)
            }
        }

    override val contentRoot: Component
        get() = hierarchyRoot

    override val renderApi by hierarchyRoot::renderApi

    override val interopBlendingSupported: Boolean
        get() = true

    override val clipComponents by hierarchyRoot::clipComponents

    override var transparency
        get() = true
        set(_) {}

    override var fullscreen
        get() = false
        set(_) {}

    override val windowHandle get() = 0L

    override fun dispose() {
        hierarchyRoot.dispose()
    }

    override fun onComposeInvalidation() {
        hierarchyRoot.repaint()
    }

    override fun renderImmediately() {
        hierarchyRoot.paintImmediately(0, 0, hierarchyRoot.width, hierarchyRoot.height)
    }

    override fun onRenderApiChanged(action: () -> Unit) = Unit
}

internal object JbrSkiaInteropRuntime {
    private const val CLASS_NAME = "org.jetbrains.skiko.jbr.JbrSkiaInterop"
    private const val SWING_LAYER_CLASS_NAME = "org.jetbrains.skiko.jbr.JbrSkiaSwingLayer"
    private const val COMMAND_RENDER_DELEGATE_CLASS_NAME = "org.jetbrains.skiko.jbr.JbrSkiaCommandRenderDelegate"
    private const val COMMAND_FRAME_CLASS_NAME = "org.jetbrains.skiko.jbr.JbrSkiaCommandFrame"
    private const val COMMAND_FRAME_KIND_CLASS_NAME = "org.jetbrains.skiko.jbr.JbrSkiaCommandFrameKind"
    private const val METHOD_NAME = "acquireCanvasOrNull"
    private const val FALLBACK_MARKER = "SKIKO_JBR_INTEROP_FALLBACK"

    @Volatile
    private var resolveAttempted = false

    @Volatile
    private var acquireCanvasMethod: Method? = null

    @Volatile
    private var layerResolveAttempted = false

    @Volatile
    private var swingLayerConstructor: java.lang.reflect.Constructor<*>? = null

    @Volatile
    private var commandDelegateResolveAttempted = false

    @Volatile
    private var commandDelegateClass: Class<*>? = null

    @Volatile
    private var commandFrameConstructor: java.lang.reflect.Constructor<*>? = null

    @Volatile
    private var commandFrameKindClass: Class<out Enum<*>>? = null

    @Volatile
    private var fallbackLogged = false

    fun createCommandRenderDelegateOrNull(
        mediator: ComposeSceneMediator,
        renderDelegate: SkikoRenderDelegate,
    ): SkikoRenderDelegate? {
        val commandInterface = commandDelegateClass ?: resolveCommandDelegateClass() ?: return null
        return runCatching {
            Proxy.newProxyInstance(
                SkikoRenderDelegate::class.java.classLoader,
                arrayOf(SkikoRenderDelegate::class.java, commandInterface),
                CommandRenderDelegateInvocationHandler(mediator, renderDelegate)
            ) as? SkikoRenderDelegate
        }.onFailure {
            logFallbackOnce("skiko-jbr-command-delegate-proxy-error")
        }.getOrNull()
    }

    fun createSwingLayerOrNull(
        renderDelegate: SkikoRenderDelegate,
        analytics: SkiaLayerAnalytics,
        accessibleContextProvider: ((Component) -> AccessibleContext)?,
    ): SkiaSwingLayer? {
        val constructor = swingLayerConstructor ?: resolveSwingLayerConstructor() ?: return null
        return runCatching {
            constructor.newInstance(
                renderDelegate,
                analytics,
                accessibleContextProvider,
                SkiaLayerProperties()
            ) as? SkiaSwingLayer
        }.onFailure {
            logFallbackOnce("skiko-jbr-layer-error")
        }.getOrNull()
    }

    fun acquireCanvasOrNull(graphics: Graphics): AutoCloseable? {
        val graphics2D = graphics as? Graphics2D ?: return null
        val method = acquireCanvasMethod ?: resolveAcquireCanvasMethod() ?: return null
        return runCatching { method.invoke(null, graphics2D) as? AutoCloseable }
            .onFailure { logFallbackOnce("skiko-jbr-runtime-error") }
            .getOrNull()
    }

    private fun resolveAcquireCanvasMethod(): Method? {
        if (resolveAttempted) return acquireCanvasMethod
        resolveAttempted = true
        acquireCanvasMethod = runCatching {
            Class.forName(CLASS_NAME).getMethod(METHOD_NAME, Graphics2D::class.java)
        }.onFailure {
            logFallbackOnce("skiko-jbr-runtime-missing")
        }.getOrNull()
        return acquireCanvasMethod
    }

    private fun resolveCommandDelegateClass(): Class<*>? {
        if (commandDelegateResolveAttempted) return commandDelegateClass
        commandDelegateResolveAttempted = true
        runCatching {
            commandDelegateClass = Class.forName(COMMAND_RENDER_DELEGATE_CLASS_NAME)
            val frameKindRaw = Class.forName(COMMAND_FRAME_KIND_CLASS_NAME)
            @Suppress("UNCHECKED_CAST")
            commandFrameKindClass = frameKindRaw.asSubclass(Enum::class.java) as Class<out Enum<*>>
            commandFrameConstructor = Class.forName(COMMAND_FRAME_CLASS_NAME)
                .getConstructor(IntArray::class.java, frameKindRaw)
        }.onFailure {
            logFallbackOnce("skiko-jbr-command-delegate-missing")
        }
        return commandDelegateClass
    }

    private fun resolveSwingLayerConstructor(): java.lang.reflect.Constructor<*>? {
        if (layerResolveAttempted) return swingLayerConstructor
        layerResolveAttempted = true
        swingLayerConstructor = runCatching {
            Class.forName(SWING_LAYER_CLASS_NAME).getConstructor(
                SkikoRenderDelegate::class.java,
                SkiaLayerAnalytics::class.java,
                Function1::class.java,
                SkiaLayerProperties::class.java
            )
        }.onFailure {
            logFallbackOnce("skiko-jbr-layer-missing")
        }.getOrNull()
        return swingLayerConstructor
    }

    private fun logFallbackOnce(reason: String) {
        if (!fallbackLogged) {
            fallbackLogged = true
            System.err.println("$FALLBACK_MARKER reason=$reason")
        }
    }

    private class CommandRenderDelegateInvocationHandler(
        private val mediator: ComposeSceneMediator,
        private val renderDelegate: SkikoRenderDelegate,
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            return when (method.name) {
                "onRender" -> {
                    mediator.onChangeDensity()
                    renderDelegate.onRender(
                        args?.get(0) as org.jetbrains.skia.Canvas,
                        args[1] as Int,
                        args[2] as Int,
                        args[3] as Long,
                    )
                    Unit
                }
                "renderJbrSkiaCommandFrame" -> {
                    mediator.onChangeDensity()
                    mediator.renderJbrSkiaCommandFrame(
                        args?.get(0) as Int,
                        args[1] as Int,
                        args[2] as Long,
                    )
                }
                "renderJbrSkiaCommandFrameInfo" -> {
                    mediator.onChangeDensity()
                    mediator.renderJbrSkiaCommandFrameData(
                        args?.get(0) as Int,
                        args[1] as Int,
                        args[2] as Long,
                    )?.toSkikoCommandFrame()
                }
                "toString" -> "JbrSkiaCommandRenderDelegateProxy($renderDelegate)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> method.invoke(renderDelegate, *(args ?: emptyArray()))
            }
        }

        private fun androidx.compose.ui.scene.JbrSkiaCommandFrameData.toSkikoCommandFrame(): Any? {
            val frameConstructor = commandFrameConstructor ?: return null
            val kindClass = commandFrameKindClass ?: return null
            val kind = enumValueOf(kindClass, kind.name)
            return frameConstructor.newInstance(commands, kind)
        }

        private fun enumValueOf(enumClass: Class<out Enum<*>>, name: String): Enum<*> {
            return java.lang.Enum.valueOf(enumClass.asSubclass(Enum::class.java), name)
        }
    }
}
