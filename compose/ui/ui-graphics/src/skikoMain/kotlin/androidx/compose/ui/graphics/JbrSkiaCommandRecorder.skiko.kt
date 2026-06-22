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

package androidx.compose.ui.graphics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt


data class JbrSkiaCommandShadowContext(
    val lightX: Float = 0f,
    val lightY: Float = -300f,
    val lightZ: Float = 600f,
    val lightRadius: Float = 800f,
    val ambientShadowAlpha: Float = 0.039f,
    val spotShadowAlpha: Float = 0.19f,
)

data class JbrSkiaCommandRecording(
    val commands: IntArray?,
    val nativeImageReferences: Array<ImageBitmap>,
    val commandWordCount: Int,
    val unsupportedCount: Int,
    val imageDefineCount: Int,
    val imageDefineWordCount: Int,
    val imageDefinePixelCount: Int,
    val imageDefinedKeys: LongArray,
    val imageReferencedKeys: LongArray,
    val imageRefCount: Int,
    val textCommandCount: Int,
    val paragraphTextCommandCount: Int,
    val imageCacheClearCount: Int,
    val imageCacheEvictCount: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JbrSkiaCommandRecording) return false
        if (commands != null) {
            if (other.commands == null || !commands.contentEquals(other.commands)) return false
        } else if (other.commands != null) {
            return false
        }
        if (!nativeImageReferences.contentEquals(other.nativeImageReferences)) return false
        if (commandWordCount != other.commandWordCount) return false
        if (unsupportedCount != other.unsupportedCount) return false
        if (imageDefineCount != other.imageDefineCount) return false
        if (imageDefineWordCount != other.imageDefineWordCount) return false
        if (imageDefinePixelCount != other.imageDefinePixelCount) return false
        if (!imageDefinedKeys.contentEquals(other.imageDefinedKeys)) return false
        if (!imageReferencedKeys.contentEquals(other.imageReferencedKeys)) return false
        if (imageRefCount != other.imageRefCount) return false
        if (textCommandCount != other.textCommandCount) return false
        if (paragraphTextCommandCount != other.paragraphTextCommandCount) return false
        if (imageCacheClearCount != other.imageCacheClearCount) return false
        return imageCacheEvictCount == other.imageCacheEvictCount
    }

    override fun hashCode(): Int {
        var result = commands?.contentHashCode() ?: 0
        result = 31 * result + nativeImageReferences.contentHashCode()
        result = 31 * result + commandWordCount
        result = 31 * result + unsupportedCount
        result = 31 * result + imageDefineCount
        result = 31 * result + imageDefineWordCount
        result = 31 * result + imageDefinePixelCount
        result = 31 * result + imageDefinedKeys.contentHashCode()
        result = 31 * result + imageReferencedKeys.contentHashCode()
        result = 31 * result + imageRefCount
        result = 31 * result + textCommandCount
        result = 31 * result + paragraphTextCommandCount
        result = 31 * result + imageCacheClearCount
        result = 31 * result + imageCacheEvictCount
        return result
    }
}

private data class ImageCacheEntry(
    val width: Int,
    val height: Int,
    val cacheKey: Long,
)

private data class NativeBitmapImageDefinition(
    val ptr: Long,
    val generationId: Int,
    val cacheKey: Long,
)

object JbrSkiaCommandRecorder {
    private const val STRICT_PROPERTY = "compose.jbr.skia.command.strict"
    private const val COLOR_FILTER_HANDLES_PROPERTY = "compose.jbr.skia.command.colorFilterHandles"
    private const val LOG_COMMAND_OP_COUNTS_PROPERTY = "compose.jbr.skia.command.logOpCounts"
    private const val MAX_COMMAND_IMAGE_DIMENSION = 4096
    private const val MAX_DEFINED_IMAGE_KEYS = 1024
    private const val MAX_DEFINED_COLOR_FILTER_HANDLES = 1024
    private const val MAX_DEFINED_SHADER_HANDLES = 1024
    private const val MAX_DEFINED_FONT_DATA_HANDLES = 1024
    private val active = ThreadLocal<Recorder?>()
    private val imageCacheLock = Any()
    private val definedImageKeys = LinkedHashMap<Long, Unit>(MAX_DEFINED_IMAGE_KEYS, 0.75f, true)
    private val confirmedNativeImageKeys = HashSet<Long>()
    private val imageIdentityCache = WeakHashMap<ImageBitmap, ImageCacheEntry>()
    private var previousFrameImageKeys = emptySet<Long>()
    private val colorFilterHandleLock = Any()
    private val definedColorFilterHandles = LinkedHashMap<Long, Unit>(MAX_DEFINED_COLOR_FILTER_HANDLES, 0.75f, true)
    private val confirmedColorFilterHandles = HashSet<Long>()
    private val shaderHandleLock = Any()
    private val definedShaderHandles = LinkedHashMap<Long, Unit>(MAX_DEFINED_SHADER_HANDLES, 0.75f, true)
    private val fontDataHandleLock = Any()
    private val definedFontDataHandles = LinkedHashMap<Long, Unit>(MAX_DEFINED_FONT_DATA_HANDLES, 0.75f, true)
    private val pendingImageCacheClear = AtomicBoolean(false)
    private val imageCacheHasDefinitions = AtomicBoolean(false)

    fun record(block: () -> Unit): IntArray? {
        return recordFrame(block = block).commands
    }

    fun recordFrame(
        shadowContext: JbrSkiaCommandShadowContext = JbrSkiaCommandShadowContext(),
        block: () -> Unit,
    ): JbrSkiaCommandRecording {
        val previous = active.get()
        val recorder = Recorder(
            shadowContext = shadowContext,
            emitPendingImageCacheClear = pendingImageCacheClear.getAndSet(false),
            forceResourceDefinitions = false,
            updateSharedResourceCaches = true,
        )
        active.set(recorder)
        try {
            block()
            val recording = recorder.toRecording(recorder.toCommandArray())
            if (recording.imageDefineCount > 0) {
                imageCacheHasDefinitions.set(true)
            }
            synchronized(imageCacheLock) {
                previousFrameImageKeys = recording.imageReferencedKeys.toSet()
            }
            return recording
        } finally {
            recorder.logFrame()
            active.set(previous)
        }
    }

    fun isRecording(): Boolean = active.get() != null

    internal fun recordNested(block: () -> Unit): JbrSkiaCommandRecording {
        val previous = active.get()
        val recorder = Recorder(
            shadowContext = previous?.shadowContext ?: JbrSkiaCommandShadowContext(),
            emitPendingImageCacheClear = false,
            forceResourceDefinitions = false,
            updateSharedResourceCaches = false,
        )
        active.set(recorder)
        try {
            block()
            val recording = recorder.toRecording(recorder.toCommandArray())
            if (recording.commands == null && recording.unsupportedCount > 0) {
                recorder.logNestedUnsupported()
            }
            return recording
        } finally {
            active.set(previous)
        }
    }

    internal fun save() {
        active.get()?.save()
    }

    internal fun restore() {
        active.get()?.restore()
    }

    internal fun saveLayer(bounds: Rect, paint: Paint) {
        active.get()?.saveLayer(bounds, paint)
    }

    internal fun translate(dx: Float, dy: Float) {
        active.get()?.translate(dx, dy)
    }

    internal fun scale(sx: Float, sy: Float) {
        active.get()?.scale(sx, sy)
    }

    internal fun rotate(degrees: Float) {
        active.get()?.rotate(degrees)
    }

    internal fun skew(sx: Float, sy: Float) {
        active.get()?.skew(sx, sy)
    }

    internal fun concat(matrix: Matrix) {
        active.get()?.concat(matrix)
    }

    internal fun unsupportedTransform() {
        active.get()?.unsupportedTransform()
    }

    internal fun unsupportedDraw(reason: String) {
        active.get()?.unsupportedDraw(reason)
    }

    internal fun drawPointLines(points: List<Offset>, paint: Paint, stepBy: Int) {
        active.get()?.drawPointLines(points, paint, stepBy)
    }

    internal fun drawRawPointLines(points: FloatArray, paint: Paint, stepBy: Int) {
        active.get()?.drawRawPointLines(points, paint, stepBy)
    }

    internal fun drawPoints(points: List<Offset>, paint: Paint) {
        active.get()?.drawPoints(points, paint)
    }

    internal fun drawRawPoints(points: FloatArray, paint: Paint) {
        active.get()?.drawRawPoints(points, paint)
    }

    internal fun replayRecordedLayer(
        recording: JbrSkiaCommandRecording,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        pivotX: Float,
        pivotY: Float,
        alpha: Float,
        scaleX: Float,
        scaleY: Float,
        rotationZ: Float,
        translationX: Float,
        translationY: Float,
        clipRect: Rect?,
        clipPath: Path?,
        blendMode: Int?,
        colorFilter: ColorFilter? = null,
        imageFilter: ImageFilterDescriptor? = null,
        shadowElevation: Float = 0f,
        ambientShadowColor: Color = Color.Black,
        spotShadowColor: Color = Color.Black,
        shadowPath: Path? = null,
        clipToLayerBounds: Boolean = false,
        transformMatrix: Matrix? = null,
    ): Boolean =
        active.get()?.replayRecordedLayer(
            recording = recording,
            left = left,
            top = top,
            width = width,
            height = height,
            pivotX = pivotX,
            pivotY = pivotY,
            alpha = alpha,
            scaleX = scaleX,
            scaleY = scaleY,
            rotationZ = rotationZ,
            translationX = translationX,
            translationY = translationY,
            clipRect = clipRect,
            clipPath = clipPath,
            blendMode = blendMode,
            colorFilter = colorFilter,
            imageFilter = imageFilter,
            shadowElevation = shadowElevation,
            ambientShadowColor = ambientShadowColor,
            spotShadowColor = spotShadowColor,
            shadowPath = shadowPath,
            clipToLayerBounds = clipToLayerBounds,
            transformMatrix = transformMatrix,
        ) ?: false

    internal fun commandBlendModeOrNull(blendMode: BlendMode): Int? =
        when (blendMode) {
            BlendMode.Plus -> COMMAND_BLEND_MODE_PLUS
            BlendMode.Multiply -> COMMAND_BLEND_MODE_MULTIPLY
            BlendMode.Screen -> COMMAND_BLEND_MODE_SCREEN
            BlendMode.Overlay -> COMMAND_BLEND_MODE_OVERLAY
            BlendMode.Darken -> COMMAND_BLEND_MODE_DARKEN
            BlendMode.Lighten -> COMMAND_BLEND_MODE_LIGHTEN
            BlendMode.Difference -> COMMAND_BLEND_MODE_DIFFERENCE
            BlendMode.Exclusion -> COMMAND_BLEND_MODE_EXCLUSION
            BlendMode.ColorDodge -> COMMAND_BLEND_MODE_COLOR_DODGE
            BlendMode.ColorBurn -> COMMAND_BLEND_MODE_COLOR_BURN
            BlendMode.Hardlight -> COMMAND_BLEND_MODE_HARDLIGHT
            BlendMode.Softlight -> COMMAND_BLEND_MODE_SOFTLIGHT
            BlendMode.Hue -> COMMAND_BLEND_MODE_HUE
            BlendMode.Saturation -> COMMAND_BLEND_MODE_SATURATION
            BlendMode.Color -> COMMAND_BLEND_MODE_COLOR
            BlendMode.Luminosity -> COMMAND_BLEND_MODE_LUMINOSITY
            else -> null
        }

    internal fun commandShaderBlendModeOrNull(blendMode: BlendMode): Int? =
        if (blendMode == BlendMode.SrcOver) COMMAND_BLEND_MODE_SRC_OVER else commandBlendModeOrNull(blendMode)

    internal fun VertexMode.commandValue(): Int =
        when (this) {
            VertexMode.Triangles -> 0
            VertexMode.TriangleStrip -> 1
            VertexMode.TriangleFan -> 2
            else -> -1
        }

    internal fun tintSrcInColorFilterOrNull(colorFilter: ColorFilter?): BlendModeColorFilter? =
        (colorFilter as? BlendModeColorFilter)?.takeIf { it.blendMode == BlendMode.SrcIn }

    internal fun blendModeColorFilterOrNull(colorFilter: ColorFilter?): BlendModeColorFilter? =
        (colorFilter as? BlendModeColorFilter)?.takeIf {
            it.blendMode == BlendMode.SrcIn || commandBlendModeOrNull(it.blendMode) != null
        }

    internal fun colorMatrixColorFilterOrNull(colorFilter: ColorFilter?): ColorMatrixColorFilter? =
        colorFilter as? ColorMatrixColorFilter

    internal fun lightingColorFilterOrNull(colorFilter: ColorFilter?): LightingColorFilter? =
        colorFilter as? LightingColorFilter

    internal fun descriptorColorFilterOrNull(colorFilter: ColorFilter?): ColorFilter? =
        blendModeColorFilterOrNull(colorFilter)
            ?: colorMatrixColorFilterOrNull(colorFilter)
            ?: lightingColorFilterOrNull(colorFilter)
            ?: (colorFilter as? JbrSkiaRuntimeEffectColorFilterHolder)

    internal sealed class ImageFilterDescriptor {
        data class Blur(
            val sigmaX: Float,
            val sigmaY: Float,
            val tileMode: Int,
            val input: ImageFilterDescriptor? = null,
        ) : ImageFilterDescriptor()

        data class Offset(
            val dx: Float,
            val dy: Float,
            val input: ImageFilterDescriptor? = null,
        ) : ImageFilterDescriptor()
    }

    internal sealed class ShaderDescriptor {
        data class LinearGradient(val shader: JbrSkiaLinearGradientShader) : ShaderDescriptor()
        data class RadialGradient(val shader: JbrSkiaRadialGradientShader) : ShaderDescriptor()
        data class SweepGradient(val shader: JbrSkiaSweepGradientShader) : ShaderDescriptor()
        data class Image(val shader: JbrSkiaImageShader) : ShaderDescriptor()
        data class Composite(val shader: JbrSkiaCompositeShader) : ShaderDescriptor()
        data class RuntimeEffect(val shader: JbrSkiaRuntimeEffectShader) : ShaderDescriptor()
        data class Transformed(val shader: JbrSkiaTransformedShader) : ShaderDescriptor()
        data class Color(val shader: JbrSkiaColorShader) : ShaderDescriptor()
        data class PerlinNoise(val shader: JbrSkiaPerlinNoiseShader) : ShaderDescriptor()
        data class ColorFiltered(val shader: ShaderDescriptor, val colorFilter: ColorFilter) : ShaderDescriptor()
    }

    internal fun shaderDescriptorOrNull(shader: Shader?): ShaderDescriptor? {
        shader ?: return null
        shader.jbrSkiaLinearGradient?.let { return ShaderDescriptor.LinearGradient(it) }
        shader.jbrSkiaRadialGradient?.let { return ShaderDescriptor.RadialGradient(it) }
        shader.jbrSkiaSweepGradient?.let { return ShaderDescriptor.SweepGradient(it) }
        shader.jbrSkiaImageShader?.let { return ShaderDescriptor.Image(it) }
        shader.jbrSkiaCompositeShader?.let { return ShaderDescriptor.Composite(it) }
        shader.jbrSkiaRuntimeEffectShader?.let { return ShaderDescriptor.RuntimeEffect(it) }
        shader.jbrSkiaTransformedShader?.let { return ShaderDescriptor.Transformed(it) }
        shader.jbrSkiaColorShader?.let { return ShaderDescriptor.Color(it) }
        shader.jbrSkiaPerlinNoiseShader?.let { return ShaderDescriptor.PerlinNoise(it) }
        return null
    }

    fun markUnsupportedDraw(reason: String) {
        active.get()?.unsupportedDraw(reason)
    }

    @JvmStatic
    fun clearInteropCachesForSurfaceChange() {
        clearInteropCaches()
        pendingImageCacheClear.set(true)
    }

    @JvmStatic
    fun markInteropImageDefinitionsRendered(keys: LongArray) {
        if (keys.isEmpty()) return
        synchronized(imageCacheLock) {
            keys.forEach(confirmedNativeImageKeys::add)
        }
    }

    @JvmStatic
    fun markInteropEffectDefinitionsRendered(handles: LongArray) {
        if (handles.isEmpty()) return
        synchronized(colorFilterHandleLock) {
            handles.forEach(confirmedColorFilterHandles::add)
        }
    }

    private fun clearInteropCaches() {
        synchronized(imageCacheLock) {
            definedImageKeys.clear()
            confirmedNativeImageKeys.clear()
            imageIdentityCache.clear()
            previousFrameImageKeys = emptySet()
        }
        imageCacheHasDefinitions.set(false)
        synchronized(colorFilterHandleLock) {
            definedColorFilterHandles.clear()
            confirmedColorFilterHandles.clear()
        }
        synchronized(shaderHandleLock) {
            definedShaderHandles.clear()
        }
        synchronized(fontDataHandleLock) {
            definedFontDataHandles.clear()
        }
    }

    internal fun clearImageCacheForTesting() {
        clearInteropCaches()
        pendingImageCacheClear.set(false)
    }

    fun drawTextUtf16(
        text: String,
        x: Float,
        baseline: Float,
        fontSize: Float,
        fontFamily: String?,
        fontWeight: Int,
        fontWidth: Int,
        fontSlant: Int,
        color: Int,
        antiAlias: Boolean,
    ): Boolean =
        active.get()
            ?.drawTextUtf16(text, x, baseline, fontSize, fontFamily, fontWeight, fontWidth, fontSlant, color, antiAlias)
            ?: false

    fun drawParagraphUtf16(
        text: String,
        x: Float,
        y: Float,
        width: Float,
        fontSize: Float,
        fontFamily: String?,
        color: Int,
        fontWeight: Int,
        fontWidth: Int,
        fontSlant: Int,
        textAlign: Int,
        textDirection: Int,
        lineHeightMultiplier1000: Int,
        maxLines: Int,
        ellipsisMode: Int,
        decorationMask: Int,
        letterSpacing1000: Int,
        backgroundSpecified: Int,
        backgroundArgb: Int,
        antiAlias: Boolean,
    ): Boolean =
        active.get()?.drawParagraphUtf16(
            text,
            x,
            y,
            width,
            fontSize,
            fontFamily,
            color,
            fontWeight,
            fontWidth,
            fontSlant,
            textAlign,
            textDirection,
            lineHeightMultiplier1000,
            maxLines,
            ellipsisMode,
            decorationMask,
            letterSpacing1000,
            backgroundSpecified,
            backgroundArgb,
            antiAlias,
        )
            ?: false

    internal fun clipRect(left: Float, top: Float, right: Float, bottom: Float, clipOp: ClipOp) {
        active.get()?.clipRect(left, top, right, bottom, clipOp)
    }

    internal fun clipPath(path: Path, clipOp: ClipOp) {
        active.get()?.clipPath(path, clipOp)
    }

    internal fun drawLine(p1: Offset, p2: Offset, paint: Paint) {
        active.get()?.drawLine(p1, p2, paint)
    }

