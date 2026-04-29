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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

object JbrSkiaCommandRecorder {
    private const val STRICT_PROPERTY = "compose.jbr.skia.command.strict"
    private const val MAX_DEFINED_IMAGE_KEYS = 256
    private val active = ThreadLocal<Recorder?>()
    private val definedImageKeys = ConcurrentHashMap.newKeySet<Long>()

    fun record(block: () -> Unit): IntArray? {
        val previous = active.get()
        val recorder = Recorder()
        active.set(recorder)
        try {
            block()
            return recorder.toCommandArray()
        } finally {
            recorder.logFrame()
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

    internal fun unsupportedTransform() {
        active.get()?.unsupportedTransform()
    }

    internal fun unsupportedDraw(reason: String) {
        active.get()?.unsupportedDraw(reason)
    }

    fun markUnsupportedDraw(reason: String) {
        active.get()?.unsupportedDraw(reason)
    }

    internal fun clearImageCacheForTesting() {
        definedImageKeys.clear()
    }

    fun drawTextUtf16(
        text: String,
        x: Float,
        baseline: Float,
        fontSize: Float,
        color: Int,
        antiAlias: Boolean,
    ): Boolean =
        active.get()?.drawTextUtf16(text, x, baseline, fontSize, color, antiAlias) ?: false

    fun drawParagraphUtf16(
        text: String,
        x: Float,
        y: Float,
        width: Float,
        fontSize: Float,
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

    private class Recorder {
        private val commands = CommandStreamWriter()
        private val stack = ArrayDeque<State>()
        private val unsupportedReasons = linkedMapOf<String, Int>()
        private var imageDefineCount = 0
        private var imageRefCount = 0
        private var textCommandCount = 0
        private var paragraphTextCommandCount = 0
        private var imageCacheClearCount = 0
        private var state = State()

        fun toCommandArray(): IntArray? =
            if (java.lang.Boolean.getBoolean(STRICT_PROPERTY) && unsupportedCount > 0) {
                null
            } else {
                commandStream()
            }

        fun logFrame() {
            val unsupported = unsupportedCount
            val reasons = unsupportedReasons.entries.joinToString(separator = " ") { (reason, count) ->
                "$reason=$count"
            }
            val suffix = if (reasons.isEmpty()) "" else " $reasons"
            System.err.println(
                "CMP_JBR_COMMAND_RECORDER_FRAME commands=${commands.streamSize} unsupported=$unsupported" +
                    " textCommands=$textCommandCount paragraphTextCommands=$paragraphTextCommandCount" +
                    " imageDefines=$imageDefineCount imageRefs=$imageRefCount" +
                    " imageCacheClears=$imageCacheClearCount$suffix"
            )
        }

        fun save() {
            commands.addCommand(COMMAND_SAVE)
            stack.addLast(state)
        }

        fun restore() {
            commands.addCommand(COMMAND_RESTORE)
            state = stack.removeLastOrNull() ?: State()
        }

        fun saveLayer(bounds: Rect, paint: Paint) {
            if (!paint.isSupportedLayerPaint) {
                countUnsupported("saveLayer")
                save()
                state = state.copy(supported = false)
                return
            }
            commands.addCommand(
                COMMAND_SAVE_LAYER,
                COMMAND_RECORD_FLAGS_NONE,
                state.x(bounds.left),
                state.y(bounds.top),
                state.width(bounds.width),
                state.height(bounds.height),
                paint.layerAlpha1000(),
            )
        }

        fun translate(dx: Float, dy: Float) {
            commands.addCommand(COMMAND_TRANSLATE, COMMAND_RECORD_FLAGS_NONE, dx.fixed1000(), dy.fixed1000())
        }

        fun scale(sx: Float, sy: Float) {
            commands.addCommand(COMMAND_SCALE, COMMAND_RECORD_FLAGS_NONE, sx.fixed1000(), sy.fixed1000())
        }

        fun rotate(degrees: Float) {
            if (degrees != 0f) {
                commands.addCommand(COMMAND_ROTATE, COMMAND_RECORD_FLAGS_NONE, degrees.fixed1000())
            }
        }

        fun unsupportedTransform() {
            countUnsupported("transform")
            state = state.copy(supported = false)
        }

        fun unsupportedDraw(reason: String) {
            countUnsupported(reason)
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
            if (!paint.isSupportedSolidColor) return
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

        fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            if (paint.blendMode == BlendMode.Clear) {
                addClearRect(left, top, right, bottom)
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
            if (!paint.isSupportedSolidColor) return
            val x = state.x(left)
            val y = state.y(top)
            val width = state.width(right - left)
            val height = state.height(bottom - top)
            when (paint.style) {
                PaintingStyle.Fill -> commands.addCommand(COMMAND_FILL_RECT, paint.recordFlags(), paint.commandColor(), x, y, width, height, 0)
                PaintingStyle.Stroke -> {
                    val stroke = state.stroke(paint.strokeWidth)
                    val recordFlags = paint.recordFlags()
                    val strokeArgs = intArrayOf(paint.strokeCap.commandValue(), paint.strokeJoin.commandValue(), paint.strokeMiter1000())
                    commands.addCommand(COMMAND_STROKE_LINE, recordFlags, paint.commandColor(), x, y, x + width, y, stroke, *strokeArgs)
                    commands.addCommand(COMMAND_STROKE_LINE, recordFlags, paint.commandColor(), x + width, y, x + width, y + height, stroke, *strokeArgs)
                    commands.addCommand(COMMAND_STROKE_LINE, recordFlags, paint.commandColor(), x + width, y + height, x, y + height, stroke, *strokeArgs)
                    commands.addCommand(COMMAND_STROKE_LINE, recordFlags, paint.commandColor(), x, y + height, x, y, stroke, *strokeArgs)
                }
                else -> countUnsupported("paintStyle")
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
            if (!paint.isSupportedSolidColor) return
            val style = when (paint.style) {
                PaintingStyle.Fill -> COMMAND_PAINT_STYLE_FILL
                PaintingStyle.Stroke -> COMMAND_PAINT_STYLE_STROKE
                else -> {
                    countUnsupported("roundRectStyle")
                    return
                }
            }
            commands.addCommand(
                COMMAND_DRAW_ROUND_RECT,
                paint.recordFlags(),
                style,
                paint.commandColor(),
                left.fixed1000(),
                top.fixed1000(),
                right.fixed1000(),
                bottom.fixed1000(),
                radiusX.fixed1000().coerceAtLeast(0),
                radiusY.fixed1000().coerceAtLeast(0),
                if (paint.style == PaintingStyle.Stroke) state.stroke(paint.strokeWidth) else 0,
                if (paint.style == PaintingStyle.Stroke) paint.strokeCap.commandValue() else 0,
                if (paint.style == PaintingStyle.Stroke) paint.strokeJoin.commandValue() else 0,
                if (paint.style == PaintingStyle.Stroke) paint.strokeMiter1000() else 0,
            )
        }

        fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            if (paint.blendMode == BlendMode.Clear) {
                addClearRect(left, top, right, bottom)
                return
            }
            if (!paint.isSupportedSolidColor) return
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
            if (!paint.isSupportedSolidColor) return
            val style = when (paint.style) {
                PaintingStyle.Fill -> COMMAND_PAINT_STYLE_FILL
                PaintingStyle.Stroke -> COMMAND_PAINT_STYLE_STROKE
                else -> {
                    countUnsupported("paintStyle")
                    return
                }
            }
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

        fun drawPath(path: Path, paint: Paint) {
            if (paint.shader?.jbrSkiaLinearGradient != null) {
                addLinearGradientPath(path, paint)
                return
            }
            if (paint.shader?.jbrSkiaRadialGradient != null) {
                addRadialGradientPath(path, paint)
                return
            }
            if (!paint.isSupportedSolidColor) return
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
            commands.addCommand(
                COMMAND_DRAW_PATH,
                paint.recordFlags(),
                style,
                paint.commandColor(),
                if (paint.style == PaintingStyle.Stroke) state.stroke(paint.strokeWidth) else 0,
                if (paint.style == PaintingStyle.Stroke) paint.strokeCap.commandValue() else 0,
                if (paint.style == PaintingStyle.Stroke) paint.strokeJoin.commandValue() else 0,
                if (paint.style == PaintingStyle.Stroke) paint.strokeMiter1000() else 0,
                path.fillType.commandValue(),
                pathData.size,
                *pathData,
            )
        }

        private fun addLinearGradientPath(path: Path, paint: Paint) {
            val gradientPayload = paint.linearGradientPayload() ?: return
            if (paint.style != PaintingStyle.Fill) {
                countUnsupported("linearGradientPathPaint")
                return
            }
            val pathData = path.commandData() ?: run {
                countUnsupported("linearGradientPath")
                return
            }
            commands.addCommand(
                COMMAND_FILL_PATH_LINEAR_GRADIENT,
                paint.recordFlags(),
                path.fillType.commandValue(),
                pathData.size,
                *pathData,
                *gradientPayload,
            )
        }

        private fun addRadialGradientPath(path: Path, paint: Paint) {
            val gradientPayload = paint.radialGradientPayload() ?: return
            if (paint.style != PaintingStyle.Fill) {
                countUnsupported("radialGradientPathPaint")
                return
            }
            val pathData = path.commandData() ?: run {
                countUnsupported("radialGradientPath")
                return
            }
            commands.addCommand(
                COMMAND_FILL_PATH_RADIAL_GRADIENT,
                paint.recordFlags(),
                path.fillType.commandValue(),
                pathData.size,
                *pathData,
                *gradientPayload,
            )
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
            if (!paint.isSupportedImagePaint || image.width <= 0 || image.height <= 0 || image.width > 2048 || image.height > 2048) {
                return false
            }
            val pixels = IntArray(image.width * image.height)
            image.readPixels(pixels)
            val cacheKey = pixels.imageCacheKey(image.width, image.height)
            if (!definedImageKeys.contains(cacheKey) && definedImageKeys.size >= MAX_DEFINED_IMAGE_KEYS) {
                definedImageKeys.clear()
                imageCacheClearCount++
                commands.addCommand(COMMAND_CLEAR_IMAGE_CACHE)
            }
            if (definedImageKeys.add(cacheKey)) {
                imageDefineCount++
                commands.addCommand(
                    COMMAND_DEFINE_IMAGE_ARGB,
                    COMMAND_RECORD_FLAGS_NONE,
                    cacheKey.highInt(),
                    cacheKey.lowInt(),
                    image.width,
                    image.height,
                    pixels.size,
                    *pixels,
                )
            }
            imageRefCount++
            commands.addCommand(
                COMMAND_DRAW_IMAGE_REF,
                paint.recordFlags(),
                srcLeft.fixed1000(),
                srcTop.fixed1000(),
                srcRight.fixed1000(),
                srcBottom.fixed1000(),
                dstLeft.fixed1000(),
                dstTop.fixed1000(),
                dstRight.fixed1000(),
                dstBottom.fixed1000(),
                cacheKey.highInt(),
                cacheKey.lowInt(),
                image.width,
                image.height,
                paint.imageAlpha1000(),
                paint.filterQuality.value,
            )
            return true
        }

        fun drawParagraphUtf16(
            text: String,
            x: Float,
            y: Float,
            width: Float,
            fontSize: Float,
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
            if (text.isEmpty() || text.length > 4096 ||
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
            color: Int,
            antiAlias: Boolean,
        ): Boolean {
            if (text.isEmpty() || text.length > 4096 || !fontSize.isFinite() || fontSize <= 0f) {
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
                text.length,
                *IntArray(text.length) { text[it].code },
            )
            return true
        }

        private val Paint.isSupportedSolidColor: Boolean
            get() {
                var supported = true
                if (!state.supported) {
                    countUnsupported("unsupportedScope")
                    supported = false
                }
                if (blendMode != BlendMode.SrcOver) {
                    countUnsupported("blendMode_${blendMode.toReasonToken()}")
                    supported = false
                }
                if (shader != null) {
                    countUnsupported("shader")
                    supported = false
                }
                if (colorFilter != null) {
                    countUnsupported("colorFilter")
                    supported = false
                }
                if (pathEffect != null) {
                    countUnsupported("pathEffect")
                    supported = false
                }
                return supported
            }

        private val Paint.isSupportedLayerPaint: Boolean
            get() =
                blendMode == BlendMode.SrcOver &&
                    shader == null &&
                    colorFilter == null &&
                    pathEffect == null

        private val Paint.isSupportedImagePaint: Boolean
            get() =
                state.supported &&
                    blendMode == BlendMode.SrcOver &&
                    shader == null &&
                    colorFilter == null &&
                    pathEffect == null

        private val Paint.isSupportedLinearGradient: Boolean
            get() {
                var supported = true
                if (!state.supported) {
                    countUnsupported("unsupportedScope")
                    supported = false
                }
                if (blendMode != BlendMode.SrcOver) {
                    countUnsupported("blendMode_${blendMode.toReasonToken()}")
                    supported = false
                }
                if (shader?.jbrSkiaLinearGradient == null && shader?.jbrSkiaRadialGradient == null) {
                    countUnsupported("shader")
                    supported = false
                }
                if (colorFilter != null) {
                    countUnsupported("colorFilter")
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
                    PathSegment.Type.Close -> data.add(PATH_VERB_CLOSE)
                    PathSegment.Type.Conic,
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

        private fun Long.highInt(): Int = (this ushr 32).toInt()

        private fun Long.lowInt(): Int = this.toInt()

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
            val gradientPayload = paint.linearGradientPayload() ?: return
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

        private fun addRadialGradientRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            val gradientPayload = paint.radialGradientPayload() ?: return
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

        private fun addRadialGradientRoundRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radiusX: Float,
            radiusY: Float,
            paint: Paint,
        ) {
            val gradientPayload = paint.radialGradientPayload() ?: return
            if (radiusX < 0f || radiusY < 0f) {
                countUnsupported("radialGradientRoundRectRadius")
                return
            }
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

        private fun addLinearGradientRoundRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radiusX: Float,
            radiusY: Float,
            paint: Paint,
        ) {
            val gradientPayload = paint.linearGradientPayload() ?: return
            if (radiusX < 0f || radiusY < 0f) {
                countUnsupported("linearGradientRoundRectRadius")
                return
            }
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

        private fun Paint.linearGradientPayload(): IntArray? {
            val gradient = shader?.jbrSkiaLinearGradient ?: return null
            if (!isSupportedLinearGradient || style != PaintingStyle.Fill) {
                countUnsupported("linearGradientPaint")
                return null
            }
            if (gradient.colors.size !in 2..16) {
                countUnsupported("linearGradientColorCount")
                return null
            }
            val stops = gradient.colorStops ?: evenlyDistributedStops(gradient.colors.size)
            if (stops.size != gradient.colors.size || stops.any { !it.isFinite() || it < 0f || it > 1f }) {
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

        private fun Paint.radialGradientPayload(): IntArray? {
            val gradient = shader?.jbrSkiaRadialGradient ?: return null
            if (!isSupportedLinearGradient || style != PaintingStyle.Fill) {
                countUnsupported("radialGradientPaint")
                return null
            }
            if (gradient.colors.size !in 2..16) {
                countUnsupported("radialGradientColorCount")
                return null
            }
            val stops = gradient.colorStops ?: evenlyDistributedStops(gradient.colors.size)
            if (stops.size != gradient.colors.size || stops.any { !it.isFinite() || it < 0f || it > 1f }) {
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

        private fun evenlyDistributedStops(count: Int): List<Float> =
            List(count) { index -> index.toFloat() / (count - 1).toFloat() }

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
        private val payload = ArrayList<Int>(1024)

        val streamSize: Int
            get() = COMMAND_STREAM_HEADER_SIZE + payload.size

        fun addCommand(op: Int, recordFlags: Int = COMMAND_RECORD_FLAGS_NONE, vararg args: Int) {
            payload.add(op)
            payload.add((args.size + 3) * Int.SIZE_BYTES)
            payload.add(recordFlags)
            args.forEach(payload::add)
        }

        fun toIntArray(): IntArray =
            IntArray(streamSize).also { stream ->
                stream[0] = COMMAND_STREAM_MAGIC
                stream[1] = COMMAND_STREAM_ABI_ID
                stream[2] = COMMAND_STREAM_FLAGS_NONE
                stream[3] = payload.size
                stream[4] = COMMAND_COORDINATE_SPACE_SWING_USER
                stream[5] = COMMAND_PAINT_FORMAT_SOLID_ARGB
                payload.forEachIndexed { index, command -> stream[COMMAND_STREAM_HEADER_SIZE + index] = command }
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
    private const val COMMAND_STREAM_MAGIC = 1246972723
    private const val COMMAND_STREAM_ABI_ID = 36
    private const val COMMAND_STREAM_HEADER_SIZE = 6
    private const val COMMAND_STREAM_FLAGS_NONE = 0
    private const val COMMAND_COORDINATE_SPACE_SWING_USER = 1
    private const val COMMAND_PAINT_FORMAT_SOLID_ARGB = 1
    private const val COMMAND_RECORD_FLAGS_NONE = 0
    private const val COMMAND_RECORD_FLAG_ANTIALIAS = 1
    private const val COMMAND_PAINT_STYLE_FILL = 0
    private const val COMMAND_PAINT_STYLE_STROKE = 1
    private const val MAX_PATH_DATA_INTS = 4096
    private const val PATH_FILL_TYPE_NON_ZERO = 0
    private const val PATH_FILL_TYPE_EVEN_ODD = 1
    private const val PATH_VERB_MOVE = 0
    private const val PATH_VERB_LINE = 1
    private const val PATH_VERB_QUAD = 2
    private const val PATH_VERB_CUBIC = 3
    private const val PATH_VERB_CLOSE = 4
}
