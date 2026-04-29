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

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JbrSkiaCommandRecorderTest {
    @Test
    fun writesAntialiasRecordFlag() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 22f,
                paint = Paint().apply {
                    color = Color.Red
                    isAntiAlias = true
                },
            )
            JbrSkiaCommandRecorder.drawRect(
                left = 3f,
                top = 4f,
                right = 13f,
                bottom = 24f,
                paint = Paint().apply {
                    color = Color.Blue
                    isAntiAlias = false
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 26, 0, 18, 1, 1,
                2, 36, 1, Color.Red.toArgb(), 1, 2, 10, 20, 0,
                2, 36, 0, Color.Blue.toArgb(), 3, 4, 10, 20, 0,
            ),
            commands,
        )
    }

    @Test
    fun writesStrokeMetadata() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawLine(
                p1 = androidx.compose.ui.geometry.Offset(1f, 2f),
                p2 = androidx.compose.ui.geometry.Offset(11f, 12f),
                paint = Paint().apply {
                    color = Color.White
                    strokeWidth = 3f
                    strokeCap = StrokeCap.Round
                    strokeJoin = StrokeJoin.Bevel
                    strokeMiterLimit = 4.5f
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 26, 0, 12, 1, 1,
                3, 48, 1, Color.White.toArgb(), 1, 2, 11, 12, 3, 1, 2, 4500,
            ),
            commands,
        )
    }

    @Test
    fun writesBasicTransformRecords() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.save()
            JbrSkiaCommandRecorder.translate(1.25f, 2.5f)
            JbrSkiaCommandRecorder.scale(1.5f, 0.5f)
            JbrSkiaCommandRecorder.rotate(18f)
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 12f,
                paint = Paint().apply { color = Color.Red },
            )
            JbrSkiaCommandRecorder.restore()
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 26, 0, 29, 1, 1,
                7, 12, 0,
                10, 20, 0, 1250, 2500,
                11, 20, 0, 1500, 500,
                12, 16, 0, 18000,
                2, 36, 1, Color.Red.toArgb(), 1, 2, 10, 10, 0,
                8, 12, 0,
            ),
            commands,
        )
    }

    @Test
    fun writesClipRectOp() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.clipRect(1f, 2f, 11f, 12f, ClipOp.Intersect)
            JbrSkiaCommandRecorder.clipRect(3f, 4f, 13f, 14f, ClipOp.Difference)
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 26, 0, 16, 1, 1,
                9, 32, 1, 1, 2, 10, 10, 0,
                9, 32, 1, 3, 4, 10, 10, 1,
            ),
            commands,
        )
    }

    @Test
    fun writesClipPathRecord() {
        val path = Path().apply {
            moveTo(1f, 2f)
            lineTo(11f, 12f)
            lineTo(21f, 2f)
            close()
        }

        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.clipPath(path, ClipOp.Intersect)
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 26, 0, 19, 1, 1,
                20, 76, 1, 0, 0, 13,
                0, 1000, 2000,
                1, 11000, 12000,
                1, 21000, 2000,
                1, 1000, 2000,
                4,
            ),
            commands,
        )
    }

    @Test
    fun writesSaveLayerRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.saveLayer(
                bounds = Rect(1f, 2f, 11f, 12f),
                paint = Paint().apply { color = Color.White.copy(alpha = 0.6f) },
            )
            JbrSkiaCommandRecorder.restore()
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 26, 0, 11, 1, 1,
                13, 32, 0, 1, 2, 10, 10, 360,
                8, 12, 0,
            ),
            commands,
        )
    }

    @Test
    fun writesImageArgbRecord() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val image = ImageBitmap(2, 2)
        Canvas(image).run {
            drawRect(0f, 0f, 1f, 1f, Paint().apply { color = Color.Red })
            drawRect(1f, 0f, 2f, 1f, Paint().apply { color = Color.Green })
            drawRect(0f, 1f, 1f, 2f, Paint().apply { color = Color.Blue })
            drawRect(1f, 1f, 2f, 2f, Paint().apply { color = Color.White })
        }

        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawImageRect(
                image = image,
                srcLeft = 0f,
                srcTop = 0f,
                srcRight = 2f,
                srcBottom = 2f,
                dstLeft = 10f,
                dstTop = 20f,
                dstRight = 30f,
                dstBottom = 40f,
                paint = Paint().apply {
                    color = Color.White
                    alpha = 0.5f
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 26, 0, 29, 1, 1,
                15, 48, 0, -1599677274, -472603669, 2, 2, 4,
                Color.Red.toArgb(), Color.Green.toArgb(), Color.Blue.toArgb(), Color.White.toArgb(),
                16, 68, 1,
                0, 0, 2000, 2000,
                10000, 20000, 30000, 40000,
                -1599677274, -472603669, 2, 2, 502, FilterQuality.Medium.value,
            ),
            commands,
        )
    }

    @Test
    fun clearsImageCacheBeforeRedefiningAfterThreshold() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()

        val commands = JbrSkiaCommandRecorder.record {
            repeat(257) { index ->
                val image = ImageBitmap(1, 1)
                Canvas(image).drawRect(0f, 0f, 1f, 1f, Paint().apply {
                    color = Color(index or 0xff000000.toInt())
                })
                JbrSkiaCommandRecorder.drawImageRect(
                    image = image,
                    srcLeft = 0f,
                    srcTop = 0f,
                    srcRight = 1f,
                    srcBottom = 1f,
                    dstLeft = 0f,
                    dstTop = 0f,
                    dstRight = 1f,
                    dstBottom = 1f,
                    paint = Paint(),
                )
            }
        }!!

        assertTrue(commands.toList().windowed(3).any { it == listOf(18, 12, 0) })
    }

    @Test
    fun writesSimpleTextRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawTextUtf16(
                text = "Hi",
                x = 1.25f,
                baseline = 18.5f,
                fontSize = 13f,
                color = Color.White.toArgb(),
                antiAlias = true,
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 26, 0, 10, 1, 1,
                17, 40, 1, 1250, 18500, 13000, Color.White.toArgb(), 2, 'H'.code, 'i'.code,
            ),
            commands,
        )
    }

    @Test
    fun writesLatin1TextRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawTextUtf16(
                text = "Caf\u00e9",
                x = 1.25f,
                baseline = 18.5f,
                fontSize = 13f,
                color = Color.White.toArgb(),
                antiAlias = true,
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 26, 0, 12, 1, 1,
                17, 48, 1, 1250, 18500, 13000, Color.White.toArgb(), 4,
                'C'.code, 'a'.code, 'f'.code, '\u00e9'.code,
            ),
            commands,
        )
    }

    @Test
    fun writesParagraphTextRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawParagraphUtf16(
                text = "Hi \uD83D\uDE80",
                x = 1.25f,
                y = 2.5f,
                width = 120f,
                fontSize = 13f,
                color = Color.White.toArgb(),
                fontWeight = 700,
                fontWidth = 5,
                fontSlant = 1,
                textAlign = 2,
                textDirection = 1,
                lineHeightMultiplier1000 = 1500,
                maxLines = 1,
                ellipsisMode = 1,
                decorationMask = 3,
                letterSpacing1000 = 2500,
                backgroundSpecified = 1,
                backgroundArgb = Color.Red.toArgb(),
                antiAlias = true,
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 26, 0, 26, 1, 1,
                19, 104, 1, 1250, 2500, 120000, 13000, Color.White.toArgb(), 700, 5, 1, 2, 1, 1500, 1, 1, 3, 2500, 1, Color.Red.toArgb(), 5,
                'H'.code, 'i'.code, ' '.code, 0xd83d, 0xde80,
            ),
            commands,
        )
    }
}
