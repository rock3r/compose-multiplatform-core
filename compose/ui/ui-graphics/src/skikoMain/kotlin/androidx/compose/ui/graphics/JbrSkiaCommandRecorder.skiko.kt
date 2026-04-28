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
import kotlin.math.roundToInt

object JbrSkiaCommandRecorder {
    private const val STRICT_PROPERTY = "compose.jbr.skia.command.strict"
    private val active = ThreadLocal<Recorder?>()

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

    internal fun saveLayer() {
        active.get()?.saveLayer()
    }

    internal fun translate(dx: Float, dy: Float) {
        active.get()?.translate(dx, dy)
    }

    internal fun scale(sx: Float, sy: Float) {
        active.get()?.scale(sx, sy)
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

    internal fun clipRect(left: Float, top: Float, right: Float, bottom: Float, clipOp: ClipOp) {
        active.get()?.clipRect(left, top, right, bottom, clipOp)
    }

    internal fun clipPath() {
        active.get()?.clipPath()
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

    private class Recorder {
        private val commands = CommandStreamWriter()
        private val stack = ArrayDeque<State>()
        private val unsupportedReasons = linkedMapOf<String, Int>()
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
                "CMP_JBR_COMMAND_RECORDER_FRAME commands=${commands.streamSize} unsupported=$unsupported$suffix"
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

        fun saveLayer() {
            countUnsupported("saveLayer")
            save()
            state = state.copy(supported = false)
        }

        fun translate(dx: Float, dy: Float) {
            state = state.copy(
                translateX = state.translateX + dx * state.scaleX,
                translateY = state.translateY + dy * state.scaleY,
            )
        }

        fun scale(sx: Float, sy: Float) {
            state = state.copy(scaleX = state.scaleX * sx, scaleY = state.scaleY * sy)
        }

        fun unsupportedTransform() {
            countUnsupported("transform")
            state = state.copy(supported = false)
        }

        fun unsupportedDraw(reason: String) {
            countUnsupported(reason)
        }

        fun clipRect(left: Float, top: Float, right: Float, bottom: Float, clipOp: ClipOp) {
            if (clipOp != ClipOp.Intersect) {
                countUnsupported("clipRect_${clipOp.toReasonToken()}")
                state = state.copy(supported = false)
                return
            }
            commands.addCommand(
                COMMAND_CLIP_RECT,
                COMMAND_RECORD_FLAG_ANTIALIAS,
                state.x(left),
                state.y(top),
                state.width(right - left),
                state.height(bottom - top),
            )
        }

        fun clipPath() {
            countUnsupported("clipPath")
            state = state.copy(supported = false)
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
            )
        }

        fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            if (paint.blendMode == BlendMode.Clear) {
                addClearRect(left, top, right, bottom)
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
                    commands.addCommand(COMMAND_STROKE_LINE, recordFlags, paint.commandColor(), x, y, x + width, y, stroke)
                    commands.addCommand(COMMAND_STROKE_LINE, recordFlags, paint.commandColor(), x + width, y, x + width, y + height, stroke)
                    commands.addCommand(COMMAND_STROKE_LINE, recordFlags, paint.commandColor(), x + width, y + height, x, y + height, stroke)
                    commands.addCommand(COMMAND_STROKE_LINE, recordFlags, paint.commandColor(), x, y + height, x, y, stroke)
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
            if (!paint.isSupportedSolidColor) return
            if (paint.style == PaintingStyle.Stroke) {
                drawRect(left, top, right, bottom, paint)
                return
            }
            if (paint.style != PaintingStyle.Fill) {
                countUnsupported("roundRectStyle")
                return
            }
            commands.addCommand(
                COMMAND_FILL_RECT,
                paint.recordFlags(),
                paint.commandColor(),
                state.x(left),
                state.y(top),
                state.width(right - left),
                state.height(bottom - top),
                ((radiusX + radiusY) / 2f * state.averageScale).roundToInt().coerceAtLeast(0),
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
            commands.addCommand(
                op,
                paint.recordFlags(),
                paint.commandColor(),
                state.x(left),
                state.y(top),
                state.width(right - left),
                state.height(bottom - top),
                *if (paint.style == PaintingStyle.Stroke) {
                    intArrayOf(state.stroke(paint.strokeWidth))
                } else {
                    intArrayOf()
                },
            )
        }

        fun drawCircle(center: Offset, radius: Float, paint: Paint) {
            drawOval(center.x - radius, center.y - radius, center.x + radius, center.y + radius, paint)
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

        private fun Paint.commandColor(): Int =
            color.copy(alpha = color.alpha * alpha).toArgb()

        private fun Paint.recordFlags(): Int =
            if (isAntiAlias) COMMAND_RECORD_FLAG_ANTIALIAS else COMMAND_RECORD_FLAGS_NONE

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
        val translateX: Float = 0f,
        val translateY: Float = 0f,
        val scaleX: Float = 1f,
        val scaleY: Float = 1f,
        val supported: Boolean = true,
    ) {
        val averageScale: Float get() = (kotlin.math.abs(scaleX) + kotlin.math.abs(scaleY)) / 2f

        fun x(value: Float): Int = (translateX + value * scaleX).roundToInt()
        fun y(value: Float): Int = (translateY + value * scaleY).roundToInt()
        fun width(value: Float): Int = (value * scaleX).roundToInt().coerceAtLeast(0)
        fun height(value: Float): Int = (value * scaleY).roundToInt().coerceAtLeast(0)
        fun stroke(value: Float): Int = (value * averageScale).roundToInt().coerceAtLeast(1)
    }

    private const val COMMAND_FILL_RECT = 2
    private const val COMMAND_STROKE_LINE = 3
    private const val COMMAND_FILL_OVAL = 4
    private const val COMMAND_STROKE_OVAL = 5
    private const val COMMAND_CLEAR_RECT = 6
    private const val COMMAND_SAVE = 7
    private const val COMMAND_RESTORE = 8
    private const val COMMAND_CLIP_RECT = 9
    private const val COMMAND_STREAM_MAGIC = 1246972723
    private const val COMMAND_STREAM_ABI_ID = 8
    private const val COMMAND_STREAM_HEADER_SIZE = 6
    private const val COMMAND_STREAM_FLAGS_NONE = 0
    private const val COMMAND_COORDINATE_SPACE_SWING_USER = 1
    private const val COMMAND_PAINT_FORMAT_SOLID_ARGB = 1
    private const val COMMAND_RECORD_FLAGS_NONE = 0
    private const val COMMAND_RECORD_FLAG_ANTIALIAS = 1
}
