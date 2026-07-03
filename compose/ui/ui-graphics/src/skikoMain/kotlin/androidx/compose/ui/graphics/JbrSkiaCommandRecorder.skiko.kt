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
    val hasAlpha: Boolean,
)

private data class NativeBitmapImageDefinition(
    val ptr: Long,
    val generationId: Int,
    val hasAlpha: Boolean,
    val cacheKey: Long,
)

private data class DefinedImage(
    val cacheKey: Long,
    val hasAlpha: Boolean,
)

object JbrSkiaCommandRecorder {
    private const val STRICT_PROPERTY = "compose.jbr.skia.command.strict"
    private const val COLOR_FILTER_HANDLES_PROPERTY = "compose.jbr.skia.command.colorFilterHandles"
    private const val LOG_COMMAND_OP_COUNTS_PROPERTY = "compose.jbr.skia.command.logOpCounts"
    private const val LOG_COMMAND_OP_WORDS_PROPERTY = "compose.jbr.skia.command.logOpWords"
    private const val LOG_COMMAND_OP_PAIRS_PROPERTY = "compose.jbr.skia.command.logOpPairs"
    private const val NATIVE_BITMAP_CONTENT_KEY_PIXELS_PROPERTY = "compose.jbr.skia.command.nativeBitmapContentKeyPixels"
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
        val emitPendingImageCacheClear = pendingImageCacheClear.getAndSet(false)
        val recorder = Recorder(
            shadowContext = shadowContext,
            emitPendingImageCacheClear = emitPendingImageCacheClear,
            forceResourceDefinitions = emitPendingImageCacheClear,
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
            forceResourceDefinitions = previous?.forceResourceDefinitions ?: false,
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
        val forceResourceDefinitions: Boolean,
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
            if (java.lang.Boolean.getBoolean(LOG_COMMAND_OP_WORDS_PROPERTY)) {
                System.err.println("CMP_JBR_COMMAND_RECORDER_OP_WORDS ${commands.opWordSummary()}")
            }
            if (java.lang.Boolean.getBoolean(LOG_COMMAND_OP_PAIRS_PROPERTY)) {
                System.err.println("CMP_JBR_COMMAND_RECORDER_OP_PAIRS ${commands.opPairWordSummary()}")
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
                val closedPolylinePoints = if (paint.style == PaintingStyle.Stroke) {
                    pathData.closedPolylinePoints()
                } else {
                    null
                }
                if (closedPolylinePoints != null) {
                    val packedDeltas = closedPolylinePoints.packedShortDeltas()
                    if (packedDeltas != null) {
                        commands.addCommand(
                            COMMAND_STROKE_CLOSED_POLYLINE_DELTA,
                            paint.recordFlags(),
                            commandColor,
                            state.stroke(paint.strokeWidth),
                            paint.strokeCap.commandValue(),
                            paint.strokeJoin.commandValue(),
                            paint.strokeMiter1000(),
                            closedPolylinePoints.size / 2,
                            closedPolylinePoints[0],
                            closedPolylinePoints[1],
                            *packedDeltas,
                        )
                    } else {
                        commands.addCommand(
                            COMMAND_STROKE_CLOSED_POLYLINE,
                            paint.recordFlags(),
                            commandColor,
                            state.stroke(paint.strokeWidth),
                            paint.strokeCap.commandValue(),
                            paint.strokeJoin.commandValue(),
                            paint.strokeMiter1000(),
                            closedPolylinePoints.size / 2,
                            *closedPolylinePoints,
                        )
                    }
                    return@withSolidColorBlendLayer
                }
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
            val definedImage = defineImageIfNeeded(image) ?: return false
            val cacheKey = definedImage.cacheKey
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
                    commands.addFullImageRefCommand(
                        paint.recordFlags(),
                        definedImage.hasAlpha,
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
            val cacheKey = defineImageIfNeeded(imageShader.image)?.cacheKey ?: return
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
            val cacheKey = defineImageIfNeeded(imageShader.image)?.cacheKey ?: return
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
            val cacheKey = defineImageIfNeeded(image)?.cacheKey ?: return null
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

        private fun IntArray.hasTransparentPixels(): Boolean = any { pixel ->
            pixel ushr 24 != 0xff
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
            mix(if (image.hasAlpha) 1 else 0)
            mix(ptr)
            mix(bitmap.generationId)
            return NativeBitmapImageDefinition(ptr, bitmap.generationId, image.hasAlpha, hash)
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

        private fun defineImageIfNeeded(image: ImageBitmap): DefinedImage? {
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
                nativeBitmapDefinition != null && pixelCount <= nativeBitmapContentKeyPixels()
            var nativeBitmapHasAlpha = nativeBitmapDefinition?.hasAlpha ?: false
            var effectiveHasAlpha = image.hasAlpha

            var evictedKey: Long? = null
            val cacheKey = synchronized(imageCacheLock) {
                if (nativeBitmapDefinition != null) {
                    if (useContentKeyForNativeBitmap) {
                        val entry = imageIdentityCache[image]
                        if (entry != null && entry.width == image.width && entry.height == image.height) {
                            nativeBitmapHasAlpha = nativeBitmapDefinition.hasAlpha || entry.hasAlpha
                            effectiveHasAlpha = nativeBitmapHasAlpha
                            entry.cacheKey
                        } else {
                            val pixelData = readPixels()
                            nativeBitmapHasAlpha = nativeBitmapDefinition.hasAlpha || pixelData.hasTransparentPixels()
                            effectiveHasAlpha = nativeBitmapHasAlpha
                            val computedKey = pixelData.imageCacheKey(image.width, image.height)
                            imageIdentityCache[image] = ImageCacheEntry(
                                image.width,
                                image.height,
                                computedKey,
                                nativeBitmapHasAlpha,
                            )
                            computedKey
                        }
                    } else {
                        effectiveHasAlpha = nativeBitmapDefinition.hasAlpha
                        nativeBitmapDefinition.cacheKey
                    }
                } else {
                    val entry = imageIdentityCache[image]
                    if (entry != null && entry.width == image.width && entry.height == image.height) {
                        effectiveHasAlpha = entry.hasAlpha
                        entry.cacheKey
                    } else {
                        val readPixels = readPixels()
                        effectiveHasAlpha = image.hasAlpha || readPixels.hasTransparentPixels()
                        val computedKey = readPixels.imageCacheKey(image.width, image.height)
                        imageIdentityCache[image] = ImageCacheEntry(
                            image.width,
                            image.height,
                            computedKey,
                            effectiveHasAlpha,
                        )
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
                if (nativeBitmapDefinition != null && (nativeBitmapDefinition.hasAlpha || !effectiveHasAlpha)) {
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
                        if (nativeBitmapHasAlpha) 1 else 0,
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
            return DefinedImage(cacheKey, effectiveHasAlpha)
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
        private var recordStarts = IntArray(128)
        private var recordStartCount = 0
        private var recordIndexDirty = false
        private val opCounts = IntArray(OP_COUNT_CAPACITY)
        private var opCountEntries = 0
        private val imageAlphaByCacheKey = mutableMapOf<Long, Boolean>()

        val streamSize: Int
            get() = COMMAND_STREAM_HEADER_SIZE + payloadSize

        fun addCommand(op: Int, recordFlags: Int = COMMAND_RECORD_FLAGS_NONE, vararg args: Int) {
            if (tryAddDrawImageRefFullDrawRoundRect(op, recordFlags, args)) {
                return
            }
            countOp(op)
            addRecordHeader(op, recordFlags, args.size)
            addAll(args)
        }

        fun addRestore() {
            if (removeEmptyLayerClipBeforeCurrentRestore()) {
                return
            }
            foldSaveTranslateIntoDrawImageRefFullBeforeTrailingRestore()
            foldSaveTranslateIntoFillRectBeforeTrailingRestore()
            foldSaveTranslateTransformableScopeBeforeTrailingRestore()
            foldTrailingTranslatedImageRefSuffix(payloadSize)
            foldTrailingTranslatedImageRefSuffixBeforeTrailingRestore()
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
            op == COMMAND_CLEAR_RECT ||
                op == COMMAND_CLEAR_DRAW_IMAGE_REF_FULL ||
                op == COMMAND_CLEAR_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT ||
                op == COMMAND_FILL_RECT ||
                op == COMMAND_STROKE_LINE ||
                op == COMMAND_STROKE_LINE_RUN

        private fun isTranslateRecord(recordStart: Int): Boolean =
            payload[recordStart] == COMMAND_TRANSLATE &&
                payload[recordStart + 1] == 5 * Int.SIZE_BYTES &&
                payload[recordStart + 2] == COMMAND_RECORD_FLAGS_NONE

        private fun foldTrailingTranslatedImageRefSuffixBeforeTrailingRestore(): Boolean {
            val restoreStart = previousRecordStart(payloadSize) ?: return false
            val restoreOp = payload[restoreStart]
            if (restoreOp != COMMAND_RESTORE && restoreOp != COMMAND_RESTORE_N) {
                return false
            }
            return foldTrailingTranslatedImageRefSuffix(restoreStart)
        }

        private fun foldTrailingTranslatedImageRefSuffix(endOffset: Int): Boolean {
            var offset = 0
            var suffixStart = endOffset
            var hasTranslate = false
            var hasImageAfterTranslate = false
            var sawTranslateInCurrentSuffix = false
            while (offset < endOffset) {
                val recordLength = payload[offset + 1] / Int.SIZE_BYTES
                if (recordLength < 3 || offset + recordLength > endOffset) return false
                val op = payload[offset]
                when {
                    isTranslateRecord(offset) -> {
                        hasTranslate = true
                        sawTranslateInCurrentSuffix = true
                    }
                    op == COMMAND_DRAW_IMAGE_REF_FULL && recordLength == 9 -> {
                        if (sawTranslateInCurrentSuffix) {
                            hasImageAfterTranslate = true
                        }
                    }
                    isTranslateScopePassThroughRecord(op) -> Unit
                    else -> {
                        suffixStart = offset + recordLength
                        hasTranslate = false
                        hasImageAfterTranslate = false
                        sawTranslateInCurrentSuffix = false
                    }
                }
                offset += recordLength
            }
            if (suffixStart >= endOffset || !hasTranslate || !hasImageAfterTranslate) {
                return false
            }

            var readOffset = suffixStart
            var writeOffset = suffixStart
            var accumulatedDx = 0
            var accumulatedDy = 0
            var removedTranslateCount = 0
            while (readOffset < endOffset) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                if (isTranslateRecord(readOffset)) {
                    accumulatedDx += payload[readOffset + 3]
                    accumulatedDy += payload[readOffset + 4]
                    removedTranslateCount++
                    readOffset += recordLength
                    continue
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = readOffset + recordLength,
                    )
                }
                if (payload[writeOffset] == COMMAND_DRAW_IMAGE_REF_FULL) {
                    payload[writeOffset + 3] += accumulatedDx
                    payload[writeOffset + 4] += accumulatedDy
                    payload[writeOffset + 5] += accumulatedDx
                    payload[writeOffset + 6] += accumulatedDy
                }
                writeOffset += recordLength
                readOffset += recordLength
            }
            if (removedTranslateCount == 0) {
                return false
            }
            val removedWords = endOffset - writeOffset
            payload.copyInto(
                payload,
                destinationOffset = writeOffset,
                startIndex = endOffset,
                endIndex = payloadSize,
            )
            payloadSize -= removedWords
            repeat(removedTranslateCount) {
                decrementOp(COMMAND_TRANSLATE)
            }
            return true
        }

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
                op == COMMAND_CLEAR_DRAW_IMAGE_REF_FULL ||
                op == COMMAND_CLEAR_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT ||
                op == COMMAND_DRAW_IMAGE_REF_FULL ||
                op == COMMAND_DRAW_IMAGE_REF_FULL_RUN ||
                op == COMMAND_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT ||
                op == COMMAND_DRAW_ROUND_RECT ||
                op == COMMAND_FILL_ROUND_RECT ||
                op == COMMAND_FILL_RECT ||
                op == COMMAND_STROKE_LINE ||
                op == COMMAND_STROKE_LINE_RUN

        private fun translateScopeRecord(recordStart: Int, dx: Int, dy: Int) {
            when (payload[recordStart]) {
                COMMAND_CLEAR_RECT -> {
                    payload[recordStart + 3] += dx / 1000
                    payload[recordStart + 4] += dy / 1000
                }
                COMMAND_CLEAR_DRAW_IMAGE_REF_FULL -> {
                    payload[recordStart + 3] += dx / 1000
                    payload[recordStart + 4] += dy / 1000
                    payload[recordStart + 7] += dx
                    payload[recordStart + 8] += dy
                    payload[recordStart + 9] += dx
                    payload[recordStart + 10] += dy
                }
                COMMAND_DRAW_IMAGE_REF_FULL -> {
                    payload[recordStart + 3] += dx
                    payload[recordStart + 4] += dy
                    payload[recordStart + 5] += dx
                    payload[recordStart + 6] += dy
                }
                COMMAND_DRAW_IMAGE_REF_FULL_RUN -> {
                    val count = payload[recordStart + 3]
                    var argsOffset = recordStart + 4
                    repeat(count) {
                        payload[argsOffset] += dx
                        payload[argsOffset + 1] += dy
                        payload[argsOffset + 2] += dx
                        payload[argsOffset + 3] += dy
                        argsOffset += 6
                    }
                }
                COMMAND_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT -> {
                    payload[recordStart + 4] += dx
                    payload[recordStart + 5] += dy
                    payload[recordStart + 6] += dx
                    payload[recordStart + 7] += dy
                    payload[recordStart + 12] += dx
                    payload[recordStart + 13] += dy
                    payload[recordStart + 14] += dx
                    payload[recordStart + 15] += dy
                }
                COMMAND_CLEAR_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT -> {
                    payload[recordStart + 3] += dx / 1000
                    payload[recordStart + 4] += dy / 1000
                    payload[recordStart + 8] += dx
                    payload[recordStart + 9] += dy
                    payload[recordStart + 10] += dx
                    payload[recordStart + 11] += dy
                    payload[recordStart + 16] += dx
                    payload[recordStart + 17] += dy
                    payload[recordStart + 18] += dx
                    payload[recordStart + 19] += dy
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
                COMMAND_STROKE_LINE -> {
                    payload[recordStart + 4] += dx / 1000
                    payload[recordStart + 5] += dy / 1000
                    payload[recordStart + 6] += dx / 1000
                    payload[recordStart + 7] += dy / 1000
                }
                COMMAND_STROKE_LINE_RUN -> {
                    val count = payload[recordStart + 8]
                    var argsOffset = recordStart + 9
                    repeat(count) {
                        payload[argsOffset] += dx / 1000
                        payload[argsOffset + 1] += dy / 1000
                        payload[argsOffset + 2] += dx / 1000
                        payload[argsOffset + 3] += dy / 1000
                        argsOffset += 4
                    }
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

        private fun removeEmptyLayerClipBeforeCurrentRestore(): Boolean {
            val clipStart = previousRecordStart(payloadSize) ?: return false
            if (!isClipRecord(payload[clipStart])) {
                return false
            }
            val layerStart = previousRecordStart(clipStart) ?: return false
            if (!isEmptyLayerSaveRecord(payload[layerStart])) {
                return false
            }
            payloadSize = layerStart
            decrementOp(payload[layerStart])
            decrementOp(payload[clipStart])
            return true
        }

        private fun isClipRecord(op: Int): Boolean =
            op == COMMAND_CLIP_RECT || op == COMMAND_CLIP_PATH

        private fun isEmptyLayerSaveRecord(op: Int): Boolean =
            op == COMMAND_SAVE_LAYER || op == COMMAND_SAVE_TRANSLATE_LAYER

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
                op == COMMAND_SAVE_TRANSLATE_LAYER ||
                op == COMMAND_SAVE_LAYER_CLIP_PATH ||
                op == COMMAND_FILL_RECT_SAVE_LAYER_CLIP_RECT

        private fun previousRecordStart(endOffset: Int): Int? {
            if (!recordStartIndexEnabled) {
                return previousRecordStartLinear(endOffset)
            }
            ensureRecordIndex()
            if (endOffset == payloadSize) {
                return if (recordStartCount == 0) null else recordStarts[recordStartCount - 1]
            }
            val index = recordStarts.binarySearch(endOffset, fromIndex = 0, toIndex = recordStartCount)
            return if (index > 0) recordStarts[index - 1] else null
        }

        private fun previousRecordStartLinear(endOffset: Int): Int? {
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
            val openSaves = openSaveStartsBefore(payloadSize) ?: return null
            val saveStart = openSaves.lastOrNull() ?: return null
            if (payload[saveStart] != COMMAND_SAVE ||
                payload[saveStart + 1] != 3 * Int.SIZE_BYTES ||
                payload[saveStart + 2] != COMMAND_RECORD_FLAGS_NONE
            ) {
                return null
            }
            return if (isStateNeutralScope(saveStart + 3, payloadSize)) saveStart else null
        }

        private fun isStateNeutralScope(startOffset: Int, endOffset: Int): Boolean {
            var offset = startOffset
            var nestedSaveDepth = 0
            while (offset < endOffset) {
                val recordLength = payload[offset + 1] / Int.SIZE_BYTES
                if (recordLength < 3 || offset + recordLength > endOffset) return false
                val op = payload[offset]
                if (nestedSaveDepth == 0) {
                    when {
                        isStateNeutralCommand(op) -> Unit
                        isAnySaveRecord(op) -> nestedSaveDepth++
                        else -> return false
                    }
                } else {
                    when {
                        isAnySaveRecord(op) -> nestedSaveDepth++
                        op == COMMAND_RESTORE -> nestedSaveDepth--
                        op == COMMAND_RESTORE_N -> {
                            val restoreCount = payload[offset + 3]
                            if (restoreCount < 0 || restoreCount > nestedSaveDepth) return false
                            nestedSaveDepth -= restoreCount
                        }
                    }
                }
                offset += recordLength
            }
            return offset == endOffset && nestedSaveDepth == 0
        }

        private fun isAnySaveRecord(op: Int): Boolean =
            op == COMMAND_SAVE ||
                op == COMMAND_SAVE_TRANSLATE ||
                isLayerSaveRecord(op)

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
                COMMAND_DRAW_IMAGE_REF_FULL,
                COMMAND_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT,
                COMMAND_CLEAR_DRAW_IMAGE_REF_FULL,
                COMMAND_STROKE_LINE_RUN -> true
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
                val saveStart = payloadSize - 14
                val layerStart = payloadSize - 11
                if (saveStart >= 0 &&
                    payload[saveStart] == COMMAND_SAVE &&
                    payload[saveStart + 1] == 3 * Int.SIZE_BYTES &&
                    payload[saveStart + 2] == COMMAND_RECORD_FLAGS_NONE &&
                    payload[layerStart] == COMMAND_SAVE_LAYER &&
                    payload[layerStart + 1] == 8 * Int.SIZE_BYTES &&
                    payload[layerStart + 2] == COMMAND_RECORD_FLAGS_NONE
                ) {
                    payload[saveStart] = COMMAND_SAVE_SAVE_LAYER_SAVE_TRANSLATE
                    payload[saveStart + 1] = 10 * Int.SIZE_BYTES
                    payload.copyInto(
                        payload,
                        destinationOffset = saveStart + 3,
                        startIndex = layerStart + 3,
                        endIndex = layerStart + 8,
                    )
                    payload[saveStart + 8] = dx1000
                    payload[saveStart + 9] = dy1000
                    payloadSize = saveStart + 10
                    decrementOp(COMMAND_SAVE)
                    decrementOp(COMMAND_SAVE)
                    decrementOp(COMMAND_SAVE_LAYER)
                    countOp(COMMAND_SAVE_SAVE_LAYER_SAVE_TRANSLATE)
                    return
                }
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

        fun addFullImageRefCommand(
            recordFlags: Int,
            imageHasAlpha: Boolean,
            dstLeft1000: Int,
            dstTop1000: Int,
            dstRight1000: Int,
            dstBottom1000: Int,
            cacheKeyHigh: Int,
            cacheKeyLow: Int,
        ) {
            imageAlphaByCacheKey[cacheKey(cacheKeyHigh, cacheKeyLow)] = imageHasAlpha
            val clearStart =
                matchingClearRectBeforeImageDefinitions(
                    dstLeft1000,
                    dstTop1000,
                    dstRight1000,
                    dstBottom1000,
                )
            if (clearStart == null) {
                addCommand(
                    COMMAND_DRAW_IMAGE_REF_FULL,
                    recordFlags,
                    dstLeft1000,
                    dstTop1000,
                    dstRight1000,
                    dstBottom1000,
                    cacheKeyHigh,
                    cacheKeyLow,
                )
                return
            }
            payload.copyInto(
                payload,
                destinationOffset = clearStart,
                startIndex = clearStart + 7,
                endIndex = payloadSize,
            )
            payloadSize -= 7
            decrementOp(COMMAND_CLEAR_RECT)
            addCommand(
                COMMAND_DRAW_IMAGE_REF_FULL,
                recordFlags,
                dstLeft1000,
                dstTop1000,
                dstRight1000,
                dstBottom1000,
                cacheKeyHigh,
                cacheKeyLow,
            )
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
            recordIndexDirty = true
        }

        private fun tryAddDrawImageRefFullDrawRoundRect(op: Int, recordFlags: Int, args: IntArray): Boolean {
            if (op != COMMAND_DRAW_ROUND_RECT || args.size != 12) {
                return false
            }
            val previous = previousRecordStart(payloadSize) ?: return false
            if (payload[previous] != COMMAND_DRAW_IMAGE_REF_FULL ||
                payload[previous + 1] != 9 * Int.SIZE_BYTES
            ) {
                return false
            }
            val imageFlags = payload[previous + 2]
            val imageArgs = payload.copyOfRange(previous + 3, previous + 9)
            payloadSize = previous
            decrementOp(COMMAND_DRAW_IMAGE_REF_FULL)
            countOp(COMMAND_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT)
            addRecordHeader(COMMAND_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT, recordFlags, 1 + imageArgs.size + args.size)
            addValue(imageFlags)
            addAll(imageArgs)
            addAll(args)
            return true
        }

        private fun matchingClearRectBeforeImageDefinitions(
            dstLeft1000: Int,
            dstTop1000: Int,
            dstRight1000: Int,
            dstBottom1000: Int,
        ): Int? {
            if (dstLeft1000 % 1000 != 0 ||
                dstTop1000 % 1000 != 0 ||
                dstRight1000 % 1000 != 0 ||
                dstBottom1000 % 1000 != 0
            ) {
                return null
            }
            val clearStart = clearRectBeforeImageDefinitions(payloadSize) ?: return null
            return if (payload[clearStart + 3] == dstLeft1000 / 1000 &&
                payload[clearStart + 4] == dstTop1000 / 1000 &&
                payload[clearStart + 5] == (dstRight1000 - dstLeft1000) / 1000 &&
                payload[clearStart + 6] == (dstBottom1000 - dstTop1000) / 1000
            ) {
                clearStart
            } else {
                null
            }
        }

        private fun clearRectBeforeImageDefinitions(endOffset: Int): Int? {
            var offset = endOffset
            while (true) {
                val previous = previousRecordStart(offset) ?: return null
                if (previous >= offset) {
                    return null
                }
                val op = payload[previous]
                if (op == COMMAND_CLEAR_RECT &&
                    payload[previous + 1] == 7 * Int.SIZE_BYTES &&
                    payload[previous + 2] == COMMAND_RECORD_FLAGS_NONE
                ) {
                    return previous
                }
                if (!isTranslateScopePassThroughRecord(op)) {
                    return null
                }
                offset = previous
            }
        }

        fun opSummary(): String =
            if (opCountEntries == 0) {
                "none"
            } else {
                opCounts
                    .indices
                    .asSequence()
                    .filter { opCounts[it] > 0 }
                    .sortedWith(compareByDescending<Int> { opCounts[it] }.thenBy { it })
                    .joinToString(separator = " ") { op -> "${opName(op)}=${opCounts[op]}" }
            }

        fun opWordSummary(): String {
            if (payloadSize == 0) return "none"
            val totals = linkedMapOf<Int, Int>()
            var offset = 0
            while (offset < payloadSize) {
                val op = payload[offset]
                val recordLength = payload[offset + 1] / Int.SIZE_BYTES
                if (recordLength < 3 || offset + recordLength > payloadSize) {
                    return "invalid"
                }
                totals[op] = (totals[op] ?: 0) + recordLength
                offset += recordLength
            }
            return totals.entries
                .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
                .joinToString(separator = " ") { (op, words) -> "${opName(op)}=$words" }
        }

        fun opPairWordSummary(): String {
            if (payloadSize == 0) return "none"
            val totals = linkedMapOf<Long, Int>()
            var previousOp: Int? = null
            var previousLength = 0
            var offset = 0
            while (offset < payloadSize) {
                val op = payload[offset]
                val recordLength = payload[offset + 1] / Int.SIZE_BYTES
                if (recordLength < 3 || offset + recordLength > payloadSize) {
                    return "invalid"
                }
                previousOp?.let { before ->
                    val key = opPairKey(before, op)
                    totals[key] = (totals[key] ?: 0) + previousLength + recordLength
                }
                previousOp = op
                previousLength = recordLength
                offset += recordLength
            }
            if (totals.isEmpty()) return "none"
            return totals.entries
                .sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
                .joinToString(separator = " ") { (key, words) ->
                    "${opName(opPairFirst(key))}>${opName(opPairSecond(key))}=$words"
                }
        }

        private fun countOp(op: Int) {
            if (op !in opCounts.indices) return
            if (opCounts[op] == 0) {
                opCountEntries++
            }
            opCounts[op]++
        }

        private fun decrementOp(op: Int) {
            recordIndexDirty = true
            if (op !in opCounts.indices) return
            val count = opCounts[op]
            if (count <= 0) return
            opCounts[op] = count - 1
            if (count == 1) {
                opCountEntries--
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

        private fun hasOp(op: Int): Boolean =
            op in opCounts.indices && opCounts[op] > 0

        private fun hasOpAtLeast(op: Int, count: Int): Boolean =
            op in opCounts.indices && opCounts[op] >= count

        private fun ensureRecordIndex() {
            if (!recordIndexDirty) return
            recordStartCount = 0
            var offset = 0
            while (offset < payloadSize) {
                val recordLength = validRecordLengthAt(offset) ?: run {
                    recordIndexDirty = false
                    return
                }
                appendRecordStart(offset)
                offset += recordLength
            }
            recordIndexDirty = false
        }

        private fun appendRecordStart(recordStart: Int) {
            if (recordStartCount == recordStarts.size) {
                recordStarts = recordStarts.copyOf(recordStarts.size * 2)
            }
            recordStarts[recordStartCount++] = recordStart
        }

        private fun opPairKey(first: Int, second: Int): Long =
            (first.toLong() shl 32) or (second.toLong() and 0xffffffffL)

        private fun opPairFirst(key: Long): Int = (key shr 32).toInt()

        private fun opPairSecond(key: Long): Int = key.toInt()

        private val defaultCompactionGroupMask: Int = 0b1111
        private val defaultCompactionGroup1PassMask: Int = 0x1ff
        // The clear+image+roundrect branch is guarded by image alpha; keep the
        // non-clear image+roundrect branch enabled for transparent icons/logos.
        private val defaultImageRefRoundRectCompactionMask: Int = 0b11
        private val maxTranslatedLayerFoldScanWords: Int = 4096
        private val compactionEnabled: Boolean =
            !java.lang.Boolean.getBoolean("compose.jbr.skia.command.disableCompaction")
        private val compactionGroupMask: Int =
            java.lang.Integer.getInteger("compose.jbr.skia.command.compactionGroupMask", defaultCompactionGroupMask)
        private val compactionGroup1PassMask: Int =
            java.lang.Integer.getInteger(
                "compose.jbr.skia.command.compactionGroup1PassMask",
                defaultCompactionGroup1PassMask,
            )
        private val imageRefRoundRectCompactionMask: Int =
            java.lang.Integer.getInteger(
                "compose.jbr.skia.command.imageRefRoundRectCompactionMask",
                defaultImageRefRoundRectCompactionMask,
            )
        private val recordStartIndexEnabled: Boolean =
            !java.lang.Boolean.getBoolean("compose.jbr.skia.command.disableRecordStartIndex")
        private val compactionOpPrerequisiteGatesEnabled: Boolean =
            !java.lang.Boolean.getBoolean("compose.jbr.skia.command.disableCompactionOpPrerequisiteGates")

        private fun isCompactionGroupEnabled(group: Int): Boolean {
            return compactionGroupMask < 0 || (compactionGroupMask and (1 shl group)) != 0
        }

        private fun isCompactionGroup1PassEnabled(pass: Int): Boolean {
            return compactionGroup1PassMask < 0 || (compactionGroup1PassMask and (1 shl pass)) != 0
        }

        private fun isImageRefRoundRectCompactionEnabled(branch: Int): Boolean {
            return imageRefRoundRectCompactionMask < 0 || (imageRefRoundRectCompactionMask and (1 shl branch)) != 0
        }

        fun toIntArray(): IntArray {
            dropInvalidPayload()
            if (compactionEnabled) {
                if (isCompactionGroupEnabled(0)) {
                    foldTrailingTranslatedRoundRectSuffix()
                    compactAdjacentSaveSaveLayerSaveTranslateRecords()
                    foldPlainSaveBeforeLayerClosedBySameRestore()
                    dropInvalidPayload()
                }
                if (isCompactionGroupEnabled(1)) {
                    if (isCompactionGroup1PassEnabled(0)) foldTransformableRecordsOutOfPlainTranslatedLayers()
                    if (isCompactionGroup1PassEnabled(1)) foldFullImageRefsOutOfPlainTranslatedLayers()
                    if (isCompactionGroup1PassEnabled(2)) compactAdjacentImageRefFullRoundRectRecords()
                    if (isCompactionGroup1PassEnabled(3)) compactAdjacentImageRefFullRoundRectRecords()
                    if (isCompactionGroup1PassEnabled(4)) compactAdjacentFullImageRefs()
                    if (isCompactionGroup1PassEnabled(5)) foldTransformableRecordsOutOfPlainTranslatedLayers()
                    if (isCompactionGroup1PassEnabled(6)) compactAdjacentStrokeLineImageRefFullRunRecords()
                    if (isCompactionGroup1PassEnabled(7)) compactAdjacentStrokeLineImageRefFullRunRestoreNRecords()
                    if (isCompactionGroup1PassEnabled(8)) compactAdjacentStrokeLineRunRecords()
                    dropInvalidPayload()
                }
                if (isCompactionGroupEnabled(2)) {
                    if (!compactionOpPrerequisiteGatesEnabled) {
                        compactAdjacentSaveTranslateLayerSaveTranslateRecords()
                        compactAdjacentSaveSaveLayerSaveTranslateRecords()
                        compactAdjacentSaveLayerSaveTranslateRecords()
                        compactAdjacentSaveSaveLayerSaveTranslateRecords()
                        compactAdjacentSaveLayerClipRectRecords()
                        compactAdjacentSaveLayerClipPathRecords()
                        compactAdjacentFillRectSaveLayerClipRectRecords()
                        compactAdjacentFullImageRefRestoreRecords()
                        compactAdjacentFullImageRefRestoreNRecords()
                        compactAdjacentSaveTranslateLayerSaveTranslateFullImageRefRestoreNRecords()
                        compactAdjacentSaveTranslateLayerSaveTranslateFullImageRefRestoreNSaveTranslateLayerSaveTranslateRecords()
                        compactAdjacentFullImageRefRestoreNSaveTranslateLayerSaveTranslateRecords()
                        compactAdjacentRoundRectRestoreNRecords()
                        compactAdjacentFillRectSaveRecords()
                        compactAdjacentSaveFillRectSaveRecords()
                        compactAdjacentFillRectSaveLayerClipRectSaveSaveLayerSaveTranslateRecords()
                    } else {
                        if (hasOp(COMMAND_SAVE_TRANSLATE_LAYER) && hasOp(COMMAND_SAVE_TRANSLATE)) {
                            compactAdjacentSaveTranslateLayerSaveTranslateRecords()
                        }
                        if (hasOp(COMMAND_SAVE) &&
                            ((hasOp(COMMAND_SAVE_LAYER) && hasOp(COMMAND_SAVE_TRANSLATE)) ||
                                hasOp(COMMAND_SAVE_LAYER_SAVE_TRANSLATE))
                        ) {
                            compactAdjacentSaveSaveLayerSaveTranslateRecords()
                        }
                        if (hasOp(COMMAND_SAVE_LAYER) && hasOp(COMMAND_SAVE_TRANSLATE)) {
                            compactAdjacentSaveLayerSaveTranslateRecords()
                        }
                        if (hasOp(COMMAND_SAVE) &&
                            ((hasOp(COMMAND_SAVE_LAYER) && hasOp(COMMAND_SAVE_TRANSLATE)) ||
                                hasOp(COMMAND_SAVE_LAYER_SAVE_TRANSLATE))
                        ) {
                            compactAdjacentSaveSaveLayerSaveTranslateRecords()
                        }
                        if (hasOp(COMMAND_SAVE_LAYER) && hasOp(COMMAND_CLIP_RECT)) {
                            compactAdjacentSaveLayerClipRectRecords()
                        }
                        if (hasOp(COMMAND_SAVE_LAYER) && hasOp(COMMAND_CLIP_PATH)) {
                            compactAdjacentSaveLayerClipPathRecords()
                        }
                        if (hasOp(COMMAND_FILL_RECT) && hasOp(COMMAND_SAVE_LAYER_CLIP_RECT)) {
                            compactAdjacentFillRectSaveLayerClipRectRecords()
                        }
                        if (hasOp(COMMAND_DRAW_IMAGE_REF_FULL) && hasOp(COMMAND_RESTORE)) {
                            compactAdjacentFullImageRefRestoreRecords()
                        }
                        if ((hasOp(COMMAND_DRAW_IMAGE_REF_FULL) ||
                            hasOp(COMMAND_DRAW_IMAGE_REF_FULL_RESTORE) ||
                            hasOp(COMMAND_DRAW_IMAGE_REF_FULL_RESTORE_N)) &&
                            (hasOp(COMMAND_RESTORE) || hasOp(COMMAND_RESTORE_N))
                        ) {
                            compactAdjacentFullImageRefRestoreNRecords()
                        }
                        if (hasOp(COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE) &&
                            hasOp(COMMAND_DRAW_IMAGE_REF_FULL_RESTORE_N)
                        ) {
                            compactAdjacentSaveTranslateLayerSaveTranslateFullImageRefRestoreNRecords()
                        }
                        if (hasOp(COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE_DRAW_IMAGE_REF_FULL_RESTORE_N) &&
                            hasOp(COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE)
                        ) {
                            compactAdjacentSaveTranslateLayerSaveTranslateFullImageRefRestoreNSaveTranslateLayerSaveTranslateRecords()
                        }
                        if (hasOp(COMMAND_DRAW_IMAGE_REF_FULL_RESTORE_N) &&
                            hasOp(COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE)
                        ) {
                            compactAdjacentFullImageRefRestoreNSaveTranslateLayerSaveTranslateRecords()
                        }
                        if ((hasOp(COMMAND_DRAW_ROUND_RECT) || hasOp(COMMAND_DRAW_ROUND_RECT_RESTORE_N)) &&
                            hasOp(COMMAND_RESTORE_N)
                        ) {
                            compactAdjacentRoundRectRestoreNRecords()
                        }
                        if (hasOp(COMMAND_FILL_RECT) && hasOp(COMMAND_SAVE)) {
                            compactAdjacentFillRectSaveRecords()
                        }
                        if (hasOp(COMMAND_SAVE) && hasOp(COMMAND_FILL_RECT_SAVE)) {
                            compactAdjacentSaveFillRectSaveRecords()
                        }
                        if (hasOp(COMMAND_FILL_RECT_SAVE_LAYER_CLIP_RECT) &&
                            hasOp(COMMAND_SAVE_SAVE_LAYER_SAVE_TRANSLATE)
                        ) {
                            compactAdjacentFillRectSaveLayerClipRectSaveSaveLayerSaveTranslateRecords()
                        }
                    }
                    dropInvalidPayload()
                }
                if (isCompactionGroupEnabled(3)) {
                    if (!compactionOpPrerequisiteGatesEnabled) {
                        compactAdjacentSaveTranslateRotateRecords()
                        compactAdjacentSaveTranslateRotateTranslateFillOvalRestoreRecords()
                        compactAdjacentSaveTranslateRotateTranslateFillOvalRestoreRuns()
                        compactAdjacentSaveTranslateRotateTranslateStrokeClosedPolylineDeltaRestoreRecords()
                        compactAdjacentStrokeOvalRuns()
                        compactAdjacentFillRectRuns()
                    } else {
                        if (hasOp(COMMAND_SAVE_TRANSLATE) && hasOp(COMMAND_ROTATE)) {
                            compactAdjacentSaveTranslateRotateRecords()
                        }
                        if (hasOp(COMMAND_SAVE_TRANSLATE_ROTATE) &&
                            hasOp(COMMAND_TRANSLATE) &&
                            hasOp(COMMAND_FILL_OVAL) &&
                            hasOp(COMMAND_RESTORE)
                        ) {
                            compactAdjacentSaveTranslateRotateTranslateFillOvalRestoreRecords()
                        }
                        if (hasOpAtLeast(COMMAND_SAVE_TRANSLATE_ROTATE_TRANSLATE_FILL_OVAL_RESTORE, 2)) {
                            compactAdjacentSaveTranslateRotateTranslateFillOvalRestoreRuns()
                        }
                        if (hasOp(COMMAND_SAVE_TRANSLATE_ROTATE) &&
                            hasOp(COMMAND_TRANSLATE) &&
                            hasOp(COMMAND_STROKE_CLOSED_POLYLINE_DELTA) &&
                            hasOp(COMMAND_RESTORE)
                        ) {
                            compactAdjacentSaveTranslateRotateTranslateStrokeClosedPolylineDeltaRestoreRecords()
                        }
                        if (hasOpAtLeast(COMMAND_STROKE_OVAL, 2)) {
                            compactAdjacentStrokeOvalRuns()
                        }
                        if (hasOpAtLeast(COMMAND_FILL_RECT, 2)) {
                            compactAdjacentFillRectRuns()
                        }
                    }
                    dropInvalidPayload()
                }
            }
            return IntArray(streamSize).also { stream ->
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
        }

        private fun dropInvalidPayload() {
            if (hasValidRecordSequence()) return
            payloadSize = 0
            recordIndexDirty = true
        }

        private fun hasValidRecordSequence(): Boolean {
            var offset = 0
            while (offset < payloadSize) {
                val recordLength = validRecordLengthAt(offset) ?: return false
                offset += recordLength
            }
            return offset == payloadSize
        }

        private fun compactAdjacentFullImageRefs(): Boolean {
            var readOffset = 0
            var writeOffset = 0
            var compacted = false
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                if (recordLength < 3 || readOffset + recordLength > payloadSize) return false
                if (canStartFullImageRefRun(readOffset)) {
                    val recordFlags = payload[readOffset + 2]
                    val runArgs = mutableListOf<IntArray>()
                    val prefixRecords = mutableListOf<IntArray>()
                    runArgs += payload.copyOfRange(readOffset + 3, readOffset + 9)
                    var scanOffset = readOffset + recordLength
                    var runEnd = scanOffset
                    while (scanOffset < payloadSize) {
                        val scanLength = payload[scanOffset + 1] / Int.SIZE_BYTES
                        if (canAppendFullImageRefRun(scanOffset, recordFlags)) {
                            runArgs += payload.copyOfRange(scanOffset + 3, scanOffset + 9)
                            runEnd = scanOffset + scanLength
                            scanOffset = runEnd
                            continue
                        }
                        if (isFullImageRefRunPrefixRecord(scanOffset)) {
                            prefixRecords += payload.copyOfRange(scanOffset, scanOffset + scanLength)
                            scanOffset += scanLength
                            continue
                        }
                        break
                    }
                    val runCount = runArgs.size
                    if (runCount > 1) {
                        prefixRecords.forEach { prefixRecord ->
                            prefixRecord.copyInto(payload, destinationOffset = writeOffset)
                            writeOffset += prefixRecord.size
                        }
                        ensureCapacity(writeOffset + 3 + 1 + runCount * 6 + payloadSize - runEnd)
                        payload[writeOffset++] = COMMAND_DRAW_IMAGE_REF_FULL_RUN
                        payload[writeOffset++] = (4 + runCount * 6) * Int.SIZE_BYTES
                        payload[writeOffset++] = recordFlags
                        payload[writeOffset++] = runCount
                        runArgs.forEach { imageArgs ->
                            imageArgs.copyInto(payload, destinationOffset = writeOffset)
                            writeOffset += imageArgs.size
                        }
                        repeat(runCount) {
                            decrementOp(COMMAND_DRAW_IMAGE_REF_FULL)
                        }
                        countOp(COMMAND_DRAW_IMAGE_REF_FULL_RUN)
                        readOffset = runEnd
                        compacted = true
                        continue
                    }
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = readOffset + recordLength,
                    )
                }
                writeOffset += recordLength
                readOffset += recordLength
            }
            if (compacted) {
                payloadSize = writeOffset
            }
            return compacted
        }

        private fun isFullImageRefRunPrefixRecord(recordStart: Int): Boolean {
            if (recordStart + 3 > payloadSize) {
                return false
            }
            val recordLength = payload[recordStart + 1] / Int.SIZE_BYTES
            if (recordStart + recordLength > payloadSize) {
                return false
            }
            return payload[recordStart] == COMMAND_DEFINE_IMAGE_BITMAP ||
                payload[recordStart] == COMMAND_DEFINE_IMAGE_ARGB
        }

        private fun canStartFullImageRefRun(recordStart: Int): Boolean =
            canAppendFullImageRefRun(recordStart, payload[recordStart + 2])

        private fun canAppendFullImageRefRun(recordStart: Int, recordFlags: Int): Boolean =
            recordStart + 9 <= payloadSize &&
                payload[recordStart] == COMMAND_DRAW_IMAGE_REF_FULL &&
                payload[recordStart + 1] == 9 * Int.SIZE_BYTES &&
                payload[recordStart + 2] == recordFlags

        private fun foldTrailingTranslatedRoundRectSuffix(): Boolean {
            val trailingRoundRectStart = previousRecordStart(payloadSize) ?: return false
            val trailingRoundRectOp = payload[trailingRoundRectStart]
            if (trailingRoundRectOp == COMMAND_FILL_ROUND_RECT || trailingRoundRectOp == COMMAND_DRAW_ROUND_RECT) {
                val trailingTranslateStart = previousRecordStart(trailingRoundRectStart) ?: return false
                if (isTranslateRecord(trailingTranslateStart)) {
                    translateRoundRectRecord(trailingTranslateStart, trailingRoundRectStart, trailingRoundRectOp)
                    val roundRectLength = payload[trailingRoundRectStart + 1] / Int.SIZE_BYTES
                    payload.copyInto(
                        payload,
                        destinationOffset = trailingTranslateStart,
                        startIndex = trailingRoundRectStart,
                        endIndex = trailingRoundRectStart + roundRectLength,
                    )
                    payloadSize = trailingTranslateStart + roundRectLength
                    decrementOp(COMMAND_TRANSLATE)
                    return true
                }
            }
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
            val saveStart = previousRecordStart(roundRectStart) ?: return false
            if (payload[saveStart] != COMMAND_SAVE ||
                payload[saveStart + 1] != 3 * Int.SIZE_BYTES ||
                payload[saveStart + 2] != COMMAND_RECORD_FLAGS_NONE
            ) {
                return false
            }
            val translateStart = previousRecordStart(saveStart) ?: return false
            if (!isTranslateRecord(translateStart)) {
                return false
            }
            val restoreCount = if (restoreOp == COMMAND_RESTORE_N) payload[restoreStart + 3] else 1
            if (restoreCount <= 0) {
                return false
            }
            translateRoundRectRecord(translateStart, roundRectStart, roundRectOp)
            val roundRectLength = payload[roundRectStart + 1] / Int.SIZE_BYTES
            payload.copyInto(
                payload,
                destinationOffset = translateStart,
                startIndex = roundRectStart,
                endIndex = roundRectStart + roundRectLength,
            )
            var writeOffset = translateStart + roundRectLength
            when (restoreOp) {
                COMMAND_RESTORE -> {
                    decrementOp(COMMAND_RESTORE)
                }
                COMMAND_RESTORE_N -> {
                    decrementOp(COMMAND_RESTORE_N)
                    when (val remainingRestoreCount = restoreCount - 1) {
                        0 -> Unit
                        1 -> {
                            payload[writeOffset++] = COMMAND_RESTORE
                            payload[writeOffset++] = 3 * Int.SIZE_BYTES
                            payload[writeOffset++] = COMMAND_RECORD_FLAGS_NONE
                            countOp(COMMAND_RESTORE)
                        }
                        else -> {
                            payload[writeOffset++] = COMMAND_RESTORE_N
                            payload[writeOffset++] = 4 * Int.SIZE_BYTES
                            payload[writeOffset++] = COMMAND_RECORD_FLAGS_NONE
                            payload[writeOffset++] = remainingRestoreCount
                            countOp(COMMAND_RESTORE_N)
                        }
                    }
                }
            }
            val restoreLength = payload[restoreStart + 1] / Int.SIZE_BYTES
            payload.copyInto(
                payload,
                destinationOffset = writeOffset,
                startIndex = restoreStart + restoreLength,
                endIndex = payloadSize,
            )
            payloadSize = writeOffset + payloadSize - restoreStart - restoreLength
            decrementOp(COMMAND_TRANSLATE)
            decrementOp(COMMAND_SAVE)
            return true
        }

        private fun foldPlainSaveBeforeLayerClosedBySameRestore(): Boolean {
            var offset = 0
            while (offset < payloadSize) {
                val recordLength = payload[offset + 1] / Int.SIZE_BYTES
                if (recordLength < 3 || offset + recordLength > payloadSize) return false
                if (payload[offset] == COMMAND_RESTORE_N &&
                    recordLength == 4 &&
                    payload[offset + 2] == COMMAND_RECORD_FLAGS_NONE
                ) {
                    val restoreCount = payload[offset + 3]
                    val openSaves = openSaveStartsBefore(offset) ?: return false
                    if (restoreCount >= 2 && restoreCount <= openSaves.size) {
                        val closingStartIndex = openSaves.size - restoreCount
                        for (index in openSaves.lastIndex downTo closingStartIndex + 1) {
                            val layerStart = openSaves[index]
                            val saveStart = openSaves[index - 1]
                            if (isRedundantPlainSaveBeforeLayer(saveStart, layerStart)) {
                                removePlainSaveBeforeJointRestore(saveStart, offset, restoreCount)
                                return true
                            }
                        }
                    }
                }
                offset += recordLength
            }
            return false
        }

        private fun isRedundantPlainSaveBeforeLayer(saveStart: Int, layerStart: Int): Boolean =
            payload[saveStart] == COMMAND_SAVE &&
                payload[saveStart + 1] == 3 * Int.SIZE_BYTES &&
                payload[saveStart + 2] == COMMAND_RECORD_FLAGS_NONE &&
                saveStart + 3 == layerStart &&
                payload[layerStart] == COMMAND_SAVE_LAYER

        private fun removePlainSaveBeforeJointRestore(saveStart: Int, restoreStart: Int, restoreCount: Int) {
            payload.copyInto(
                payload,
                destinationOffset = saveStart,
                startIndex = saveStart + 3,
                endIndex = restoreStart + 4,
            )
            val shiftedRestoreStart = restoreStart - 3
            val remainingRestoreCount = restoreCount - 1
            if (remainingRestoreCount == 1) {
                payload[shiftedRestoreStart] = COMMAND_RESTORE
                payload[shiftedRestoreStart + 1] = 3 * Int.SIZE_BYTES
                payload[shiftedRestoreStart + 2] = COMMAND_RECORD_FLAGS_NONE
                payload.copyInto(
                    payload,
                    destinationOffset = shiftedRestoreStart + 3,
                    startIndex = shiftedRestoreStart + 4,
                    endIndex = payloadSize - 3,
                )
                payloadSize -= 4
                decrementOp(COMMAND_RESTORE_N)
                countOp(COMMAND_RESTORE)
            } else {
                payload[shiftedRestoreStart + 3] = remainingRestoreCount
                payload.copyInto(
                    payload,
                    destinationOffset = shiftedRestoreStart + 4,
                    startIndex = restoreStart + 4,
                    endIndex = payloadSize,
                )
                payloadSize -= 3
            }
            decrementOp(COMMAND_SAVE)
        }

        private fun foldTransformableRecordsOutOfPlainTranslatedLayers() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = validRecordLengthAt(readOffset) ?: return
                val nextOffset = readOffset + recordLength
                if (payload[readOffset] == COMMAND_SAVE_TRANSLATE_LAYER && recordLength == 10) {
                    val layerEnd = plainTranslatedLayerContentEnd(nextOffset)
                    if (layerEnd != null && canFoldTransformableRecordsOutOfTranslatedLayer(readOffset, nextOffset, layerEnd)) {
                        val dx = payload[readOffset + 3]
                        val dy = payload[readOffset + 4]
                        var contentOffset = nextOffset
                        while (contentOffset < layerEnd) {
                            val contentLength = payload[contentOffset + 1] / Int.SIZE_BYTES
                            val op = payload[contentOffset]
                            payload.copyInto(
                                payload,
                                destinationOffset = writeOffset,
                                startIndex = contentOffset,
                                endIndex = contentOffset + contentLength,
                            )
                            if (isTranslatedLayerFoldTransformableRecord(op)) {
                                translateScopeRecord(writeOffset, dx, dy)
                            }
                            writeOffset += contentLength
                            contentOffset += contentLength
                        }
                        val restoreCount = payload[layerEnd + 3]
                        val remainingRestoreCount = restoreCount - 2
                        if (remainingRestoreCount > 0) {
                            payload[writeOffset++] = COMMAND_RESTORE_N
                            payload[writeOffset++] = 4 * Int.SIZE_BYTES
                            payload[writeOffset++] = COMMAND_RECORD_FLAGS_NONE
                            payload[writeOffset++] = remainingRestoreCount
                        }
                        decrementOp(COMMAND_SAVE_TRANSLATE_LAYER)
                        decrementRestoreCount(restoreCount)
                        if (remainingRestoreCount > 0) {
                            countOp(COMMAND_RESTORE_N)
                        }
                        readOffset = layerEnd + 4
                        continue
                    }
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun plainTranslatedLayerContentEnd(startOffset: Int): Int? {
            var offset = startOffset
            var transformableRecordCount = 0
            while (offset < payloadSize) {
                if (offset - startOffset > maxTranslatedLayerFoldScanWords) return null
                val recordLength = validRecordLengthAt(offset) ?: return null
                val op = payload[offset]
                if (op == COMMAND_RESTORE_N && recordLength == 4 && payload[offset + 2] == COMMAND_RECORD_FLAGS_NONE) {
                    return if (payload[offset + 3] >= 2 && transformableRecordCount > 0) offset else null
                }
                if (isTranslatedLayerFoldTransformableRecord(op)) {
                    transformableRecordCount++
                } else if (!isTranslateScopePassThroughRecord(op)) {
                    return null
                }
                offset += recordLength
            }
            return null
        }

        private fun canFoldTransformableRecordsOutOfTranslatedLayer(
            layerStart: Int,
            contentStart: Int,
            contentEnd: Int,
        ): Boolean {
            if (payload[layerStart + 9] != 1000) return false
            val dx = payload[layerStart + 3]
            val dy = payload[layerStart + 4]
            val layerLeft1000 = payload[layerStart + 5] * 1000
            val layerTop1000 = payload[layerStart + 6] * 1000
            val layerRight1000 = layerLeft1000 + payload[layerStart + 7] * 1000
            val layerBottom1000 = layerTop1000 + payload[layerStart + 8] * 1000
            if (payload[layerStart + 7] < 0 || payload[layerStart + 8] < 0) return false
            var offset = contentStart
            while (offset < contentEnd) {
                val op = payload[offset]
                val recordLength = validRecordLengthAt(offset) ?: return false
                if (isTranslatedLayerFoldTransformableRecord(op)) {
                    if (requiresWholePixelTranslation(op) && (dx % 1000 != 0 || dy % 1000 != 0)) return false
                    if (!isRecordInsideLayerBounds(offset, layerLeft1000, layerTop1000, layerRight1000, layerBottom1000)) {
                        return false
                    }
                }
                offset += recordLength
            }
            return offset == contentEnd
        }

        private fun validRecordLengthAt(recordStart: Int): Int? {
            if (recordStart < 0 || recordStart + 2 > payloadSize) return null
            val recordLengthBytes = payload[recordStart + 1]
            if (recordLengthBytes <= 0 || recordLengthBytes % Int.SIZE_BYTES != 0) return null
            val recordLength = recordLengthBytes / Int.SIZE_BYTES
            if (recordLength < 3 || recordStart + recordLength > payloadSize) return null
            return recordLength
        }

        private fun isTranslatedLayerFoldTransformableRecord(op: Int): Boolean =
            op == COMMAND_DRAW_ROUND_RECT ||
                op == COMMAND_DRAW_IMAGE_REF_FULL ||
                op == COMMAND_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT ||
                op == COMMAND_CLEAR_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT ||
                op == COMMAND_DRAW_IMAGE_REF_FULL_RUN ||
                op == COMMAND_FILL_ROUND_RECT ||
                op == COMMAND_FILL_RECT ||
                op == COMMAND_STROKE_LINE ||
                op == COMMAND_STROKE_LINE_RUN

        private fun isRecordInsideLayerBounds(
            recordStart: Int,
            layerLeft1000: Int,
            layerTop1000: Int,
            layerRight1000: Int,
            layerBottom1000: Int,
        ): Boolean =
            when (payload[recordStart]) {
                COMMAND_DRAW_ROUND_RECT -> {
                    payload[recordStart + 5] >= layerLeft1000 &&
                        payload[recordStart + 6] >= layerTop1000 &&
                        payload[recordStart + 7] <= layerRight1000 &&
                        payload[recordStart + 8] <= layerBottom1000
                }
                COMMAND_FILL_ROUND_RECT -> {
                    payload[recordStart + 4] >= layerLeft1000 &&
                        payload[recordStart + 5] >= layerTop1000 &&
                        payload[recordStart + 6] <= layerRight1000 &&
                        payload[recordStart + 7] <= layerBottom1000
                }
                COMMAND_FILL_RECT -> {
                    val left1000 = payload[recordStart + 4] * 1000
                    val top1000 = payload[recordStart + 5] * 1000
                    left1000 >= layerLeft1000 &&
                        top1000 >= layerTop1000 &&
                        left1000 + payload[recordStart + 6] * 1000 <= layerRight1000 &&
                        top1000 + payload[recordStart + 7] * 1000 <= layerBottom1000
                }
                COMMAND_DRAW_IMAGE_REF_FULL -> {
                    payload[recordStart + 3] >= layerLeft1000 &&
                        payload[recordStart + 4] >= layerTop1000 &&
                        payload[recordStart + 5] <= layerRight1000 &&
                        payload[recordStart + 6] <= layerBottom1000
                }
                COMMAND_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT -> {
                    payload[recordStart + 4] >= layerLeft1000 &&
                        payload[recordStart + 5] >= layerTop1000 &&
                        payload[recordStart + 6] <= layerRight1000 &&
                        payload[recordStart + 7] <= layerBottom1000 &&
                        payload[recordStart + 12] >= layerLeft1000 &&
                        payload[recordStart + 13] >= layerTop1000 &&
                        payload[recordStart + 14] <= layerRight1000 &&
                        payload[recordStart + 15] <= layerBottom1000
                }
                COMMAND_CLEAR_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT -> {
                    val clearLeft1000 = payload[recordStart + 3] * 1000
                    val clearTop1000 = payload[recordStart + 4] * 1000
                    val clearRight1000 = clearLeft1000 + payload[recordStart + 5] * 1000
                    val clearBottom1000 = clearTop1000 + payload[recordStart + 6] * 1000
                    clearLeft1000 >= layerLeft1000 &&
                        clearTop1000 >= layerTop1000 &&
                        clearRight1000 <= layerRight1000 &&
                        clearBottom1000 <= layerBottom1000 &&
                        payload[recordStart + 8] >= layerLeft1000 &&
                        payload[recordStart + 9] >= layerTop1000 &&
                        payload[recordStart + 10] <= layerRight1000 &&
                        payload[recordStart + 11] <= layerBottom1000 &&
                        payload[recordStart + 16] >= layerLeft1000 &&
                        payload[recordStart + 17] >= layerTop1000 &&
                        payload[recordStart + 18] <= layerRight1000 &&
                        payload[recordStart + 19] <= layerBottom1000
                }
                COMMAND_DRAW_IMAGE_REF_FULL_RUN -> {
                    val recordEnd = recordStart + payload[recordStart + 1] / Int.SIZE_BYTES
                    val count = payload[recordStart + 3]
                    if (count <= 1 || recordStart + 4 + count * 6 != recordEnd) {
                        false
                    } else {
                        var argOffset = recordStart + 4
                        var inside = true
                        repeat(count) {
                            if (payload[argOffset] < layerLeft1000 ||
                                payload[argOffset + 1] < layerTop1000 ||
                                payload[argOffset + 2] > layerRight1000 ||
                                payload[argOffset + 3] > layerBottom1000
                            ) {
                                inside = false
                            }
                            argOffset += 6
                        }
                        inside
                    }
                }
                COMMAND_STROKE_LINE -> {
                    payload[recordStart + 4] * 1000 >= layerLeft1000 &&
                        payload[recordStart + 5] * 1000 >= layerTop1000 &&
                        payload[recordStart + 4] * 1000 <= layerRight1000 &&
                        payload[recordStart + 5] * 1000 <= layerBottom1000 &&
                        payload[recordStart + 6] * 1000 >= layerLeft1000 &&
                        payload[recordStart + 7] * 1000 >= layerTop1000 &&
                        payload[recordStart + 6] * 1000 <= layerRight1000 &&
                        payload[recordStart + 7] * 1000 <= layerBottom1000
                }
                COMMAND_STROKE_LINE_RUN -> {
                    val recordEnd = recordStart + payload[recordStart + 1] / Int.SIZE_BYTES
                    val count = payload[recordStart + 8]
                    if (count <= 1 || recordStart + 9 + count * 4 != recordEnd) {
                        false
                    } else {
                        var argOffset = recordStart + 9
                        var inside = true
                        repeat(count) {
                            if (payload[argOffset] * 1000 < layerLeft1000 ||
                                payload[argOffset + 1] * 1000 < layerTop1000 ||
                                payload[argOffset] * 1000 > layerRight1000 ||
                                payload[argOffset + 1] * 1000 > layerBottom1000 ||
                                payload[argOffset + 2] * 1000 < layerLeft1000 ||
                                payload[argOffset + 3] * 1000 < layerTop1000 ||
                                payload[argOffset + 2] * 1000 > layerRight1000 ||
                                payload[argOffset + 3] * 1000 > layerBottom1000
                            ) {
                                inside = false
                            }
                            argOffset += 4
                        }
                        inside
                    }
                }
                else -> false
            }

        private fun foldFullImageRefsOutOfPlainTranslatedLayers() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                val nextOffset = readOffset + recordLength
                if (payload[readOffset] == COMMAND_SAVE_TRANSLATE_LAYER && recordLength == 10) {
                    val imageStart = fullImageRefAfterPassThroughRecords(nextOffset)
                    if (imageStart != null) {
                        val restoreStart = imageStart + 9
                        val restoreCount = restoreCountOrZero(restoreStart)
                        if (restoreCount >= 2 &&
                            canFoldFullImageRefOutOfTranslatedLayer(readOffset, imageStart)
                        ) {
                            val dx = payload[readOffset + 3]
                            val dy = payload[readOffset + 4]
                            if (nextOffset < imageStart) {
                                payload.copyInto(
                                    payload,
                                    destinationOffset = writeOffset,
                                    startIndex = nextOffset,
                                    endIndex = imageStart,
                                )
                                writeOffset += imageStart - nextOffset
                            }
                            payload.copyInto(
                                payload,
                                destinationOffset = writeOffset,
                                startIndex = imageStart,
                                endIndex = imageStart + 9,
                            )
                            payload[writeOffset + 3] += dx
                            payload[writeOffset + 4] += dy
                            payload[writeOffset + 5] += dx
                            payload[writeOffset + 6] += dy
                            writeOffset += 9
                            val restoreLength = payload[restoreStart + 1] / Int.SIZE_BYTES
                            val remainingRestoreCount = restoreCount - 2
                            if (remainingRestoreCount > 0) {
                                payload[writeOffset++] = COMMAND_RESTORE_N
                                payload[writeOffset++] = 4 * Int.SIZE_BYTES
                                payload[writeOffset++] = COMMAND_RECORD_FLAGS_NONE
                                payload[writeOffset++] = remainingRestoreCount
                            }
                            decrementOp(COMMAND_SAVE_TRANSLATE_LAYER)
                            decrementRestoreCount(restoreCount)
                            if (remainingRestoreCount > 0) {
                                countOp(COMMAND_RESTORE_N)
                            }
                            readOffset = restoreStart + restoreLength
                            continue
                        }
                    }
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun fullImageRefAfterPassThroughRecords(startOffset: Int): Int? {
            var offset = startOffset
            while (offset < payloadSize) {
                val recordLength = payload[offset + 1] / Int.SIZE_BYTES
                if (recordLength < 3 || offset + recordLength > payloadSize) return null
                if (payload[offset] == COMMAND_DRAW_IMAGE_REF_FULL && recordLength == 9) {
                    return offset
                }
                if (!isTranslateScopePassThroughRecord(payload[offset])) {
                    return null
                }
                offset += recordLength
            }
            return null
        }

        private fun restoreCountOrZero(recordStart: Int): Int {
            if (recordStart >= payloadSize) return 0
            val recordLength = payload[recordStart + 1] / Int.SIZE_BYTES
            if (payload[recordStart] != COMMAND_RESTORE_N ||
                recordLength != 4 ||
                payload[recordStart + 2] != COMMAND_RECORD_FLAGS_NONE
            ) {
                return 0
            }
            return payload[recordStart + 3]
        }

        private fun canFoldFullImageRefOutOfTranslatedLayer(layerStart: Int, imageStart: Int): Boolean {
            if (payload[layerStart + 9] != 1000) return false
            val layerLeft1000 = payload[layerStart + 5] * 1000
            val layerTop1000 = payload[layerStart + 6] * 1000
            val layerRight1000 = layerLeft1000 + payload[layerStart + 7] * 1000
            val layerBottom1000 = layerTop1000 + payload[layerStart + 8] * 1000
            return payload[layerStart + 7] >= 0 &&
                payload[layerStart + 8] >= 0 &&
                payload[imageStart + 3] >= layerLeft1000 &&
                payload[imageStart + 4] >= layerTop1000 &&
                payload[imageStart + 5] <= layerRight1000 &&
                payload[imageStart + 6] <= layerBottom1000
        }

        private fun decrementRestoreCount(restoreCount: Int) {
            decrementOp(COMMAND_RESTORE_N)
            if (restoreCount > 2) {
                countOp(COMMAND_RESTORE_N)
            }
        }

        private fun compactAdjacentImageRefFullRoundRectRecords() {
            var readOffset = 0
            var writeOffset = 0
            val allowDirectClearImageRoundRect = directClearImageRoundRectCandidateCount() >= 2
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                val nextOffset = readOffset + recordLength
                if (recordLength < 3 || nextOffset > payloadSize) return
                if (isImageRefRoundRectCompactionEnabled(0) &&
                    payload[readOffset] == COMMAND_CLEAR_RECT &&
                    recordLength == 7 &&
                    payload[readOffset + 2] == COMMAND_RECORD_FLAGS_NONE &&
                    nextOffset < payloadSize
                ) {
                    var imageOffset = nextOffset
                    while (imageOffset < payloadSize &&
                        payload[imageOffset] == COMMAND_DEFINE_IMAGE_BITMAP
                    ) {
                        val imageDefinitionLength = payload[imageOffset + 1] / Int.SIZE_BYTES
                        if (imageDefinitionLength < 3 || imageOffset + imageDefinitionLength > payloadSize) return
                        imageOffset += imageDefinitionLength
                    }
                    val foldedImageRoundRect = imageOffset < payloadSize &&
                        payload[imageOffset] == COMMAND_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT &&
                        payload[imageOffset + 1] == 22 * Int.SIZE_BYTES
                    val imageOffsetEnd = imageOffset + 9
                    val directImageRoundRect = imageOffsetEnd < payloadSize &&
                        payload[imageOffset] == COMMAND_DRAW_IMAGE_REF_FULL &&
                        payload[imageOffset + 1] == 9 * Int.SIZE_BYTES &&
                        payload[imageOffsetEnd] == COMMAND_DRAW_ROUND_RECT &&
                        payload[imageOffsetEnd + 1] == 15 * Int.SIZE_BYTES
                    val imageHasAlpha =
                        if (foldedImageRoundRect) {
                            imageRefHasAlpha(imageOffset + 8)
                        } else if (directImageRoundRect) {
                            imageRefHasAlpha(imageOffset + 7)
                        } else {
                            true
                        }
                    if (imageHasAlpha ||
                        !foldedImageRoundRect && !(allowDirectClearImageRoundRect && directImageRoundRect)
                    ) {
                        if (writeOffset != readOffset) {
                            payload.copyInto(
                                payload,
                                destinationOffset = writeOffset,
                                startIndex = readOffset,
                                endIndex = readOffset + recordLength,
                            )
                        }
                        writeOffset += recordLength
                        readOffset += recordLength
                        continue
                    }
                    val clearX = payload[readOffset + 3]
                    val clearY = payload[readOffset + 4]
                    val clearWidth = payload[readOffset + 5]
                    val clearHeight = payload[readOffset + 6]
                    if (imageOffset > nextOffset) {
                        payload.copyInto(
                            payload,
                            destinationOffset = writeOffset,
                            startIndex = nextOffset,
                            endIndex = imageOffset,
                        )
                        writeOffset += imageOffset - nextOffset
                    }
                    payload[writeOffset++] = COMMAND_CLEAR_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT
                    payload[writeOffset++] = 26 * Int.SIZE_BYTES
                    payload[writeOffset++] =
                        if (foldedImageRoundRect) payload[imageOffset + 2] else payload[imageOffsetEnd + 2]
                    payload[writeOffset++] = clearX
                    payload[writeOffset++] = clearY
                    payload[writeOffset++] = clearWidth
                    payload[writeOffset++] = clearHeight
                    if (foldedImageRoundRect) {
                        payload.copyInto(
                            payload,
                            destinationOffset = writeOffset,
                            startIndex = imageOffset + 3,
                            endIndex = imageOffset + 22,
                        )
                        writeOffset += 19
                    } else {
                        payload[writeOffset++] = payload[imageOffset + 2]
                        payload.copyInto(
                            payload,
                            destinationOffset = writeOffset,
                            startIndex = imageOffset + 3,
                            endIndex = imageOffset + 9,
                        )
                        writeOffset += 6
                        payload.copyInto(
                            payload,
                            destinationOffset = writeOffset,
                            startIndex = imageOffsetEnd + 3,
                            endIndex = imageOffsetEnd + 15,
                        )
                        writeOffset += 12
                    }
                    decrementOp(COMMAND_CLEAR_RECT)
                    if (foldedImageRoundRect) {
                        decrementOp(COMMAND_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT)
                    } else {
                        decrementOp(COMMAND_DRAW_IMAGE_REF_FULL)
                        decrementOp(COMMAND_DRAW_ROUND_RECT)
                    }
                    countOp(COMMAND_CLEAR_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT)
                    readOffset = if (foldedImageRoundRect) imageOffset + 22 else imageOffsetEnd + 15
                    continue
                }
                if (isImageRefRoundRectCompactionEnabled(1) &&
                    payload[readOffset] == COMMAND_DRAW_IMAGE_REF_FULL &&
                    recordLength == 9 &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_DRAW_ROUND_RECT &&
                    payload[nextOffset + 1] == 15 * Int.SIZE_BYTES
                ) {
                    payload[writeOffset++] = COMMAND_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT
                    payload[writeOffset++] = 22 * Int.SIZE_BYTES
                    payload[writeOffset++] = payload[nextOffset + 2]
                    payload[writeOffset++] = payload[readOffset + 2]
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset + 3,
                        endIndex = readOffset + 9,
                    )
                    writeOffset += 6
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = nextOffset + 3,
                        endIndex = nextOffset + 15,
                    )
                    writeOffset += 12
                    decrementOp(COMMAND_DRAW_IMAGE_REF_FULL)
                    decrementOp(COMMAND_DRAW_ROUND_RECT)
                    countOp(COMMAND_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT)
                    readOffset = nextOffset + 15
                    continue
                }
                if (payload[readOffset] == COMMAND_DRAW_IMAGE_REF_FULL &&
                    recordLength == 9 &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_FILL_RECT &&
                    payload[nextOffset + 1] == 9 * Int.SIZE_BYTES
                ) {
                    val imageFlags = payload[readOffset + 2]
                    val imageArgs = payload.copyOfRange(readOffset + 3, readOffset + 9)
                    payload[writeOffset++] = COMMAND_DRAW_IMAGE_REF_FULL_FILL_RECT
                    payload[writeOffset++] = 16 * Int.SIZE_BYTES
                    payload[writeOffset++] = payload[nextOffset + 2]
                    payload[writeOffset++] = imageFlags
                    imageArgs.copyInto(payload, destinationOffset = writeOffset)
                    writeOffset += 6
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = nextOffset + 3,
                        endIndex = nextOffset + 9,
                    )
                    writeOffset += 6
                    decrementOp(COMMAND_DRAW_IMAGE_REF_FULL)
                    decrementOp(COMMAND_FILL_RECT)
                    countOp(COMMAND_DRAW_IMAGE_REF_FULL_FILL_RECT)
                    readOffset = nextOffset + 9
                    continue
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun imageRefHasAlpha(keyHighIndex: Int): Boolean {
            if (keyHighIndex + 1 >= payloadSize) return true
            return imageAlphaByCacheKey[cacheKey(payload[keyHighIndex], payload[keyHighIndex + 1])] ?: true
        }

        private fun cacheKey(high: Int, low: Int): Long =
            (high.toLong() shl Int.SIZE_BITS) or (low.toLong() and 0xffffffffL)

        private fun directClearImageRoundRectCandidateCount(): Int {
            var offset = 0
            var count = 0
            while (offset < payloadSize) {
                val recordLength = payload[offset + 1] / Int.SIZE_BYTES
                val nextOffset = offset + recordLength
                if (recordLength < 3 || nextOffset > payloadSize) return count
                if (payload[offset] == COMMAND_CLEAR_RECT &&
                    recordLength == 7 &&
                    payload[offset + 2] == COMMAND_RECORD_FLAGS_NONE
                ) {
                    var imageOffset = nextOffset
                    while (imageOffset < payloadSize &&
                        payload[imageOffset] == COMMAND_DEFINE_IMAGE_BITMAP
                    ) {
                        val imageDefinitionLength = payload[imageOffset + 1] / Int.SIZE_BYTES
                        if (imageDefinitionLength < 3 ||
                            imageOffset + imageDefinitionLength > payloadSize
                        ) {
                            return count
                        }
                        imageOffset += imageDefinitionLength
                    }
                    val imageRecordEnd = imageOffset + 9
                    if (imageRecordEnd <= payloadSize &&
                        payload[imageOffset] == COMMAND_DRAW_IMAGE_REF_FULL &&
                        payload[imageOffset + 1] == 9 * Int.SIZE_BYTES &&
                        !imageRefHasAlpha(imageOffset + 7)
                    ) {
                        val roundRectEnd = imageRecordEnd + 15
                        if (roundRectEnd <= payloadSize &&
                            payload[imageRecordEnd] == COMMAND_DRAW_ROUND_RECT &&
                            payload[imageRecordEnd + 1] == 15 * Int.SIZE_BYTES
                        ) {
                            count++
                        }
                    }
                }
                offset = nextOffset
            }
            return count
        }

        private fun compactAdjacentStrokeLineImageRefFullRunRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = validRecordLengthAt(readOffset) ?: return
                val nextOffset = readOffset + recordLength
                if (payload[readOffset] == COMMAND_STROKE_LINE &&
                    recordLength == 12
                ) {
                    var runOffset = nextOffset
                    while (runOffset < payloadSize && payload[runOffset] == COMMAND_DEFINE_IMAGE_BITMAP) {
                        val definitionLength = validRecordLengthAt(runOffset) ?: break
                        if (definitionLength != 11) break
                        runOffset += definitionLength
                    }
                    if (runOffset < payloadSize &&
                        payload[runOffset] == COMMAND_DRAW_IMAGE_REF_FULL_RUN
                    ) {
                        val runRecordLength = validRecordLengthAt(runOffset)
                        if (runRecordLength == null || runRecordLength < 16) {
                            if (writeOffset != readOffset) {
                                payload.copyInto(
                                    payload,
                                    destinationOffset = writeOffset,
                                    startIndex = readOffset,
                                    endIndex = nextOffset,
                                )
                            }
                            writeOffset += recordLength
                            readOffset = nextOffset
                            continue
                        }
                        val strokeArgs = payload.copyOfRange(readOffset + 3, nextOffset)
                        val runArgs = payload.copyOfRange(runOffset + 3, runOffset + runRecordLength)
                        if (nextOffset < runOffset) {
                            payload.copyInto(
                                payload,
                                destinationOffset = writeOffset,
                                startIndex = nextOffset,
                                endIndex = runOffset,
                            )
                            writeOffset += runOffset - nextOffset
                        }
                        payload[writeOffset++] = COMMAND_STROKE_LINE_DRAW_IMAGE_REF_FULL_RUN
                        payload[writeOffset++] = (recordLength + runRecordLength - 2) * Int.SIZE_BYTES
                        payload[writeOffset++] = payload[readOffset + 2]
                        payload[writeOffset++] = payload[runOffset + 2]
                        strokeArgs.copyInto(payload, destinationOffset = writeOffset)
                        writeOffset += strokeArgs.size
                        runArgs.copyInto(payload, destinationOffset = writeOffset)
                        writeOffset += runArgs.size
                        decrementOp(COMMAND_STROKE_LINE)
                        decrementOp(COMMAND_DRAW_IMAGE_REF_FULL_RUN)
                        countOp(COMMAND_STROKE_LINE_DRAW_IMAGE_REF_FULL_RUN)
                        readOffset = runOffset + runRecordLength
                        continue
                    }
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun compactAdjacentStrokeLineRunRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = validRecordLengthAt(readOffset) ?: return
                val nextOffset = readOffset + recordLength
                if (isStrokeLineRunCandidate(readOffset)) {
                    val recordFlags = payload[readOffset + 2]
                    val color = payload[readOffset + 3]
                    val strokeWidth = payload[readOffset + 8]
                    val strokeCap = payload[readOffset + 9]
                    val strokeJoin = payload[readOffset + 10]
                    val strokeMiter = payload[readOffset + 11]
                    var runCount = 1
                    var scanOffset = nextOffset
                    while (scanOffset < payloadSize &&
                        isStrokeLineRunCandidate(
                            scanOffset,
                            recordFlags,
                            color,
                            strokeWidth,
                            strokeCap,
                            strokeJoin,
                            strokeMiter,
                        )
                    ) {
                        runCount++
                        scanOffset += 12
                    }
                    if (runCount > 1) {
                        val firstX1 = payload[readOffset + 4]
                        val firstY1 = payload[readOffset + 5]
                        val firstX2 = payload[readOffset + 6]
                        val firstY2 = payload[readOffset + 7]
                        payload[writeOffset++] = COMMAND_STROKE_LINE_RUN
                        payload[writeOffset++] = (9 + runCount * 4) * Int.SIZE_BYTES
                        payload[writeOffset++] = recordFlags
                        payload[writeOffset++] = color
                        payload[writeOffset++] = strokeWidth
                        payload[writeOffset++] = strokeCap
                        payload[writeOffset++] = strokeJoin
                        payload[writeOffset++] = strokeMiter
                        payload[writeOffset++] = runCount
                        var lineOffset = readOffset
                        repeat(runCount) { index ->
                            if (index == 0) {
                                payload[writeOffset++] = firstX1
                                payload[writeOffset++] = firstY1
                                payload[writeOffset++] = firstX2
                                payload[writeOffset++] = firstY2
                            } else {
                                payload.copyInto(
                                    payload,
                                    destinationOffset = writeOffset,
                                    startIndex = lineOffset + 4,
                                    endIndex = lineOffset + 8,
                                )
                                writeOffset += 4
                            }
                            lineOffset += 12
                            decrementOp(COMMAND_STROKE_LINE)
                        }
                        countOp(COMMAND_STROKE_LINE_RUN)
                        readOffset = scanOffset
                        continue
                    }
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun isStrokeLineRunCandidate(recordStart: Int): Boolean {
            if (recordStart + 12 > payloadSize) return false
            return isStrokeLineRunCandidate(
                recordStart,
                payload[recordStart + 2],
                payload[recordStart + 3],
                payload[recordStart + 8],
                payload[recordStart + 9],
                payload[recordStart + 10],
                payload[recordStart + 11],
            )
        }

        private fun isStrokeLineRunCandidate(
            recordStart: Int,
            recordFlags: Int,
            color: Int,
            strokeWidth: Int,
            strokeCap: Int,
            strokeJoin: Int,
            strokeMiter: Int,
        ): Boolean =
            recordStart + 12 <= payloadSize &&
                payload[recordStart] == COMMAND_STROKE_LINE &&
                payload[recordStart + 1] == 12 * Int.SIZE_BYTES &&
                payload[recordStart + 2] == recordFlags &&
                (recordFlags == COMMAND_RECORD_FLAGS_NONE || recordFlags == COMMAND_RECORD_FLAG_ANTIALIAS) &&
                payload[recordStart + 3] == color &&
                payload[recordStart + 8] == strokeWidth &&
                payload[recordStart + 9] == strokeCap &&
                payload[recordStart + 10] == strokeJoin &&
                payload[recordStart + 11] == strokeMiter

        private fun compactAdjacentStrokeLineImageRefFullRunRestoreNRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val op = payload[readOffset]
                val recordLength = validRecordLengthAt(readOffset) ?: return
                val nextOffset = readOffset + recordLength
                val isRunRecord =
                    (op == COMMAND_STROKE_LINE_DRAW_IMAGE_REF_FULL_RUN && recordLength >= 26) ||
                        (op == COMMAND_STROKE_LINE_DRAW_IMAGE_REF_FULL_RUN_RESTORE_N && recordLength >= 27)
                if (isRunRecord &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_RESTORE_N &&
                    payload[nextOffset + 1] == 4 * Int.SIZE_BYTES &&
                    payload[nextOffset + 2] == COMMAND_RECORD_FLAGS_NONE &&
                    payload[nextOffset + 3] > 0
                ) {
                    val restoreCount =
                        payload[nextOffset + 3] +
                            if (op == COMMAND_STROKE_LINE_DRAW_IMAGE_REF_FULL_RUN_RESTORE_N) {
                                payload[readOffset + recordLength - 1]
                            } else {
                                0
                            }
                    val bodyEnd =
                        if (op == COMMAND_STROKE_LINE_DRAW_IMAGE_REF_FULL_RUN_RESTORE_N) {
                            readOffset + recordLength - 1
                        } else {
                            readOffset + recordLength
                        }
                    payload[writeOffset++] = COMMAND_STROKE_LINE_DRAW_IMAGE_REF_FULL_RUN_RESTORE_N
                    payload[writeOffset++] =
                        (recordLength +
                            if (op == COMMAND_STROKE_LINE_DRAW_IMAGE_REF_FULL_RUN_RESTORE_N) 0 else 1) *
                            Int.SIZE_BYTES
                    payload[writeOffset++] = payload[readOffset + 2]
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset + 3,
                        endIndex = bodyEnd,
                    )
                    writeOffset += bodyEnd - (readOffset + 3)
                    payload[writeOffset++] = restoreCount
                    decrementOp(op)
                    decrementOp(COMMAND_RESTORE_N)
                    countOp(COMMAND_STROKE_LINE_DRAW_IMAGE_REF_FULL_RUN_RESTORE_N)
                    readOffset = nextOffset + 4
                    continue
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun compactAdjacentSaveTranslateLayerSaveTranslateRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                val nextOffset = readOffset + recordLength
                if (payload[readOffset] == COMMAND_SAVE_TRANSLATE_LAYER &&
                    recordLength == 10 &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_SAVE_TRANSLATE
                ) {
                    val translateRecordLength = payload[nextOffset + 1] / Int.SIZE_BYTES
                    if (translateRecordLength == 5) {
                        payload[writeOffset++] = COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE
                        payload[writeOffset++] = 12 * Int.SIZE_BYTES
                        payload[writeOffset++] = payload[readOffset + 2]
                        payload.copyInto(
                            payload,
                            destinationOffset = writeOffset,
                            startIndex = readOffset + 3,
                            endIndex = nextOffset,
                        )
                        writeOffset += 7
                        payload.copyInto(
                            payload,
                            destinationOffset = writeOffset,
                            startIndex = nextOffset + 3,
                            endIndex = nextOffset + translateRecordLength,
                        )
                        writeOffset += 2
                        decrementOp(COMMAND_SAVE_TRANSLATE_LAYER)
                        decrementOp(COMMAND_SAVE_TRANSLATE)
                        countOp(COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE)
                        readOffset = nextOffset + translateRecordLength
                        continue
                    }
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun compactAdjacentFullImageRefRestoreNSaveTranslateLayerSaveTranslateRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                val nextOffset = readOffset + recordLength
                if (payload[readOffset] == COMMAND_DRAW_IMAGE_REF_FULL_RESTORE_N &&
                    recordLength == 10 &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE &&
                    payload[nextOffset + 1] == 12 * Int.SIZE_BYTES &&
                    payload[nextOffset + 2] == COMMAND_RECORD_FLAGS_NONE
                ) {
                    payload[writeOffset++] = COMMAND_DRAW_IMAGE_REF_FULL_RESTORE_N_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE
                    payload[writeOffset++] = 19 * Int.SIZE_BYTES
                    payload[writeOffset++] = payload[readOffset + 2]
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset + 3,
                        endIndex = readOffset + 10,
                    )
                    writeOffset += 7
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = nextOffset + 3,
                        endIndex = nextOffset + 12,
                    )
                    writeOffset += 9
                    decrementOp(COMMAND_DRAW_IMAGE_REF_FULL_RESTORE_N)
                    decrementOp(COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE)
                    countOp(COMMAND_DRAW_IMAGE_REF_FULL_RESTORE_N_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE)
                    readOffset = nextOffset + 12
                    continue
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun compactAdjacentSaveTranslateLayerSaveTranslateFullImageRefRestoreNRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                val nextOffset = readOffset + recordLength
                if (payload[readOffset] == COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE &&
                    recordLength == 12 &&
                    payload[readOffset + 2] == COMMAND_RECORD_FLAGS_NONE &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_DRAW_IMAGE_REF_FULL_RESTORE_N &&
                    payload[nextOffset + 1] == 10 * Int.SIZE_BYTES
                ) {
                    payload[writeOffset++] = COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE_DRAW_IMAGE_REF_FULL_RESTORE_N
                    payload[writeOffset++] = 19 * Int.SIZE_BYTES
                    payload[writeOffset++] = payload[nextOffset + 2]
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset + 3,
                        endIndex = readOffset + 12,
                    )
                    writeOffset += 9
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = nextOffset + 3,
                        endIndex = nextOffset + 10,
                    )
                    writeOffset += 7
                    decrementOp(COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE)
                    decrementOp(COMMAND_DRAW_IMAGE_REF_FULL_RESTORE_N)
                    countOp(COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE_DRAW_IMAGE_REF_FULL_RESTORE_N)
                    readOffset = nextOffset + 10
                    continue
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun compactAdjacentSaveTranslateLayerSaveTranslateFullImageRefRestoreNSaveTranslateLayerSaveTranslateRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                val nextOffset = readOffset + recordLength
                if (payload[readOffset] == COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE_DRAW_IMAGE_REF_FULL_RESTORE_N &&
                    recordLength == 19 &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE &&
                    payload[nextOffset + 1] == 12 * Int.SIZE_BYTES &&
                    payload[nextOffset + 2] == COMMAND_RECORD_FLAGS_NONE
                ) {
                    payload[writeOffset++] =
                        COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE_DRAW_IMAGE_REF_FULL_RESTORE_N_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE
                    payload[writeOffset++] = 28 * Int.SIZE_BYTES
                    payload[writeOffset++] = payload[readOffset + 2]
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset + 3,
                        endIndex = readOffset + 19,
                    )
                    writeOffset += 16
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = nextOffset + 3,
                        endIndex = nextOffset + 12,
                    )
                    writeOffset += 9
                    decrementOp(COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE_DRAW_IMAGE_REF_FULL_RESTORE_N)
                    decrementOp(COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE)
                    countOp(
                        COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE_DRAW_IMAGE_REF_FULL_RESTORE_N_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE
                    )
                    readOffset = nextOffset + 12
                    continue
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun compactAdjacentSaveLayerClipRectRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                val nextOffset = readOffset + recordLength
                if (payload[readOffset] == COMMAND_SAVE_LAYER &&
                    recordLength == 8 &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_CLIP_RECT &&
                    payload[nextOffset + 1] == 8 * Int.SIZE_BYTES
                ) {
                    payload[writeOffset++] = COMMAND_SAVE_LAYER_CLIP_RECT
                    payload[writeOffset++] = 13 * Int.SIZE_BYTES
                    payload[writeOffset++] = payload[nextOffset + 2]
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset + 3,
                        endIndex = readOffset + 8,
                    )
                    writeOffset += 5
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = nextOffset + 3,
                        endIndex = nextOffset + 8,
                    )
                    writeOffset += 5
                    decrementOp(COMMAND_SAVE_LAYER)
                    decrementOp(COMMAND_CLIP_RECT)
                    countOp(COMMAND_SAVE_LAYER_CLIP_RECT)
                    readOffset = nextOffset + 8
                    continue
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun compactAdjacentSaveLayerClipPathRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                val nextOffset = readOffset + recordLength
                if (payload[readOffset] == COMMAND_SAVE_LAYER &&
                    recordLength == 8 &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_CLIP_PATH
                ) {
                    val clipPathLength = payload[nextOffset + 1] / Int.SIZE_BYTES
                    val clipPathEnd = nextOffset + clipPathLength
                    if (clipPathLength < 6 || clipPathEnd > payloadSize) return
                    payload[writeOffset++] = COMMAND_SAVE_LAYER_CLIP_PATH
                    payload[writeOffset++] = (clipPathLength + 5) * Int.SIZE_BYTES
                    payload[writeOffset++] = payload[nextOffset + 2]
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset + 3,
                        endIndex = readOffset + 8,
                    )
                    writeOffset += 5
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = nextOffset + 3,
                        endIndex = clipPathEnd,
                    )
                    writeOffset += clipPathLength - 3
                    decrementOp(COMMAND_SAVE_LAYER)
                    decrementOp(COMMAND_CLIP_PATH)
                    countOp(COMMAND_SAVE_LAYER_CLIP_PATH)
                    readOffset = clipPathEnd
                    continue
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun compactAdjacentFillRectSaveLayerClipRectRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                val nextOffset = readOffset + recordLength
                if (payload[readOffset] == COMMAND_FILL_RECT &&
                    recordLength == 9 &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_SAVE_LAYER_CLIP_RECT &&
                    payload[nextOffset + 1] == 13 * Int.SIZE_BYTES
                ) {
                    payload[writeOffset++] = COMMAND_FILL_RECT_SAVE_LAYER_CLIP_RECT
                    payload[writeOffset++] = 20 * Int.SIZE_BYTES
                    payload[writeOffset++] = payload[readOffset + 2]
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset + 3,
                        endIndex = readOffset + 9,
                    )
                    writeOffset += 6
                    payload[writeOffset++] = payload[nextOffset + 2]
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = nextOffset + 3,
                        endIndex = nextOffset + 13,
                    )
                    writeOffset += 10
                    decrementOp(COMMAND_FILL_RECT)
                    decrementOp(COMMAND_SAVE_LAYER_CLIP_RECT)
                    countOp(COMMAND_FILL_RECT_SAVE_LAYER_CLIP_RECT)
                    readOffset = nextOffset + 13
                    continue
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun compactAdjacentFillRectSaveLayerClipRectSaveSaveLayerSaveTranslateRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                val nextOffset = readOffset + recordLength
                if (payload[readOffset] == COMMAND_FILL_RECT_SAVE_LAYER_CLIP_RECT &&
                    recordLength == 20 &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_SAVE_SAVE_LAYER_SAVE_TRANSLATE &&
                    payload[nextOffset + 1] == 10 * Int.SIZE_BYTES &&
                    payload[nextOffset + 2] == COMMAND_RECORD_FLAGS_NONE
                ) {
                    payload[writeOffset++] = COMMAND_FILL_RECT_SAVE_LAYER_CLIP_RECT_SAVE_SAVE_LAYER_SAVE_TRANSLATE
                    payload[writeOffset++] = 27 * Int.SIZE_BYTES
                    payload[writeOffset++] = payload[readOffset + 2]
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset + 3,
                        endIndex = readOffset + 20,
                    )
                    writeOffset += 17
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = nextOffset + 3,
                        endIndex = nextOffset + 10,
                    )
                    writeOffset += 7
                    decrementOp(COMMAND_FILL_RECT_SAVE_LAYER_CLIP_RECT)
                    decrementOp(COMMAND_SAVE_SAVE_LAYER_SAVE_TRANSLATE)
                    countOp(COMMAND_FILL_RECT_SAVE_LAYER_CLIP_RECT_SAVE_SAVE_LAYER_SAVE_TRANSLATE)
                    readOffset = nextOffset + 10
                    continue
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun compactAdjacentSaveTranslateRotateRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                val nextOffset = readOffset + recordLength
                if (payload[readOffset] == COMMAND_SAVE_TRANSLATE &&
                    recordLength == 5 &&
                    payload[readOffset + 2] == COMMAND_RECORD_FLAGS_NONE &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_ROTATE &&
                    payload[nextOffset + 1] == 4 * Int.SIZE_BYTES &&
                    payload[nextOffset + 2] == COMMAND_RECORD_FLAGS_NONE
                ) {
                    payload[writeOffset++] = COMMAND_SAVE_TRANSLATE_ROTATE
                    payload[writeOffset++] = 6 * Int.SIZE_BYTES
                    payload[writeOffset++] = COMMAND_RECORD_FLAGS_NONE
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset + 3,
                        endIndex = readOffset + 5,
                    )
                    writeOffset += 2
                    payload[writeOffset++] = payload[nextOffset + 3]
                    decrementOp(COMMAND_SAVE_TRANSLATE)
                    decrementOp(COMMAND_ROTATE)
                    countOp(COMMAND_SAVE_TRANSLATE_ROTATE)
                    readOffset = nextOffset + 4
                    continue
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun compactAdjacentSaveTranslateRotateTranslateFillOvalRestoreRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                val nextOffset = readOffset + recordLength
                val nextRecordLength =
                    if (nextOffset < payloadSize) payload[nextOffset + 1] / Int.SIZE_BYTES else 0
                val thirdOffset = nextOffset + nextRecordLength
                val thirdRecordLength =
                    if (thirdOffset < payloadSize) payload[thirdOffset + 1] / Int.SIZE_BYTES else 0
                val fourthOffset = thirdOffset + thirdRecordLength
                if (payload[readOffset] == COMMAND_SAVE_TRANSLATE_ROTATE &&
                    recordLength == 6 &&
                    payload[readOffset + 2] == COMMAND_RECORD_FLAGS_NONE &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_TRANSLATE &&
                    nextRecordLength == 5 &&
                    payload[nextOffset + 2] == COMMAND_RECORD_FLAGS_NONE &&
                    thirdOffset < payloadSize &&
                    payload[thirdOffset] == COMMAND_FILL_OVAL &&
                    thirdRecordLength == 8 &&
                    fourthOffset < payloadSize &&
                    payload[fourthOffset] == COMMAND_RESTORE &&
                    payload[fourthOffset + 1] == 3 * Int.SIZE_BYTES &&
                    payload[fourthOffset + 2] == COMMAND_RECORD_FLAGS_NONE
                ) {
                    payload[writeOffset++] = COMMAND_SAVE_TRANSLATE_ROTATE_TRANSLATE_FILL_OVAL_RESTORE
                    payload[writeOffset++] = 13 * Int.SIZE_BYTES
                    payload[writeOffset++] = payload[thirdOffset + 2]
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset + 3,
                        endIndex = readOffset + 6,
                    )
                    writeOffset += 3
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = nextOffset + 3,
                        endIndex = nextOffset + 5,
                    )
                    writeOffset += 2
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = thirdOffset + 3,
                        endIndex = thirdOffset + 8,
                    )
                    writeOffset += 5
                    decrementOp(COMMAND_SAVE_TRANSLATE_ROTATE)
                    decrementOp(COMMAND_TRANSLATE)
                    decrementOp(COMMAND_FILL_OVAL)
                    decrementOp(COMMAND_RESTORE)
                    countOp(COMMAND_SAVE_TRANSLATE_ROTATE_TRANSLATE_FILL_OVAL_RESTORE)
                    readOffset = fourthOffset + 3
                    continue
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun compactAdjacentSaveTranslateRotateTranslateFillOvalRestoreRuns() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                if (canAppendSaveTranslateRotateTranslateFillOvalRestoreRun(readOffset, payload[readOffset + 2])) {
                    val recordFlags = payload[readOffset + 2]
                    val runArgs = mutableListOf<IntArray>()
                    var runEnd = readOffset
                    while (runEnd < payloadSize &&
                        canAppendSaveTranslateRotateTranslateFillOvalRestoreRun(runEnd, recordFlags)
                    ) {
                        runArgs += payload.copyOfRange(
                            runEnd + 3,
                            runEnd + SAVE_TRANSLATE_ROTATE_TRANSLATE_FILL_OVAL_RESTORE_RECORD_INTS,
                        )
                        runEnd += SAVE_TRANSLATE_ROTATE_TRANSLATE_FILL_OVAL_RESTORE_RECORD_INTS
                    }
                    val runCount = runArgs.size
                    if (runCount > 1) {
                        payload[writeOffset++] = COMMAND_SAVE_TRANSLATE_ROTATE_TRANSLATE_FILL_OVAL_RESTORE_RUN
                        payload[writeOffset++] =
                            (4 + runCount * SAVE_TRANSLATE_ROTATE_TRANSLATE_FILL_OVAL_RESTORE_BODY_INTS) *
                            Int.SIZE_BYTES
                        payload[writeOffset++] = recordFlags
                        payload[writeOffset++] = runCount

                        runArgs.forEach { args ->
                            args.copyInto(payload, destinationOffset = writeOffset)
                            writeOffset += args.size
                            decrementOp(COMMAND_SAVE_TRANSLATE_ROTATE_TRANSLATE_FILL_OVAL_RESTORE)
                        }
                        countOp(COMMAND_SAVE_TRANSLATE_ROTATE_TRANSLATE_FILL_OVAL_RESTORE_RUN)
                        readOffset = runEnd
                        continue
                    }
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = readOffset + recordLength,
                    )
                }
                writeOffset += recordLength
                readOffset += recordLength
            }
            payloadSize = writeOffset
        }

        private fun canAppendSaveTranslateRotateTranslateFillOvalRestoreRun(recordStart: Int, recordFlags: Int): Boolean =
            recordStart + SAVE_TRANSLATE_ROTATE_TRANSLATE_FILL_OVAL_RESTORE_RECORD_INTS <= payloadSize &&
                payload[recordStart] == COMMAND_SAVE_TRANSLATE_ROTATE_TRANSLATE_FILL_OVAL_RESTORE &&
                payload[recordStart + 1] ==
                    SAVE_TRANSLATE_ROTATE_TRANSLATE_FILL_OVAL_RESTORE_RECORD_INTS * Int.SIZE_BYTES &&
                payload[recordStart + 2] == recordFlags

        private fun compactAdjacentSaveTranslateRotateTranslateStrokeClosedPolylineDeltaRestoreRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                val nextOffset = readOffset + recordLength
                val nextRecordLength =
                    if (nextOffset < payloadSize) payload[nextOffset + 1] / Int.SIZE_BYTES else 0
                val thirdOffset = nextOffset + nextRecordLength
                val thirdRecordLength =
                    if (thirdOffset < payloadSize) payload[thirdOffset + 1] / Int.SIZE_BYTES else 0
                val fourthOffset = thirdOffset + thirdRecordLength
                if (payload[readOffset] == COMMAND_SAVE_TRANSLATE_ROTATE &&
                    recordLength == 6 &&
                    payload[readOffset + 2] == COMMAND_RECORD_FLAGS_NONE &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_TRANSLATE &&
                    nextRecordLength == 5 &&
                    payload[nextOffset + 2] == COMMAND_RECORD_FLAGS_NONE &&
                    thirdOffset < payloadSize &&
                    payload[thirdOffset] == COMMAND_STROKE_CLOSED_POLYLINE_DELTA &&
                    thirdRecordLength >= 12 &&
                    fourthOffset < payloadSize &&
                    payload[fourthOffset] == COMMAND_RESTORE &&
                    payload[fourthOffset + 1] == 3 * Int.SIZE_BYTES &&
                    payload[fourthOffset + 2] == COMMAND_RECORD_FLAGS_NONE
                ) {
                    val pointCount = payload[thirdOffset + 8]
                    if (pointCount >= 2 && thirdRecordLength == 10 + pointCount) {
                        payload[writeOffset++] =
                            COMMAND_SAVE_TRANSLATE_ROTATE_TRANSLATE_STROKE_CLOSED_POLYLINE_DELTA_RESTORE
                        payload[writeOffset++] = (15 + pointCount) * Int.SIZE_BYTES
                        payload[writeOffset++] = payload[thirdOffset + 2]
                        payload.copyInto(
                            payload,
                            destinationOffset = writeOffset,
                            startIndex = readOffset + 3,
                            endIndex = readOffset + 6,
                        )
                        writeOffset += 3
                        payload.copyInto(
                            payload,
                            destinationOffset = writeOffset,
                            startIndex = nextOffset + 3,
                            endIndex = nextOffset + 5,
                        )
                        writeOffset += 2
                        payload.copyInto(
                            payload,
                            destinationOffset = writeOffset,
                            startIndex = thirdOffset + 3,
                            endIndex = thirdOffset + thirdRecordLength,
                        )
                        writeOffset += thirdRecordLength - 3
                        decrementOp(COMMAND_SAVE_TRANSLATE_ROTATE)
                        decrementOp(COMMAND_TRANSLATE)
                        decrementOp(COMMAND_STROKE_CLOSED_POLYLINE_DELTA)
                        decrementOp(COMMAND_RESTORE)
                        countOp(COMMAND_SAVE_TRANSLATE_ROTATE_TRANSLATE_STROKE_CLOSED_POLYLINE_DELTA_RESTORE)
                        readOffset = fourthOffset + 3
                        continue
                    }
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun compactAdjacentStrokeOvalRuns() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                if (canStartStrokeOvalRun(readOffset)) {
                    val recordFlags = payload[readOffset + 2]
                    val ovalArgs = mutableListOf<IntArray>()
                    ovalArgs += payload.copyOfRange(readOffset + 3, readOffset + 12)
                    var scanOffset = readOffset + recordLength
                    while (canAppendStrokeOvalRun(scanOffset, recordFlags)) {
                        ovalArgs += payload.copyOfRange(scanOffset + 3, scanOffset + 12)
                        scanOffset += payload[scanOffset + 1] / Int.SIZE_BYTES
                    }
                    val runCount = ovalArgs.size
                    if (runCount > 1) {
                        payload[writeOffset++] = COMMAND_STROKE_OVAL_RUN
                        payload[writeOffset++] = (4 + runCount * 9) * Int.SIZE_BYTES
                        payload[writeOffset++] = recordFlags
                        payload[writeOffset++] = runCount
                        ovalArgs.forEach { args ->
                            args.copyInto(payload, destinationOffset = writeOffset)
                            writeOffset += args.size
                        }
                        repeat(runCount) {
                            decrementOp(COMMAND_STROKE_OVAL)
                        }
                        countOp(COMMAND_STROKE_OVAL_RUN)
                        readOffset = scanOffset
                        continue
                    }
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = readOffset + recordLength,
                    )
                }
                writeOffset += recordLength
                readOffset += recordLength
            }
            payloadSize = writeOffset
        }

        private fun canStartStrokeOvalRun(recordStart: Int): Boolean =
            recordStart + 12 <= payloadSize &&
                canAppendStrokeOvalRun(
                    recordStart = recordStart,
                    recordFlags = payload[recordStart + 2],
                )

        private fun canAppendStrokeOvalRun(
            recordStart: Int,
            recordFlags: Int,
        ): Boolean =
            recordStart + 12 <= payloadSize &&
                payload[recordStart] == COMMAND_STROKE_OVAL &&
                payload[recordStart + 1] == 12 * Int.SIZE_BYTES &&
                payload[recordStart + 2] == recordFlags

        private fun compactAdjacentFillRectRuns() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                if (canAppendFillRectRun(readOffset, payload[readOffset + 2])) {
                    val recordFlags = payload[readOffset + 2]
                    val rectArgs = mutableListOf<IntArray>()
                    rectArgs += payload.copyOfRange(readOffset + 3, readOffset + 9)
                    var scanOffset = readOffset + recordLength
                    while (canAppendFillRectRun(scanOffset, recordFlags)) {
                        rectArgs += payload.copyOfRange(scanOffset + 3, scanOffset + 9)
                        scanOffset += payload[scanOffset + 1] / Int.SIZE_BYTES
                    }
                    val runCount = rectArgs.size
                    if (runCount > 1) {
                        payload[writeOffset++] = COMMAND_FILL_RECT_RUN
                        payload[writeOffset++] = (4 + runCount * 6) * Int.SIZE_BYTES
                        payload[writeOffset++] = recordFlags
                        payload[writeOffset++] = runCount
                        rectArgs.forEach { args ->
                            args.copyInto(payload, destinationOffset = writeOffset)
                            writeOffset += args.size
                        }
                        repeat(runCount) {
                            decrementOp(COMMAND_FILL_RECT)
                        }
                        countOp(COMMAND_FILL_RECT_RUN)
                        readOffset = scanOffset
                        continue
                    }
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = readOffset + recordLength,
                    )
                }
                writeOffset += recordLength
                readOffset += recordLength
            }
            payloadSize = writeOffset
        }