    internal fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        active.get()?.drawRect(left, top, right, bottom, paint)
    }

    internal fun drawRoundRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radiusX: Float,
        radiusY: Float,
        paint: Paint,
    ) {
        active.get()?.drawRoundRect(left, top, right, bottom, radiusX, radiusY, paint)
    }

    internal fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        active.get()?.drawOval(left, top, right, bottom, paint)
    }

    internal fun drawCircle(center: Offset, radius: Float, paint: Paint) {
        active.get()?.drawCircle(center, radius, paint)
    }

    internal fun drawArc(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        startAngle: Float,
        sweepAngle: Float,
        useCenter: Boolean,
        paint: Paint,
    ) {
        active.get()?.drawArc(left, top, right, bottom, startAngle, sweepAngle, useCenter, paint)
    }

    internal fun drawPath(path: Path, paint: Paint) {
        active.get()?.drawPath(path, paint)
    }

    internal fun drawVertices(vertices: Vertices, blendMode: BlendMode, paint: Paint): Boolean =
        active.get()?.drawVertices(vertices, blendMode, paint) ?: false

    fun drawImageRect(
        image: ImageBitmap,
        srcLeft: Float,
        srcTop: Float,
        srcRight: Float,
        srcBottom: Float,
        dstLeft: Float,
        dstTop: Float,
        dstRight: Float,
        dstBottom: Float,
        paint: Paint,
    ): Boolean =
        active.get()?.drawImageRect(image, srcLeft, srcTop, srcRight, srcBottom, dstLeft, dstTop, dstRight, dstBottom, paint)
            ?: false

    fun defineFontData(handle: Long, data: ByteArray): Boolean =
        active.get()?.defineFontData(handle, data) ?: false

    private class Recorder(
        val shadowContext: JbrSkiaCommandShadowContext,
        emitPendingImageCacheClear: Boolean,
        private val forceResourceDefinitions: Boolean,
        private val updateSharedResourceCaches: Boolean,
    ) {
        private val commands = CommandStreamWriter()
        private val stack = ArrayDeque<State>()
        private val unsupportedReasons = linkedMapOf<String, Int>()
        private var imageDefineCount = 0
        private var imageDefineWordCount = 0
        private var imageDefinePixelCount = 0
        private val imageDefinedKeys = mutableListOf<Long>()
        private val imageReferencedKeys = linkedSetOf<Long>()
        private val nativeImageReferences = mutableListOf<ImageBitmap>()
        private var imageRefCount = 0
        private var textCommandCount = 0
        private var paragraphTextCommandCount = 0
        private var imageCacheClearCount = 0
        private var imageCacheEvictCount = 0
        private var state = State()

        init {
            if (emitPendingImageCacheClear) {
                commands.addCommand(COMMAND_CLEAR_IMAGE_CACHE)
                imageCacheClearCount++
            }
        }

        fun toCommandArray(): IntArray? =
            if (java.lang.Boolean.getBoolean(STRICT_PROPERTY) && unsupportedCount > 0) {
                null
            } else {
                commandStream()
            }

        fun toRecording(commands: IntArray?): JbrSkiaCommandRecording =
            JbrSkiaCommandRecording(
                commands = commands,
                nativeImageReferences = nativeImageReferences.toTypedArray(),
                commandWordCount = this.commands.streamSize,
                unsupportedCount = unsupportedCount,
                imageDefineCount = imageDefineCount,
                imageDefineWordCount = imageDefineWordCount,
                imageDefinePixelCount = imageDefinePixelCount,
                imageDefinedKeys = imageDefinedKeys.toLongArray(),
                imageReferencedKeys = imageReferencedKeys.toLongArray(),
                imageRefCount = imageRefCount,
                textCommandCount = textCommandCount,
                paragraphTextCommandCount = paragraphTextCommandCount,
                imageCacheClearCount = imageCacheClearCount,
                imageCacheEvictCount = imageCacheEvictCount,
            )

        fun logFrame() {
            val unsupported = unsupportedCount
            val reasons = unsupportedReasons.entries.joinToString(separator = " ") { (reason, count) ->
                "$reason=$count"
            }
            val suffix = if (reasons.isEmpty()) "" else " $reasons"
            System.err.println(
                "CMP_JBR_COMMAND_RECORDER_FRAME commands=${commands.streamSize} unsupported=$unsupported" +
                    " textCommands=$textCommandCount paragraphTextCommands=$paragraphTextCommandCount" +
                " imageDefines=$imageDefineCount imageDefineWords=$imageDefineWordCount" +
                    " imageDefinePixels=$imageDefinePixelCount imageRefs=$imageRefCount" +
                    " imageCacheClears=$imageCacheClearCount imageCacheEvicts=$imageCacheEvictCount$suffix"
            )
            if (java.lang.Boolean.getBoolean(LOG_COMMAND_OP_COUNTS_PROPERTY)) {
                System.err.println("CMP_JBR_COMMAND_RECORDER_OPS ${commands.opSummary()}")
            }
        }

        fun logNestedUnsupported() {
            val reasons = unsupportedReasons.entries.joinToString(separator = " ") { (reason, count) ->
                "$reason=$count"
            }
            val suffix = if (reasons.isEmpty()) "" else " $reasons"
            System.err.println(
                "CMP_JBR_COMMAND_RECORDER_NESTED_UNSUPPORTED commands=${commands.streamSize}" +
                    " unsupported=$unsupportedCount$suffix"
            )
        }

        fun save() {
            commands.addCommand(COMMAND_SAVE)
            stack.addLast(state)
        }

        fun restore() {
            commands.addRestore()
            state = stack.removeLastOrNull() ?: State()
        }

        fun saveLayer(bounds: Rect, paint: Paint) {
            if (!paint.isSupportedLayerPaint) {
                countUnsupported("saveLayer")
                save()
                state = state.copy(supported = false)
                return
            }
            val blendMode = paint.commandBlendMode
            paint.tintSrcInColorFilter?.let {
                if (blendMode != null) {
                    saveLayerWithBlendTintSrcInColorFilter(bounds, paint, blendMode, it)
                    return
                }
                saveLayerWithTintSrcInColorFilter(bounds, paint, it)
                return
            }
            descriptorColorFilterOrNull(paint.colorFilter)?.let {
                if (blendMode != null) {
                    if (saveLayerWithBlendColorFilterHandle(bounds, paint, blendMode, it)) {
                        return
                    }
                } else if (saveLayerWithColorFilterHandle(bounds, paint, it)) {
                    return
                }
            }
            blendMode?.let {
                saveLayerWithBlendMode(bounds, paint, it)
                return
            }
            commands.addSaveLayer(
                state.x(bounds.left),
                state.y(bounds.top),
                state.width(bounds.width),
                state.height(bounds.height),
                paint.layerAlpha1000(),
            )
        }

        fun saveLayerWithTintSrcInColorFilter(bounds: Rect, paint: Paint, colorFilter: BlendModeColorFilter) {
            commands.addCommand(
                COMMAND_SAVE_LAYER_COLOR_FILTER,
                COMMAND_RECORD_FLAGS_NONE,
                state.x(bounds.left),
                state.y(bounds.top),
                state.width(bounds.width),
                state.height(bounds.height),
                paint.layerAlpha1000(),
                colorFilter.color.toArgb(),
                COMMAND_BLEND_MODE_SRC_IN,
            )
        }

        fun saveLayerWithBlendTintSrcInColorFilter(
            bounds: Rect,
            paint: Paint,
            blendMode: Int,
            colorFilter: BlendModeColorFilter,
        ) {
            commands.addCommand(
                COMMAND_SAVE_LAYER_BLEND_COLOR_FILTER,
                COMMAND_RECORD_FLAGS_NONE,
                state.x(bounds.left),
                state.y(bounds.top),
                state.width(bounds.width),
                state.height(bounds.height),
                paint.layerAlpha1000(),
                blendMode,
                colorFilter.color.toArgb(),
                COMMAND_BLEND_MODE_SRC_IN,
            )
        }

        fun saveLayerWithBlendMode(bounds: Rect, paint: Paint, blendMode: Int) {
            commands.addCommand(
                COMMAND_SAVE_LAYER_BLEND_MODE,
                COMMAND_RECORD_FLAGS_NONE,
                state.x(bounds.left),
                state.y(bounds.top),
                state.width(bounds.width),
                state.height(bounds.height),
                paint.layerAlpha1000(),
                blendMode,
            )
        }

        private fun savePrimitiveBlendLayer(left: Float, top: Float, right: Float, bottom: Float, blendMode: Int) {
            commands.addCommand(
                COMMAND_SAVE_LAYER_BLEND_MODE,
                COMMAND_RECORD_FLAGS_NONE,
                state.x(left),
                state.y(top),
                state.width(right - left),
                state.height(bottom - top),
                1000,
                blendMode,
            )
        }

        private fun withSolidColorBlendLayer(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            paint: Paint,
            block: () -> Unit,
        ) {
            val blendMode = paint.commandBlendMode
            if (blendMode == null) {
                block()
                return
            }
            val layerLeft = minOf(left, right)
            val layerTop = minOf(top, bottom)
            val layerRight = maxOf(left, right)
            val layerBottom = maxOf(top, bottom)
            if (!layerLeft.isFinite() || !layerTop.isFinite() || !layerRight.isFinite() || !layerBottom.isFinite()) {
                countUnsupported("blendLayerBounds")
                return
            }
            if (layerRight <= layerLeft || layerBottom <= layerTop) {
                block()
                return
            }
            savePrimitiveBlendLayer(layerLeft, layerTop, layerRight, layerBottom, blendMode)
            block()
            commands.addRestore()
        }

        private fun saveLayerWithColorFilterHandle(bounds: Rect, paint: Paint, colorFilter: ColorFilter): Boolean {
            val handle = defineDescriptorColorFilterIfNeeded(colorFilter) ?: return false
            commands.addCommand(
                COMMAND_SAVE_LAYER_COLOR_FILTER_REF,
                COMMAND_RECORD_FLAGS_NONE,
                state.x(bounds.left),
                state.y(bounds.top),
                state.width(bounds.width),
                state.height(bounds.height),
                paint.layerAlpha1000(),
                handle.highInt(),
                handle.lowInt(),
            )
            return true
        }

        private fun saveLayerWithBlendColorFilterHandle(
            bounds: Rect,
            paint: Paint,
            blendMode: Int,
            colorFilter: ColorFilter,
        ): Boolean {
            val handle = defineDescriptorColorFilterIfNeeded(colorFilter) ?: return false
            commands.addCommand(
                COMMAND_SAVE_LAYER_BLEND_COLOR_FILTER_REF,
                COMMAND_RECORD_FLAGS_NONE,
                state.x(bounds.left),
                state.y(bounds.top),
                state.width(bounds.width),
                state.height(bounds.height),
                paint.layerAlpha1000(),
                blendMode,
                handle.highInt(),
                handle.lowInt(),
            )
            return true
        }

        fun translate(dx: Float, dy: Float) {
            if (dx == 0f && dy == 0f) return
            commands.addTranslate(dx.fixed1000(), dy.fixed1000())
        }

        fun scale(sx: Float, sy: Float) {
            if (sx == 1f && sy == 1f) return
            commands.addCommand(COMMAND_SCALE, COMMAND_RECORD_FLAGS_NONE, sx.fixed1000(), sy.fixed1000())
        }

        fun rotate(degrees: Float) {
            if (degrees != 0f) {
                commands.addCommand(COMMAND_ROTATE, COMMAND_RECORD_FLAGS_NONE, degrees.fixed1000())
            }
        }

        fun skew(sx: Float, sy: Float) {
            if (sx == 0f && sy == 0f) return
            concat(Matrix().apply {
                values[Matrix.SkewX] = sx
                values[Matrix.SkewY] = sy
            })
        }

        fun concat(matrix: Matrix) {
            if (matrix.isIdentity()) return
            val values = matrix.values
            val matrixValues = floatArrayOf(
                values[Matrix.ScaleX],
                values[Matrix.SkewX],
                values[Matrix.TranslateX],
                values[Matrix.SkewY],
                values[Matrix.ScaleY],
                values[Matrix.TranslateY],
                values[Matrix.Perspective0],
                values[Matrix.Perspective1],
                values[Matrix.Perspective2],
            )
            if (matrixValues.any { !it.isFinite() }) {
                unsupportedTransform()
                return
            }
            commands.addCommand(
                COMMAND_CONCAT_MATRIX33,
                COMMAND_RECORD_FLAGS_NONE,
                *IntArray(matrixValues.size) { index -> matrixValues[index].toRawBits() },
            )
        }

        fun unsupportedTransform() {
            countUnsupported("transform")
            state = state.copy(supported = false)
        }

        fun unsupportedDraw(reason: String) {
            countUnsupported(reason)
        }

        fun replayRecordedLayer(
            recording: JbrSkiaCommandRecording,
            left: Float,
            top: Float,
            width: Float,
            height: Float,
            pivotX: Float,
            pivotY: Float,
            alpha: Float,
            scaleX: Float,
            scaleY: Float,
            rotationZ: Float,
            translationX: Float,
            translationY: Float,
            clipRect: Rect?,
            clipPath: Path?,
            blendMode: Int?,
            colorFilter: ColorFilter?,
            imageFilter: ImageFilterDescriptor?,
            shadowElevation: Float = 0f,
            ambientShadowColor: Color = Color.Black,
            spotShadowColor: Color = Color.Black,
            shadowPath: Path? = null,
            clipToLayerBounds: Boolean = false,
            transformMatrix: Matrix? = null,
        ): Boolean {
            val childCommands = recording.commands ?: run {
                countUnsupported("graphicsLayer:childCommands")
                return false
            }
            if (recording.unsupportedCount > 0) {
                countUnsupported("graphicsLayer:childUnsupported")
                return false
            }
            if (childCommands.size < COMMAND_STREAM_HEADER_SIZE) {
                countUnsupported("graphicsLayer:childHeaderSize")
                return false
            }
            if (childCommands[0] != COMMAND_STREAM_MAGIC ||
                childCommands[1] != COMMAND_STREAM_ABI_ID ||
                childCommands[2] != COMMAND_STREAM_FLAGS_NONE ||
                childCommands[3] != childCommands.size - COMMAND_STREAM_HEADER_SIZE ||
                childCommands[4] != COMMAND_COORDINATE_SPACE_SWING_USER ||
                childCommands[5] != COMMAND_PAINT_FORMAT_SOLID_ARGB
            ) {
                countUnsupported("graphicsLayer:childHeader")
                return false
            }
            save()
            if (transformMatrix != null) {
                translate(left, top)
                concat(transformMatrix)
            } else {
                translate(left + translationX, top + translationY)
                translate(pivotX, pivotY)
                rotate(rotationZ)
                scale(scaleX, scaleY)
                translate(-pivotX, -pivotY)
            }
            if (shadowElevation > 0f) {
                if (!addLayerShadow(width, height, shadowElevation, alpha, ambientShadowColor, spotShadowColor, shadowPath)) {
                    return false
                }
            }
            val tintColorFilter = tintSrcInColorFilterOrNull(colorFilter)
            val descriptorColorFilter = descriptorColorFilterOrNull(colorFilter)
            var layerSaveCount = 0
            if (imageFilter != null) {
                val handle = defineImageFilterIfNeeded(imageFilter) ?: run {
                    countUnsupported("graphicsLayer:renderEffect")
                    return false
                }
                commands.addCommand(
                    COMMAND_SAVE_LAYER_IMAGE_FILTER_REF,
                    COMMAND_RECORD_FLAGS_NONE,
                    0,
                    0,
                    width.roundToInt().coerceAtLeast(0),
                    height.roundToInt().coerceAtLeast(0),
                    (alpha * 1000f).roundToInt().coerceIn(0, 1000),
                    handle.highInt(),
                    handle.lowInt(),
                )
                layerSaveCount++
            }
            val paintLayerAlpha = if (imageFilter != null) 1f else alpha
            if (tintColorFilter != null && blendMode != null) {
                commands.addCommand(
                    COMMAND_SAVE_LAYER_BLEND_COLOR_FILTER,
                    COMMAND_RECORD_FLAGS_NONE,
                    0,
                    0,
                    width.roundToInt().coerceAtLeast(0),
                    height.roundToInt().coerceAtLeast(0),
                    (paintLayerAlpha * 1000f).roundToInt().coerceIn(0, 1000),
                    blendMode,
                    tintColorFilter.color.toArgb(),
                    COMMAND_BLEND_MODE_SRC_IN,
                )
                layerSaveCount++
            } else if (descriptorColorFilter != null && blendMode != null) {
                val handle = defineDescriptorColorFilterIfNeeded(descriptorColorFilter) ?: run {
                    countUnsupported("graphicsLayer:colorFilter")
                    return false
                }
                commands.addCommand(
                    COMMAND_SAVE_LAYER_BLEND_COLOR_FILTER_REF,
                    COMMAND_RECORD_FLAGS_NONE,
                    0,
                    0,
                    width.roundToInt().coerceAtLeast(0),
                    height.roundToInt().coerceAtLeast(0),
                    (paintLayerAlpha * 1000f).roundToInt().coerceIn(0, 1000),
                    blendMode,
                    handle.highInt(),
                    handle.lowInt(),
                )
                layerSaveCount++
            } else if (tintColorFilter != null) {
                commands.addCommand(
                    COMMAND_SAVE_LAYER_COLOR_FILTER,
                    COMMAND_RECORD_FLAGS_NONE,
                    0,
                    0,
                    width.roundToInt().coerceAtLeast(0),
                    height.roundToInt().coerceAtLeast(0),
                    (paintLayerAlpha * 1000f).roundToInt().coerceIn(0, 1000),
                    tintColorFilter.color.toArgb(),
                    COMMAND_BLEND_MODE_SRC_IN,
                )
                layerSaveCount++
            } else if (descriptorColorFilter != null) {
                val handle = defineDescriptorColorFilterIfNeeded(descriptorColorFilter) ?: run {
                    countUnsupported("graphicsLayer:colorFilter")
                    return false
                }
                commands.addCommand(
                    COMMAND_SAVE_LAYER_COLOR_FILTER_REF,
                    COMMAND_RECORD_FLAGS_NONE,
                    0,
                    0,
                    width.roundToInt().coerceAtLeast(0),
                    height.roundToInt().coerceAtLeast(0),
                    (paintLayerAlpha * 1000f).roundToInt().coerceIn(0, 1000),
                    handle.highInt(),
                    handle.lowInt(),
                )
                layerSaveCount++
            } else if (blendMode != null) {
                commands.addCommand(
                    COMMAND_SAVE_LAYER_BLEND_MODE,
                    COMMAND_RECORD_FLAGS_NONE,
                    0,
                    0,
                    width.roundToInt().coerceAtLeast(0),
                    height.roundToInt().coerceAtLeast(0),
                    (paintLayerAlpha * 1000f).roundToInt().coerceIn(0, 1000),
                    blendMode,
                )
                layerSaveCount++
            } else if (imageFilter == null) {
                commands.addSaveLayer(
                    0,
                    0,
                    width.roundToInt().coerceAtLeast(0),
                    height.roundToInt().coerceAtLeast(0),
                    (alpha * 1000f).roundToInt().coerceIn(0, 1000),
                )
                layerSaveCount++
            }
            if (clipToLayerBounds) {
                clipRect(0f, 0f, width, height, ClipOp.Intersect)
            }
            if (clipRect != null) {
                clipRect(clipRect.left, clipRect.top, clipRect.right, clipRect.bottom, ClipOp.Intersect)
            }
            if (clipPath != null) {
                clipPath(clipPath, ClipOp.Intersect)
            }
            commands.appendRecords(childCommands, COMMAND_STREAM_HEADER_SIZE, childCommands.size)
            rememberDefinedImageKeys(recording.imageDefinedKeys)
            imageReferencedKeys.addAll(recording.imageReferencedKeys.asIterable())
            nativeImageReferences.addAll(recording.nativeImageReferences)
            imageDefineCount += recording.imageDefineCount
            imageDefineWordCount += recording.imageDefineWordCount
            imageDefinePixelCount += recording.imageDefinePixelCount
            imageRefCount += recording.imageRefCount
            textCommandCount += recording.textCommandCount
            paragraphTextCommandCount += recording.paragraphTextCommandCount
            imageCacheClearCount += recording.imageCacheClearCount
            imageCacheEvictCount += recording.imageCacheEvictCount
            repeat(layerSaveCount) {
                restore()
            }
            restore()
            return true
        }

        private fun rememberDefinedImageKeys(cacheKeys: LongArray) {
            if (cacheKeys.isEmpty()) return
            synchronized(imageCacheLock) {
                cacheKeys.forEach { cacheKey ->
                    if (definedImageKeys.size >= MAX_DEFINED_IMAGE_KEYS && !definedImageKeys.containsKey(cacheKey)) {
                        definedImageKeys.remove(definedImageKeys.keys.first())
                    }
                    definedImageKeys[cacheKey] = Unit
                }
            }
        }

        private fun addLayerShadow(
            width: Float,
            height: Float,
            elevation: Float,
            layerAlpha: Float,
            ambientColor: Color,
            spotColor: Color,
            shapePath: Path?,
        ): Boolean {
            if (!width.isFinite() || !height.isFinite() || width < 0f || height < 0f ||
                !elevation.isFinite() || elevation <= 0f
            ) {
                countUnsupported("graphicsLayer:shadow")
                return false
            }
            val shadowPath = shapePath ?: Path().apply {
                addRect(Rect(0f, 0f, width, height))
            }
            if (addLayerShadowPath(shadowPath, elevation, layerAlpha, ambientColor, spotColor)) {
                return true
            }
            if (!addLayerShadowPass(
                    width = width,
                    height = height,
                    sigma = (elevation * 0.35f).coerceAtLeast(1f),
                    offsetY = 0f,
                    color = ambientColor,
                    alphaScale = 0.18f,
                    shapePath = shapePath,
                )
            ) {
                return false
            }
            return addLayerShadowPass(
                width = width,
                height = height,
                sigma = (elevation * 0.5f).coerceAtLeast(1f),
                offsetY = (elevation * 0.35f).coerceAtLeast(1f),
                color = spotColor,
                alphaScale = 0.28f,
                shapePath = shapePath,
            )
        }

        private fun addLayerShadowPath(
            path: Path,
            elevation: Float,
            layerAlpha: Float,
            ambientColor: Color,
            spotColor: Color,
        ): Boolean {
            val pathData = path.commandData() ?: run {
                countUnsupported("graphicsLayer:shadowPath")
                return false
            }
            val ambientAlpha = (ambientColor.alpha * shadowContext.ambientShadowAlpha * layerAlpha).coerceIn(0f, 1f)
            val spotAlpha = (spotColor.alpha * shadowContext.spotShadowAlpha * layerAlpha).coerceIn(0f, 1f)
            if (ambientAlpha <= 0f && spotAlpha <= 0f) return true
            commands.addCommand(
                COMMAND_DRAW_SHADOW_PATH,
                COMMAND_RECORD_FLAG_ANTIALIAS,
                ambientColor.copy(alpha = ambientAlpha).toArgb(),
                spotColor.copy(alpha = spotAlpha).toArgb(),
                0f.toRawBits(),
                0f.toRawBits(),
                elevation.toRawBits(),
                shadowContext.lightX.toRawBits(),
                shadowContext.lightY.toRawBits(),
                shadowContext.lightZ.toRawBits(),
                shadowContext.lightRadius.toRawBits(),
                if (layerAlpha < 1f) 1 else 0,
                path.fillType.commandValue(),
                pathData.size,
                *pathData,
            )
            return true
        }

        private fun addLayerShadowPass(
            width: Float,
            height: Float,
            sigma: Float,
            offsetY: Float,
            color: Color,
            alphaScale: Float,
            shapePath: Path?,
        ): Boolean {
            val pad = (sigma * 3f).roundToInt().coerceAtLeast(1)
            val shadowAlpha = (color.alpha * alphaScale).coerceIn(0f, 1f)
            if (shadowAlpha <= 0f) return true
            val shadowFilter = ImageFilterDescriptor.Blur(
                sigmaX = sigma,
                sigmaY = sigma,
                tileMode = 3,
            )
            val handle = defineImageFilterIfNeeded(shadowFilter) ?: run {
                countUnsupported("graphicsLayer:shadowFilter")
                return false
            }
            commands.addCommand(
                COMMAND_SAVE_LAYER_IMAGE_FILTER_REF,
                COMMAND_RECORD_FLAGS_NONE,
                -pad,
                (offsetY - pad).roundToInt(),
                (width + pad * 2f).roundToInt().coerceAtLeast(0),
                (height + pad * 2f).roundToInt().coerceAtLeast(0),
                1000,
                handle.highInt(),
                handle.lowInt(),
            )
            if (shapePath != null) {
                save()
                translate(0f, offsetY)
                clipPath(shapePath, ClipOp.Intersect)
                commands.addCommand(
                    COMMAND_FILL_RECT,
                    COMMAND_RECORD_FLAG_ANTIALIAS,
                    color.copy(alpha = shadowAlpha).toArgb(),
                    0,
                    0,
                    width.roundToInt().coerceAtLeast(0),
                    height.roundToInt().coerceAtLeast(0),
                    0,
                )
                restore()
            } else {
                commands.addCommand(
                    COMMAND_FILL_RECT,
                    COMMAND_RECORD_FLAG_ANTIALIAS,
                    color.copy(alpha = shadowAlpha).toArgb(),
                    0,
                    offsetY.roundToInt(),
                    width.roundToInt().coerceAtLeast(0),
                    height.roundToInt().coerceAtLeast(0),
                    0,
                )
            }
            restore()
            return true
        }

        fun clipRect(left: Float, top: Float, right: Float, bottom: Float, clipOp: ClipOp) {
            commands.addCommand(
                COMMAND_CLIP_RECT,
                COMMAND_RECORD_FLAG_ANTIALIAS,
                state.x(left),
                state.y(top),
                state.width(right - left),
                state.height(bottom - top),
                clipOp.commandValue(),
            )
        }

        fun clipPath(path: Path, clipOp: ClipOp) {
            val pathData = path.commandData() ?: run {
                countUnsupported("clipPath")
                state = state.copy(supported = false)
                return
            }
            commands.addCommand(
                COMMAND_CLIP_PATH,
                COMMAND_RECORD_FLAG_ANTIALIAS,
                clipOp.commandValue(),
                path.fillType.commandValue(),
                pathData.size,
                *pathData,
            )
        }

        fun drawLine(p1: Offset, p2: Offset, paint: Paint) {
            val dashPathEffect = paint.dashPathEffect
            if (dashPathEffect != null) {
                addDashedLine(p1, p2, paint, dashPathEffect)
                return
            }
            if (!paint.isSupportedSolidColorForBlendLayer) return
            val outset = paint.blendLayerOutset(forceStroke = true)
            withSolidColorBlendLayer(
                left = minOf(p1.x, p2.x) - outset,
                top = minOf(p1.y, p2.y) - outset,
                right = maxOf(p1.x, p2.x) + outset,
                bottom = maxOf(p1.y, p2.y) + outset,
                paint = paint,
            ) {
                commands.addCommand(
                    COMMAND_STROKE_LINE,
                    paint.recordFlags(),
                    paint.commandColor(),
                    state.x(p1.x),
                    state.y(p1.y),
                    state.x(p2.x),
                    state.y(p2.y),
                    state.stroke(paint.strokeWidth),
                    paint.strokeCap.commandValue(),
                    paint.strokeJoin.commandValue(),
                    paint.strokeMiter1000(),
                )
            }
        }

        fun drawPointLines(points: List<Offset>, paint: Paint, stepBy: Int) {
            if (points.size < 2) return
            var i = 0
            while (i < points.size - 1) {
                drawLine(points[i], points[i + 1], paint)
                i += stepBy
            }
        }

        fun drawRawPointLines(points: FloatArray, paint: Paint, stepBy: Int) {
            if (points.size < 4 || points.size % 2 != 0) return
            var i = 0
            while (i < points.size - 3) {
                drawLine(
                    p1 = Offset(points[i], points[i + 1]),
                    p2 = Offset(points[i + 2], points[i + 3]),
                    paint = paint,
                )
                i += stepBy * 2
            }
        }

        fun drawPoints(points: List<Offset>, paint: Paint) {
            if (points.isEmpty()) return
            if (!paint.isSupportedSolidColorForBlendLayer) return
            val payload = IntArray(points.size * 2)
            var payloadIndex = 0
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (point in points) {
                if (!point.x.isFinite() || !point.y.isFinite()) {
                    countUnsupported("points")
                    return
                }
                minX = minOf(minX, point.x)
                minY = minOf(minY, point.y)
                maxX = maxOf(maxX, point.x)
                maxY = maxOf(maxY, point.y)
                payload[payloadIndex++] = state.x(point.x)
                payload[payloadIndex++] = state.y(point.y)
            }
            val outset = paint.blendLayerOutset(forceStroke = true)
            withSolidColorBlendLayer(minX - outset, minY - outset, maxX + outset, maxY + outset, paint) {
                commands.addCommand(
                    COMMAND_DRAW_POINTS,
                    paint.recordFlags(),
                    paint.commandColor(),
                    state.stroke(paint.strokeWidth),
                    paint.strokeCap.commandValue(),
                    paint.strokeJoin.commandValue(),
                    paint.strokeMiter1000(),
                    points.size,
                    *payload,
                )
            }
        }

        fun drawRawPoints(points: FloatArray, paint: Paint) {
            if (points.isEmpty() || points.size % 2 != 0) return
            if (!paint.isSupportedSolidColorForBlendLayer) return
            val pointCount = points.size / 2
            val payload = IntArray(points.size)
            var sourceIndex = 0
            var payloadIndex = 0
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            while (sourceIndex < points.size - 1) {
                val x = points[sourceIndex]
                val y = points[sourceIndex + 1]
                if (!x.isFinite() || !y.isFinite()) {
                    countUnsupported("points")
                    return
                }
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
                payload[payloadIndex++] = state.x(x)
                payload[payloadIndex++] = state.y(y)
                sourceIndex += 2
            }
            val outset = paint.blendLayerOutset(forceStroke = true)
            withSolidColorBlendLayer(minX - outset, minY - outset, maxX + outset, maxY + outset, paint) {
                commands.addCommand(
                    COMMAND_DRAW_POINTS,
                    paint.recordFlags(),
                    paint.commandColor(),
                    state.stroke(paint.strokeWidth),
                    paint.strokeCap.commandValue(),
                    paint.strokeJoin.commandValue(),
                    paint.strokeMiter1000(),
                    pointCount,
                    *payload,
                )
            }
        }

        fun drawVertices(vertices: Vertices, blendMode: BlendMode, paint: Paint): Boolean {
            if (!paint.isSupportedSolidColorForBlendLayer) return false
            val commandBlendMode = commandShaderBlendModeOrNull(blendMode) ?: run {
                countUnsupported("blendMode_${blendMode.toReasonToken()}")
                return false
            }
            val commandVertexMode = vertices.vertexMode.commandValue()
            if (commandVertexMode < 0) return false
            val vertexCount = vertices.positions.size / 2
            val indexCount = vertices.indices.size
            if (vertexCount < 3 || vertexCount > 4096 || indexCount > 8192) return false
            val payload = IntArray(vertexCount * 5 + indexCount)
            var payloadIndex = 0
            var pointIndex = 0
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            while (pointIndex < vertices.positions.size - 1) {
                val x = vertices.positions[pointIndex]
                val y = vertices.positions[pointIndex + 1]
                if (!x.isFinite() || !y.isFinite()) return false
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
                payload[payloadIndex++] = state.x(x)
                payload[payloadIndex++] = state.y(y)
                pointIndex += 2
            }
            pointIndex = 0
            while (pointIndex < vertices.textureCoordinates.size - 1) {
                val x = vertices.textureCoordinates[pointIndex]
                val y = vertices.textureCoordinates[pointIndex + 1]
                if (!x.isFinite() || !y.isFinite()) return false
                payload[payloadIndex++] = x.toRawBits()
                payload[payloadIndex++] = y.toRawBits()
                pointIndex += 2
            }
            for (color in vertices.colors) {
                payload[payloadIndex++] = color
            }
            for (index in vertices.indices) {
                payload[payloadIndex++] = index.toInt() and 0xffff
            }
            withSolidColorBlendLayer(minX, minY, maxX, maxY, paint) {
                commands.addCommand(
                    COMMAND_DRAW_VERTICES,
                    paint.recordFlags(),
                    commandVertexMode,
                    commandBlendMode,
                    paint.color.toArgb(),
                    vertexCount,
                    indexCount,
                    *payload,
                )
            }
            return true
        }

        private fun addDashedLine(
            p1: Offset,
            p2: Offset,
            paint: Paint,
            dashPathEffect: JbrSkiaDashPathEffect,
        ) {
            if (!paint.isSupportedDashedSolidColorForBlendLayer) return
            val intervals = dashPathEffect.intervals
            if (
                intervals.size !in 2..16 ||
                intervals.any { !it.isFinite() || it <= 0f } ||
                !dashPathEffect.phase.isFinite() ||
                dashPathEffect.phase < 0f
            ) {
                countUnsupported("pathEffect")
                return
            }
            val outset = paint.blendLayerOutset(forceStroke = true)
            withSolidColorBlendLayer(
                left = minOf(p1.x, p2.x) - outset,
                top = minOf(p1.y, p2.y) - outset,
                right = maxOf(p1.x, p2.x) + outset,
                bottom = maxOf(p1.y, p2.y) + outset,
                paint = paint,
            ) {
                commands.addCommand(
                    COMMAND_STROKE_LINE_DASH_PATH_EFFECT,
                    paint.recordFlags(),
                    paint.commandColor(),
                    state.x(p1.x),
                    state.y(p1.y),
                    state.x(p2.x),
                    state.y(p2.y),
                    state.stroke(paint.strokeWidth),
                    paint.strokeCap.commandValue(),
                    paint.strokeJoin.commandValue(),
                    paint.strokeMiter1000(),
                    dashPathEffect.phase.fixed1000(),
                    intervals.size,
                    *IntArray(intervals.size) { intervals[it].fixed1000() },
                )
            }
        }

        fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            if (paint.blendMode == BlendMode.Clear) {
                addClearRect(left, top, right, bottom)
                return
            }
            val descriptor = shaderDescriptorOrNull(paint.shader)
            val shaderColorFilter = descriptorColorFilterOrNull(paint.colorFilter)
            if (descriptor != null && shaderColorFilter != null && paint.style == PaintingStyle.Fill) {
                addShaderDescriptorRect(left, top, right, bottom, paint, ShaderDescriptor.ColorFiltered(descriptor, shaderColorFilter))
                return
            }
            if (descriptor is ShaderDescriptor.Composite ||
                descriptor is ShaderDescriptor.RuntimeEffect ||
                descriptor is ShaderDescriptor.Transformed ||
                descriptor is ShaderDescriptor.Color ||
                descriptor is ShaderDescriptor.PerlinNoise
            ) {
                addShaderDescriptorRect(left, top, right, bottom, paint, descriptor)
                return
            }
            if (paint.shader?.jbrSkiaLinearGradient != null) {
                addLinearGradientRect(left, top, right, bottom, paint)
                return
            }
            if (paint.shader?.jbrSkiaRadialGradient != null) {
                addRadialGradientRect(left, top, right, bottom, paint)
                return
            }
            if (paint.shader?.jbrSkiaSweepGradient != null) {
                addSweepGradientRect(left, top, right, bottom, paint)
                return
            }
            if (paint.shader?.jbrSkiaImageShader != null) {
                addImageShaderRect(left, top, right, bottom, paint)
                return
            }
            if (paint.shader == null && paint.colorFilter == null && paint.style == PaintingStyle.Fill) {
                when (paint.blendMode) {
                    BlendMode.Plus -> {
                        addBlendModeFillRect(left, top, right, bottom, paint, COMMAND_BLEND_MODE_PLUS)
                        return
                    }
                    BlendMode.Multiply -> {
                        addBlendModeFillRect(left, top, right, bottom, paint, COMMAND_BLEND_MODE_MULTIPLY)
                        return
                    }
                    BlendMode.Screen -> {
                        addBlendModeFillRect(left, top, right, bottom, paint, COMMAND_BLEND_MODE_SCREEN)
                        return
                    }
                    BlendMode.Overlay -> {
                        addBlendModeFillRect(left, top, right, bottom, paint, COMMAND_BLEND_MODE_OVERLAY)
                        return
                    }
                    BlendMode.Darken -> {
                        addBlendModeFillRect(left, top, right, bottom, paint, COMMAND_BLEND_MODE_DARKEN)
                        return
                    }
                    BlendMode.Lighten -> {
                        addBlendModeFillRect(left, top, right, bottom, paint, COMMAND_BLEND_MODE_LIGHTEN)
                        return
                    }
                    BlendMode.Difference -> {
                        addBlendModeFillRect(left, top, right, bottom, paint, COMMAND_BLEND_MODE_DIFFERENCE)
                        return
                    }
                    BlendMode.Exclusion -> {
                        addBlendModeFillRect(left, top, right, bottom, paint, COMMAND_BLEND_MODE_EXCLUSION)
                        return
                    }
                    BlendMode.ColorDodge -> {
                        addBlendModeFillRect(left, top, right, bottom, paint, COMMAND_BLEND_MODE_COLOR_DODGE)
                        return
                    }
                    BlendMode.ColorBurn -> {
                        addBlendModeFillRect(left, top, right, bottom, paint, COMMAND_BLEND_MODE_COLOR_BURN)
                        return
                    }
                    BlendMode.Hardlight -> {
                        addBlendModeFillRect(left, top, right, bottom, paint, COMMAND_BLEND_MODE_HARDLIGHT)
                        return
                    }
                    BlendMode.Softlight -> {
                        addBlendModeFillRect(left, top, right, bottom, paint, COMMAND_BLEND_MODE_SOFTLIGHT)
                        return
                    }
                    BlendMode.Hue -> {
                        addBlendModeFillRect(left, top, right, bottom, paint, COMMAND_BLEND_MODE_HUE)
                        return
                    }
                    BlendMode.Saturation -> {
                        addBlendModeFillRect(left, top, right, bottom, paint, COMMAND_BLEND_MODE_SATURATION)
                        return
                    }
                    BlendMode.Color -> {
                        addBlendModeFillRect(left, top, right, bottom, paint, COMMAND_BLEND_MODE_COLOR)
                        return
                    }
                    BlendMode.Luminosity -> {
                        addBlendModeFillRect(left, top, right, bottom, paint, COMMAND_BLEND_MODE_LUMINOSITY)
                        return
                    }
                    else -> Unit
                }
            }
            val tintColorFilter = paint.tintSrcInColorFilter
            if (tintColorFilter != null && paint.shader == null && paint.style == PaintingStyle.Fill) {
                if (java.lang.Boolean.getBoolean(COLOR_FILTER_HANDLES_PROPERTY)) {
                    addTintColorFilterHandleFillRect(left, top, right, bottom, paint, tintColorFilter)
                } else {
                    addTintColorFilterFillRect(left, top, right, bottom, paint, tintColorFilter)
                }
                return
            }
            val colorMatrixFilter = paint.colorMatrixColorFilter
            if (colorMatrixFilter != null && paint.shader == null && paint.style == PaintingStyle.Fill) {
                addColorMatrixFilterHandleFillRect(left, top, right, bottom, paint, colorMatrixFilter)
                return
            }
            val lightingFilter = paint.lightingColorFilter
            if (lightingFilter != null && paint.shader == null && paint.style == PaintingStyle.Fill) {
                addLightingFilterHandleFillRect(left, top, right, bottom, paint, lightingFilter)
                return
            }
            val descriptorColorFilter = descriptorColorFilterOrNull(paint.colorFilter)
            if (descriptorColorFilter != null && paint.shader == null && paint.style == PaintingStyle.Fill) {
                addDescriptorColorFilterHandleFillRect(left, top, right, bottom, paint, descriptorColorFilter)
                return
            }
            val dashPathEffect = paint.dashPathEffect
            if (dashPathEffect != null && paint.style == PaintingStyle.Stroke) {
                addDashedRect(left, top, right, bottom, paint, dashPathEffect)
                return
            }
            if (!paint.isSupportedSolidColorForBlendLayer) return
            val x = state.x(left)
            val y = state.y(top)
            val width = state.width(right - left)
            val height = state.height(bottom - top)
            val outset = paint.blendLayerOutset()
            withSolidColorBlendLayer(left - outset, top - outset, right + outset, bottom + outset, paint) {
                when (paint.style) {
                    PaintingStyle.Fill -> commands.addCommand(COMMAND_FILL_RECT, paint.recordFlags(), paint.commandColor(), x, y, width, height, 0)
                    PaintingStyle.Stroke -> addSolidStrokeRect(left, top, right, bottom, paint)
                    else -> countUnsupported("paintStyle")
                }
            }
        }

        private fun addSolidStrokeRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            paint: Paint,
        ) {
            commands.addCommand(
                COMMAND_DRAW_ROUND_RECT,
                paint.recordFlags(),
                COMMAND_PAINT_STYLE_STROKE,
                paint.commandColor(),
                left.fixed1000(),
                top.fixed1000(),
                right.fixed1000(),
                bottom.fixed1000(),
                0,
                0,
                state.stroke(paint.strokeWidth),
                paint.strokeCap.commandValue(),
                paint.strokeJoin.commandValue(),
                paint.strokeMiter1000(),
            )
        }

        private fun addDashedRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            paint: Paint,
            dashPathEffect: JbrSkiaDashPathEffect,
        ) {
            if (!paint.isSupportedDashedSolidColorForBlendLayer) return
            val intervals = dashPathEffect.intervals
            if (
                intervals.size !in 2..16 ||
                intervals.any { !it.isFinite() || it <= 0f } ||
                !dashPathEffect.phase.isFinite() ||
                dashPathEffect.phase < 0f
            ) {
                countUnsupported("pathEffect")
                return
            }
            val outset = paint.blendLayerOutset(forceStroke = true)
            withSolidColorBlendLayer(left - outset, top - outset, right + outset, bottom + outset, paint) {
                commands.addCommand(
                    COMMAND_STROKE_RECT_DASH_PATH_EFFECT,
                    paint.recordFlags(),
                    paint.commandColor(),
                    state.x(left),
                    state.y(top),
                    state.width(right - left),
                    state.height(bottom - top),
                    state.stroke(paint.strokeWidth),
                    paint.strokeCap.commandValue(),
                    paint.strokeJoin.commandValue(),
                    paint.strokeMiter1000(),
                    dashPathEffect.phase.fixed1000(),
                    intervals.size,
                    *IntArray(intervals.size) { intervals[it].fixed1000() },
                )
            }
        }

        private fun addBlendModeFillRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            paint: Paint,
            blendMode: Int,
        ) {
            if (!state.supported) {
                countUnsupported("unsupportedScope")
                return
            }
            paint.colorFilter?.let {
                countUnsupported(it.unsupportedReasonToken())
                return
            }
            if (paint.pathEffect != null) {
                countUnsupported("pathEffect")
                return
            }
            commands.addCommand(
                COMMAND_FILL_RECT_BLEND_MODE,
                paint.recordFlags(),
                paint.commandColor(),
                blendMode,
                state.x(left),
                state.y(top),
                state.width(right - left),
                state.height(bottom - top),
            )
        }

        private fun addTintColorFilterFillRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            paint: Paint,
            colorFilter: BlendModeColorFilter,
        ) {
            if (!paint.isSupportedFillRectColorFilterForBlendLayer) return
            withSolidColorBlendLayer(left, top, right, bottom, paint) {
                commands.addCommand(
                    COMMAND_FILL_RECT_COLOR_FILTER,
                    paint.recordFlags(),
                    paint.commandColor(),
                    colorFilter.color.toArgb(),
                    COMMAND_BLEND_MODE_SRC_IN,
                    state.x(left),
                    state.y(top),
                    state.width(right - left),
                    state.height(bottom - top),
                )
            }
        }

        private fun addTintColorFilterHandleFillRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            paint: Paint,
            colorFilter: BlendModeColorFilter,
        ) {
            if (!paint.isSupportedFillRectColorFilterForBlendLayer) return
            val handle = colorFilter.handleKey()
            defineTintColorFilterIfNeeded(handle, colorFilter)
            withSolidColorBlendLayer(left, top, right, bottom, paint) {
                commands.addCommand(
                    COMMAND_FILL_RECT_COLOR_FILTER_REF,
                    paint.recordFlags(),
                    paint.commandColor(),
                    handle.highInt(),
                    handle.lowInt(),
                    state.x(left),
                    state.y(top),
                    state.width(right - left),
                    state.height(bottom - top),
                )
            }
        }

        private fun defineTintColorFilterIfNeeded(handle: Long, colorFilter: BlendModeColorFilter) {
            var evictedHandle: Long? = null
            val shouldDefine = synchronized(colorFilterHandleLock) {
                val alreadyDefined = definedColorFilterHandles.containsKey(handle)
                if (!alreadyDefined) {
                    if (definedColorFilterHandles.size >= MAX_DEFINED_COLOR_FILTER_HANDLES) {
                        val eldest = definedColorFilterHandles.keys.first()
                        definedColorFilterHandles.remove(eldest)
                        confirmedColorFilterHandles.remove(eldest)
                        evictedHandle = eldest
                    }
                }
                definedColorFilterHandles[handle] = Unit
                forceResourceDefinitions || handle !in confirmedColorFilterHandles
            }
            evictedHandle?.let {
                commands.addCommand(
                    COMMAND_EVICT_COLOR_FILTER_HANDLE,
                    COMMAND_RECORD_FLAGS_NONE,
                    it.highInt(),
                    it.lowInt(),
                )
            }
            if (shouldDefine) {
                commands.addCommand(
                    COMMAND_DEFINE_EFFECT_DESCRIPTOR,
                    COMMAND_RECORD_FLAGS_NONE,
                    handle.highInt(),
                    handle.lowInt(),
                    COMMAND_EFFECT_DESCRIPTOR_TINT_COLOR_FILTER,
                    COMMAND_EFFECT_DESCRIPTOR_VERSION_1,
                    2,
                    colorFilter.color.toArgb(),
                    colorFilter.commandBlendModeOrNull() ?: return,
                )
            }
        }

        private fun defineDescriptorColorFilterIfNeeded(colorFilter: ColorFilter): Long? =
            when (colorFilter) {
                is BlendModeColorFilter -> {
                    colorFilter.commandBlendModeOrNull()?.let {
                        colorFilter.handleKey().also { defineTintColorFilterIfNeeded(it, colorFilter) }
                    }
                }
                is ColorMatrixColorFilter -> {
                    val matrix = colorFilter.skiaColorMatrixValues()
                    if (matrix.any { !java.lang.Float.isFinite(it) }) {
                        countUnsupported("colorMatrixNonfinite")
                        null
                    } else {
                        matrix.colorMatrixHandleKey().also { defineColorMatrixFilterIfNeeded(it, matrix) }
                    }
                }
                is LightingColorFilter -> {
                    colorFilter.lightingHandleKey().also { defineLightingFilterIfNeeded(it, colorFilter) }
                }
                is JbrSkiaRuntimeEffectColorFilterHolder -> {
                    val payload = colorFilter.jbrSkiaRuntimeEffectColorFilter.runtimeEffectColorFilterDescriptorPayload()
                        ?: return null
                    val handle = effectDescriptorHandleKey(COMMAND_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER, payload)
                    defineEffectDescriptorIfNeeded(handle, COMMAND_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER, payload)
                    handle
                }
                else -> null
            }

        private fun defineEffectDescriptorIfNeeded(handle: Long, type: Int, payload: IntArray) {
            var evictedHandle: Long? = null
            val shouldDefine = synchronized(colorFilterHandleLock) {
                val alreadyDefined = definedColorFilterHandles.containsKey(handle)
                if (!alreadyDefined) {
                    if (definedColorFilterHandles.size >= MAX_DEFINED_COLOR_FILTER_HANDLES) {
                        val eldest = definedColorFilterHandles.keys.first()
                        definedColorFilterHandles.remove(eldest)
                        confirmedColorFilterHandles.remove(eldest)
                        evictedHandle = eldest
                    }
                }
                definedColorFilterHandles[handle] = Unit
                forceResourceDefinitions || handle !in confirmedColorFilterHandles
            }
            evictedHandle?.let {
                commands.addCommand(
                    COMMAND_EVICT_COLOR_FILTER_HANDLE,
                    COMMAND_RECORD_FLAGS_NONE,
                    it.highInt(),
                    it.lowInt(),
                )
            }
            if (shouldDefine) {
                commands.addCommand(
                    COMMAND_DEFINE_EFFECT_DESCRIPTOR,
                    COMMAND_RECORD_FLAGS_NONE,
                    handle.highInt(),
                    handle.lowInt(),
                    type,
                    COMMAND_EFFECT_DESCRIPTOR_VERSION_1,
                    payload.size,
                    *payload,
                )
            }
        }

        private fun defineImageFilterIfNeeded(imageFilter: ImageFilterDescriptor): Long? =
            when (imageFilter) {
                is ImageFilterDescriptor.Blur -> {
                    if (!imageFilter.sigmaX.isFinite() || !imageFilter.sigmaY.isFinite()
                        || imageFilter.sigmaX < 0f || imageFilter.sigmaY < 0f
                        || imageFilter.tileMode !in 0..3
                    ) {
                        null
                    } else {
                        val inputHandle = imageFilter.input?.let { defineImageFilterIfNeeded(it) ?: return null }
                        val handle = imageFilter.blurHandleKey()
                        defineBlurImageFilterIfNeeded(handle, imageFilter, inputHandle)
                        handle
                    }
                }
                is ImageFilterDescriptor.Offset -> {
                    if (!imageFilter.dx.isFinite() || !imageFilter.dy.isFinite()) {
                        null
                    } else {
                        val inputHandle = imageFilter.input?.let { defineImageFilterIfNeeded(it) ?: return null }
                        val handle = imageFilter.offsetHandleKey()
                        defineOffsetImageFilterIfNeeded(handle, imageFilter, inputHandle)
                        handle
                    }
                }
            }

        private fun defineBlurImageFilterIfNeeded(
            handle: Long,
            imageFilter: ImageFilterDescriptor.Blur,
            inputHandle: Long?,
        ) {
            var evictedHandle: Long? = null
            val shouldDefine = synchronized(colorFilterHandleLock) {
                val alreadyDefined = definedColorFilterHandles.containsKey(handle)
                if (!alreadyDefined) {
                    if (definedColorFilterHandles.size >= MAX_DEFINED_COLOR_FILTER_HANDLES) {
                        val eldest = definedColorFilterHandles.keys.first()
                        definedColorFilterHandles.remove(eldest)
                        confirmedColorFilterHandles.remove(eldest)
                        evictedHandle = eldest
                    }
                }
                definedColorFilterHandles[handle] = Unit
                forceResourceDefinitions || handle !in confirmedColorFilterHandles
            }
            evictedHandle?.let {
                commands.addCommand(
                    COMMAND_EVICT_COLOR_FILTER_HANDLE,
                    COMMAND_RECORD_FLAGS_NONE,
                    it.highInt(),
                    it.lowInt(),
                )
            }
            if (shouldDefine) {
                if (inputHandle == null) {
                    commands.addCommand(
                        COMMAND_DEFINE_EFFECT_DESCRIPTOR,
                        COMMAND_RECORD_FLAGS_NONE,
                        handle.highInt(),
                        handle.lowInt(),
                        COMMAND_EFFECT_DESCRIPTOR_BLUR_IMAGE_FILTER,
                        COMMAND_EFFECT_DESCRIPTOR_VERSION_1,
                        3,
                        imageFilter.sigmaX.toRawBits(),
                        imageFilter.sigmaY.toRawBits(),
                        imageFilter.tileMode,
                    )
                } else {
                    commands.addCommand(
                        COMMAND_DEFINE_EFFECT_DESCRIPTOR,
                        COMMAND_RECORD_FLAGS_NONE,
                        handle.highInt(),
                        handle.lowInt(),
                        COMMAND_EFFECT_DESCRIPTOR_BLUR_IMAGE_FILTER_WITH_INPUT,
                        COMMAND_EFFECT_DESCRIPTOR_VERSION_1,
                        5,
                        inputHandle.highInt(),
                        inputHandle.lowInt(),
                        imageFilter.sigmaX.toRawBits(),
                        imageFilter.sigmaY.toRawBits(),
                        imageFilter.tileMode,
                    )
                }
            }
        }

        private fun defineOffsetImageFilterIfNeeded(
            handle: Long,
            imageFilter: ImageFilterDescriptor.Offset,
            inputHandle: Long?,
        ) {
            var evictedHandle: Long? = null
            val shouldDefine = synchronized(colorFilterHandleLock) {
                val alreadyDefined = definedColorFilterHandles.containsKey(handle)
                if (!alreadyDefined) {
                    if (definedColorFilterHandles.size >= MAX_DEFINED_COLOR_FILTER_HANDLES) {
                        val eldest = definedColorFilterHandles.keys.first()
                        definedColorFilterHandles.remove(eldest)
                        confirmedColorFilterHandles.remove(eldest)
                        evictedHandle = eldest
                    }
                }
                definedColorFilterHandles[handle] = Unit
                forceResourceDefinitions || handle !in confirmedColorFilterHandles
            }
            evictedHandle?.let {
                commands.addCommand(
                    COMMAND_EVICT_COLOR_FILTER_HANDLE,
                    COMMAND_RECORD_FLAGS_NONE,
                    it.highInt(),
                    it.lowInt(),
                )
            }
            if (shouldDefine) {
                if (inputHandle == null) {
                    commands.addCommand(
                        COMMAND_DEFINE_EFFECT_DESCRIPTOR,
                        COMMAND_RECORD_FLAGS_NONE,
                        handle.highInt(),
                        handle.lowInt(),
                        COMMAND_EFFECT_DESCRIPTOR_OFFSET_IMAGE_FILTER,
                        COMMAND_EFFECT_DESCRIPTOR_VERSION_1,
                        2,
                        imageFilter.dx.toRawBits(),
                        imageFilter.dy.toRawBits(),
                    )
                } else {
                    commands.addCommand(
                        COMMAND_DEFINE_EFFECT_DESCRIPTOR,
                        COMMAND_RECORD_FLAGS_NONE,
                        handle.highInt(),
                        handle.lowInt(),
                        COMMAND_EFFECT_DESCRIPTOR_OFFSET_IMAGE_FILTER_WITH_INPUT,
                        COMMAND_EFFECT_DESCRIPTOR_VERSION_1,
                        4,
                        inputHandle.highInt(),
                        inputHandle.lowInt(),
                        imageFilter.dx.toRawBits(),
                        imageFilter.dy.toRawBits(),
                    )
                }
            }
        }

        private fun addColorMatrixFilterHandleFillRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            paint: Paint,
            colorFilter: ColorMatrixColorFilter,
        ) {
            if (!paint.isSupportedFillRectColorFilterForBlendLayer) return
            val matrix = colorFilter.skiaColorMatrixValues()
            if (matrix.any { !java.lang.Float.isFinite(it) }) {
                countUnsupported("colorMatrixNonfinite")
                return
            }
            val handle = matrix.colorMatrixHandleKey()
            defineColorMatrixFilterIfNeeded(handle, matrix)
            withSolidColorBlendLayer(left, top, right, bottom, paint) {
                commands.addCommand(
                    COMMAND_FILL_RECT_COLOR_FILTER_REF,
                    paint.recordFlags(),
                    paint.commandColor(),
                    handle.highInt(),
                    handle.lowInt(),
                    state.x(left),
                    state.y(top),
                    state.width(right - left),
                    state.height(bottom - top),
                )
            }
        }

        private fun defineColorMatrixFilterIfNeeded(handle: Long, matrix: FloatArray) {
            var evictedHandle: Long? = null
            val shouldDefine = synchronized(colorFilterHandleLock) {
                val alreadyDefined = definedColorFilterHandles.containsKey(handle)
                if (!alreadyDefined) {
                    if (definedColorFilterHandles.size >= MAX_DEFINED_COLOR_FILTER_HANDLES) {
                        val eldest = definedColorFilterHandles.keys.first()
                        definedColorFilterHandles.remove(eldest)
                        confirmedColorFilterHandles.remove(eldest)
                        evictedHandle = eldest
                    }
                }
                definedColorFilterHandles[handle] = Unit
                forceResourceDefinitions || handle !in confirmedColorFilterHandles
            }
            evictedHandle?.let {
                commands.addCommand(
                    COMMAND_EVICT_COLOR_FILTER_HANDLE,
                    COMMAND_RECORD_FLAGS_NONE,
                    it.highInt(),
                    it.lowInt(),
                )
            }
            if (shouldDefine) {
                commands.addCommand(
                    COMMAND_DEFINE_EFFECT_DESCRIPTOR,
                    COMMAND_RECORD_FLAGS_NONE,
                    handle.highInt(),
                    handle.lowInt(),
                    COMMAND_EFFECT_DESCRIPTOR_COLOR_MATRIX_FILTER,
                    COMMAND_EFFECT_DESCRIPTOR_VERSION_1,
                    20,
                    *IntArray(20) { index -> matrix[index].toRawBits() },
                )
            }
        }

        private fun addLightingFilterHandleFillRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            paint: Paint,
            colorFilter: LightingColorFilter,
        ) {
            if (!paint.isSupportedFillRectColorFilterForBlendLayer) return
            val handle = colorFilter.lightingHandleKey()
            defineLightingFilterIfNeeded(handle, colorFilter)
            withSolidColorBlendLayer(left, top, right, bottom, paint) {
                commands.addCommand(
                    COMMAND_FILL_RECT_COLOR_FILTER_REF,
                    paint.recordFlags(),
                    paint.commandColor(),
                    handle.highInt(),
                    handle.lowInt(),
                    state.x(left),
                    state.y(top),
                    state.width(right - left),
                    state.height(bottom - top),
                )
            }
        }

        private fun defineLightingFilterIfNeeded(handle: Long, colorFilter: LightingColorFilter) {
            var evictedHandle: Long? = null
            val shouldDefine = synchronized(colorFilterHandleLock) {
                val alreadyDefined = definedColorFilterHandles.containsKey(handle)
                if (!alreadyDefined) {
                    if (definedColorFilterHandles.size >= MAX_DEFINED_COLOR_FILTER_HANDLES) {
                        val eldest = definedColorFilterHandles.keys.first()
                        definedColorFilterHandles.remove(eldest)
                        confirmedColorFilterHandles.remove(eldest)
                        evictedHandle = eldest
                    }
                }
                definedColorFilterHandles[handle] = Unit
                forceResourceDefinitions || handle !in confirmedColorFilterHandles
            }
            evictedHandle?.let {
                commands.addCommand(
                    COMMAND_EVICT_COLOR_FILTER_HANDLE,
                    COMMAND_RECORD_FLAGS_NONE,
                    it.highInt(),
                    it.lowInt(),
                )
            }
            if (shouldDefine) {
                commands.addCommand(
                    COMMAND_DEFINE_EFFECT_DESCRIPTOR,
                    COMMAND_RECORD_FLAGS_NONE,
                    handle.highInt(),
                    handle.lowInt(),
                    COMMAND_EFFECT_DESCRIPTOR_LIGHTING_FILTER,
                    COMMAND_EFFECT_DESCRIPTOR_VERSION_1,
                    2,
                    colorFilter.multiply.toArgb(),
                    colorFilter.add.toArgb(),
                )
            }
        }

        private fun addDescriptorColorFilterHandleFillRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            paint: Paint,
            colorFilter: ColorFilter,
        ) {
            if (!paint.isSupportedFillRectColorFilterForBlendLayer) return
            val handle = defineDescriptorColorFilterIfNeeded(colorFilter) ?: run {
                countUnsupported("colorFilterDescriptor")
                return
            }
            withSolidColorBlendLayer(left, top, right, bottom, paint) {
                commands.addCommand(
                    COMMAND_FILL_RECT_COLOR_FILTER_REF,
                    paint.recordFlags(),
                    paint.commandColor(),
                    handle.highInt(),
                    handle.lowInt(),
                    state.x(left),
                    state.y(top),
                    state.width(right - left),
                    state.height(bottom - top),
                )
            }
        }

        fun drawRoundRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radiusX: Float,
            radiusY: Float,
            paint: Paint,
        ) {
            if (paint.blendMode == BlendMode.Clear) {
                addClearRect(left, top, right, bottom)
                return
            }
            if (paint.shader?.jbrSkiaLinearGradient != null) {
                addLinearGradientRoundRect(left, top, right, bottom, radiusX, radiusY, paint)
                return
            }
            if (paint.shader?.jbrSkiaRadialGradient != null) {
                addRadialGradientRoundRect(left, top, right, bottom, radiusX, radiusY, paint)
                return
            }
            if (paint.shader?.jbrSkiaSweepGradient != null) {
                addSweepGradientRoundRect(left, top, right, bottom, radiusX, radiusY, paint)
                return
            }
            val dashPathEffect = paint.dashPathEffect
            if (dashPathEffect != null && paint.style == PaintingStyle.Stroke) {
                addDashedRoundRect(left, top, right, bottom, radiusX, radiusY, paint, dashPathEffect)
                return
            }
            if (!paint.isSupportedSolidColorForBlendLayer) return
            val style = when (paint.style) {
                PaintingStyle.Fill -> COMMAND_PAINT_STYLE_FILL
                PaintingStyle.Stroke -> COMMAND_PAINT_STYLE_STROKE
                else -> {
                    countUnsupported("roundRectStyle")
                    return
                }
            }
            val outset = paint.blendLayerOutset()
            withSolidColorBlendLayer(left - outset, top - outset, right + outset, bottom + outset, paint) {
                val left1000 = left.fixed1000()
                val top1000 = top.fixed1000()
                val right1000 = right.fixed1000()
                val bottom1000 = bottom.fixed1000()
                val radiusX1000 = radiusX.fixed1000().coerceAtLeast(0)
                val radiusY1000 = radiusY.fixed1000().coerceAtLeast(0)
                if (paint.style == PaintingStyle.Fill) {
                    commands.addCommand(
                        COMMAND_FILL_ROUND_RECT,
                        paint.recordFlags(),
                        paint.commandColor(),
                        left1000,
                        top1000,
                        right1000,
                        bottom1000,
                        radiusX1000,
                        radiusY1000,
                    )
                } else {
                    commands.addCommand(
                        COMMAND_DRAW_ROUND_RECT,
                        paint.recordFlags(),
                        style,
                        paint.commandColor(),
                        left1000,
                        top1000,
                        right1000,
                        bottom1000,
                        radiusX1000,
                        radiusY1000,
                        state.stroke(paint.strokeWidth),
                        paint.strokeCap.commandValue(),
                        paint.strokeJoin.commandValue(),
                        paint.strokeMiter1000(),
                    )
                }
            }
        }

        private fun addDashedRoundRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radiusX: Float,
            radiusY: Float,
            paint: Paint,
            dashPathEffect: JbrSkiaDashPathEffect,
        ) {
            if (!paint.isSupportedDashedSolidColorForBlendLayer) return
            val intervals = dashPathEffect.intervals
            if (
                intervals.size !in 2..16 ||
                intervals.any { !it.isFinite() || it <= 0f } ||
                !dashPathEffect.phase.isFinite() ||
                !radiusX.isFinite() ||
                !radiusY.isFinite() ||
                dashPathEffect.phase < 0f
            ) {
                countUnsupported("pathEffect")
                return
            }
            val outset = paint.blendLayerOutset(forceStroke = true)
            withSolidColorBlendLayer(left - outset, top - outset, right + outset, bottom + outset, paint) {
                commands.addCommand(
                    COMMAND_STROKE_ROUND_RECT_DASH_PATH_EFFECT,
                    paint.recordFlags(),
                    paint.commandColor(),
                    left.fixed1000(),
                    top.fixed1000(),
                    right.fixed1000(),
                    bottom.fixed1000(),
                    radiusX.fixed1000().coerceAtLeast(0),
                    radiusY.fixed1000().coerceAtLeast(0),
                    state.stroke(paint.strokeWidth),
                    paint.strokeCap.commandValue(),
                    paint.strokeJoin.commandValue(),
                    paint.strokeMiter1000(),
                    dashPathEffect.phase.fixed1000(),
                    intervals.size,
                    *IntArray(intervals.size) { intervals[it].fixed1000() },
                )
            }
        }

        fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            if (paint.blendMode == BlendMode.Clear) {
                addClearRect(left, top, right, bottom)
                return
            }
            if (!paint.isSupportedSolidColorForBlendLayer) return
            val op = when (paint.style) {
                PaintingStyle.Fill -> COMMAND_FILL_OVAL
                PaintingStyle.Stroke -> COMMAND_STROKE_OVAL
                else -> {
                    countUnsupported("paintStyle")
                    return
                }
            }
            val strokeArgs = if (paint.style == PaintingStyle.Stroke) {
                intArrayOf(
                    state.stroke(paint.strokeWidth),
                    paint.strokeCap.commandValue(),
                    paint.strokeJoin.commandValue(),
                    paint.strokeMiter1000(),
                )
            } else {
                intArrayOf()
            }
            val outset = paint.blendLayerOutset()
            withSolidColorBlendLayer(left - outset, top - outset, right + outset, bottom + outset, paint) {
                commands.addCommand(
                    op,
                    paint.recordFlags(),
                    paint.commandColor(),
                    state.x(left),
                    state.y(top),
                    state.width(right - left),
                    state.height(bottom - top),
                    *strokeArgs,
                )
            }
        }

        fun drawCircle(center: Offset, radius: Float, paint: Paint) {
            drawOval(center.x - radius, center.y - radius, center.x + radius, center.y + radius, paint)
        }

        fun drawArc(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            startAngle: Float,
            sweepAngle: Float,
            useCenter: Boolean,
            paint: Paint,
        ) {
            if (!paint.isSupportedSolidColorForBlendLayer) return
            val style = when (paint.style) {
                PaintingStyle.Fill -> COMMAND_PAINT_STYLE_FILL
                PaintingStyle.Stroke -> COMMAND_PAINT_STYLE_STROKE
                else -> {
                    countUnsupported("paintStyle")
                    return
                }
            }
            val outset = paint.blendLayerOutset()
            withSolidColorBlendLayer(left - outset, top - outset, right + outset, bottom + outset, paint) {
                commands.addCommand(
                    COMMAND_DRAW_ARC,
                    paint.recordFlags(),
                    style,
                    paint.commandColor(),
                    left.fixed1000(),
                    top.fixed1000(),
                    right.fixed1000(),
                    bottom.fixed1000(),
                    startAngle.fixed1000(),
                    sweepAngle.fixed1000(),
                    if (useCenter) 1 else 0,
                    if (paint.style == PaintingStyle.Stroke) state.stroke(paint.strokeWidth) else 0,
                    if (paint.style == PaintingStyle.Stroke) paint.strokeCap.commandValue() else 0,
                    if (paint.style == PaintingStyle.Stroke) paint.strokeJoin.commandValue() else 0,
                    if (paint.style == PaintingStyle.Stroke) paint.strokeMiter1000() else 0,
                )
            }
        }

        fun drawPath(path: Path, paint: Paint) {
            if (paint.shader?.jbrSkiaLinearGradient != null) {
                addLinearGradientPath(path, paint)
                return
            }
            if (paint.shader?.jbrSkiaRadialGradient != null) {
                addRadialGradientPath(path, paint)
                return
            }
            if (paint.shader?.jbrSkiaSweepGradient != null) {
                addSweepGradientPath(path, paint)
                return
            }
            val dashPathEffect = paint.dashPathEffect
            if (dashPathEffect != null && paint.style == PaintingStyle.Stroke) {
                addDashedPath(path, paint, dashPathEffect)
                return
            }
            val pathEffectDescriptor = paint.pathEffectDescriptor
            if (pathEffectDescriptor != null) {
                addPathEffectDescriptorPath(path, paint, pathEffectDescriptor)
                return
            }
            val tintColorFilter = paint.tintSrcInColorFilter
            val commandColor = if (tintColorFilter != null && paint.shader == null && paint.pathEffect == null) {
                paint.tintSrcInCommandColor(tintColorFilter)
            } else {
                if (!paint.isSupportedSolidColorForBlendLayer) return
                paint.commandColor()
            }
            val style = when (paint.style) {
                PaintingStyle.Fill -> COMMAND_PAINT_STYLE_FILL
                PaintingStyle.Stroke -> COMMAND_PAINT_STYLE_STROKE
                else -> {
                    countUnsupported("paintStyle")
                    return
                }
            }
            val pathData = path.commandData() ?: run {
                countUnsupported("path")
                return
            }
            val bounds = path.getBounds()
            val outset = paint.blendLayerOutset()
            withSolidColorBlendLayer(
                left = bounds.left - outset,
                top = bounds.top - outset,
                right = bounds.right + outset,
                bottom = bounds.bottom + outset,
                paint = paint,
            ) {
                commands.addCommand(
                    COMMAND_DRAW_PATH,
                    paint.recordFlags(),
                    style,
                    commandColor,
                    if (paint.style == PaintingStyle.Stroke) state.stroke(paint.strokeWidth) else 0,
                    if (paint.style == PaintingStyle.Stroke) paint.strokeCap.commandValue() else 0,
                    if (paint.style == PaintingStyle.Stroke) paint.strokeJoin.commandValue() else 0,
                    if (paint.style == PaintingStyle.Stroke) paint.strokeMiter1000() else 0,
                    path.fillType.commandValue(),
                    pathData.size,
                    *pathData,
                )
            }
        }

        private fun addPathEffectDescriptorPath(
            path: Path,
            paint: Paint,
            descriptor: JbrSkiaPathEffectDescriptor,
        ) {
            if (!paint.isSupportedPathEffectDescriptorSolidColorForBlendLayer) return
            val style = when (paint.style) {
                PaintingStyle.Fill -> COMMAND_PAINT_STYLE_FILL
                PaintingStyle.Stroke -> COMMAND_PAINT_STYLE_STROKE
                else -> {
                    countUnsupported("paintStyle")
                    return
                }
            }
            val handle = definePathEffectIfNeeded(descriptor) ?: return
            val pathData = path.commandData() ?: run {
                countUnsupported("path")
                return
            }
            val bounds = path.getBounds()
            val outset = paint.blendLayerOutset()
            withSolidColorBlendLayer(
                left = bounds.left - outset,
                top = bounds.top - outset,
                right = bounds.right + outset,
                bottom = bounds.bottom + outset,
                paint = paint,
            ) {
                commands.addCommand(
                    COMMAND_DRAW_PATH_PATH_EFFECT_REF,
                    paint.recordFlags(),
                    style,
                    paint.commandColor(),
                    if (paint.style == PaintingStyle.Stroke) state.stroke(paint.strokeWidth) else 0,
                    if (paint.style == PaintingStyle.Stroke) paint.strokeCap.commandValue() else 0,
                    if (paint.style == PaintingStyle.Stroke) paint.strokeJoin.commandValue() else 0,
                    if (paint.style == PaintingStyle.Stroke) paint.strokeMiter1000() else 0,
                    handle.highInt(),
                    handle.lowInt(),
                    path.fillType.commandValue(),
                    pathData.size,
                    *pathData,
                )
            }
        }

        private fun addDashedPath(path: Path, paint: Paint, dashPathEffect: JbrSkiaDashPathEffect) {
            if (!paint.isSupportedDashedSolidColorForBlendLayer) return
            val intervals = dashPathEffect.intervals
            if (
                intervals.size !in 2..16 ||
                intervals.any { !it.isFinite() || it <= 0f } ||
                !dashPathEffect.phase.isFinite() ||
                dashPathEffect.phase < 0f
            ) {
                countUnsupported("pathEffect")
                return
            }
            val pathData = path.commandData() ?: run {
                countUnsupported("path")
                return
            }
            val bounds = path.getBounds()
            val outset = paint.blendLayerOutset(forceStroke = true)
            withSolidColorBlendLayer(
                left = bounds.left - outset,
                top = bounds.top - outset,
                right = bounds.right + outset,
                bottom = bounds.bottom + outset,
                paint = paint,
            ) {
                commands.addCommand(
                    COMMAND_STROKE_PATH_DASH_PATH_EFFECT,
                    paint.recordFlags(),
                    paint.commandColor(),
                    state.stroke(paint.strokeWidth),
                    paint.strokeCap.commandValue(),
                    paint.strokeJoin.commandValue(),
                    paint.strokeMiter1000(),
                    dashPathEffect.phase.fixed1000(),
                    intervals.size,
                    *IntArray(intervals.size) { index -> intervals[index].fixed1000() },
                    path.fillType.commandValue(),
                    pathData.size,
                    *pathData,
                )
            }
        }

        private fun definePathEffectIfNeeded(descriptor: JbrSkiaPathEffectDescriptor): Long? {
            val payload = when (descriptor) {
                is JbrSkiaPathEffectDescriptor.Corner -> {
                    if (!descriptor.radius.isFinite() || descriptor.radius < 0f) {
                        countUnsupported("pathEffect")
                        return null
                    }
                    intArrayOf(descriptor.radius.toRawBits())
                }
                is JbrSkiaPathEffectDescriptor.Stamped -> {
                    if (!descriptor.advance.isFinite() || descriptor.advance <= 0f ||
                        !descriptor.phase.isFinite() || descriptor.phase < 0f
                    ) {
                        countUnsupported("pathEffect")
                        return null
                    }
                    val pathData = descriptor.shape.commandData() ?: run {
                        countUnsupported("pathEffect")
                        return null
                    }
                    intArrayOf(
                        descriptor.advance.toRawBits(),
                        descriptor.phase.toRawBits(),
                        descriptor.style.commandValue() ?: run {
                            countUnsupported("pathEffect")
                            return null
                        },
                        descriptor.shape.fillType.commandValue(),
                        pathData.size,
                        *pathData,
                    )
                }
                is JbrSkiaPathEffectDescriptor.Chain -> {
                    val outerHandle = definePathEffectIfNeeded(descriptor.outer) ?: return null
                    val innerHandle = definePathEffectIfNeeded(descriptor.inner) ?: return null
                    intArrayOf(
                        outerHandle.highInt(),
                        outerHandle.lowInt(),
                        innerHandle.highInt(),
                        innerHandle.lowInt(),
                    )
                }
            }
            val type = when (descriptor) {
                is JbrSkiaPathEffectDescriptor.Corner -> COMMAND_EFFECT_DESCRIPTOR_CORNER_PATH_EFFECT
                is JbrSkiaPathEffectDescriptor.Stamped -> COMMAND_EFFECT_DESCRIPTOR_STAMPED_PATH_EFFECT
                is JbrSkiaPathEffectDescriptor.Chain -> COMMAND_EFFECT_DESCRIPTOR_CHAIN_PATH_EFFECT
            }
            val handle = effectDescriptorHandleKey(type, payload)
            defineEffectDescriptorIfNeeded(handle, type, payload)
            return handle
        }

        private fun addLinearGradientPath(path: Path, paint: Paint) {
            if (paint.style == PaintingStyle.Stroke) {
                addLinearGradientStrokePath(path, paint)
                return
            }
            if (paint.style != PaintingStyle.Fill) {
                countUnsupported("linearGradientPathPaint")
                return
            }
            val gradientPayload = paint.linearGradientPayload() ?: return
            val pathData = path.commandData() ?: run {
                countUnsupported("linearGradientPath")
                return
            }
            val bounds = path.getBounds()
            withSolidColorBlendLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, paint) {
                commands.addCommand(
                    COMMAND_FILL_PATH_LINEAR_GRADIENT,
                    paint.recordFlags(),
                    path.fillType.commandValue(),
                    pathData.size,
                    *pathData,
                    *gradientPayload,
                )
            }
        }

        private fun addLinearGradientStrokePath(path: Path, paint: Paint) {
            val gradientPayload = paint.linearGradientPayload(requiredStyle = PaintingStyle.Stroke) ?: return
            if (!paint.strokeWidth.isFinite() || paint.strokeWidth <= 0f) {
                countUnsupported("linearGradientStrokeWidth")
                return
            }
            val pathData = path.commandData() ?: run {
                countUnsupported("linearGradientPath")
                return
            }
            val bounds = path.getBounds()
            val outset = paint.blendLayerOutset(forceStroke = true)
            withSolidColorBlendLayer(
                bounds.left - outset,
                bounds.top - outset,
                bounds.right + outset,
                bounds.bottom + outset,
                paint,
            ) {
                commands.addCommand(
                    COMMAND_STROKE_PATH_LINEAR_GRADIENT,
                    paint.recordFlags(),
                    paint.strokeWidth.fixed1000(),
                    paint.strokeCap.commandValue(),
                    paint.strokeJoin.commandValue(),
                    paint.strokeMiter1000(),
                    path.fillType.commandValue(),
                    pathData.size,
                    *pathData,
                    *gradientPayload,
                )
            }
        }

        private fun addRadialGradientPath(path: Path, paint: Paint) {
            if (paint.style == PaintingStyle.Stroke) {
                addRadialGradientStrokePath(path, paint)
                return
            }
            if (paint.style != PaintingStyle.Fill) {
                countUnsupported("radialGradientPathPaint")
                return
            }
            val gradientPayload = paint.radialGradientPayload() ?: return
            val pathData = path.commandData() ?: run {
                countUnsupported("radialGradientPath")
                return
            }
            val bounds = path.getBounds()
            withSolidColorBlendLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, paint) {
                commands.addCommand(
                    COMMAND_FILL_PATH_RADIAL_GRADIENT,
                    paint.recordFlags(),
                    path.fillType.commandValue(),
                    pathData.size,
                    *pathData,
                    *gradientPayload,
                )
            }
        }

        private fun addRadialGradientStrokePath(path: Path, paint: Paint) {
            val gradientPayload = paint.radialGradientPayload(requiredStyle = PaintingStyle.Stroke) ?: return
            if (!paint.strokeWidth.isFinite() || paint.strokeWidth <= 0f) {
                countUnsupported("radialGradientStrokeWidth")
                return
            }
            val pathData = path.commandData() ?: run {
                countUnsupported("radialGradientPath")
                return
            }
            val bounds = path.getBounds()
            val outset = paint.blendLayerOutset(forceStroke = true)
            withSolidColorBlendLayer(
                bounds.left - outset,
                bounds.top - outset,
                bounds.right + outset,
                bounds.bottom + outset,
                paint,
            ) {
                commands.addCommand(
                    COMMAND_STROKE_PATH_RADIAL_GRADIENT,
                    paint.recordFlags(),
                    paint.strokeWidth.fixed1000(),
                    paint.strokeCap.commandValue(),
                    paint.strokeJoin.commandValue(),
                    paint.strokeMiter1000(),
                    path.fillType.commandValue(),
                    pathData.size,
                    *pathData,
                    *gradientPayload,
                )
            }
        }

        private fun addSweepGradientPath(path: Path, paint: Paint) {
            if (paint.style == PaintingStyle.Stroke) {
                addSweepGradientStrokePath(path, paint)
                return
            }
            if (paint.style != PaintingStyle.Fill) {
                countUnsupported("sweepGradientPathPaint")
                return
            }
            val gradientPayload = paint.sweepGradientPayload() ?: return
            val pathData = path.commandData() ?: run {
                countUnsupported("sweepGradientPath")
                return
            }
            val bounds = path.getBounds()
            withSolidColorBlendLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, paint) {
                commands.addCommand(
                    COMMAND_FILL_PATH_SWEEP_GRADIENT,
                    paint.recordFlags(),
                    path.fillType.commandValue(),
                    pathData.size,
                    *pathData,
                    *gradientPayload,
                )
            }
        }

        private fun addSweepGradientStrokePath(path: Path, paint: Paint) {
            val gradientPayload = paint.sweepGradientPayload(requiredStyle = PaintingStyle.Stroke) ?: return
            if (!paint.strokeWidth.isFinite() || paint.strokeWidth <= 0f) {
                countUnsupported("sweepGradientStrokeWidth")
                return
            }
            val pathData = path.commandData() ?: run {
                countUnsupported("sweepGradientPath")
                return
            }
            val bounds = path.getBounds()
            val outset = paint.blendLayerOutset(forceStroke = true)
            withSolidColorBlendLayer(
                bounds.left - outset,
                bounds.top - outset,
                bounds.right + outset,
                bounds.bottom + outset,
                paint,
            ) {
                commands.addCommand(
                    COMMAND_STROKE_PATH_SWEEP_GRADIENT,
                    paint.recordFlags(),
                    paint.strokeWidth.fixed1000(),
                    paint.strokeCap.commandValue(),
                    paint.strokeJoin.commandValue(),
                    paint.strokeMiter1000(),
                    path.fillType.commandValue(),
                    pathData.size,
                    *pathData,
                    *gradientPayload,
                )
            }
        }

        fun drawImageRect(
            image: ImageBitmap,
            srcLeft: Float,
            srcTop: Float,
            srcRight: Float,
            srcBottom: Float,
            dstLeft: Float,
            dstTop: Float,
            dstRight: Float,
            dstBottom: Float,
            paint: Paint,
        ): Boolean {
            if (!paint.isSupportedImagePaintForBlendLayer ||
                image.width <= 0 ||
                image.height <= 0 ||
                image.width > MAX_COMMAND_IMAGE_DIMENSION ||
                image.height > MAX_COMMAND_IMAGE_DIMENSION
            ) {
                return false
            }
            val cacheKey = defineImageIfNeeded(image) ?: return false
            imageRefCount++
            val tintColorFilter = paint.tintSrcInColorFilter
            val descriptorColorFilter = if (tintColorFilter == null) descriptorColorFilterOrNull(paint.colorFilter) else null
            val descriptorHandle = descriptorColorFilter?.let { defineDescriptorColorFilterIfNeeded(it) } ?: run {
                if (descriptorColorFilter != null) return false
                null
            }
            withSolidColorBlendLayer(dstLeft, dstTop, dstRight, dstBottom, paint) {
                val srcLeft1000 = srcLeft.fixed1000()
                val srcTop1000 = srcTop.fixed1000()
                val srcRight1000 = srcRight.fixed1000()
                val srcBottom1000 = srcBottom.fixed1000()
                val dstLeft1000 = dstLeft.fixed1000()
                val dstTop1000 = dstTop.fixed1000()
                val dstRight1000 = dstRight.fixed1000()
                val dstBottom1000 = dstBottom.fixed1000()
                val alpha1000 = paint.imageAlpha1000()
                if (tintColorFilter == null &&
                    descriptorHandle == null &&
                    alpha1000 == 1000 &&
                    srcLeft1000 == 0 &&
                    srcTop1000 == 0 &&
                    srcRight1000 == image.width * 1000 &&
                    srcBottom1000 == image.height * 1000
                ) {
                    commands.addCommand(
                        COMMAND_DRAW_IMAGE_REF_FULL,
                        paint.recordFlags(),
                        dstLeft1000,
                        dstTop1000,
                        dstRight1000,
                        dstBottom1000,
                        cacheKey.highInt(),
                        cacheKey.lowInt(),
                    )
                } else {
                    commands.addCommand(
                        when {
                            tintColorFilter != null -> COMMAND_DRAW_IMAGE_REF_COLOR_FILTER
                            descriptorHandle != null -> COMMAND_DRAW_IMAGE_REF_COLOR_FILTER_REF
                            else -> COMMAND_DRAW_IMAGE_REF
                        },
                        paint.recordFlags(),
                        srcLeft1000,
                        srcTop1000,
                        srcRight1000,
                        srcBottom1000,
                        dstLeft1000,
                        dstTop1000,
                        dstRight1000,
                        dstBottom1000,
                        cacheKey.highInt(),
                        cacheKey.lowInt(),
                        image.width,
                        image.height,
                        alpha1000,
                        paint.filterQuality.value,
                        *when {
                            tintColorFilter != null -> intArrayOf(tintColorFilter.color.toArgb(), COMMAND_BLEND_MODE_SRC_IN)
                            descriptorHandle != null -> intArrayOf(descriptorHandle.highInt(), descriptorHandle.lowInt())
                            else -> IntArray(0)
                        },
                    )
                }
            }
            return true
        }

        private fun addImageShaderRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            val imageShader = paint.shader?.jbrSkiaImageShader ?: run {
                countUnsupported("shader")
                return
            }
            if (paint.style == PaintingStyle.Stroke) {
                addImageShaderStrokeRect(left, top, right, bottom, paint, imageShader)
                return
            }
            if (!paint.isSupportedImageShaderPaintForBlendLayer ||
                paint.style != PaintingStyle.Fill
            ) {
                if (paint.style != PaintingStyle.Fill) countUnsupported("paintStyle")
                return
            }
            if (imageShader.image.width <= 0 ||
                imageShader.image.height <= 0 ||
                imageShader.image.width > MAX_COMMAND_IMAGE_DIMENSION ||
                imageShader.image.height > MAX_COMMAND_IMAGE_DIMENSION
            ) {
                countUnsupported("imageShaderImage")
                return
            }
            val cacheKey = defineImageIfNeeded(imageShader.image) ?: return
            imageRefCount++
            withSolidColorBlendLayer(left, top, right, bottom, paint) {
                commands.addCommand(
                    COMMAND_FILL_RECT_IMAGE_SHADER,
                    paint.recordFlags(),
                    left.fixed1000(),
                    top.fixed1000(),
                    right.fixed1000(),
                    bottom.fixed1000(),
                    cacheKey.highInt(),
                    cacheKey.lowInt(),
                    imageShader.image.width,
                    imageShader.image.height,
                    imageShader.tileModeX.commandValue(),
                    imageShader.tileModeY.commandValue(),
                    paint.imageAlpha1000(),
                )
            }
        }

        private fun addImageShaderStrokeRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            paint: Paint,
            imageShader: JbrSkiaImageShader,
        ) {
            if (!paint.isSupportedImageShaderPaintForBlendLayer) return
            if (!paint.strokeWidth.isFinite() || paint.strokeWidth <= 0f) {
                countUnsupported("imageShaderStrokeWidth")
                return
            }
            if (imageShader.image.width <= 0 ||
                imageShader.image.height <= 0 ||
                imageShader.image.width > MAX_COMMAND_IMAGE_DIMENSION ||
                imageShader.image.height > MAX_COMMAND_IMAGE_DIMENSION
            ) {
                countUnsupported("imageShaderImage")
                return
            }
            val cacheKey = defineImageIfNeeded(imageShader.image) ?: return
            imageRefCount++
            val outset = paint.blendLayerOutset(forceStroke = true)
            withSolidColorBlendLayer(left - outset, top - outset, right + outset, bottom + outset, paint) {
                commands.addCommand(
                    COMMAND_STROKE_RECT_IMAGE_SHADER,
                    paint.recordFlags(),
                    left.fixed1000(),
                    top.fixed1000(),
                    right.fixed1000(),
                    bottom.fixed1000(),
                    cacheKey.highInt(),
                    cacheKey.lowInt(),
                    imageShader.image.width,
                    imageShader.image.height,
                    imageShader.tileModeX.commandValue(),
                    imageShader.tileModeY.commandValue(),
                    paint.imageAlpha1000(),
                    paint.strokeWidth.fixed1000(),
                    paint.strokeCap.commandValue(),
                    paint.strokeJoin.commandValue(),
                    paint.strokeMiter1000(),
                )
            }
        }

        private fun addShaderDescriptorRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            paint: Paint,
            descriptor: ShaderDescriptor? = shaderDescriptorOrNull(paint.shader),
        ) {
            descriptor ?: run {
                countUnsupported("shader")
                return
            }
            if (!paint.isSupportedShaderDescriptorPaintForBlendLayer(descriptor)) {
                return
            }
            val handle = defineShaderIfNeeded(descriptor) ?: run {
                countUnsupported("shaderDescriptor")
                return
            }
            if (paint.style == PaintingStyle.Stroke) {
                addShaderDescriptorStrokeRect(left, top, right, bottom, paint, handle)
                return
            }
            if (paint.style != PaintingStyle.Fill) {
                countUnsupported("paintStyle")
                return
            }
            withSolidColorBlendLayer(left, top, right, bottom, paint) {
                commands.addCommand(
                    COMMAND_FILL_RECT_SHADER_REF,
                    paint.recordFlags(),
                    handle.highInt(),
                    handle.lowInt(),
                    left.fixed1000(),
                    top.fixed1000(),
                    right.fixed1000(),
                    bottom.fixed1000(),
                    paint.imageAlpha1000(),
                )
            }
        }

        private fun addShaderDescriptorStrokeRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            paint: Paint,
            handle: Long,
        ) {
            if (!paint.strokeWidth.isFinite() || paint.strokeWidth <= 0f) {
                countUnsupported("shaderDescriptorStrokeWidth")
                return
            }
            val outset = paint.blendLayerOutset(forceStroke = true)
            withSolidColorBlendLayer(left - outset, top - outset, right + outset, bottom + outset, paint) {
                commands.addCommand(
                    COMMAND_STROKE_RECT_SHADER_REF,
                    paint.recordFlags(),
                    handle.highInt(),
                    handle.lowInt(),
                    left.fixed1000(),
                    top.fixed1000(),
                    right.fixed1000(),
                    bottom.fixed1000(),
                    paint.strokeWidth.fixed1000(),
                    paint.strokeCap.commandValue(),
                    paint.strokeJoin.commandValue(),
                    paint.strokeMiter1000(),
                    paint.imageAlpha1000(),
                )
            }
        }

        fun drawParagraphUtf16(
            text: String,
            x: Float,
            y: Float,
            width: Float,
            fontSize: Float,
            fontFamily: String?,
            color: Int,
            fontWeight: Int,
            fontWidth: Int,
            fontSlant: Int,
            textAlign: Int,
            textDirection: Int,
            lineHeightMultiplier1000: Int,
            maxLines: Int,
            ellipsisMode: Int,
            decorationMask: Int,
            letterSpacing1000: Int,
            backgroundSpecified: Int,
            backgroundArgb: Int,
            antiAlias: Boolean,
        ): Boolean {
            val encodedFontFamily = fontFamily.orEmpty()
            if (text.isEmpty() || text.length > 4096 ||
                encodedFontFamily.length > 256 ||
                !x.isFinite() || !y.isFinite() ||
                !width.isFinite() || width <= 0f ||
                !fontSize.isFinite() || fontSize <= 0f ||
                fontWeight !in 1..1000 ||
                fontWidth !in 1..9 ||
                fontSlant !in 0..2 ||
                textAlign !in 0..5 ||
                textDirection !in 0..1 ||
                lineHeightMultiplier1000 !in 0..100000 ||
                maxLines !in 0..4096 ||
                ellipsisMode !in 0..1 ||
                decorationMask !in 0..3 ||
                letterSpacing1000 !in -100000..100000 ||
                backgroundSpecified !in 0..1
            ) {
                return false
            }
            paragraphTextCommandCount++
            commands.addCommand(
                COMMAND_DRAW_PARAGRAPH_UTF16,
                if (antiAlias) COMMAND_RECORD_FLAG_ANTIALIAS else COMMAND_RECORD_FLAGS_NONE,
                x.fixed1000(),
                y.fixed1000(),
                width.fixed1000(),
                fontSize.fixed1000(),
                color,
                fontWeight,
                fontWidth,
                fontSlant,
                encodedFontFamily.length,
                *IntArray(encodedFontFamily.length) { encodedFontFamily[it].code },
                textAlign,
                textDirection,
                lineHeightMultiplier1000,
                maxLines,
                ellipsisMode,
                decorationMask,
                letterSpacing1000,
                backgroundSpecified,
                backgroundArgb,
                text.length,
                *IntArray(text.length) { text[it].code },
            )
            return true
        }

        fun drawTextUtf16(
            text: String,
            x: Float,
            baseline: Float,
            fontSize: Float,
            fontFamily: String?,
            fontWeight: Int,
            fontWidth: Int,
            fontSlant: Int,
            color: Int,
            antiAlias: Boolean,
        ): Boolean {
            val encodedFontFamily = fontFamily.orEmpty()
            if (
                text.isEmpty() || text.length > 4096 ||
                encodedFontFamily.length > 256 ||
                !fontSize.isFinite() || fontSize <= 0f ||
                fontWeight !in 1..1000 ||
                fontWidth !in 1..9 ||
                fontSlant !in 0..2
            ) {
                return false
            }
            textCommandCount++
            commands.addCommand(
                COMMAND_DRAW_TEXT_UTF16,
                if (antiAlias) COMMAND_RECORD_FLAG_ANTIALIAS else COMMAND_RECORD_FLAGS_NONE,
                x.fixed1000(),
                baseline.fixed1000(),
                fontSize.fixed1000(),
                color,
                fontWeight,
                fontWidth,
                fontSlant,
                encodedFontFamily.length,
                *IntArray(encodedFontFamily.length) { encodedFontFamily[it].code },
                text.length,
                *IntArray(text.length) { text[it].code },
            )
            return true
        }

        fun defineFontData(handle: Long, data: ByteArray): Boolean {
            if (handle == 0L || data.isEmpty() || data.size > MAX_FONT_DATA_BYTES) {
                return false
            }
            val shouldDefine = synchronized(fontDataHandleLock) {
                if (definedFontDataHandles.containsKey(handle)) {
                    definedFontDataHandles[handle] = Unit
                    forceResourceDefinitions
                } else {
                    if (definedFontDataHandles.size >= MAX_DEFINED_FONT_DATA_HANDLES) {
                        val eldest = definedFontDataHandles.keys.first()
                        definedFontDataHandles.remove(eldest)
                    }
                    definedFontDataHandles[handle] = Unit
                    true
                }
            }
            if (!shouldDefine) {
                return true
            }
            commands.addCommand(
                COMMAND_DEFINE_FONT_DATA,
                COMMAND_RECORD_FLAGS_NONE,
                handle.highInt(),
                handle.lowInt(),
                data.size,
                *IntArray(data.size) { data[it].toInt() and 0xff },
            )
            return true
        }

        private val Paint.isSupportedSolidColorForBlendLayer: Boolean
            get() {
                var supported = true
                if (!state.supported) {
                    countUnsupported("unsupportedScope")
                    supported = false
                }
                if (blendMode != BlendMode.SrcOver && commandBlendMode == null) {
                    countUnsupported("blendMode_${blendMode.toReasonToken()}")
                    supported = false
                }
                if (shader != null) {
                    countUnsupported("shader")
                    supported = false
                }
                colorFilter?.let {
                    countUnsupported(it.unsupportedReasonToken())
                    supported = false
                }
                if (pathEffect != null) {
                    countUnsupported("pathEffect")
                    supported = false
                }
                return supported
            }

        private fun Paint.blendLayerOutset(forceStroke: Boolean = false): Float {
            if (!forceStroke && style != PaintingStyle.Stroke) return 0f
            return (strokeWidth / 2f + 1f).takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
        }

        private val Paint.isSupportedFillRectColorFilterForBlendLayer: Boolean
            get() {
                var supported = true
                if (!state.supported) {
                    countUnsupported("unsupportedScope")
                    supported = false
                }
                if (blendMode != BlendMode.SrcOver && commandBlendMode == null) {
                    countUnsupported("blendMode_${blendMode.toReasonToken()}")
                    supported = false
                }
                if (pathEffect != null) {
                    countUnsupported("pathEffect")
                    supported = false
                }
                return supported
            }

        private val Paint.isSupportedDashedSolidColorForBlendLayer: Boolean
            get() {
                var supported = true
                if (!state.supported) {
                    countUnsupported("unsupportedScope")
                    supported = false
                }
                if (blendMode != BlendMode.SrcOver && commandBlendMode == null) {
                    countUnsupported("blendMode_${blendMode.toReasonToken()}")
                    supported = false
                }
                if (shader != null) {
                    countUnsupported("shader")
                    supported = false
                }
                colorFilter?.let {
                    countUnsupported(it.unsupportedReasonToken())
                    supported = false
                }
                return supported
            }

        private val Paint.tintSrcInColorFilter: BlendModeColorFilter?
            get() =
                (colorFilter as? BlendModeColorFilter)?.takeIf { it.blendMode == BlendMode.SrcIn }

        private val Paint.colorMatrixColorFilter: ColorMatrixColorFilter?
            get() = colorMatrixColorFilterOrNull(colorFilter)

        private val Paint.lightingColorFilter: LightingColorFilter?
            get() = lightingColorFilterOrNull(colorFilter)

        private val Paint.dashPathEffect: JbrSkiaDashPathEffect?
            get() =
                (pathEffect as? SkiaBackedPathEffect)?.jbrSkiaDashPathEffect

        private val Paint.pathEffectDescriptor: JbrSkiaPathEffectDescriptor?
            get() =
                (pathEffect as? SkiaBackedPathEffect)?.jbrSkiaPathEffectDescriptor

        private val Paint.isSupportedLayerPaint: Boolean
            get() =
                (blendMode == BlendMode.SrcOver || commandBlendMode != null) &&
                    shader == null &&
                    (colorFilter == null || descriptorColorFilterOrNull(colorFilter) != null) &&
                    pathEffect == null

        private val Paint.isSupportedPathEffectDescriptorSolidColorForBlendLayer: Boolean
            get() {
                var supported = true
                if (!state.supported) {
                    countUnsupported("unsupportedScope")
                    supported = false
                }
                if (blendMode != BlendMode.SrcOver && commandBlendMode == null) {
                    countUnsupported("blendMode_${blendMode.toReasonToken()}")
                    supported = false
                }
                if (shader != null) {
                    countUnsupported("shader")
                    supported = false
                }
                colorFilter?.let {
                    countUnsupported(it.unsupportedReasonToken())
                    supported = false
                }
                return supported
            }

        private val Paint.commandBlendMode: Int?
            get() = commandBlendModeOrNull(blendMode)

        private val Paint.isSupportedImagePaintForBlendLayer: Boolean
            get() {
                var supported = true
                if (!state.supported) {
                    countUnsupported("unsupportedScope")
                    supported = false
                }
                if (blendMode != BlendMode.SrcOver && commandBlendMode == null) {
                    countUnsupported("blendMode_${blendMode.toReasonToken()}")
                    supported = false
                }
                if (shader != null) {
                    countUnsupported("shader")
                    supported = false
                }
                val tmpColorFilter = colorFilter
                if (tmpColorFilter != null &&
                    tintSrcInColorFilter == null &&
                    descriptorColorFilterOrNull(tmpColorFilter) == null
                ) {
                    countUnsupported(tmpColorFilter.unsupportedReasonToken())
                    supported = false
                }
                return supported
            }

        private val Paint.isSupportedImageShaderPaintForBlendLayer: Boolean
            get() {
                var supported = true
                if (!state.supported) {
                    countUnsupported("unsupportedScope")
                    supported = false
                }
                if (blendMode != BlendMode.SrcOver && commandBlendMode == null) {
                    countUnsupported("blendMode_${blendMode.toReasonToken()}")
                    supported = false
                }
                if (shader?.jbrSkiaImageShader == null) {
                    countUnsupported("shader")
                    supported = false
                }
                colorFilter?.let {
                    countUnsupported(it.unsupportedReasonToken())
                    supported = false
                }
                if (pathEffect != null) {
                    countUnsupported("pathEffect")
                    supported = false
                }
                return supported
            }

        private fun Paint.isSupportedShaderDescriptorPaintForBlendLayer(descriptor: ShaderDescriptor): Boolean {
            var supported = true
            if (!state.supported) {
                countUnsupported("unsupportedScope")
                supported = false
            }
            if (blendMode != BlendMode.SrcOver && commandBlendMode == null) {
                countUnsupported("blendMode_${blendMode.toReasonToken()}")
                supported = false
            }
            if (shaderDescriptorOrNull(shader) == null) {
                countUnsupported("shader")
                supported = false
            }
            colorFilter?.takeIf { descriptor !is ShaderDescriptor.ColorFiltered }?.let {
                countUnsupported(it.unsupportedReasonToken())
                supported = false
            }
            if (pathEffect != null) {
                countUnsupported("pathEffect")
                supported = false
            }
            return supported
        }

        private val Paint.isSupportedGradientForBlendLayer: Boolean
            get() {
                var supported = true
                if (!state.supported) {
                    countUnsupported("unsupportedScope")
                    supported = false
                }
                if (blendMode != BlendMode.SrcOver && commandBlendMode == null) {
                    countUnsupported("blendMode_${blendMode.toReasonToken()}")
                    supported = false
                }
                if (shader?.jbrSkiaLinearGradient == null &&
                    shader?.jbrSkiaRadialGradient == null &&
                    shader?.jbrSkiaSweepGradient == null
                ) {
                    countUnsupported("shader")
                    supported = false
                }
                colorFilter?.let {
                    countUnsupported(it.unsupportedReasonToken())
                    supported = false
                }
                if (pathEffect != null) {
                    countUnsupported("pathEffect")
                    supported = false
                }
                return supported
            }

        private fun Paint.commandColor(): Int =
            color.copy(alpha = color.alpha * alpha).toArgb()

        private fun Paint.tintSrcInCommandColor(colorFilter: BlendModeColorFilter): Int =
            colorFilter.color.copy(alpha = colorFilter.color.alpha * color.alpha * alpha).toArgb()

        private fun ColorFilter.unsupportedReasonToken(): String =
            when (this) {
                is BlendModeColorFilter -> "colorFilter:BlendMode:${blendMode.toReasonToken()}"
                is ColorMatrixColorFilter -> "colorFilter:ColorMatrix"
                is LightingColorFilter -> "colorFilter:Lighting"
                is JbrSkiaRuntimeEffectColorFilterHolder -> "colorFilter:RuntimeEffect"
                else -> "colorFilter:unknown"
            }

        private fun Paint.recordFlags(): Int =
            if (isAntiAlias) COMMAND_RECORD_FLAG_ANTIALIAS else COMMAND_RECORD_FLAGS_NONE

        private fun Paint.strokeMiter1000(): Int =
            (strokeMiterLimit * 1000f).roundToInt().coerceAtLeast(0)

        private fun Paint.layerAlpha1000(): Int =
            (color.alpha * alpha * 1000f).roundToInt().coerceIn(0, 1000)

        private fun Paint.imageAlpha1000(): Int =
            (alpha * 1000f).roundToInt().coerceIn(0, 1000)

        private fun Float.fixed1000(): Int =
            (this * 1000f).roundToInt()

        private fun Float.fixed1000000(): Int =
            (this * 1_000_000f).roundToInt()

        private fun Path.commandData(): IntArray? {
            val data = ArrayList<Int>(64)
            val points = FloatArray(8)
            val iterator = iterator(PathIterator.ConicEvaluation.AsQuadratics)
            while (iterator.hasNext()) {
                when (iterator.next(points)) {
                    PathSegment.Type.Move -> {
                        if (!points[0].isFinite() || !points[1].isFinite()) return null
                        data.add(PATH_VERB_MOVE)
                        data.add(points[0].fixed1000())
                        data.add(points[1].fixed1000())
                    }
                    PathSegment.Type.Line -> {
                        if (!points[2].isFinite() || !points[3].isFinite()) return null
                        data.add(PATH_VERB_LINE)
                        data.add(points[2].fixed1000())
                        data.add(points[3].fixed1000())
                    }
                    PathSegment.Type.Quadratic -> {
                        if (!points[2].isFinite() || !points[3].isFinite() || !points[4].isFinite() || !points[5].isFinite()) {
                            return null
                        }
                        data.add(PATH_VERB_QUAD)
                        data.add(points[2].fixed1000())
                        data.add(points[3].fixed1000())
                        data.add(points[4].fixed1000())
                        data.add(points[5].fixed1000())
                    }
                    PathSegment.Type.Cubic -> {
                        if (!points[2].isFinite() || !points[3].isFinite() ||
                            !points[4].isFinite() || !points[5].isFinite() ||
                            !points[6].isFinite() || !points[7].isFinite()
                        ) {
                            return null
                        }
                        data.add(PATH_VERB_CUBIC)
                        data.add(points[2].fixed1000())
                        data.add(points[3].fixed1000())
                        data.add(points[4].fixed1000())
                        data.add(points[5].fixed1000())
                        data.add(points[6].fixed1000())
                        data.add(points[7].fixed1000())
                    }
                    PathSegment.Type.Conic -> {
                        if (!points[2].isFinite() || !points[3].isFinite() || !points[4].isFinite() || !points[5].isFinite()) {
                            return null
                        }
                        data.add(PATH_VERB_QUAD)
                        data.add(points[2].fixed1000())
                        data.add(points[3].fixed1000())
                        data.add(points[4].fixed1000())
                        data.add(points[5].fixed1000())
                    }
                    PathSegment.Type.Close -> data.add(PATH_VERB_CLOSE)
                    PathSegment.Type.Done -> return null
                }
                if (data.size > MAX_PATH_DATA_INTS) return null
            }
            return if (data.isEmpty()) null else data.toIntArray()
        }

        private fun PathFillType.commandValue(): Int =
            when (this) {
                PathFillType.NonZero -> PATH_FILL_TYPE_NON_ZERO
                PathFillType.EvenOdd -> PATH_FILL_TYPE_EVEN_ODD
                else -> PATH_FILL_TYPE_NON_ZERO
            }

        private fun StampedPathEffectStyle.commandValue(): Int? =
            when (this) {
                StampedPathEffectStyle.Translate -> 0
                StampedPathEffectStyle.Rotate -> 1
                StampedPathEffectStyle.Morph -> 2
                else -> null
            }

        private fun Long.highInt(): Int = (this ushr 32).toInt()

        private fun Long.lowInt(): Int = this.toInt()

        private fun BlendModeColorFilter.handleKey(): Long =
            (color.toArgb().toLong() shl 32) xor ((commandBlendModeOrNull() ?: 0).toLong() and 0xffffffffL)

        private fun BlendModeColorFilter.commandBlendModeOrNull(): Int? =
            if (blendMode == BlendMode.SrcIn) COMMAND_BLEND_MODE_SRC_IN else commandBlendModeOrNull(blendMode)

        private fun ColorMatrixColorFilter.skiaColorMatrixValues(): FloatArray {
            val values = copyColorMatrix().values.copyOf()
            values[4] *= 1f / 255f
            values[9] *= 1f / 255f
            values[14] *= 1f / 255f
            values[19] *= 1f / 255f
            return values
        }

        private fun FloatArray.colorMatrixHandleKey(): Long {
            var hash = -3750763034362895579L
            fun mix(value: Int) {
                hash = hash xor value.toLong()
                hash *= 1099511628211L
            }
            mix(COMMAND_EFFECT_DESCRIPTOR_COLOR_MATRIX_FILTER)
            forEach { mix(it.toRawBits()) }
            return hash
        }

        private fun LightingColorFilter.lightingHandleKey(): Long =
            (multiply.toArgb().toLong() shl 32) xor (add.toArgb().toLong() and 0xffffffffL) xor
                (COMMAND_EFFECT_DESCRIPTOR_LIGHTING_FILTER.toLong() shl 56)

        private fun effectDescriptorHandleKey(type: Int, payload: IntArray): Long {
            var hash = -3750763034362895579L
            fun mix(value: Int) {
                hash = hash xor value.toLong()
                hash *= 1099511628211L
            }
            mix(type)
            payload.forEach(::mix)
            return hash
        }

        private fun ImageFilterDescriptor.Blur.blurHandleKey(): Long {
            var hash = -3750763034362895579L
            fun mix(value: Int) {
                hash = hash xor value.toLong()
                hash *= 1099511628211L
            }
            mix(COMMAND_EFFECT_DESCRIPTOR_BLUR_IMAGE_FILTER)
            mix(sigmaX.toRawBits())
            mix(sigmaY.toRawBits())
            mix(tileMode)
            input?.let { mix(it.imageFilterHandleKey().highInt()); mix(it.imageFilterHandleKey().lowInt()) }
            return hash
        }

        private fun ImageFilterDescriptor.Offset.offsetHandleKey(): Long {
            var hash = -3750763034362895579L
            fun mix(value: Int) {
                hash = hash xor value.toLong()
                hash *= 1099511628211L
            }
            mix(COMMAND_EFFECT_DESCRIPTOR_OFFSET_IMAGE_FILTER)
            mix(dx.toRawBits())
            mix(dy.toRawBits())
            input?.let { mix(it.imageFilterHandleKey().highInt()); mix(it.imageFilterHandleKey().lowInt()) }
            return hash
        }

        private fun ImageFilterDescriptor.imageFilterHandleKey(): Long =
            when (this) {
                is ImageFilterDescriptor.Blur -> blurHandleKey()
                is ImageFilterDescriptor.Offset -> offsetHandleKey()
            }

        private fun defineShaderIfNeeded(shader: ShaderDescriptor): Long? {
            val payload = when (shader) {
                is ShaderDescriptor.LinearGradient -> shader.shader.linearGradientDescriptorPayload()
                is ShaderDescriptor.RadialGradient -> shader.shader.radialGradientDescriptorPayload()
                is ShaderDescriptor.SweepGradient -> shader.shader.sweepGradientDescriptorPayload()
                is ShaderDescriptor.Image -> shader.shader.imageShaderDescriptorPayload()
                is ShaderDescriptor.Color -> intArrayOf(shader.shader.color.toArgb())
                is ShaderDescriptor.PerlinNoise -> shader.shader.perlinNoiseDescriptorPayload()
                is ShaderDescriptor.RuntimeEffect -> shader.shader.runtimeEffectDescriptorPayload()
                is ShaderDescriptor.Transformed -> {
                    val childHandle = defineShaderIfNeeded(shaderDescriptorOrNull(shader.shader.shader) ?: return null) ?: return null
                    shader.shader.transformDescriptorPayload(childHandle)
                }
                is ShaderDescriptor.Composite -> {
                    val dstHandle = defineShaderIfNeeded(shaderDescriptorOrNull(shader.shader.dst) ?: return null) ?: return null
                    val srcHandle = defineShaderIfNeeded(shaderDescriptorOrNull(shader.shader.src) ?: return null) ?: return null
                    val blendMode = commandShaderBlendModeOrNull(shader.shader.blendMode) ?: return null
                    intArrayOf(dstHandle.highInt(), dstHandle.lowInt(), srcHandle.highInt(), srcHandle.lowInt(), blendMode)
                }
                is ShaderDescriptor.ColorFiltered -> {
                    val shaderHandle = defineShaderIfNeeded(shader.shader) ?: return null
                    val colorFilterHandle = defineDescriptorColorFilterIfNeeded(shader.colorFilter) ?: return null
                    intArrayOf(
                        shaderHandle.highInt(),
                        shaderHandle.lowInt(),
                        colorFilterHandle.highInt(),
                        colorFilterHandle.lowInt(),
                    )
                }
            } ?: return null
            val type = shader.commandDescriptorType()
            val handle = shaderHandleKey(type, payload)
            defineShaderHandleIfNeeded(handle, type, payload)
            return handle
        }

        private fun defineShaderHandleIfNeeded(handle: Long, type: Int, payload: IntArray) {
            var evictedHandle: Long? = null
            val shouldDefine = synchronized(shaderHandleLock) {
                if (definedShaderHandles.containsKey(handle)) {
                    definedShaderHandles[handle] = Unit
                    forceResourceDefinitions
                } else {
                    if (definedShaderHandles.size >= MAX_DEFINED_SHADER_HANDLES) {
                        val eldest = definedShaderHandles.keys.first()
                        definedShaderHandles.remove(eldest)
                        evictedHandle = eldest
                    }
                    definedShaderHandles[handle] = Unit
                    true
                }
            }
            evictedHandle?.let {
                commands.addCommand(
                    COMMAND_EVICT_SHADER_HANDLE,
                    COMMAND_RECORD_FLAGS_NONE,
                    it.highInt(),
                    it.lowInt(),
                )
            }
            if (shouldDefine) {
                commands.addCommand(
                    COMMAND_DEFINE_SHADER_DESCRIPTOR,
                    COMMAND_RECORD_FLAGS_NONE,
                    handle.highInt(),
                    handle.lowInt(),
                    type,
                    COMMAND_SHADER_DESCRIPTOR_VERSION_1,
                    payload.size,
                    *payload,
                )
            }
        }

        private fun ShaderDescriptor.commandDescriptorType(): Int =
            when (this) {
                is ShaderDescriptor.LinearGradient -> COMMAND_SHADER_DESCRIPTOR_LINEAR_GRADIENT
                is ShaderDescriptor.RadialGradient -> COMMAND_SHADER_DESCRIPTOR_RADIAL_GRADIENT
                is ShaderDescriptor.SweepGradient -> COMMAND_SHADER_DESCRIPTOR_SWEEP_GRADIENT
                is ShaderDescriptor.Image -> COMMAND_SHADER_DESCRIPTOR_IMAGE
                is ShaderDescriptor.Composite -> COMMAND_SHADER_DESCRIPTOR_COMPOSITE
                is ShaderDescriptor.RuntimeEffect -> COMMAND_SHADER_DESCRIPTOR_RUNTIME_EFFECT
                is ShaderDescriptor.Transformed -> COMMAND_SHADER_DESCRIPTOR_TRANSFORM
                is ShaderDescriptor.Color -> COMMAND_SHADER_DESCRIPTOR_COLOR
                is ShaderDescriptor.PerlinNoise -> COMMAND_SHADER_DESCRIPTOR_PERLIN_NOISE
                is ShaderDescriptor.ColorFiltered -> COMMAND_SHADER_DESCRIPTOR_COLOR_FILTER
            }

        private fun shaderHandleKey(type: Int, payload: IntArray): Long {
            var hash = -3750763034362895579L
            fun mix(value: Int) {
                hash = hash xor value.toLong()
                hash *= 1099511628211L
            }
            mix(type)
            payload.forEach(::mix)
            return hash
        }

        private fun JbrSkiaLinearGradientShader.linearGradientDescriptorPayload(): IntArray? {
            if (colors.size !in 2..16 ||
                !from.x.isFinite() ||
                !from.y.isFinite() ||
                !to.x.isFinite() ||
                !to.y.isFinite()
            ) return null
            val stops = colorStops ?: evenlyDistributedStops(colors.size)
            if (!stops.areValidGradientStops(colors.size)) return null
            return listOf(
                from.x.fixed1000(),
                from.y.fixed1000(),
                to.x.fixed1000(),
                to.y.fixed1000(),
                tileMode.commandValue(),
                colors.size,
            ).plus(colors.flatMapIndexed { index, color ->
                listOf(color.toArgb(), stops[index].fixed1000())
            }).toIntArray()
        }

        private fun JbrSkiaRadialGradientShader.radialGradientDescriptorPayload(): IntArray? {
            if (colors.size !in 2..16 ||
                !center.x.isFinite() ||
                !center.y.isFinite() ||
                !radius.isFinite() ||
                radius <= 0f
            ) return null
            val stops = colorStops ?: evenlyDistributedStops(colors.size)
            if (!stops.areValidGradientStops(colors.size)) return null
            return listOf(
                center.x.fixed1000(),
                center.y.fixed1000(),
                radius.fixed1000(),
                tileMode.commandValue(),
                colors.size,
            ).plus(colors.flatMapIndexed { index, color ->
                listOf(color.toArgb(), stops[index].fixed1000())
            }).toIntArray()
        }

        private fun JbrSkiaSweepGradientShader.sweepGradientDescriptorPayload(): IntArray? {
            if (colors.size !in 2..16 || !center.x.isFinite() || !center.y.isFinite()) return null
            val stops = colorStops ?: evenlyDistributedStops(colors.size)
            if (!stops.areValidGradientStops(colors.size)) return null
            return listOf(
                center.x.fixed1000(),
                center.y.fixed1000(),
                colors.size,
            ).plus(colors.flatMapIndexed { index, color ->
                listOf(color.toArgb(), stops[index].fixed1000())
            }).toIntArray()
        }

        private fun JbrSkiaPerlinNoiseShader.perlinNoiseDescriptorPayload(): IntArray? {
            if (!baseFrequencyX.isFinite() ||
                !baseFrequencyY.isFinite() ||
                !seed.isFinite() ||
                baseFrequencyX <= 0f ||
                baseFrequencyY <= 0f ||
                numOctaves !in 1..16 ||
                tileWidth < 0 ||
                tileHeight < 0 ||
                tileWidth > 4096 ||
                tileHeight > 4096
            ) return null
            return intArrayOf(
                kind.commandValue,
                baseFrequencyX.fixed1000000(),
                baseFrequencyY.fixed1000000(),
                numOctaves,
                seed.fixed1000(),
                tileWidth,
                tileHeight,
            )
        }

        private fun JbrSkiaTransformedShader.transformDescriptorPayload(childHandle: Long): IntArray? {
            if (matrix.size != 9 || matrix.any { !it.isFinite() }) return null
            return intArrayOf(childHandle.highInt(), childHandle.lowInt()) +
                matrix.map { it.fixed1000() }.toIntArray()
        }

        private fun JbrSkiaImageShader.imageShaderDescriptorPayload(): IntArray? {
            if (
                image.width <= 0 ||
                image.height <= 0 ||
                image.width > MAX_COMMAND_IMAGE_DIMENSION ||
                image.height > MAX_COMMAND_IMAGE_DIMENSION
            ) {
                return null
            }
            val cacheKey = defineImageIfNeeded(image) ?: return null
            imageRefCount++
            return intArrayOf(
                cacheKey.highInt(),
                cacheKey.lowInt(),
                image.width,
                image.height,
                tileModeX.commandValue(),
                tileModeY.commandValue(),
            )
        }

        @OptIn(ExperimentalGraphicsApi::class)
        private fun JbrSkiaRuntimeEffectShader.runtimeEffectDescriptorPayload(): IntArray? {
            if (sksl.isEmpty() ||
                sksl.length > 4096 ||
                uniforms.size > 256 ||
                uniformSchema.size > 16 ||
                children.size + namedChildren.size > 8 ||
                namedChildren.size > 8
            ) return null
            if (sksl.any { it.code !in 1..127 }) return null
            val uniformSchemaPayload = uniformSchema.runtimeEffectUniformSchemaPayload(uniforms.size) ?: return null
            val childShaders = children + namedChildren.map { it.shader }
            val namedChildSchemaPayload = namedChildren.runtimeEffectChildSchemaPayload(children.size) ?: return null
            val sourceHash = sksl.shaderSourceHash()
            val childHandles = childShaders.flatMap { child ->
                val handle = defineShaderIfNeeded(shaderDescriptorOrNull(child) ?: return null) ?: return null
                listOf(handle.highInt(), handle.lowInt())
            }.toIntArray()
            return intArrayOf(
                sksl.length,
                uniforms.size,
                childShaders.size,
                uniformSchema.size,
                namedChildren.size,
                sourceHash.highInt(),
                sourceHash.lowInt(),
            ) +
                childHandles +
                uniformSchemaPayload +
                namedChildSchemaPayload +
                sksl.map { it.code }.toIntArray() +
                uniforms.map { it.toRawBits() }.toIntArray()
        }

        @OptIn(ExperimentalGraphicsApi::class)
        private fun JbrSkiaRuntimeEffectColorFilter.runtimeEffectColorFilterDescriptorPayload(): IntArray? {
            if (sksl.isEmpty() ||
                sksl.length > 4096 ||
                uniforms.size > 256 ||
                uniformSchema.size > 16 ||
                children.size + namedChildren.size > 8 ||
                namedChildren.size > 8
            ) return null
            if (sksl.any { it.code !in 1..127 }) return null
            val uniformSchemaPayload = uniformSchema.runtimeEffectUniformSchemaPayload(uniforms.size) ?: return null
            val childColorFilters = children + namedChildren.map { it.colorFilter }
            val namedChildSchemaPayload = namedChildren.runtimeEffectColorFilterChildSchemaPayload(children.size) ?: return null
            val sourceHash = sksl.shaderSourceHash()
            val childHandles = childColorFilters.flatMap { child ->
                val handle = defineDescriptorColorFilterIfNeeded(descriptorColorFilterOrNull(child) ?: return null)
                    ?: return null
                listOf(handle.highInt(), handle.lowInt())
            }.toIntArray()
            return intArrayOf(
                sksl.length,
                uniforms.size,
                childColorFilters.size,
                uniformSchema.size,
                namedChildren.size,
                sourceHash.highInt(),
                sourceHash.lowInt(),
            ) +
                childHandles +
                uniformSchemaPayload +
                namedChildSchemaPayload +
                sksl.map { it.code }.toIntArray() +
                uniforms.map { it.toRawBits() }.toIntArray()
        }

        @OptIn(ExperimentalGraphicsApi::class)
        private fun List<RuntimeEffectUniform>.runtimeEffectUniformSchemaPayload(uniformFloatCount: Int): IntArray? {
            val values = mutableListOf<Int>()
            for (uniform in this) {
                if (!uniform.name.isValidRuntimeEffectUniformName() ||
                    uniform.floatOffset < 0 ||
                    uniform.floatCount <= 0 ||
                    uniform.floatOffset > uniformFloatCount - uniform.floatCount
                ) return null
                values += uniform.floatOffset
                values += uniform.floatCount
                values += uniform.name.length
                uniform.name.forEach { values += it.code }
            }
            return values.toIntArray()
        }

        @OptIn(ExperimentalGraphicsApi::class)
        private fun List<RuntimeEffectChild>.runtimeEffectChildSchemaPayload(firstNamedChildIndex: Int): IntArray? {
            val values = mutableListOf<Int>()
            for ((relativeIndex, child) in withIndex()) {
                if (!child.name.isValidRuntimeEffectUniformName()) return null
                values += firstNamedChildIndex + relativeIndex
                values += child.name.length
                child.name.forEach { values += it.code }
            }
            return values.toIntArray()
        }

        @OptIn(ExperimentalGraphicsApi::class)
        private fun List<RuntimeEffectColorFilterChild>.runtimeEffectColorFilterChildSchemaPayload(
            firstNamedChildIndex: Int
        ): IntArray? {
            val values = mutableListOf<Int>()
            for ((relativeIndex, child) in withIndex()) {
                if (!child.name.isValidRuntimeEffectUniformName()) return null
                values += firstNamedChildIndex + relativeIndex
                values += child.name.length
                child.name.forEach { values += it.code }
            }
            return values.toIntArray()
        }

        private fun String.isValidRuntimeEffectUniformName(): Boolean {
            if (isEmpty() || length > 64) return false
            val first = first()
            if (first != '_' && first !in 'A'..'Z' && first !in 'a'..'z') return false
            return all { it == '_' || it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }
        }

        private fun String.shaderSourceHash(): Long {
            var hash = -3750763034362895579L
            forEach { char ->
                hash = hash xor char.code.toLong()
                hash *= 1099511628211L
            }
            return hash
        }

        private fun IntArray.imageCacheKey(width: Int, height: Int): Long {
            var hash = -3750763034362895579L
            fun mix(value: Int) {
                hash = hash xor value.toLong()
                hash *= 1099511628211L
            }
            mix(width)
            mix(height)
            forEach(::mix)
            return hash
        }

        private fun nativeBitmapImageDefinition(image: ImageBitmap): NativeBitmapImageDefinition? {
            val bitmap = runCatching { image.asSkiaBitmap() }.getOrNull() ?: return null
            val ptr = bitmap.jbrSkiaNativePtrOrZero()
            if (ptr == 0L) return null
            var hash = -3750763034362895579L
            fun mix(value: Int) {
                hash = hash xor value.toLong()
                hash *= 1099511628211L
            }
            fun mix(value: Long) {
                hash = hash xor value
                hash *= 1099511628211L
            }
            mix(image.width)
            mix(image.height)
            mix(ptr)
            mix(bitmap.generationId)
            return NativeBitmapImageDefinition(ptr, bitmap.generationId, hash)
        }

        private fun org.jetbrains.skia.Bitmap.jbrSkiaNativePtrOrZero(): Long {
            val marker = "_ptr=0x"
            val text = toString()
            val start = text.indexOf(marker)
            if (start < 0) return 0L
            val digits = text.substring(start + marker.length).substringBefore(')')
            return digits.toLongOrNull(radix = 16) ?: 0L
        }

        private fun StrokeCap.commandValue(): Int = when (this) {
            StrokeCap.Butt -> 0
            StrokeCap.Round -> 1
            StrokeCap.Square -> 2
            else -> 0
        }

        private fun StrokeJoin.commandValue(): Int = when (this) {
            StrokeJoin.Miter -> 0
            StrokeJoin.Round -> 1
            StrokeJoin.Bevel -> 2
            else -> 0
        }

        private fun ClipOp.commandValue(): Int = when (this) {
            ClipOp.Intersect -> 0
            ClipOp.Difference -> 1
            else -> 0
        }

        private fun TileMode.commandValue(): Int = when (this) {
            TileMode.Clamp -> 0
            TileMode.Repeated -> 1
            TileMode.Mirror -> 2
            TileMode.Decal -> 3
            else -> 0
        }

        private fun defineImageIfNeeded(image: ImageBitmap): Long? {
            val nativeBitmapDefinition = nativeBitmapImageDefinition(image)
            var pixels: IntArray? = null
            fun readPixels(): IntArray {
                pixels?.let { return it }
                return IntArray(image.width * image.height).also {
                    image.readPixels(it)
                    pixels = it
                }
            }
            val pixelCount = image.width * image.height
            val useContentKeyForNativeBitmap =
                nativeBitmapDefinition != null && pixelCount <= SMALL_NATIVE_BITMAP_CONTENT_KEY_PIXELS

            var evictedKey: Long? = null
            val cacheKey = synchronized(imageCacheLock) {
                if (nativeBitmapDefinition != null) {
                    if (useContentKeyForNativeBitmap) {
                        val entry = imageIdentityCache[image]
                        if (entry != null && entry.width == image.width && entry.height == image.height) {
                            entry.cacheKey
                        } else {
                            val computedKey = readPixels().imageCacheKey(image.width, image.height)
                            imageIdentityCache[image] = ImageCacheEntry(image.width, image.height, computedKey)
                            computedKey
                        }
                    } else {
                        nativeBitmapDefinition.cacheKey
                    }
                } else {
                    val entry = imageIdentityCache[image]
                    if (entry != null && entry.width == image.width && entry.height == image.height) {
                        entry.cacheKey
                    } else {
                        val readPixels = readPixels()
                        val computedKey = readPixels.imageCacheKey(image.width, image.height)
                        imageIdentityCache[image] = ImageCacheEntry(image.width, image.height, computedKey)
                        computedKey
                    }
                }
            }
            var shouldEnsureDefined = false
            val shouldDefinePixels = synchronized(imageCacheLock) {
                val enteringFrame = cacheKey !in previousFrameImageKeys
                imageReferencedKeys += cacheKey
                if (definedImageKeys.containsKey(cacheKey)) {
                    definedImageKeys[cacheKey] = Unit
                    val needsDefinition = forceResourceDefinitions || !imageCacheHasDefinitions.get()
                    shouldEnsureDefined = !needsDefinition && enteringFrame && cacheKey in confirmedNativeImageKeys
                    needsDefinition ||
                        nativeBitmapDefinition != null && cacheKey !in confirmedNativeImageKeys ||
                        enteringFrame && cacheKey !in confirmedNativeImageKeys
                } else {
                    if (updateSharedResourceCaches && definedImageKeys.size >= MAX_DEFINED_IMAGE_KEYS) {
                        val eldest = definedImageKeys.keys.first()
                        definedImageKeys.remove(eldest)
                        confirmedNativeImageKeys.remove(eldest)
                        evictedKey = eldest
                    }
                    if (updateSharedResourceCaches) {
                        definedImageKeys[cacheKey] = Unit
                    }
                    true
                }
            }
            evictedKey?.let {
                imageCacheEvictCount++
                commands.addCommand(
                    COMMAND_EVICT_IMAGE_CACHE_KEY,
                    COMMAND_RECORD_FLAGS_NONE,
                    it.highInt(),
                    it.lowInt(),
                )
            }
            if (shouldDefinePixels) {
                if (nativeBitmapDefinition != null) {
                    nativeImageReferences += image
                    imageDefineCount++
                    imageDefineWordCount += 7
                    imageDefinedKeys += cacheKey
                    commands.addCommand(
                        COMMAND_DEFINE_IMAGE_BITMAP,
                        COMMAND_RECORD_FLAGS_NONE,
                        cacheKey.highInt(),
                        cacheKey.lowInt(),
                        image.width,
                        image.height,
                        nativeBitmapDefinition.ptr.highInt(),
                        nativeBitmapDefinition.ptr.lowInt(),
                        nativeBitmapDefinition.generationId,
                    )
                } else {
                    val definePixels = readPixels()
                    imageDefineCount++
                    imageDefineWordCount += definePixels.size + 7
                    imageDefinePixelCount += definePixels.size
                    imageDefinedKeys += cacheKey
                    commands.addCommand(
                        op = COMMAND_DEFINE_IMAGE_ARGB,
                        recordFlags = COMMAND_RECORD_FLAGS_NONE,
                        pixels = definePixels,
                        cacheKey.highInt(),
                        cacheKey.lowInt(),
                        image.width,
                        image.height,
                        definePixels.size,
                    )
                }
            } else if (shouldEnsureDefined) {
                imageDefineCount++
                imageDefineWordCount += 7
                imageDefinedKeys += cacheKey
                commands.addCommand(
                    op = COMMAND_DEFINE_IMAGE_ARGB,
                    recordFlags = COMMAND_RECORD_FLAGS_NONE,
                    pixels = IntArray(0),
                    cacheKey.highInt(),
                    cacheKey.lowInt(),
                    image.width,
                    image.height,
                    0,
                )
            }
            return cacheKey
        }

        private fun addClearRect(left: Float, top: Float, right: Float, bottom: Float) {
            commands.addCommand(
                COMMAND_CLEAR_RECT,
                COMMAND_RECORD_FLAGS_NONE,
                state.x(left),
                state.y(top),
                state.width(right - left),
                state.height(bottom - top),
            )
        }

        private fun addLinearGradientRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            if (paint.style == PaintingStyle.Stroke) {
                addLinearGradientStrokeRect(left, top, right, bottom, paint)
                return
            }
            val gradientPayload = paint.linearGradientPayload() ?: return
            withSolidColorBlendLayer(left, top, right, bottom, paint) {
                commands.addCommand(
                    COMMAND_FILL_RECT_LINEAR_GRADIENT,
                    paint.recordFlags(),
                    left.fixed1000(),
                    top.fixed1000(),
                    right.fixed1000(),
                    bottom.fixed1000(),
                    *gradientPayload,
                )
            }
        }

        private fun addLinearGradientStrokeRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            val gradientPayload = paint.linearGradientPayload(requiredStyle = PaintingStyle.Stroke) ?: return
            if (!paint.strokeWidth.isFinite() || paint.strokeWidth <= 0f) {
                countUnsupported("linearGradientStrokeWidth")
                return
            }
            val outset = paint.blendLayerOutset(forceStroke = true)
            withSolidColorBlendLayer(left - outset, top - outset, right + outset, bottom + outset, paint) {
                commands.addCommand(
                    COMMAND_STROKE_RECT_LINEAR_GRADIENT,
                    paint.recordFlags(),
                    left.fixed1000(),
                    top.fixed1000(),
                    right.fixed1000(),
                    bottom.fixed1000(),
                    paint.strokeWidth.fixed1000(),
                    paint.strokeCap.commandValue(),
                    paint.strokeJoin.commandValue(),
                    paint.strokeMiter1000(),
                    *gradientPayload,
                )
            }
        }

        private fun addRadialGradientRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            if (paint.style == PaintingStyle.Stroke) {
                addRadialGradientStrokeRect(left, top, right, bottom, paint)
                return
            }
            val gradientPayload = paint.radialGradientPayload() ?: return
            withSolidColorBlendLayer(left, top, right, bottom, paint) {
                commands.addCommand(
                    COMMAND_FILL_RECT_RADIAL_GRADIENT,
                    paint.recordFlags(),
                    left.fixed1000(),
                    top.fixed1000(),
                    right.fixed1000(),
                    bottom.fixed1000(),
                    *gradientPayload,
                )
            }
        }

        private fun addRadialGradientStrokeRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            val gradientPayload = paint.radialGradientPayload(requiredStyle = PaintingStyle.Stroke) ?: return
            if (!paint.strokeWidth.isFinite() || paint.strokeWidth <= 0f) {
                countUnsupported("radialGradientStrokeWidth")
                return
            }
            val outset = paint.blendLayerOutset(forceStroke = true)
            withSolidColorBlendLayer(left - outset, top - outset, right + outset, bottom + outset, paint) {
                commands.addCommand(
                    COMMAND_STROKE_RECT_RADIAL_GRADIENT,
                    paint.recordFlags(),
                    left.fixed1000(),
                    top.fixed1000(),
                    right.fixed1000(),
                    bottom.fixed1000(),
                    paint.strokeWidth.fixed1000(),
                    paint.strokeCap.commandValue(),
                    paint.strokeJoin.commandValue(),
                    paint.strokeMiter1000(),
                    *gradientPayload,
                )
            }
        }

        private fun addSweepGradientRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            if (paint.style == PaintingStyle.Stroke) {
                addSweepGradientStrokeRect(left, top, right, bottom, paint)
                return
            }
            val gradientPayload = paint.sweepGradientPayload() ?: return
            withSolidColorBlendLayer(left, top, right, bottom, paint) {
                commands.addCommand(
                    COMMAND_FILL_RECT_SWEEP_GRADIENT,
                    paint.recordFlags(),
                    left.fixed1000(),
                    top.fixed1000(),
                    right.fixed1000(),
                    bottom.fixed1000(),
                    *gradientPayload,
                )
            }
        }

        private fun addSweepGradientStrokeRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            val gradientPayload = paint.sweepGradientPayload(requiredStyle = PaintingStyle.Stroke) ?: return
            if (!paint.strokeWidth.isFinite() || paint.strokeWidth <= 0f) {
                countUnsupported("sweepGradientStrokeWidth")
                return
            }
            val outset = paint.blendLayerOutset(forceStroke = true)
            withSolidColorBlendLayer(left - outset, top - outset, right + outset, bottom + outset, paint) {
                commands.addCommand(
                    COMMAND_STROKE_RECT_SWEEP_GRADIENT,
                    paint.recordFlags(),
                    left.fixed1000(),
                    top.fixed1000(),
                    right.fixed1000(),
                    bottom.fixed1000(),
                    paint.strokeWidth.fixed1000(),
                    paint.strokeCap.commandValue(),
                    paint.strokeJoin.commandValue(),
                    paint.strokeMiter1000(),
                    *gradientPayload,
                )
            }
        }

        private fun addSweepGradientRoundRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radiusX: Float,
            radiusY: Float,
            paint: Paint,
        ) {
            if (paint.style == PaintingStyle.Stroke) {
                addSweepGradientStrokeRoundRect(left, top, right, bottom, radiusX, radiusY, paint)
                return
            }
            val gradientPayload = paint.sweepGradientPayload() ?: return
            if (radiusX < 0f || radiusY < 0f) {
                countUnsupported("sweepGradientRoundRectRadius")
                return
            }
            withSolidColorBlendLayer(left, top, right, bottom, paint) {
                commands.addCommand(
                    COMMAND_FILL_ROUND_RECT_SWEEP_GRADIENT,
                    paint.recordFlags(),
                    left.fixed1000(),
                    top.fixed1000(),
                    right.fixed1000(),
                    bottom.fixed1000(),
                    radiusX.fixed1000().coerceAtLeast(0),
                    radiusY.fixed1000().coerceAtLeast(0),
                    *gradientPayload,
                )
            }
        }

        private fun addSweepGradientStrokeRoundRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radiusX: Float,
            radiusY: Float,
            paint: Paint,
        ) {
            val gradientPayload = paint.sweepGradientPayload(requiredStyle = PaintingStyle.Stroke) ?: return
            if (radiusX < 0f || radiusY < 0f) {
                countUnsupported("sweepGradientStrokeRoundRectRadius")
                return
            }
            if (!paint.strokeWidth.isFinite() || paint.strokeWidth <= 0f) {
                countUnsupported("sweepGradientStrokeWidth")
                return
            }
            val outset = paint.blendLayerOutset(forceStroke = true)
            withSolidColorBlendLayer(left - outset, top - outset, right + outset, bottom + outset, paint) {
                commands.addCommand(
                    COMMAND_STROKE_ROUND_RECT_SWEEP_GRADIENT,
                    paint.recordFlags(),
                    left.fixed1000(),
                    top.fixed1000(),
                    right.fixed1000(),
                    bottom.fixed1000(),
                    radiusX.fixed1000().coerceAtLeast(0),
                    radiusY.fixed1000().coerceAtLeast(0),
                    paint.strokeWidth.fixed1000(),
                    paint.strokeCap.commandValue(),
                    paint.strokeJoin.commandValue(),
                    paint.strokeMiter1000(),
                    *gradientPayload,
                )
            }
        }

        private fun addRadialGradientRoundRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radiusX: Float,
            radiusY: Float,
            paint: Paint,
        ) {
            if (paint.style == PaintingStyle.Stroke) {
                addRadialGradientStrokeRoundRect(left, top, right, bottom, radiusX, radiusY, paint)
                return
            }
            val gradientPayload = paint.radialGradientPayload() ?: return
            if (radiusX < 0f || radiusY < 0f) {
                countUnsupported("radialGradientRoundRectRadius")
                return
            }
            withSolidColorBlendLayer(left, top, right, bottom, paint) {
                commands.addCommand(
                    COMMAND_FILL_ROUND_RECT_RADIAL_GRADIENT,
                    paint.recordFlags(),
                    left.fixed1000(),
                    top.fixed1000(),
                    right.fixed1000(),
                    bottom.fixed1000(),
                    radiusX.fixed1000().coerceAtLeast(0),
                    radiusY.fixed1000().coerceAtLeast(0),
                    *gradientPayload,
                )
            }
        }

        private fun addRadialGradientStrokeRoundRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radiusX: Float,
            radiusY: Float,
            paint: Paint,
        ) {
            val gradientPayload = paint.radialGradientPayload(requiredStyle = PaintingStyle.Stroke) ?: return
            if (radiusX < 0f || radiusY < 0f) {
                countUnsupported("radialGradientStrokeRoundRectRadius")
                return
            }
            if (!paint.strokeWidth.isFinite() || paint.strokeWidth <= 0f) {
                countUnsupported("radialGradientStrokeWidth")
                return
            }
            val outset = paint.blendLayerOutset(forceStroke = true)
            withSolidColorBlendLayer(left - outset, top - outset, right + outset, bottom + outset, paint) {
                commands.addCommand(
                    COMMAND_STROKE_ROUND_RECT_RADIAL_GRADIENT,
                    paint.recordFlags(),
                    left.fixed1000(),
                    top.fixed1000(),
                    right.fixed1000(),
                    bottom.fixed1000(),
                    radiusX.fixed1000().coerceAtLeast(0),
                    radiusY.fixed1000().coerceAtLeast(0),
                    paint.strokeWidth.fixed1000(),
                    paint.strokeCap.commandValue(),
                    paint.strokeJoin.commandValue(),
                    paint.strokeMiter1000(),
                    *gradientPayload,
                )
            }
        }

        private fun addLinearGradientRoundRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radiusX: Float,
            radiusY: Float,
            paint: Paint,
        ) {
            if (paint.style == PaintingStyle.Stroke) {
                addLinearGradientStrokeRoundRect(left, top, right, bottom, radiusX, radiusY, paint)
                return
            }
            val gradientPayload = paint.linearGradientPayload() ?: return
            if (radiusX < 0f || radiusY < 0f) {
                countUnsupported("linearGradientRoundRectRadius")
                return
            }
            withSolidColorBlendLayer(left, top, right, bottom, paint) {
                commands.addCommand(
                    COMMAND_FILL_ROUND_RECT_LINEAR_GRADIENT,
                    paint.recordFlags(),
                    left.fixed1000(),
                    top.fixed1000(),
                    right.fixed1000(),
                    bottom.fixed1000(),
                    radiusX.fixed1000().coerceAtLeast(0),
                    radiusY.fixed1000().coerceAtLeast(0),
                    *gradientPayload,
                )
            }
        }

        private fun addLinearGradientStrokeRoundRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radiusX: Float,
            radiusY: Float,
            paint: Paint,
        ) {
            val gradientPayload = paint.linearGradientPayload(requiredStyle = PaintingStyle.Stroke) ?: return
            if (radiusX < 0f || radiusY < 0f) {
                countUnsupported("linearGradientStrokeRoundRectRadius")
                return
            }
            if (!paint.strokeWidth.isFinite() || paint.strokeWidth <= 0f) {
                countUnsupported("linearGradientStrokeWidth")
                return
            }
            val outset = paint.blendLayerOutset(forceStroke = true)
            withSolidColorBlendLayer(left - outset, top - outset, right + outset, bottom + outset, paint) {
                commands.addCommand(
                    COMMAND_STROKE_ROUND_RECT_LINEAR_GRADIENT,
                    paint.recordFlags(),
                    left.fixed1000(),
                    top.fixed1000(),
                    right.fixed1000(),
                    bottom.fixed1000(),
                    radiusX.fixed1000().coerceAtLeast(0),
                    radiusY.fixed1000().coerceAtLeast(0),
                    paint.strokeWidth.fixed1000(),
                    paint.strokeCap.commandValue(),
                    paint.strokeJoin.commandValue(),
                    paint.strokeMiter1000(),
                    *gradientPayload,
                )
            }
        }

        private fun Paint.linearGradientPayload(requiredStyle: PaintingStyle = PaintingStyle.Fill): IntArray? {
            val gradient = shader?.jbrSkiaLinearGradient ?: return null
            if (!isSupportedGradientForBlendLayer || style != requiredStyle) {
                countUnsupported("linearGradientPaint")
                return null
            }
            if (gradient.colors.size !in 2..16) {
                countUnsupported("linearGradientColorCount")
                return null
            }
            val stops = gradient.colorStops ?: evenlyDistributedStops(gradient.colors.size)
            if (!stops.areValidGradientStops(gradient.colors.size)) {
                countUnsupported("linearGradientStops")
                return null
            }
            if (!gradient.from.x.isFinite() || !gradient.from.y.isFinite() ||
                !gradient.to.x.isFinite() || !gradient.to.y.isFinite()
            ) {
                countUnsupported("linearGradientPoints")
                return null
            }
            val colorStopPairs = gradient.colors.flatMapIndexed { index, color ->
                listOf(
                    color.copy(alpha = color.alpha * alpha).toArgb(),
                    stops[index].fixed1000(),
                )
            }
            return listOf(
                gradient.from.x.fixed1000(),
                gradient.from.y.fixed1000(),
                gradient.to.x.fixed1000(),
                gradient.to.y.fixed1000(),
                gradient.tileMode.commandValue(),
                gradient.colors.size,
            ).plus(colorStopPairs).toIntArray()
        }

        private fun Paint.radialGradientPayload(requiredStyle: PaintingStyle = PaintingStyle.Fill): IntArray? {
            val gradient = shader?.jbrSkiaRadialGradient ?: return null
            if (!isSupportedGradientForBlendLayer || style != requiredStyle) {
                countUnsupported("radialGradientPaint")
                return null
            }
            if (gradient.colors.size !in 2..16) {
                countUnsupported("radialGradientColorCount")
                return null
            }
            val stops = gradient.colorStops ?: evenlyDistributedStops(gradient.colors.size)
            if (!stops.areValidGradientStops(gradient.colors.size)) {
                countUnsupported("radialGradientStops")
                return null
            }
            if (!gradient.center.x.isFinite() || !gradient.center.y.isFinite() ||
                !gradient.radius.isFinite() || gradient.radius <= 0f
            ) {
                countUnsupported("radialGradientGeometry")
                return null
            }
            val colorStopPairs = gradient.colors.flatMapIndexed { index, color ->
                listOf(
                    color.copy(alpha = color.alpha * alpha).toArgb(),
                    stops[index].fixed1000(),
                )
            }
            return listOf(
                gradient.center.x.fixed1000(),
                gradient.center.y.fixed1000(),
                gradient.radius.fixed1000(),
                gradient.tileMode.commandValue(),
                gradient.colors.size,
            ).plus(colorStopPairs).toIntArray()
        }

        private fun Paint.sweepGradientPayload(requiredStyle: PaintingStyle = PaintingStyle.Fill): IntArray? {
            val gradient = shader?.jbrSkiaSweepGradient ?: return null
            if (!isSupportedGradientForBlendLayer || style != requiredStyle) {
                countUnsupported("sweepGradientPaint")
                return null
            }
            if (gradient.colors.size !in 2..16) {
                countUnsupported("sweepGradientColorCount")
                return null
            }
            val stops = gradient.colorStops ?: evenlyDistributedStops(gradient.colors.size)
            if (!stops.areValidGradientStops(gradient.colors.size)) {
                countUnsupported("sweepGradientStops")
                return null
            }
            if (!gradient.center.x.isFinite() || !gradient.center.y.isFinite()) {
                countUnsupported("sweepGradientGeometry")
                return null
            }
            val colorStopPairs = gradient.colors.flatMapIndexed { index, color ->
                listOf(
                    color.copy(alpha = color.alpha * alpha).toArgb(),
                    stops[index].fixed1000(),
                )
            }
            return listOf(
                gradient.center.x.fixed1000(),
                gradient.center.y.fixed1000(),
                gradient.colors.size,
            ).plus(colorStopPairs).toIntArray()
        }

        private fun evenlyDistributedStops(count: Int): List<Float> =
            List(count) { index -> index.toFloat() / (count - 1).toFloat() }

        private fun List<Float>.areValidGradientStops(colorCount: Int): Boolean {
            if (size != colorCount) return false
            var previous = -1f
            for (stop in this) {
                if (!stop.isFinite() || stop < 0f || stop > 1f || stop <= previous) {
                    return false
                }
                previous = stop
            }
            return true
        }

        private fun List<Int>.toIntArray(): IntArray =
            IntArray(size) { this[it] }

        private fun countUnsupported(reason: String) {
            unsupportedReasons[reason] = unsupportedReasons.getOrElse(reason) { 0 } + 1
        }

        private fun commandStream(): IntArray =
            commands.toIntArray()

        private val unsupportedCount: Int
            get() = unsupportedReasons.values.sum()

        private fun Any.toReasonToken(): String =
            toString().replace("[^A-Za-z0-9]".toRegex(), "_")
    }

    private class CommandStreamWriter {
        private var payload = IntArray(1024)
        private var payloadSize = 0
        private val opCounts = linkedMapOf<Int, Int>()

        val streamSize: Int
            get() = COMMAND_STREAM_HEADER_SIZE + payloadSize

        fun addCommand(op: Int, recordFlags: Int = COMMAND_RECORD_FLAGS_NONE, vararg args: Int) {
            countOp(op)
            addRecordHeader(op, recordFlags, args.size)
            addAll(args)
        }

        fun addRestore() {
            foldSaveTranslateIntoDrawImageRefFullBeforeTrailingRestore()
            foldSaveTranslateIntoFillRectBeforeTrailingRestore()
            foldSaveTranslateTransformableScopeBeforeTrailingRestore()
            foldTrailingTranslateIntoDrawImageRefFullBeforeTrailingRestore()
            foldTrailingTranslateIntoRoundRectBeforeTrailingRestore()
            if (removeRedundantSaveBeforeLayerBeforeCurrentRestore()) {
                return
            }
            if (foldSaveTranslateIntoDrawImageRefFullBeforeRestore()) {
                return
            }
            foldTrailingTranslateIntoDrawImageRefFull()
            foldTrailingTranslateIntoRoundRect()
            foldTrailingTranslateIntoFillRect()
            removeTrailingTranslate()
            if (removeRedundantSaveAroundStateNeutralRecords()) {
                return
            }
            if (payloadSize >= 4 &&
                payload[payloadSize - 4] == COMMAND_RESTORE_N &&
                payload[payloadSize - 3] == 4 * Int.SIZE_BYTES &&
                payload[payloadSize - 2] == COMMAND_RECORD_FLAGS_NONE
            ) {
                payload[payloadSize - 1] += 1
                return
            }
            if (payloadSize >= 3 &&
                payload[payloadSize - 3] == COMMAND_RESTORE &&
                payload[payloadSize - 2] == 3 * Int.SIZE_BYTES &&
                payload[payloadSize - 1] == COMMAND_RECORD_FLAGS_NONE
            ) {
                ensureCapacity(payloadSize + 1)
                payload[payloadSize - 3] = COMMAND_RESTORE_N
                payload[payloadSize - 2] = 4 * Int.SIZE_BYTES
                payload[payloadSize++] = 2
                decrementOp(COMMAND_RESTORE)
                countOp(COMMAND_RESTORE_N)
                return
            }
            addCommand(COMMAND_RESTORE)
        }

        private fun foldSaveTranslateIntoDrawImageRefFullBeforeTrailingRestore(): Boolean {
            val restoreStart = previousRecordStart(payloadSize) ?: return false
            val restoreOp = payload[restoreStart]
            if (restoreOp != COMMAND_RESTORE && restoreOp != COMMAND_RESTORE_N) {
                return false
            }
            val imageStart = previousRecordStart(restoreStart) ?: return false
            if (payload[imageStart] != COMMAND_DRAW_IMAGE_REF_FULL) {
                return false
            }
            val saveTranslateStart = saveTranslateBeforeImageDefinitions(imageStart) ?: return false
            foldSaveTranslateIntoDrawImageRefFull(saveTranslateStart, imageStart)
            val shiftedRestoreStart = restoreStart - 5
            removeOneTrailingRestore(shiftedRestoreStart, restoreOp)
            return true
        }

        private fun foldSaveTranslateIntoFillRectBeforeTrailingRestore(): Boolean {
            val restoreStart = previousRecordStart(payloadSize) ?: return false
            val restoreOp = payload[restoreStart]
            if (restoreOp != COMMAND_RESTORE && restoreOp != COMMAND_RESTORE_N) {
                return false
            }
            val fillRectStart = previousRecordStart(restoreStart) ?: return false
            if (payload[fillRectStart] != COMMAND_FILL_RECT) {
                return false
            }
            val saveTranslateStart = previousRecordStart(fillRectStart) ?: return false
            if (!isSaveTranslateRecord(saveTranslateStart) ||
                !foldSaveTranslateIntoFillRect(saveTranslateStart, fillRectStart)
            ) {
                return false
            }
            val shiftedRestoreStart = restoreStart - 5
            removeOneTrailingRestore(shiftedRestoreStart, restoreOp)
            return true
        }

        private fun foldSaveTranslateTransformableScopeBeforeTrailingRestore(): Boolean {
            val restoreStart = previousRecordStart(payloadSize) ?: return false
            val restoreOp = payload[restoreStart]
            if (restoreOp != COMMAND_RESTORE && restoreOp != COMMAND_RESTORE_N) {
                return false
            }
            var offset = restoreStart
            var saveTranslateStart: Int? = null
            var transformableRecordCount = 0
            var innerTranslateCount = 0
            while (true) {
                val previous = previousRecordStart(offset) ?: return false
                if (isSaveTranslateRecord(previous)) {
                    saveTranslateStart = previous
                    break
                }
                val op = payload[previous]
                if (isTranslateRecord(previous)) {
                    if (payload[previous + 3] % 1000 != 0 || payload[previous + 4] % 1000 != 0) {
                        return false
                    }
                    innerTranslateCount++
                    offset = previous
                    continue
                }
                if (isTranslateScopePassThroughRecord(op)) {
                    offset = previous
                    continue
                }
                if (!isTranslateScopeTransformableRecord(op)) {
                    return false
                }
                transformableRecordCount++
                offset = previous
            }
            if (transformableRecordCount == 0) {
                return false
            }
            val start = saveTranslateStart ?: return false
            val dx = payload[start + 3]
            val dy = payload[start + 4]
            if (dx % 1000 != 0 || dy % 1000 != 0) {
                return false
            }
            var readOffset = start + 5
            var writeOffset = start
            var accumulatedDx = dx
            var accumulatedDy = dy
            while (readOffset < restoreStart) {
                val op = payload[readOffset]
                val recordSize = payload[readOffset + 1] / Int.SIZE_BYTES
                if (isTranslateRecord(readOffset)) {
                    accumulatedDx += payload[readOffset + 3]
                    accumulatedDy += payload[readOffset + 4]
                    readOffset += recordSize
                    continue
                }
                if (isTranslateScopeTransformableRecord(op)) {
                    if (requiresWholePixelTranslation(op) &&
                        (accumulatedDx % 1000 != 0 || accumulatedDy % 1000 != 0)
                    ) {
                        return false
                    }
                    translateScopeRecord(readOffset, accumulatedDx, accumulatedDy)
                }
                payload.copyInto(
                    payload,
                    destinationOffset = writeOffset,
                    startIndex = readOffset,
                    endIndex = readOffset + recordSize,
                )
                writeOffset += recordSize
                readOffset += recordSize
            }
            if (readOffset != restoreStart) {
                return false
            }
            val removedWords = restoreStart - writeOffset
            payload.copyInto(
                payload,
                destinationOffset = writeOffset,
                startIndex = restoreStart,
                endIndex = payloadSize,
            )
            payloadSize -= removedWords
            decrementOp(COMMAND_SAVE_TRANSLATE)
            repeat(innerTranslateCount) {
                decrementOp(COMMAND_TRANSLATE)
            }
            val shiftedRestoreStart = restoreStart - removedWords
            removeOneTrailingRestore(shiftedRestoreStart, restoreOp)
            return true
        }

        private fun requiresWholePixelTranslation(op: Int): Boolean =
            op == COMMAND_CLEAR_RECT || op == COMMAND_FILL_RECT

        private fun isTranslateRecord(recordStart: Int): Boolean =
            payload[recordStart] == COMMAND_TRANSLATE &&
                payload[recordStart + 1] == 5 * Int.SIZE_BYTES &&
                payload[recordStart + 2] == COMMAND_RECORD_FLAGS_NONE

        private fun foldSaveTranslateIntoDrawImageRefFullBeforeRestore(): Boolean {
            val imageStart = previousRecordStart(payloadSize) ?: return false
            if (payload[imageStart] != COMMAND_DRAW_IMAGE_REF_FULL) {
                return false
            }
            val saveTranslateStart = saveTranslateBeforeImageDefinitions(imageStart) ?: return false
            foldSaveTranslateIntoDrawImageRefFull(saveTranslateStart, imageStart)
            return true
        }

        private fun foldSaveTranslateIntoDrawImageRefFull(saveTranslateStart: Int, imageStart: Int) {
            val dx = payload[saveTranslateStart + 3]
            val dy = payload[saveTranslateStart + 4]
            payload[imageStart + 3] += dx
            payload[imageStart + 4] += dy
            payload[imageStart + 5] += dx
            payload[imageStart + 6] += dy
            payload.copyInto(
                payload,
                destinationOffset = saveTranslateStart,
                startIndex = saveTranslateStart + 5,
                endIndex = payloadSize,
            )
            payloadSize -= 5
            decrementOp(COMMAND_SAVE_TRANSLATE)
        }

        private fun foldSaveTranslateIntoFillRect(saveTranslateStart: Int, fillRectStart: Int): Boolean {
            val dx = payload[saveTranslateStart + 3]
            val dy = payload[saveTranslateStart + 4]
            if (dx % 1000 != 0 || dy % 1000 != 0) {
                return false
            }
            payload[fillRectStart + 4] += dx / 1000
            payload[fillRectStart + 5] += dy / 1000
            payload.copyInto(
                payload,
                destinationOffset = saveTranslateStart,
                startIndex = saveTranslateStart + 5,
                endIndex = payloadSize,
            )
            payloadSize -= 5
            decrementOp(COMMAND_SAVE_TRANSLATE)
            return true
        }

        private fun removeOneTrailingRestore(restoreStart: Int, restoreOp: Int) {
            when (restoreOp) {
                COMMAND_RESTORE -> {
                    payloadSize -= 3
                    decrementOp(COMMAND_RESTORE)
                }
                COMMAND_RESTORE_N -> {
                    val restoreCount = payload[restoreStart + 3]
                    if (restoreCount <= 2) {
                        payload[restoreStart] = COMMAND_RESTORE
                        payload[restoreStart + 1] = 3 * Int.SIZE_BYTES
                        payloadSize -= 1
                        decrementOp(COMMAND_RESTORE_N)
                        countOp(COMMAND_RESTORE)
                    } else {
                        payload[restoreStart + 3] = restoreCount - 1
                    }
                }
            }
        }

        private fun saveTranslateBeforeImageDefinitions(imageStart: Int): Int? {
            var offset = imageStart
            while (true) {
                val previous = previousRecordStart(offset) ?: return null
                val op = payload[previous]
                if (isSaveTranslateRecord(previous)) {
                    return previous
                }
                if (op != COMMAND_DEFINE_IMAGE_BITMAP &&
                    op != COMMAND_DEFINE_IMAGE_ARGB &&
                    op != COMMAND_EVICT_IMAGE_CACHE_KEY
                ) {
                    return null
                }
                offset = previous
            }
        }

        private fun isSaveTranslateRecord(recordStart: Int): Boolean =
            payload[recordStart] == COMMAND_SAVE_TRANSLATE &&
                payload[recordStart + 1] == 5 * Int.SIZE_BYTES &&
                payload[recordStart + 2] == COMMAND_RECORD_FLAGS_NONE

        private fun isTranslateScopePassThroughRecord(op: Int): Boolean =
            op == COMMAND_DEFINE_IMAGE_BITMAP ||
                op == COMMAND_DEFINE_IMAGE_ARGB ||
                op == COMMAND_EVICT_IMAGE_CACHE_KEY

        private fun isTranslateScopeTransformableRecord(op: Int): Boolean =
            op == COMMAND_CLEAR_RECT ||
                op == COMMAND_DRAW_IMAGE_REF_FULL ||
                op == COMMAND_DRAW_ROUND_RECT ||
                op == COMMAND_FILL_ROUND_RECT ||
                op == COMMAND_FILL_RECT

        private fun translateScopeRecord(recordStart: Int, dx: Int, dy: Int) {
            when (payload[recordStart]) {
                COMMAND_CLEAR_RECT -> {
                    payload[recordStart + 3] += dx / 1000
                    payload[recordStart + 4] += dy / 1000
                }
                COMMAND_DRAW_IMAGE_REF_FULL -> {
                    payload[recordStart + 3] += dx
                    payload[recordStart + 4] += dy
                    payload[recordStart + 5] += dx
                    payload[recordStart + 6] += dy
                }
                COMMAND_DRAW_ROUND_RECT -> {
                    payload[recordStart + 5] += dx
                    payload[recordStart + 6] += dy
                    payload[recordStart + 7] += dx
                    payload[recordStart + 8] += dy
                }
                COMMAND_FILL_ROUND_RECT -> {
                    payload[recordStart + 4] += dx
                    payload[recordStart + 5] += dy
                    payload[recordStart + 6] += dx
                    payload[recordStart + 7] += dy
                }
                COMMAND_FILL_RECT -> {
                    payload[recordStart + 4] += dx / 1000
                    payload[recordStart + 5] += dy / 1000
                }
            }
        }

        private fun removeTrailingTranslate(): Boolean {
            if (payloadSize < 5 ||
                payload[payloadSize - 5] != COMMAND_TRANSLATE ||
                payload[payloadSize - 4] != 5 * Int.SIZE_BYTES ||
                payload[payloadSize - 3] != COMMAND_RECORD_FLAGS_NONE
            ) {
                return false
            }
            payloadSize -= 5
            decrementOp(COMMAND_TRANSLATE)
            return true
        }

        private fun foldTrailingTranslateIntoFillRect(): Boolean {
            val fillRectStart = previousRecordStart(payloadSize) ?: return false
            if (payload[fillRectStart] != COMMAND_FILL_RECT) {
                return false
            }
            val translateStart = previousRecordStart(fillRectStart) ?: return false
            if (payload[translateStart] != COMMAND_TRANSLATE ||
                payload[translateStart + 1] != 5 * Int.SIZE_BYTES ||
                payload[translateStart + 2] != COMMAND_RECORD_FLAGS_NONE
            ) {
                return false
            }
            val dx = payload[translateStart + 3]
            val dy = payload[translateStart + 4]
            if (dx % 1000 != 0 || dy % 1000 != 0) {
                return false
            }
            payload[fillRectStart + 4] += dx / 1000
            payload[fillRectStart + 5] += dy / 1000
            payload.copyInto(
                payload,
                destinationOffset = translateStart,
                startIndex = fillRectStart,
                endIndex = payloadSize,
            )
            payloadSize -= 5
            decrementOp(COMMAND_TRANSLATE)
            return true
        }

        private fun foldTrailingTranslateIntoDrawImageRefFull(): Boolean {
            val imageStart = previousRecordStart(payloadSize) ?: return false
            if (payload[imageStart] != COMMAND_DRAW_IMAGE_REF_FULL) {
                return false
            }
            val translateStart = translateBeforeImageDefinitions(imageStart) ?: return false
            translateDrawImageRefFullRecord(translateStart, imageStart)
            payload.copyInto(
                payload,
                destinationOffset = translateStart,
                startIndex = translateStart + 5,
                endIndex = payloadSize,
            )
            payloadSize -= 5
            decrementOp(COMMAND_TRANSLATE)
            return true
        }

        private fun foldTrailingTranslateIntoRoundRect(): Boolean {
            val roundRectStart = previousRecordStart(payloadSize) ?: return false
            val roundRectOp = payload[roundRectStart]
            if (roundRectOp != COMMAND_FILL_ROUND_RECT && roundRectOp != COMMAND_DRAW_ROUND_RECT) {
                return false
            }
            val translateStart = previousRecordStart(roundRectStart) ?: return false
            if (payload[translateStart] != COMMAND_TRANSLATE ||
                payload[translateStart + 1] != 5 * Int.SIZE_BYTES ||
                payload[translateStart + 2] != COMMAND_RECORD_FLAGS_NONE
            ) {
                return false
            }
            translateRoundRectRecord(translateStart, roundRectStart, roundRectOp)
            payload.copyInto(
                payload,
                destinationOffset = translateStart,
                startIndex = roundRectStart,
                endIndex = payloadSize,
            )
            payloadSize -= 5
            decrementOp(COMMAND_TRANSLATE)
            return true
        }

        private fun foldTrailingTranslateIntoDrawImageRefFullBeforeTrailingRestore(): Boolean {
            val restoreStart = previousRecordStart(payloadSize) ?: return false
            val restoreOp = payload[restoreStart]
            if (restoreOp != COMMAND_RESTORE && restoreOp != COMMAND_RESTORE_N) {
                return false
            }
            val imageStart = previousRecordStart(restoreStart) ?: return false
            if (payload[imageStart] != COMMAND_DRAW_IMAGE_REF_FULL) {
                return false
            }
            val translateStart = translateBeforeImageDefinitions(imageStart) ?: return false
            translateDrawImageRefFullRecord(translateStart, imageStart)
            payload.copyInto(
                payload,
                destinationOffset = translateStart,
                startIndex = translateStart + 5,
                endIndex = payloadSize,
            )
            payloadSize -= 5
            decrementOp(COMMAND_TRANSLATE)
            return true
        }

        private fun foldTrailingTranslateIntoRoundRectBeforeTrailingRestore(): Boolean {
            val restoreStart = previousRecordStart(payloadSize) ?: return false
            val restoreOp = payload[restoreStart]
            if (restoreOp != COMMAND_RESTORE && restoreOp != COMMAND_RESTORE_N) {
                return false
            }
            val roundRectStart = previousRecordStart(restoreStart) ?: return false
            val roundRectOp = payload[roundRectStart]
            if (roundRectOp != COMMAND_FILL_ROUND_RECT && roundRectOp != COMMAND_DRAW_ROUND_RECT) {
                return false
            }
            val translateStart = previousRecordStart(roundRectStart) ?: return false
            if (!isTranslateRecord(translateStart)) {
                return false
            }
            translateRoundRectRecord(translateStart, roundRectStart, roundRectOp)
            payload.copyInto(
                payload,
                destinationOffset = translateStart,
                startIndex = roundRectStart,
                endIndex = payloadSize,
            )
            payloadSize -= 5
            decrementOp(COMMAND_TRANSLATE)
            return true
        }

        private fun translateDrawImageRefFullRecord(translateStart: Int, imageStart: Int) {
            val dx = payload[translateStart + 3]
            val dy = payload[translateStart + 4]
            payload[imageStart + 3] += dx
            payload[imageStart + 4] += dy
            payload[imageStart + 5] += dx
            payload[imageStart + 6] += dy
        }

        private fun translateBeforeImageDefinitions(imageStart: Int): Int? {
            var offset = imageStart
            while (true) {
                val previous = previousRecordStart(offset) ?: return null
                if (isTranslateRecord(previous)) {
                    return previous
                }
                val op = payload[previous]
                if (op != COMMAND_DEFINE_IMAGE_BITMAP &&
                    op != COMMAND_DEFINE_IMAGE_ARGB &&
                    op != COMMAND_EVICT_IMAGE_CACHE_KEY
                ) {
                    return null
                }
                offset = previous
            }
        }

        private fun translateRoundRectRecord(translateStart: Int, roundRectStart: Int, roundRectOp: Int) {
            val dx = payload[translateStart + 3]
            val dy = payload[translateStart + 4]
            val leftIndex = if (roundRectOp == COMMAND_FILL_ROUND_RECT) roundRectStart + 4 else roundRectStart + 5
            payload[leftIndex] += dx
            payload[leftIndex + 1] += dy
            payload[leftIndex + 2] += dx
            payload[leftIndex + 3] += dy
        }

        private fun removeRedundantSaveAroundStateNeutralRecords(): Boolean {
            val saveStart = redundantStateNeutralSaveStart() ?: return false
            payload.copyInto(
                payload,
                destinationOffset = saveStart,
                startIndex = saveStart + 3,
                endIndex = payloadSize,
            )
            payloadSize -= 3
            decrementOp(COMMAND_SAVE)
            return true
        }

        private fun removeRedundantSaveBeforeLayerBeforeCurrentRestore(): Boolean {
            val restoreStart = previousRecordStart(payloadSize) ?: return false
            val restoreOp = payload[restoreStart]
            if (restoreOp != COMMAND_RESTORE && restoreOp != COMMAND_RESTORE_N) {
                return false
            }
            val openSaves = openSaveStartsBefore(restoreStart) ?: return false
            if (openSaves.size < 2) {
                return false
            }
            val layerStart = openSaves.last()
            val saveStart = openSaves[openSaves.lastIndex - 1]
            if (payload[saveStart] != COMMAND_SAVE ||
                payload[saveStart + 1] != 3 * Int.SIZE_BYTES ||
                payload[saveStart + 2] != COMMAND_RECORD_FLAGS_NONE ||
                saveStart + 3 != layerStart ||
                !isLayerSaveRecord(payload[layerStart])
            ) {
                return false
            }
            payload.copyInto(
                payload,
                destinationOffset = saveStart,
                startIndex = saveStart + 3,
                endIndex = payloadSize,
            )
            payloadSize -= 3
            decrementOp(COMMAND_SAVE)
            return true
        }

        private fun openSaveStartsBefore(endOffset: Int): MutableList<Int>? {
            val openSaves = mutableListOf<Int>()
            var offset = 0
            while (offset < endOffset) {
                val recordLength = payload[offset + 1] / Int.SIZE_BYTES
                if (recordLength < 3 || offset + recordLength > endOffset) return null
                when (payload[offset]) {
                    COMMAND_SAVE,
                    COMMAND_SAVE_LAYER,
                    COMMAND_SAVE_LAYER_COLOR_FILTER,
                    COMMAND_SAVE_LAYER_BLEND_MODE,
                    COMMAND_SAVE_LAYER_BLEND_COLOR_FILTER,
                    COMMAND_SAVE_LAYER_COLOR_FILTER_REF,
                    COMMAND_SAVE_LAYER_BLEND_COLOR_FILTER_REF,
                    COMMAND_SAVE_LAYER_IMAGE_FILTER_REF,
                    COMMAND_SAVE_TRANSLATE,
                    COMMAND_SAVE_TRANSLATE_LAYER -> openSaves += offset
                    COMMAND_RESTORE -> {
                        if (openSaves.isEmpty()) return null
                        openSaves.removeAt(openSaves.lastIndex)
                    }
                    COMMAND_RESTORE_N -> {
                        val restoreCount = payload[offset + 3]
                        if (restoreCount < 0 || restoreCount > openSaves.size) return null
                        repeat(restoreCount) {
                            openSaves.removeAt(openSaves.lastIndex)
                        }
                    }
                }
                offset += recordLength
            }
            return openSaves
        }

        private fun isLayerSaveRecord(op: Int): Boolean =
            op == COMMAND_SAVE_LAYER ||
                op == COMMAND_SAVE_LAYER_COLOR_FILTER ||
                op == COMMAND_SAVE_LAYER_BLEND_MODE ||
                op == COMMAND_SAVE_LAYER_BLEND_COLOR_FILTER ||
                op == COMMAND_SAVE_LAYER_COLOR_FILTER_REF ||
                op == COMMAND_SAVE_LAYER_BLEND_COLOR_FILTER_REF ||
                op == COMMAND_SAVE_LAYER_IMAGE_FILTER_REF ||
                op == COMMAND_SAVE_TRANSLATE_LAYER

        private fun previousRecordStart(endOffset: Int): Int? {
            var offset = 0
            var previous = 0
            while (offset < endOffset) {
                previous = offset
                val recordLength = payload[offset + 1] / Int.SIZE_BYTES
                if (recordLength < 3 || offset + recordLength > endOffset) return null
                offset += recordLength
            }
            return if (offset == endOffset) previous else null
        }

        private fun redundantStateNeutralSaveStart(): Int? {
            var offset = 0
            var saveStart: Int? = null
            while (offset < payloadSize) {
                val recordLength = payload[offset + 1] / Int.SIZE_BYTES
                if (recordLength < 3 || offset + recordLength > payloadSize) return null
                val op = payload[offset]
                if (op == COMMAND_SAVE &&
                    recordLength == 3 &&
                    payload[offset + 2] == COMMAND_RECORD_FLAGS_NONE
                ) {
                    saveStart = offset
                } else if (saveStart != null && !isStateNeutralCommand(op)) {
                    saveStart = null
                }
                offset += recordLength
            }
            return saveStart
        }

        private fun isStateNeutralCommand(op: Int): Boolean =
            when (op) {
                COMMAND_FILL_RECT,
                COMMAND_STROKE_LINE,
                COMMAND_FILL_OVAL,
                COMMAND_STROKE_OVAL,
                COMMAND_CLEAR_RECT,
                COMMAND_FILL_ROUND_RECT,
                COMMAND_DEFINE_IMAGE_ARGB,
                COMMAND_DRAW_IMAGE_REF,
                COMMAND_DRAW_TEXT_UTF16,
                COMMAND_CLEAR_IMAGE_CACHE,
                COMMAND_DRAW_PARAGRAPH_UTF16,
                COMMAND_DRAW_PATH,
                COMMAND_DRAW_ARC,
                COMMAND_DRAW_ROUND_RECT,
                COMMAND_FILL_RECT_LINEAR_GRADIENT,
                COMMAND_FILL_ROUND_RECT_LINEAR_GRADIENT,
                COMMAND_FILL_RECT_RADIAL_GRADIENT,
                COMMAND_FILL_ROUND_RECT_RADIAL_GRADIENT,
                COMMAND_FILL_PATH_LINEAR_GRADIENT,
                COMMAND_FILL_PATH_RADIAL_GRADIENT,
                COMMAND_FILL_RECT_SWEEP_GRADIENT,
                COMMAND_FILL_ROUND_RECT_SWEEP_GRADIENT,
                COMMAND_FILL_PATH_SWEEP_GRADIENT,
                COMMAND_EVICT_IMAGE_CACHE_KEY,
                COMMAND_FILL_RECT_IMAGE_SHADER,
                COMMAND_STROKE_RECT_LINEAR_GRADIENT,
                COMMAND_STROKE_ROUND_RECT_LINEAR_GRADIENT,
                COMMAND_STROKE_RECT_RADIAL_GRADIENT,
                COMMAND_STROKE_ROUND_RECT_RADIAL_GRADIENT,
                COMMAND_STROKE_RECT_SWEEP_GRADIENT,
                COMMAND_STROKE_ROUND_RECT_SWEEP_GRADIENT,
                COMMAND_FILL_RECT_BLEND_MODE,
                COMMAND_FILL_RECT_COLOR_FILTER,
                COMMAND_STROKE_LINE_DASH_PATH_EFFECT,
                COMMAND_DRAW_IMAGE_REF_COLOR_FILTER,
                COMMAND_DEFINE_COLOR_FILTER_TINT,
                COMMAND_FILL_RECT_COLOR_FILTER_REF,
                COMMAND_EVICT_COLOR_FILTER_HANDLE,
                COMMAND_DEFINE_EFFECT_DESCRIPTOR,
                COMMAND_DRAW_IMAGE_REF_COLOR_FILTER_REF,
                COMMAND_DEFINE_SHADER_DESCRIPTOR,
                COMMAND_EVICT_SHADER_HANDLE,
                COMMAND_FILL_RECT_SHADER_REF,
                COMMAND_STROKE_RECT_DASH_PATH_EFFECT,
                COMMAND_STROKE_ROUND_RECT_DASH_PATH_EFFECT,
                COMMAND_STROKE_PATH_DASH_PATH_EFFECT,
                COMMAND_DRAW_PATH_PATH_EFFECT_REF,
                COMMAND_DRAW_SHADOW_PATH,
                COMMAND_DRAW_POINTS,
                COMMAND_DEFINE_FONT_DATA,
                COMMAND_DRAW_VERTICES,
                COMMAND_STROKE_PATH_LINEAR_GRADIENT,
                COMMAND_STROKE_PATH_RADIAL_GRADIENT,
                COMMAND_STROKE_PATH_SWEEP_GRADIENT,
                COMMAND_STROKE_RECT_SHADER_REF,
                COMMAND_STROKE_RECT_IMAGE_SHADER,
                COMMAND_DEFINE_IMAGE_BITMAP,
                COMMAND_DRAW_IMAGE_REF_FULL -> true
                else -> false
            }

        fun addTranslate(dx1000: Int, dy1000: Int) {
            if (payloadSize >= 5 &&
                payload[payloadSize - 5] == COMMAND_SAVE_TRANSLATE &&
                payload[payloadSize - 4] == 5 * Int.SIZE_BYTES &&
                payload[payloadSize - 3] == COMMAND_RECORD_FLAGS_NONE
            ) {
                val mergedDx = payload[payloadSize - 2] + dx1000
                val mergedDy = payload[payloadSize - 1] + dy1000
                if (mergedDx == 0 && mergedDy == 0) {
                    payload[payloadSize - 5] = COMMAND_SAVE
                    payload[payloadSize - 4] = 3 * Int.SIZE_BYTES
                    payloadSize -= 2
                    decrementOp(COMMAND_SAVE_TRANSLATE)
                    countOp(COMMAND_SAVE)
                } else {
                    payload[payloadSize - 2] = mergedDx
                    payload[payloadSize - 1] = mergedDy
                }
                return
            }
            if (payloadSize >= 3 &&
                payload[payloadSize - 3] == COMMAND_SAVE &&
                payload[payloadSize - 2] == 3 * Int.SIZE_BYTES &&
                payload[payloadSize - 1] == COMMAND_RECORD_FLAGS_NONE
            ) {
                ensureCapacity(payloadSize + 2)
                payload[payloadSize - 3] = COMMAND_SAVE_TRANSLATE
                payload[payloadSize - 2] = 5 * Int.SIZE_BYTES
                payload[payloadSize++] = dx1000
                payload[payloadSize++] = dy1000
                decrementOp(COMMAND_SAVE)
                countOp(COMMAND_SAVE_TRANSLATE)
                return
            }
            if (payloadSize >= 5 &&
                payload[payloadSize - 5] == COMMAND_TRANSLATE &&
                payload[payloadSize - 4] == 5 * Int.SIZE_BYTES &&
                payload[payloadSize - 3] == COMMAND_RECORD_FLAGS_NONE
            ) {
                val mergedDx = payload[payloadSize - 2] + dx1000
                val mergedDy = payload[payloadSize - 1] + dy1000
                if (mergedDx == 0 && mergedDy == 0) {
                    payloadSize -= 5
                    decrementOp(COMMAND_TRANSLATE)
                } else {
                    payload[payloadSize - 2] = mergedDx
                    payload[payloadSize - 1] = mergedDy
                }
                return
            }
            addCommand(COMMAND_TRANSLATE, COMMAND_RECORD_FLAGS_NONE, dx1000, dy1000)
        }

        fun addSaveLayer(x: Int, y: Int, width: Int, height: Int, alpha1000: Int) {
            if (payloadSize >= 5 &&
                payload[payloadSize - 5] == COMMAND_SAVE_TRANSLATE &&
                payload[payloadSize - 4] == 5 * Int.SIZE_BYTES &&
                payload[payloadSize - 3] == COMMAND_RECORD_FLAGS_NONE
            ) {
                ensureCapacity(payloadSize + 5)
                payload[payloadSize - 5] = COMMAND_SAVE_TRANSLATE_LAYER
                payload[payloadSize - 4] = 10 * Int.SIZE_BYTES
                payload[payloadSize++] = x
                payload[payloadSize++] = y
                payload[payloadSize++] = width
                payload[payloadSize++] = height
                payload[payloadSize++] = alpha1000
                decrementOp(COMMAND_SAVE_TRANSLATE)
                countOp(COMMAND_SAVE_TRANSLATE_LAYER)
                return
            }
            addCommand(COMMAND_SAVE_LAYER, COMMAND_RECORD_FLAGS_NONE, x, y, width, height, alpha1000)
        }

        fun addCommand(
            op: Int,
            recordFlags: Int = COMMAND_RECORD_FLAGS_NONE,
            pixels: IntArray,
            vararg args: Int,
        ) {
            countOp(op)
            addRecordHeader(op, recordFlags, args.size + pixels.size)
            addAll(args)
            addAll(pixels)
        }

        fun appendRecords(records: IntArray, startIndex: Int, endIndex: Int) {
            countOps(records, startIndex, endIndex)
            val count = endIndex - startIndex
            ensureCapacity(payloadSize + count)
            records.copyInto(payload, destinationOffset = payloadSize, startIndex = startIndex, endIndex = endIndex)
            payloadSize += count
        }

        fun opSummary(): String =
            if (opCounts.isEmpty()) {
                "none"
            } else {
                opCounts.entries
                    .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
                    .joinToString(separator = " ") { (op, count) -> "${opName(op)}=$count" }
            }

        private fun countOp(op: Int) {
            opCounts[op] = (opCounts[op] ?: 0) + 1
        }

        private fun decrementOp(op: Int) {
            val count = opCounts[op] ?: return
            if (count <= 1) {
                opCounts.remove(op)
            } else {
                opCounts[op] = count - 1
            }
        }

        private fun countOps(records: IntArray, startIndex: Int, endIndex: Int) {
            var index = startIndex
            while (index + 2 < endIndex) {
                val op = records[index]
                val recordByteSize = records[index + 1]
                if (recordByteSize < 3 * Int.SIZE_BYTES || recordByteSize % Int.SIZE_BYTES != 0) return
                val recordIntSize = recordByteSize / Int.SIZE_BYTES
                if (index + recordIntSize > endIndex) return
                countOp(op)
                index += recordIntSize
            }
        }

        fun toIntArray(): IntArray =
            IntArray(streamSize).also { stream ->
                stream[0] = COMMAND_STREAM_MAGIC
                stream[1] = COMMAND_STREAM_ABI_ID
                stream[2] = COMMAND_STREAM_FLAGS_NONE
                stream[3] = payloadSize
                stream[4] = COMMAND_COORDINATE_SPACE_SWING_USER
                stream[5] = COMMAND_PAINT_FORMAT_SOLID_ARGB
                payload.copyInto(
                    destination = stream,
                    destinationOffset = COMMAND_STREAM_HEADER_SIZE,
                    startIndex = 0,
                    endIndex = payloadSize,
                )
            }

        private fun addRecordHeader(op: Int, recordFlags: Int, argCount: Int) {
            ensureCapacity(payloadSize + argCount + 3)
            payload[payloadSize++] = op
            payload[payloadSize++] = (argCount + 3) * Int.SIZE_BYTES
            payload[payloadSize++] = recordFlags
        }

        private fun addAll(values: IntArray) {
            ensureCapacity(payloadSize + values.size)
            values.copyInto(payload, destinationOffset = payloadSize)
            payloadSize += values.size
        }

        private fun ensureCapacity(requiredSize: Int) {
            if (requiredSize <= payload.size) return
            var newSize = payload.size
            while (newSize < requiredSize) {
                newSize *= 2
            }
            payload = payload.copyOf(newSize)
        }

        private fun opName(op: Int): String =
            when (op) {
                COMMAND_FILL_RECT -> "fillRect"
                COMMAND_STROKE_LINE -> "strokeLine"
                COMMAND_FILL_OVAL -> "fillOval"
                COMMAND_STROKE_OVAL -> "strokeOval"
                COMMAND_CLEAR_RECT -> "clearRect"
                COMMAND_SAVE -> "save"
                COMMAND_RESTORE -> "restore"
                COMMAND_CLIP_RECT -> "clipRect"
                COMMAND_TRANSLATE -> "translate"
                COMMAND_SAVE_TRANSLATE -> "saveTranslate"
                COMMAND_RESTORE_N -> "restoreN"
                COMMAND_SAVE_TRANSLATE_LAYER -> "saveTranslateLayer"
                COMMAND_DRAW_IMAGE_REF_FULL -> "drawImageRefFull"
                COMMAND_FILL_ROUND_RECT -> "fillRoundRect"
                COMMAND_SCALE -> "scale"
                COMMAND_ROTATE -> "rotate"
                COMMAND_SAVE_LAYER -> "saveLayer"
                COMMAND_DEFINE_IMAGE_ARGB -> "defineImageArgb"
                COMMAND_DRAW_IMAGE_REF -> "drawImageRef"
                COMMAND_DRAW_TEXT_UTF16 -> "drawTextUtf16"
                COMMAND_CLEAR_IMAGE_CACHE -> "clearImageCache"
                COMMAND_DRAW_PARAGRAPH_UTF16 -> "drawParagraphUtf16"
                COMMAND_CLIP_PATH -> "clipPath"
                COMMAND_DRAW_PATH -> "drawPath"
                COMMAND_DRAW_ARC -> "drawArc"
                COMMAND_DRAW_ROUND_RECT -> "drawRoundRect"
                COMMAND_FILL_RECT_LINEAR_GRADIENT -> "fillRectLinearGradient"
                COMMAND_FILL_ROUND_RECT_LINEAR_GRADIENT -> "fillRoundRectLinearGradient"
                COMMAND_FILL_RECT_RADIAL_GRADIENT -> "fillRectRadialGradient"
                COMMAND_FILL_ROUND_RECT_RADIAL_GRADIENT -> "fillRoundRectRadialGradient"
                COMMAND_FILL_PATH_LINEAR_GRADIENT -> "fillPathLinearGradient"
                COMMAND_FILL_PATH_RADIAL_GRADIENT -> "fillPathRadialGradient"
                COMMAND_FILL_RECT_SWEEP_GRADIENT -> "fillRectSweepGradient"
                COMMAND_FILL_ROUND_RECT_SWEEP_GRADIENT -> "fillRoundRectSweepGradient"
                COMMAND_FILL_PATH_SWEEP_GRADIENT -> "fillPathSweepGradient"
                COMMAND_EVICT_IMAGE_CACHE_KEY -> "evictImageCacheKey"
                COMMAND_FILL_RECT_IMAGE_SHADER -> "fillRectImageShader"
                COMMAND_STROKE_RECT_LINEAR_GRADIENT -> "strokeRectLinearGradient"
                COMMAND_STROKE_ROUND_RECT_LINEAR_GRADIENT -> "strokeRoundRectLinearGradient"
                COMMAND_STROKE_RECT_RADIAL_GRADIENT -> "strokeRectRadialGradient"
                COMMAND_STROKE_ROUND_RECT_RADIAL_GRADIENT -> "strokeRoundRectRadialGradient"
                COMMAND_STROKE_RECT_SWEEP_GRADIENT -> "strokeRectSweepGradient"
                COMMAND_STROKE_ROUND_RECT_SWEEP_GRADIENT -> "strokeRoundRectSweepGradient"
                COMMAND_FILL_RECT_BLEND_MODE -> "fillRectBlendMode"
                COMMAND_FILL_RECT_COLOR_FILTER -> "fillRectColorFilter"
                COMMAND_STROKE_LINE_DASH_PATH_EFFECT -> "strokeLineDashPathEffect"
                COMMAND_SAVE_LAYER_COLOR_FILTER -> "saveLayerColorFilter"
                COMMAND_DRAW_IMAGE_REF_COLOR_FILTER -> "drawImageRefColorFilter"
                COMMAND_DEFINE_COLOR_FILTER_TINT -> "defineColorFilterTint"
                COMMAND_FILL_RECT_COLOR_FILTER_REF -> "fillRectColorFilterRef"
                COMMAND_EVICT_COLOR_FILTER_HANDLE -> "evictColorFilterHandle"
                COMMAND_DEFINE_EFFECT_DESCRIPTOR -> "defineEffectDescriptor"
                COMMAND_SAVE_LAYER_BLEND_MODE -> "saveLayerBlendMode"
                COMMAND_SAVE_LAYER_BLEND_COLOR_FILTER -> "saveLayerBlendColorFilter"
                COMMAND_SAVE_LAYER_COLOR_FILTER_REF -> "saveLayerColorFilterRef"
                COMMAND_DRAW_IMAGE_REF_COLOR_FILTER_REF -> "drawImageRefColorFilterRef"
                COMMAND_SAVE_LAYER_BLEND_COLOR_FILTER_REF -> "saveLayerBlendColorFilterRef"
                COMMAND_SAVE_LAYER_IMAGE_FILTER_REF -> "saveLayerImageFilterRef"
                COMMAND_DEFINE_SHADER_DESCRIPTOR -> "defineShaderDescriptor"
                COMMAND_EVICT_SHADER_HANDLE -> "evictShaderHandle"
                COMMAND_FILL_RECT_SHADER_REF -> "fillRectShaderRef"
                COMMAND_STROKE_RECT_DASH_PATH_EFFECT -> "strokeRectDashPathEffect"
                COMMAND_STROKE_ROUND_RECT_DASH_PATH_EFFECT -> "strokeRoundRectDashPathEffect"
                COMMAND_STROKE_PATH_DASH_PATH_EFFECT -> "strokePathDashPathEffect"
                COMMAND_DRAW_PATH_PATH_EFFECT_REF -> "drawPathPathEffectRef"
                COMMAND_CONCAT_MATRIX33 -> "concatMatrix33"
                COMMAND_DRAW_SHADOW_PATH -> "drawShadowPath"
                COMMAND_DRAW_POINTS -> "drawPoints"
                COMMAND_DEFINE_FONT_DATA -> "defineFontData"
                COMMAND_DRAW_VERTICES -> "drawVertices"
                COMMAND_STROKE_PATH_LINEAR_GRADIENT -> "strokePathLinearGradient"
                COMMAND_STROKE_PATH_RADIAL_GRADIENT -> "strokePathRadialGradient"
                COMMAND_STROKE_PATH_SWEEP_GRADIENT -> "strokePathSweepGradient"
                COMMAND_STROKE_RECT_SHADER_REF -> "strokeRectShaderRef"
                COMMAND_STROKE_RECT_IMAGE_SHADER -> "strokeRectImageShader"
                COMMAND_DEFINE_IMAGE_BITMAP -> "defineImageBitmap"
                else -> "op$op"
            }
    }

    private data class State(
        val supported: Boolean = true,
    ) {
        fun x(value: Float): Int = value.roundToInt()
        fun y(value: Float): Int = value.roundToInt()
        fun width(value: Float): Int = value.roundToInt().coerceAtLeast(0)
        fun height(value: Float): Int = value.roundToInt().coerceAtLeast(0)
        fun stroke(value: Float): Int = value.roundToInt().coerceAtLeast(1)
    }

    private const val COMMAND_FILL_RECT = 2
    private const val COMMAND_STROKE_LINE = 3
    private const val COMMAND_FILL_OVAL = 4
    private const val COMMAND_STROKE_OVAL = 5
    private const val COMMAND_CLEAR_RECT = 6
    private const val COMMAND_SAVE = 7
    private const val COMMAND_RESTORE = 8
    private const val COMMAND_CLIP_RECT = 9
    private const val COMMAND_TRANSLATE = 10
    private const val COMMAND_SAVE_TRANSLATE = 74
    private const val COMMAND_RESTORE_N = 75
    private const val COMMAND_SAVE_TRANSLATE_LAYER = 76
    private const val COMMAND_DRAW_IMAGE_REF_FULL = 77
    private const val COMMAND_FILL_ROUND_RECT = 78
    private const val COMMAND_SCALE = 11
    private const val COMMAND_ROTATE = 12
    private const val COMMAND_SAVE_LAYER = 13
    private const val COMMAND_DEFINE_IMAGE_ARGB = 15
    private const val COMMAND_DRAW_IMAGE_REF = 16
    private const val COMMAND_DRAW_TEXT_UTF16 = 17
    private const val COMMAND_CLEAR_IMAGE_CACHE = 18
    private const val COMMAND_DRAW_PARAGRAPH_UTF16 = 19
    private const val COMMAND_CLIP_PATH = 20
    private const val COMMAND_DRAW_PATH = 21
    private const val COMMAND_DRAW_ARC = 22
    private const val COMMAND_DRAW_ROUND_RECT = 23
    private const val COMMAND_FILL_RECT_LINEAR_GRADIENT = 24
    private const val COMMAND_FILL_ROUND_RECT_LINEAR_GRADIENT = 25
    private const val COMMAND_FILL_RECT_RADIAL_GRADIENT = 26
    private const val COMMAND_FILL_ROUND_RECT_RADIAL_GRADIENT = 27
    private const val COMMAND_FILL_PATH_LINEAR_GRADIENT = 28
    private const val COMMAND_FILL_PATH_RADIAL_GRADIENT = 29
    private const val COMMAND_FILL_RECT_SWEEP_GRADIENT = 30
    private const val COMMAND_FILL_ROUND_RECT_SWEEP_GRADIENT = 31
    private const val COMMAND_FILL_PATH_SWEEP_GRADIENT = 32
    private const val COMMAND_EVICT_IMAGE_CACHE_KEY = 33
    private const val COMMAND_FILL_RECT_IMAGE_SHADER = 34
    private const val COMMAND_STROKE_RECT_LINEAR_GRADIENT = 35
    private const val COMMAND_STROKE_ROUND_RECT_LINEAR_GRADIENT = 36
    private const val COMMAND_STROKE_RECT_RADIAL_GRADIENT = 37
    private const val COMMAND_STROKE_ROUND_RECT_RADIAL_GRADIENT = 38
    private const val COMMAND_STROKE_RECT_SWEEP_GRADIENT = 39
    private const val COMMAND_STROKE_ROUND_RECT_SWEEP_GRADIENT = 40
    private const val COMMAND_FILL_RECT_BLEND_MODE = 41
    private const val COMMAND_FILL_RECT_COLOR_FILTER = 42
    private const val COMMAND_STROKE_LINE_DASH_PATH_EFFECT = 43
    private const val COMMAND_SAVE_LAYER_COLOR_FILTER = 44
    private const val COMMAND_DRAW_IMAGE_REF_COLOR_FILTER = 45
    private const val COMMAND_DEFINE_COLOR_FILTER_TINT = 46
    private const val COMMAND_FILL_RECT_COLOR_FILTER_REF = 47
    private const val COMMAND_EVICT_COLOR_FILTER_HANDLE = 48
    private const val COMMAND_DEFINE_EFFECT_DESCRIPTOR = 49
    private const val COMMAND_SAVE_LAYER_BLEND_MODE = 50
    private const val COMMAND_SAVE_LAYER_BLEND_COLOR_FILTER = 51
    private const val COMMAND_SAVE_LAYER_COLOR_FILTER_REF = 52
    private const val COMMAND_DRAW_IMAGE_REF_COLOR_FILTER_REF = 53
    private const val COMMAND_SAVE_LAYER_BLEND_COLOR_FILTER_REF = 54
    private const val COMMAND_SAVE_LAYER_IMAGE_FILTER_REF = 55
    private const val COMMAND_DEFINE_SHADER_DESCRIPTOR = 56
    private const val COMMAND_EVICT_SHADER_HANDLE = 57
    private const val COMMAND_FILL_RECT_SHADER_REF = 58
    private const val COMMAND_STROKE_RECT_DASH_PATH_EFFECT = 59
    private const val COMMAND_STROKE_ROUND_RECT_DASH_PATH_EFFECT = 60
    private const val COMMAND_STROKE_PATH_DASH_PATH_EFFECT = 61
    private const val COMMAND_DRAW_PATH_PATH_EFFECT_REF = 62
    private const val COMMAND_CONCAT_MATRIX33 = 63
    private const val COMMAND_DRAW_SHADOW_PATH = 64
    private const val COMMAND_DRAW_POINTS = 65
    private const val COMMAND_DEFINE_FONT_DATA = 66
    private const val COMMAND_DRAW_VERTICES = 67
    private const val COMMAND_STROKE_PATH_LINEAR_GRADIENT = 68
    private const val COMMAND_STROKE_PATH_RADIAL_GRADIENT = 69
    private const val COMMAND_STROKE_PATH_SWEEP_GRADIENT = 70
    private const val COMMAND_STROKE_RECT_SHADER_REF = 71
    private const val COMMAND_STROKE_RECT_IMAGE_SHADER = 72
    private const val COMMAND_DEFINE_IMAGE_BITMAP = 73
    private const val COMMAND_EFFECT_DESCRIPTOR_TINT_COLOR_FILTER = 1
    private const val COMMAND_EFFECT_DESCRIPTOR_COLOR_MATRIX_FILTER = 2
    private const val COMMAND_EFFECT_DESCRIPTOR_LIGHTING_FILTER = 3
    private const val COMMAND_EFFECT_DESCRIPTOR_BLUR_IMAGE_FILTER = 4
    private const val COMMAND_EFFECT_DESCRIPTOR_OFFSET_IMAGE_FILTER = 5
    private const val COMMAND_EFFECT_DESCRIPTOR_BLUR_IMAGE_FILTER_WITH_INPUT = 6
    private const val COMMAND_EFFECT_DESCRIPTOR_OFFSET_IMAGE_FILTER_WITH_INPUT = 7
    private const val COMMAND_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER = 8
    private const val COMMAND_EFFECT_DESCRIPTOR_CORNER_PATH_EFFECT = 9
    private const val COMMAND_EFFECT_DESCRIPTOR_STAMPED_PATH_EFFECT = 10
    private const val COMMAND_EFFECT_DESCRIPTOR_CHAIN_PATH_EFFECT = 11
    private const val COMMAND_EFFECT_DESCRIPTOR_VERSION_1 = 1
    private const val COMMAND_SHADER_DESCRIPTOR_LINEAR_GRADIENT = 1
    private const val COMMAND_SHADER_DESCRIPTOR_RADIAL_GRADIENT = 2
    private const val COMMAND_SHADER_DESCRIPTOR_SWEEP_GRADIENT = 3
    private const val COMMAND_SHADER_DESCRIPTOR_IMAGE = 4
    private const val COMMAND_SHADER_DESCRIPTOR_COMPOSITE = 5
    private const val COMMAND_SHADER_DESCRIPTOR_RUNTIME_EFFECT = 6
    private const val COMMAND_SHADER_DESCRIPTOR_COLOR_FILTER = 7
    private const val COMMAND_SHADER_DESCRIPTOR_TRANSFORM = 8
    private const val COMMAND_SHADER_DESCRIPTOR_COLOR = 9
    private const val COMMAND_SHADER_DESCRIPTOR_PERLIN_NOISE = 10
    private const val COMMAND_SHADER_DESCRIPTOR_VERSION_1 = 1
    private const val COMMAND_BLEND_MODE_PLUS = 1
    private const val COMMAND_BLEND_MODE_SRC_IN = 2
    private const val COMMAND_BLEND_MODE_MULTIPLY = 3
    private const val COMMAND_BLEND_MODE_SCREEN = 4
    private const val COMMAND_BLEND_MODE_OVERLAY = 5
    private const val COMMAND_BLEND_MODE_DARKEN = 6
    private const val COMMAND_BLEND_MODE_LIGHTEN = 7
    private const val COMMAND_BLEND_MODE_DIFFERENCE = 8
    private const val COMMAND_BLEND_MODE_EXCLUSION = 9
    private const val COMMAND_BLEND_MODE_COLOR_DODGE = 10
    private const val COMMAND_BLEND_MODE_COLOR_BURN = 11
    private const val COMMAND_BLEND_MODE_HARDLIGHT = 12
    private const val COMMAND_BLEND_MODE_SOFTLIGHT = 13
    private const val COMMAND_BLEND_MODE_HUE = 14
    private const val COMMAND_BLEND_MODE_SATURATION = 15
    private const val COMMAND_BLEND_MODE_COLOR = 16
    private const val COMMAND_BLEND_MODE_LUMINOSITY = 17
    private const val COMMAND_BLEND_MODE_SRC_OVER = 18
    private const val COMMAND_STREAM_MAGIC = 1246972723
    private const val COMMAND_STREAM_ABI_ID = 111
    private const val MAX_FONT_DATA_BYTES = 1_048_576
    private const val COMMAND_STREAM_HEADER_SIZE = 6
    private const val COMMAND_STREAM_FLAGS_NONE = 0
    private const val COMMAND_COORDINATE_SPACE_SWING_USER = 1
    private const val COMMAND_PAINT_FORMAT_SOLID_ARGB = 1
    private const val COMMAND_RECORD_FLAGS_NONE = 0
    private const val COMMAND_RECORD_FLAG_ANTIALIAS = 1
    private const val COMMAND_PAINT_STYLE_FILL = 0
    private const val COMMAND_PAINT_STYLE_STROKE = 1
    private const val SMALL_NATIVE_BITMAP_CONTENT_KEY_PIXELS = 262_144
    private const val MAX_PATH_DATA_INTS = 4096
    private const val PATH_FILL_TYPE_NON_ZERO = 0
    private const val PATH_FILL_TYPE_EVEN_ODD = 1
    private const val PATH_VERB_MOVE = 0
    private const val PATH_VERB_LINE = 1
    private const val PATH_VERB_QUAD = 2
    private const val PATH_VERB_CUBIC = 3
    private const val PATH_VERB_CLOSE = 4
}