        private fun canAppendFillRectRun(recordStart: Int, recordFlags: Int): Boolean =
            recordStart + 9 <= payloadSize &&
                payload[recordStart] == COMMAND_FILL_RECT &&
                payload[recordStart + 1] == 9 * Int.SIZE_BYTES &&
                payload[recordStart + 2] == recordFlags &&
                (recordFlags == COMMAND_RECORD_FLAGS_NONE || recordFlags == COMMAND_RECORD_FLAG_ANTIALIAS)

        private fun compactAdjacentSaveLayerSaveTranslateRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                val nextOffset = readOffset + recordLength
                if (payload[readOffset] == COMMAND_SAVE_LAYER &&
                    recordLength == 8 &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_SAVE_TRANSLATE &&
                    payload[nextOffset + 1] == 5 * Int.SIZE_BYTES &&
                    payload[nextOffset + 2] == COMMAND_RECORD_FLAGS_NONE
                ) {
                    payload[writeOffset++] = COMMAND_SAVE_LAYER_SAVE_TRANSLATE
                    payload[writeOffset++] = 10 * Int.SIZE_BYTES
                    payload[writeOffset++] = COMMAND_RECORD_FLAGS_NONE
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset + 3,
                        endIndex = readOffset + 8,
                    )
                    writeOffset += 5
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = nextOffset + 3,
                        endIndex = nextOffset + 5,
                    )
                    writeOffset += 2
                    decrementOp(COMMAND_SAVE_LAYER)
                    decrementOp(COMMAND_SAVE_TRANSLATE)
                    countOp(COMMAND_SAVE_LAYER_SAVE_TRANSLATE)
                    readOffset = nextOffset + 5
                    continue
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun compactAdjacentSaveSaveLayerSaveTranslateRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                val nextOffset = readOffset + recordLength
                val nextLength = if (nextOffset < payloadSize) payload[nextOffset + 1] / Int.SIZE_BYTES else 0
                val thirdOffset = nextOffset + nextLength
                if (payload[readOffset] == COMMAND_SAVE &&
                    payload[readOffset + 1] == 3 * Int.SIZE_BYTES &&
                    payload[readOffset + 2] == COMMAND_RECORD_FLAGS_NONE &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_SAVE_LAYER &&
                    nextLength == 8 &&
                    thirdOffset < payloadSize &&
                    payload[thirdOffset] == COMMAND_SAVE_TRANSLATE &&
                    payload[thirdOffset + 1] == 5 * Int.SIZE_BYTES &&
                    payload[thirdOffset + 2] == COMMAND_RECORD_FLAGS_NONE
                ) {
                    payload[writeOffset++] = COMMAND_SAVE_SAVE_LAYER_SAVE_TRANSLATE
                    payload[writeOffset++] = 10 * Int.SIZE_BYTES
                    payload[writeOffset++] = COMMAND_RECORD_FLAGS_NONE
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = nextOffset + 3,
                        endIndex = nextOffset + 8,
                    )
                    writeOffset += 5
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = thirdOffset + 3,
                        endIndex = thirdOffset + 5,
                    )
                    writeOffset += 2
                    decrementOp(COMMAND_SAVE)
                    decrementOp(COMMAND_SAVE_LAYER)
                    decrementOp(COMMAND_SAVE_TRANSLATE)
                    countOp(COMMAND_SAVE_SAVE_LAYER_SAVE_TRANSLATE)
                    readOffset = thirdOffset + 5
                    continue
                }
                if (payload[readOffset] == COMMAND_SAVE &&
                    payload[readOffset + 1] == 3 * Int.SIZE_BYTES &&
                    payload[readOffset + 2] == COMMAND_RECORD_FLAGS_NONE &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_SAVE_LAYER_SAVE_TRANSLATE &&
                    payload[nextOffset + 1] == 10 * Int.SIZE_BYTES &&
                    payload[nextOffset + 2] == COMMAND_RECORD_FLAGS_NONE
                ) {
                    payload[writeOffset++] = COMMAND_SAVE_SAVE_LAYER_SAVE_TRANSLATE
                    payload[writeOffset++] = 10 * Int.SIZE_BYTES
                    payload[writeOffset++] = COMMAND_RECORD_FLAGS_NONE
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = nextOffset + 3,
                        endIndex = nextOffset + 10,
                    )
                    writeOffset += 7
                    decrementOp(COMMAND_SAVE)
                    decrementOp(COMMAND_SAVE_LAYER_SAVE_TRANSLATE)
                    countOp(COMMAND_SAVE_SAVE_LAYER_SAVE_TRANSLATE)
                    readOffset = nextOffset + 10
                    continue
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun compactAdjacentFullImageRefRestoreRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                val nextOffset = readOffset + recordLength
                if (payload[readOffset] == COMMAND_DRAW_IMAGE_REF_FULL &&
                    recordLength == 9 &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_RESTORE &&
                    payload[nextOffset + 1] == 3 * Int.SIZE_BYTES &&
                    payload[nextOffset + 2] == COMMAND_RECORD_FLAGS_NONE
                ) {
                    payload[writeOffset++] = COMMAND_DRAW_IMAGE_REF_FULL_RESTORE
                    payload[writeOffset++] = 9 * Int.SIZE_BYTES
                    payload[writeOffset++] = payload[readOffset + 2]
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset + 3,
                        endIndex = readOffset + 9,
                    )
                    writeOffset += 6
                    decrementOp(COMMAND_DRAW_IMAGE_REF_FULL)
                    decrementOp(COMMAND_RESTORE)
                    countOp(COMMAND_DRAW_IMAGE_REF_FULL_RESTORE)
                    readOffset = nextOffset + 3
                    continue
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun compactAdjacentFullImageRefRestoreNRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val op = payload[readOffset]
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                val nextOffset = readOffset + recordLength
                val isImageRestoreRecord =
                    ((op == COMMAND_DRAW_IMAGE_REF_FULL_RESTORE || op == COMMAND_DRAW_IMAGE_REF_FULL) &&
                        recordLength == 9) ||
                        op == COMMAND_DRAW_IMAGE_REF_FULL_RESTORE_N &&
                        recordLength == 10
                if (isImageRestoreRecord &&
                    nextOffset < payloadSize &&
                    ((payload[nextOffset] == COMMAND_RESTORE &&
                        payload[nextOffset + 1] == 3 * Int.SIZE_BYTES &&
                        payload[nextOffset + 2] == COMMAND_RECORD_FLAGS_NONE &&
                        (op == COMMAND_DRAW_IMAGE_REF_FULL_RESTORE ||
                            op == COMMAND_DRAW_IMAGE_REF_FULL_RESTORE_N)) ||
                        (payload[nextOffset] == COMMAND_RESTORE_N &&
                            payload[nextOffset + 1] == 4 * Int.SIZE_BYTES &&
                            payload[nextOffset + 2] == COMMAND_RECORD_FLAGS_NONE &&
                            payload[nextOffset + 3] > 0 &&
                            (op == COMMAND_DRAW_IMAGE_REF_FULL_RESTORE ||
                                op == COMMAND_DRAW_IMAGE_REF_FULL_RESTORE_N ||
                                payload[nextOffset + 3] > 1)))
                ) {
                    val extraRestoreCount = when (op) {
                        COMMAND_DRAW_IMAGE_REF_FULL_RESTORE_N ->
                            payload[readOffset + 9] + if (payload[nextOffset] == COMMAND_RESTORE) {
                                1
                            } else {
                                payload[nextOffset + 3]
                            }
                        COMMAND_DRAW_IMAGE_REF_FULL_RESTORE -> if (payload[nextOffset] == COMMAND_RESTORE) {
                            1
                        } else {
                            payload[nextOffset + 3]
                        }
                        else -> payload[nextOffset + 3] - 1
                    }
                    payload[writeOffset++] = COMMAND_DRAW_IMAGE_REF_FULL_RESTORE_N
                    payload[writeOffset++] = 10 * Int.SIZE_BYTES
                    payload[writeOffset++] = payload[readOffset + 2]
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset + 3,
                        endIndex = readOffset + 9,
                    )
                    writeOffset += 6
                    payload[writeOffset++] = extraRestoreCount
                    decrementOp(op)
                    decrementOp(payload[nextOffset])
                    countOp(COMMAND_DRAW_IMAGE_REF_FULL_RESTORE_N)
                    readOffset = nextOffset + if (payload[nextOffset] == COMMAND_RESTORE) 3 else 4
                    continue
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun compactAdjacentRoundRectRestoreNRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                val nextOffset = readOffset + recordLength
                val op = payload[readOffset]
                if (((op == COMMAND_DRAW_ROUND_RECT && recordLength == 15) ||
                    (op == COMMAND_DRAW_ROUND_RECT_RESTORE_N && recordLength == 16)) &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_RESTORE_N &&
                    payload[nextOffset + 1] == 4 * Int.SIZE_BYTES &&
                    payload[nextOffset + 2] == COMMAND_RECORD_FLAGS_NONE &&
                    payload[nextOffset + 3] > 0
                ) {
                    payload[writeOffset++] = COMMAND_DRAW_ROUND_RECT_RESTORE_N
                    payload[writeOffset++] = 16 * Int.SIZE_BYTES
                    payload[writeOffset++] = payload[readOffset + 2]
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset + 3,
                        endIndex = readOffset + 15,
                    )
                    writeOffset += 12
                    payload[writeOffset++] = if (op == COMMAND_DRAW_ROUND_RECT_RESTORE_N) {
                        payload[readOffset + 15] + payload[nextOffset + 3]
                    } else {
                        payload[nextOffset + 3]
                    }
                    decrementOp(op)
                    decrementOp(COMMAND_RESTORE_N)
                    countOp(COMMAND_DRAW_ROUND_RECT_RESTORE_N)
                    readOffset = nextOffset + 4
                    continue
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun compactAdjacentFillRectSaveRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                val nextOffset = readOffset + recordLength
                if (payload[readOffset] == COMMAND_FILL_RECT &&
                    recordLength == 9 &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_SAVE &&
                    payload[nextOffset + 1] == 3 * Int.SIZE_BYTES &&
                    payload[nextOffset + 2] == COMMAND_RECORD_FLAGS_NONE
                ) {
                    payload[writeOffset++] = COMMAND_FILL_RECT_SAVE
                    payload[writeOffset++] = 9 * Int.SIZE_BYTES
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset + 2,
                        endIndex = readOffset + 9,
                    )
                    writeOffset += 7
                    decrementOp(COMMAND_FILL_RECT)
                    decrementOp(COMMAND_SAVE)
                    countOp(COMMAND_FILL_RECT_SAVE)
                    readOffset = nextOffset + 3
                    continue
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun compactAdjacentSaveFillRectSaveRecords() {
            var readOffset = 0
            var writeOffset = 0
            while (readOffset < payloadSize) {
                val recordLength = payload[readOffset + 1] / Int.SIZE_BYTES
                val nextOffset = readOffset + recordLength
                if (payload[readOffset] == COMMAND_SAVE &&
                    recordLength == 3 &&
                    payload[readOffset + 2] == COMMAND_RECORD_FLAGS_NONE &&
                    nextOffset < payloadSize &&
                    payload[nextOffset] == COMMAND_FILL_RECT_SAVE &&
                    payload[nextOffset + 1] == 9 * Int.SIZE_BYTES
                ) {
                    payload[writeOffset++] = COMMAND_SAVE_FILL_RECT_SAVE
                    payload[writeOffset++] = 9 * Int.SIZE_BYTES
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = nextOffset + 2,
                        endIndex = nextOffset + 9,
                    )
                    writeOffset += 7
                    decrementOp(COMMAND_SAVE)
                    decrementOp(COMMAND_FILL_RECT_SAVE)
                    countOp(COMMAND_SAVE_FILL_RECT_SAVE)
                    readOffset = nextOffset + 9
                    continue
                }
                if (writeOffset != readOffset) {
                    payload.copyInto(
                        payload,
                        destinationOffset = writeOffset,
                        startIndex = readOffset,
                        endIndex = nextOffset,
                    )
                }
                writeOffset += recordLength
                readOffset = nextOffset
            }
            payloadSize = writeOffset
        }

        private fun addRecordHeader(op: Int, recordFlags: Int, argCount: Int) {
            ensureCapacity(payloadSize + argCount + 3)
            ensureRecordIndex()
            appendRecordStart(payloadSize)
            payload[payloadSize++] = op
            payload[payloadSize++] = (argCount + 3) * Int.SIZE_BYTES
            payload[payloadSize++] = recordFlags
        }

        private fun addAll(values: IntArray) {
            ensureCapacity(payloadSize + values.size)
            values.copyInto(payload, destinationOffset = payloadSize)
            payloadSize += values.size
        }

        private fun addValue(value: Int) {
            ensureCapacity(payloadSize + 1)
            payload[payloadSize++] = value
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
                COMMAND_FILL_RECT_SAVE -> "fillRectSave"
                COMMAND_SAVE_FILL_RECT_SAVE -> "saveFillRectSave"
                COMMAND_SAVE_LAYER_SAVE_TRANSLATE -> "saveLayerSaveTranslate"
                COMMAND_SAVE_SAVE_LAYER_SAVE_TRANSLATE -> "saveSaveLayerSaveTranslate"
                COMMAND_FILL_RECT_SAVE_LAYER_CLIP_RECT -> "fillRectSaveLayerClipRect"
                COMMAND_STROKE_LINE -> "strokeLine"
                COMMAND_FILL_OVAL -> "fillOval"
                COMMAND_STROKE_OVAL -> "strokeOval"
                COMMAND_STROKE_OVAL_RUN -> "strokeOvalRun"
                COMMAND_FILL_RECT_RUN -> "fillRectRun"
                COMMAND_CLEAR_RECT -> "clearRect"
                COMMAND_SAVE -> "save"
                COMMAND_RESTORE -> "restore"
                COMMAND_CLIP_RECT -> "clipRect"
                COMMAND_TRANSLATE -> "translate"
                COMMAND_SAVE_TRANSLATE -> "saveTranslate"
                COMMAND_RESTORE_N -> "restoreN"
                COMMAND_SAVE_TRANSLATE_LAYER -> "saveTranslateLayer"
                COMMAND_DRAW_IMAGE_REF_FULL -> "drawImageRefFull"
                COMMAND_DRAW_IMAGE_REF_FULL_RUN -> "drawImageRefFullRun"
                COMMAND_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT -> "drawImageRefFullDrawRoundRect"
                COMMAND_CLEAR_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT -> "clearDrawImageRefFullDrawRoundRect"
                COMMAND_STROKE_LINE_RUN -> "strokeLineRun"
                COMMAND_SAVE_LAYER_CLIP_RECT -> "saveLayerClipRect"
                COMMAND_SAVE_LAYER_CLIP_PATH -> "saveLayerClipPath"
                COMMAND_DRAW_IMAGE_REF_FULL_FILL_RECT -> "drawImageRefFullFillRect"
                COMMAND_STROKE_LINE_DRAW_IMAGE_REF_FULL_RUN -> "strokeLineDrawImageRefFullRun"
                COMMAND_STROKE_LINE_DRAW_IMAGE_REF_FULL_RUN_RESTORE_N -> "strokeLineDrawImageRefFullRunRestoreN"
                COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE -> "saveTranslateLayerSaveTranslate"
                COMMAND_DRAW_IMAGE_REF_FULL_RESTORE_N_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE ->
                    "drawImageRefFullRestoreNSaveTranslateLayerSaveTranslate"
                COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE_DRAW_IMAGE_REF_FULL_RESTORE_N ->
                    "saveTranslateLayerSaveTranslateDrawImageRefFullRestoreN"
                COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE_DRAW_IMAGE_REF_FULL_RESTORE_N_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE ->
                    "saveTranslateLayerSaveTranslateDrawImageRefFullRestoreNSaveTranslateLayerSaveTranslate"
                COMMAND_FILL_RECT_SAVE_LAYER_CLIP_RECT_SAVE_SAVE_LAYER_SAVE_TRANSLATE ->
                    "fillRectSaveLayerClipRectSaveSaveLayerSaveTranslate"
                COMMAND_SAVE_TRANSLATE_ROTATE -> "saveTranslateRotate"
                COMMAND_SAVE_TRANSLATE_ROTATE_TRANSLATE_FILL_OVAL_RESTORE ->
                    "saveTranslateRotateTranslateFillOvalRestore"
                COMMAND_SAVE_TRANSLATE_ROTATE_TRANSLATE_FILL_OVAL_RESTORE_RUN ->
                    "saveTranslateRotateTranslateFillOvalRestoreRun"
                COMMAND_SAVE_TRANSLATE_ROTATE_TRANSLATE_STROKE_CLOSED_POLYLINE_DELTA_RESTORE ->
                    "saveTranslateRotateTranslateStrokeClosedPolylineDeltaRestore"
                COMMAND_STROKE_CLOSED_POLYLINE -> "strokeClosedPolyline"
                COMMAND_STROKE_CLOSED_POLYLINE_DELTA -> "strokeClosedPolylineDelta"
                COMMAND_DRAW_IMAGE_REF_FULL_RESTORE -> "drawImageRefFullRestore"
                COMMAND_DRAW_IMAGE_REF_FULL_RESTORE_N -> "drawImageRefFullRestoreN"
                COMMAND_DRAW_ROUND_RECT_RESTORE_N -> "drawRoundRectRestoreN"
                COMMAND_FILL_ROUND_RECT -> "fillRoundRect"
                COMMAND_CLEAR_DRAW_IMAGE_REF_FULL -> "clearDrawImageRefFull"
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
    private const val COMMAND_CLEAR_DRAW_IMAGE_REF_FULL = 79
    private const val COMMAND_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT = 80
    private const val COMMAND_DRAW_IMAGE_REF_FULL_RUN = 81
    private const val COMMAND_SAVE_LAYER_CLIP_RECT = 82
    private const val COMMAND_DRAW_IMAGE_REF_FULL_FILL_RECT = 83
    private const val COMMAND_STROKE_LINE_DRAW_IMAGE_REF_FULL_RUN = 84
    private const val COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE = 85
    private const val COMMAND_DRAW_IMAGE_REF_FULL_RESTORE = 86
    private const val COMMAND_DRAW_IMAGE_REF_FULL_RESTORE_N = 87
    private const val COMMAND_DRAW_ROUND_RECT_RESTORE_N = 88
    private const val COMMAND_STROKE_LINE_DRAW_IMAGE_REF_FULL_RUN_RESTORE_N = 89
    private const val COMMAND_DRAW_IMAGE_REF_FULL_RESTORE_N_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE = 90
    private const val COMMAND_FILL_RECT_SAVE = 91
    private const val COMMAND_SAVE_FILL_RECT_SAVE = 92
    private const val COMMAND_SAVE_LAYER_SAVE_TRANSLATE = 93
    private const val COMMAND_SAVE_SAVE_LAYER_SAVE_TRANSLATE = 94
    private const val COMMAND_FILL_RECT_SAVE_LAYER_CLIP_RECT = 95
    private const val COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE_DRAW_IMAGE_REF_FULL_RESTORE_N = 96
    private const val COMMAND_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE_DRAW_IMAGE_REF_FULL_RESTORE_N_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE = 97
    private const val COMMAND_FILL_RECT_SAVE_LAYER_CLIP_RECT_SAVE_SAVE_LAYER_SAVE_TRANSLATE = 98
    private const val COMMAND_SAVE_TRANSLATE_ROTATE = 99
    private const val COMMAND_SAVE_TRANSLATE_ROTATE_TRANSLATE_FILL_OVAL_RESTORE = 100
    private const val COMMAND_STROKE_CLOSED_POLYLINE = 101
    private const val COMMAND_STROKE_CLOSED_POLYLINE_DELTA = 102
    private const val COMMAND_STROKE_OVAL_RUN = 103
    private const val COMMAND_SAVE_TRANSLATE_ROTATE_TRANSLATE_FILL_OVAL_RESTORE_RUN = 106
    private const val COMMAND_SAVE_TRANSLATE_ROTATE_TRANSLATE_STROKE_CLOSED_POLYLINE_DELTA_RESTORE = 107
    private const val COMMAND_FILL_RECT_RUN = 108
    private const val COMMAND_CLEAR_DRAW_IMAGE_REF_FULL_DRAW_ROUND_RECT = 109
    private const val COMMAND_STROKE_LINE_RUN = 110
    private const val COMMAND_SAVE_LAYER_CLIP_PATH = 111
    private const val OP_COUNT_CAPACITY = 128
    private const val SAVE_TRANSLATE_ROTATE_TRANSLATE_FILL_OVAL_RESTORE_RECORD_INTS = 13
    private const val SAVE_TRANSLATE_ROTATE_TRANSLATE_FILL_OVAL_RESTORE_BODY_INTS = 10
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
    private const val DEFAULT_NATIVE_BITMAP_CONTENT_KEY_PIXELS = 1_048_576
    private const val MAX_PATH_DATA_INTS = 4096
    private const val PATH_FILL_TYPE_NON_ZERO = 0
    private const val PATH_FILL_TYPE_EVEN_ODD = 1
    private const val PATH_VERB_MOVE = 0
    private const val PATH_VERB_LINE = 1
    private const val PATH_VERB_QUAD = 2
    private const val PATH_VERB_CUBIC = 3
    private const val PATH_VERB_CLOSE = 4

    private fun nativeBitmapContentKeyPixels(): Int =
        java.lang.Integer.getInteger(
            NATIVE_BITMAP_CONTENT_KEY_PIXELS_PROPERTY,
            DEFAULT_NATIVE_BITMAP_CONTENT_KEY_PIXELS,
        ).coerceAtLeast(0)

    private fun IntArray.closedPolylinePoints(): IntArray? {
        if (size < 7 || this[0] != PATH_VERB_MOVE || this[size - 1] != PATH_VERB_CLOSE) {
            return null
        }
        val lineCount = (size - 4) / 3
        if (lineCount <= 0 || size != 4 + lineCount * 3) {
            return null
        }
        val points = IntArray((lineCount + 1) * 2)
        points[0] = this[1]
        points[1] = this[2]
        var sourceOffset = 3
        var pointOffset = 2
        repeat(lineCount) {
            if (this[sourceOffset] != PATH_VERB_LINE) {
                return null
            }
            points[pointOffset++] = this[sourceOffset + 1]
            points[pointOffset++] = this[sourceOffset + 2]
            sourceOffset += 3
        }
        if (sourceOffset != size - 1) {
            return null
        }
        val lastPointOffset = points.size - 2
        return if (points.size > 4 && points[lastPointOffset] == points[0] && points[lastPointOffset + 1] == points[1]) {
            points.copyOf(points.size - 2)
        } else {
            points
        }
    }

    private fun IntArray.packedShortDeltas(): IntArray? {
        val pointCount = size / 2
        if (pointCount < 2 || size != pointCount * 2) {
            return null
        }
        val deltas = IntArray(pointCount - 1)
        var previousX = this[0]
        var previousY = this[1]
        var sourceOffset = 2
        for (index in deltas.indices) {
            val x = this[sourceOffset++]
            val y = this[sourceOffset++]
            val dx = x - previousX
            val dy = y - previousY
            if (dx < Short.MIN_VALUE || dx > Short.MAX_VALUE || dy < Short.MIN_VALUE || dy > Short.MAX_VALUE) {
                return null
            }
            deltas[index] = ((dx and 0xffff) shl 16) or (dy and 0xffff)
            previousX = x
            previousY = y
        }
        return deltas
    }
}
