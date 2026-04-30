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
import androidx.compose.ui.geometry.RoundRect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
                1246972723, 77, 0, 18, 1, 1,
                2, 36, 1, Color.Red.toArgb(), 1, 2, 10, 20, 0,
                2, 36, 0, Color.Blue.toArgb(), 3, 4, 10, 20, 0,
            ),
            commands,
        )
    }

    @Test
    fun recordsFrameMetadata() {
        val recording = JbrSkiaCommandRecorder.recordFrame {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 22f,
                paint = Paint().apply {
                    color = Color.Red
                },
            )
            JbrSkiaCommandRecorder.drawTextUtf16(
                text = "Hi",
                x = 1.25f,
                baseline = 18.5f,
                fontSize = 13f,
                fontFamily = "Inter",
                color = Color.White.toArgb(),
                antiAlias = true,
            )
        }

        assertEquals(recording.commands!!.size, recording.commandWordCount)
        assertEquals(0, recording.unsupportedCount)
        assertEquals(0, recording.imageDefineCount)
        assertEquals(0, recording.imageRefCount)
        assertEquals(1, recording.textCommandCount)
        assertEquals(0, recording.paragraphTextCommandCount)
        assertEquals(0, recording.imageCacheClearCount)
        assertEquals(0, recording.imageCacheEvictCount)
    }

    @Test
    fun nestedRecordingReplaysAtLayerDrawSite() {
        val recording = JbrSkiaCommandRecorder.recordFrame {
            val nested = JbrSkiaCommandRecorder.recordNested {
                JbrSkiaCommandRecorder.drawRect(
                    left = 1f,
                    top = 2f,
                    right = 11f,
                    bottom = 22f,
                    paint = Paint().apply {
                        color = Color.Red
                    },
                )
            }
            JbrSkiaCommandRecorder.drawRect(
                left = 50f,
                top = 60f,
                right = 70f,
                bottom = 80f,
                paint = Paint().apply {
                    color = Color.Blue
                },
            )

            assertTrue(
                JbrSkiaCommandRecorder.replayRecordedLayer(
                    recording = nested,
                    left = 100f,
                    top = 200f,
                    width = 30f,
                    height = 40f,
                    pivotX = 15f,
                    pivotY = 20f,
                    alpha = 0.5f,
                    scaleX = 1f,
                    scaleY = 1f,
                    rotationZ = -4f,
                    translationX = 5f,
                    translationY = 6f,
                    clipRect = null,
                    clipPath = null,
                    blendMode = null,
                )
            )
        }

        val records = recording.commands!!.commandRecords()
        assertEquals(2, records[0][0])
        assertEquals(Color.Blue.toArgb(), records[0][3])
        assertEquals(7, records[1][0])
        assertEquals(10, records[2][0])
        assertEquals(105000, records[2][3])
        assertEquals(206000, records[2][4])
        assertEquals(13, records[7][0])
        assertEquals(500, records[7][7])
        assertEquals(2, records[8][0])
        assertEquals(Color.Red.toArgb(), records[8][3])
        assertEquals(8, records[9][0])
        assertEquals(8, records[10][0])
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun nestedRecordingReplaysLayerClipPath() {
        val clipPath = Path().apply {
            moveTo(1f, 2f)
            lineTo(11f, 12f)
            lineTo(21f, 2f)
            close()
        }
        val recording = JbrSkiaCommandRecorder.recordFrame {
            val nested = JbrSkiaCommandRecorder.recordNested {
                JbrSkiaCommandRecorder.drawRect(
                    left = 1f,
                    top = 2f,
                    right = 11f,
                    bottom = 22f,
                    paint = Paint().apply {
                        color = Color.Red
                    },
                )
            }

            assertTrue(
                JbrSkiaCommandRecorder.replayRecordedLayer(
                    recording = nested,
                    left = 100f,
                    top = 200f,
                    width = 30f,
                    height = 40f,
                    pivotX = 15f,
                    pivotY = 20f,
                    alpha = 0.5f,
                    scaleX = 1f,
                    scaleY = 1f,
                    rotationZ = 0f,
                    translationX = 0f,
                    translationY = 0f,
                    clipRect = null,
                    clipPath = clipPath,
                    blendMode = null,
                )
            )
        }

        val records = recording.commands!!.commandRecords()
        val saveLayerIndex = records.indexOfFirst { it[0] == 13 }
        val clipPathIndex = records.indexOfFirst { it[0] == 20 }
        val drawRectIndex = records.indexOfFirst { it[0] == 2 && it[3] == Color.Red.toArgb() }
        assertTrue(saveLayerIndex >= 0)
        assertTrue(clipPathIndex > saveLayerIndex)
        assertTrue(drawRectIndex > clipPathIndex)
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun nestedRecordingReplaysLayerBlendMode() {
        val recording = JbrSkiaCommandRecorder.recordFrame {
            val nested = JbrSkiaCommandRecorder.recordNested {
                JbrSkiaCommandRecorder.drawRect(
                    left = 1f,
                    top = 2f,
                    right = 11f,
                    bottom = 22f,
                    paint = Paint().apply {
                        color = Color.Red
                    },
                )
            }

            assertTrue(
                JbrSkiaCommandRecorder.replayRecordedLayer(
                    recording = nested,
                    left = 100f,
                    top = 200f,
                    width = 30f,
                    height = 40f,
                    pivotX = 15f,
                    pivotY = 20f,
                    alpha = 0.5f,
                    scaleX = 1f,
                    scaleY = 1f,
                    rotationZ = 0f,
                    translationX = 0f,
                    translationY = 0f,
                    clipRect = null,
                    clipPath = null,
                    blendMode = JbrSkiaCommandRecorder.commandBlendModeOrNull(BlendMode.Plus),
                )
            )
        }

        val records = recording.commands!!.commandRecords()
        val saveLayerBlendIndex = records.indexOfFirst { it[0] == 50 }
        val drawRectIndex = records.indexOfFirst { it[0] == 2 && it[3] == Color.Red.toArgb() }
        assertTrue(saveLayerBlendIndex >= 0)
        assertTrue(drawRectIndex > saveLayerBlendIndex)
        assertEquals(1, records[saveLayerBlendIndex][8])
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun nestedRecordingReplaysLayerTintColorFilter() {
        val recording = JbrSkiaCommandRecorder.recordFrame {
            val nested = JbrSkiaCommandRecorder.recordNested {
                JbrSkiaCommandRecorder.drawRect(
                    left = 1f,
                    top = 2f,
                    right = 11f,
                    bottom = 22f,
                    paint = Paint().apply {
                        color = Color.Red
                    },
                )
            }

            assertTrue(
                JbrSkiaCommandRecorder.replayRecordedLayer(
                    recording = nested,
                    left = 100f,
                    top = 200f,
                    width = 30f,
                    height = 40f,
                    pivotX = 15f,
                    pivotY = 20f,
                    alpha = 0.5f,
                    scaleX = 1f,
                    scaleY = 1f,
                    rotationZ = 0f,
                    translationX = 0f,
                    translationY = 0f,
                    clipRect = null,
                    clipPath = null,
                    blendMode = null,
                    colorFilter = JbrSkiaCommandRecorder.tintSrcInColorFilterOrNull(ColorFilter.tint(Color.Cyan)),
                )
            )
        }

        val records = recording.commands!!.commandRecords()
        val saveLayerColorFilterIndex = records.indexOfFirst { it[0] == 44 }
        val drawRectIndex = records.indexOfFirst { it[0] == 2 && it[3] == Color.Red.toArgb() }
        assertTrue(saveLayerColorFilterIndex >= 0)
        assertTrue(drawRectIndex > saveLayerColorFilterIndex)
        assertEquals(Color.Cyan.toArgb(), records[saveLayerColorFilterIndex][8])
        assertEquals(2, records[saveLayerColorFilterIndex][9])
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun nestedRecordingReplaysLayerBlendModeAndTintColorFilter() {
        val recording = JbrSkiaCommandRecorder.recordFrame {
            val nested = JbrSkiaCommandRecorder.recordNested {
                JbrSkiaCommandRecorder.drawRect(
                    left = 1f,
                    top = 2f,
                    right = 11f,
                    bottom = 22f,
                    paint = Paint().apply {
                        color = Color.Red
                    },
                )
            }

            assertTrue(
                JbrSkiaCommandRecorder.replayRecordedLayer(
                    recording = nested,
                    left = 100f,
                    top = 200f,
                    width = 30f,
                    height = 40f,
                    pivotX = 15f,
                    pivotY = 20f,
                    alpha = 0.5f,
                    scaleX = 1f,
                    scaleY = 1f,
                    rotationZ = 0f,
                    translationX = 0f,
                    translationY = 0f,
                    clipRect = null,
                    clipPath = null,
                    blendMode = JbrSkiaCommandRecorder.commandBlendModeOrNull(BlendMode.Plus),
                    colorFilter = JbrSkiaCommandRecorder.tintSrcInColorFilterOrNull(ColorFilter.tint(Color.Cyan)),
                )
            )
        }

        val records = recording.commands!!.commandRecords()
        val saveLayerBlendColorFilterIndex = records.indexOfFirst { it[0] == 51 }
        val drawRectIndex = records.indexOfFirst { it[0] == 2 && it[3] == Color.Red.toArgb() }
        assertTrue(saveLayerBlendColorFilterIndex >= 0)
        assertTrue(drawRectIndex > saveLayerBlendColorFilterIndex)
        assertEquals(1, records[saveLayerBlendColorFilterIndex][8])
        assertEquals(Color.Cyan.toArgb(), records[saveLayerBlendColorFilterIndex][9])
        assertEquals(2, records[saveLayerBlendColorFilterIndex][10])
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun writesFillRectPlusBlendModeRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 22f,
                paint = Paint().apply {
                    color = Color.Red
                    alpha = 0.5f
                    blendMode = BlendMode.Plus
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 9, 1, 1,
                41, 36, 1, Color.Red.copy(alpha = 0.25f).toArgb(), 1, 1, 2, 10, 20,
            ),
            commands,
        )
    }

    @Test
    fun writesFillRectMultiplyBlendModeRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 22f,
                paint = Paint().apply {
                    color = Color.Red
                    alpha = 0.5f
                    blendMode = BlendMode.Multiply
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 9, 1, 1,
                41, 36, 1, Color.Red.copy(alpha = 0.25f).toArgb(), 3, 1, 2, 10, 20,
            ),
            commands,
        )
    }

    @Test
    fun writesFillRectScreenBlendModeRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 22f,
                paint = Paint().apply {
                    color = Color.Red
                    alpha = 0.5f
                    blendMode = BlendMode.Screen
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 9, 1, 1,
                41, 36, 1, Color.Red.copy(alpha = 0.25f).toArgb(), 4, 1, 2, 10, 20,
            ),
            commands,
        )
    }

    @Test
    fun writesFillRectOverlayBlendModeRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 22f,
                paint = Paint().apply {
                    color = Color.Red
                    alpha = 0.5f
                    blendMode = BlendMode.Overlay
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 9, 1, 1,
                41, 36, 1, Color.Red.copy(alpha = 0.25f).toArgb(), 5, 1, 2, 10, 20,
            ),
            commands,
        )
    }

    @Test
    fun writesFillRectDarkenBlendModeRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 22f,
                paint = Paint().apply {
                    color = Color.Red
                    alpha = 0.5f
                    blendMode = BlendMode.Darken
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 9, 1, 1,
                41, 36, 1, Color.Red.copy(alpha = 0.25f).toArgb(), 6, 1, 2, 10, 20,
            ),
            commands,
        )
    }

    @Test
    fun writesFillRectLightenBlendModeRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 22f,
                paint = Paint().apply {
                    color = Color.Red
                    alpha = 0.5f
                    blendMode = BlendMode.Lighten
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 9, 1, 1,
                41, 36, 1, Color.Red.copy(alpha = 0.25f).toArgb(), 7, 1, 2, 10, 20,
            ),
            commands,
        )
    }

    @Test
    fun writesFillRectDifferenceBlendModeRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 22f,
                paint = Paint().apply {
                    color = Color.Red
                    alpha = 0.5f
                    blendMode = BlendMode.Difference
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 9, 1, 1,
                41, 36, 1, Color.Red.copy(alpha = 0.25f).toArgb(), 8, 1, 2, 10, 20,
            ),
            commands,
        )
    }

    @Test
    fun writesFillRectExclusionBlendModeRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 22f,
                paint = Paint().apply {
                    color = Color.Red
                    alpha = 0.5f
                    blendMode = BlendMode.Exclusion
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 9, 1, 1,
                41, 36, 1, Color.Red.copy(alpha = 0.25f).toArgb(), 9, 1, 2, 10, 20,
            ),
            commands,
        )
    }

    @Test
    fun writesFillRectColorDodgeBlendModeRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 22f,
                paint = Paint().apply {
                    color = Color.Red
                    alpha = 0.5f
                    blendMode = BlendMode.ColorDodge
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 9, 1, 1,
                41, 36, 1, Color.Red.copy(alpha = 0.25f).toArgb(), 10, 1, 2, 10, 20,
            ),
            commands,
        )
    }

    @Test
    fun writesFillRectColorBurnBlendModeRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 22f,
                paint = Paint().apply {
                    color = Color.Red
                    alpha = 0.5f
                    blendMode = BlendMode.ColorBurn
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 9, 1, 1,
                41, 36, 1, Color.Red.copy(alpha = 0.25f).toArgb(), 11, 1, 2, 10, 20,
            ),
            commands,
        )
    }

    @Test
    fun writesFillRectHardlightBlendModeRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 22f,
                paint = Paint().apply {
                    color = Color.Red
                    alpha = 0.5f
                    blendMode = BlendMode.Hardlight
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 9, 1, 1,
                41, 36, 1, Color.Red.copy(alpha = 0.25f).toArgb(), 12, 1, 2, 10, 20,
            ),
            commands,
        )
    }

    @Test
    fun writesFillRectSoftlightBlendModeRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 22f,
                paint = Paint().apply {
                    color = Color.Red
                    alpha = 0.5f
                    blendMode = BlendMode.Softlight
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 9, 1, 1,
                41, 36, 1, Color.Red.copy(alpha = 0.25f).toArgb(), 13, 1, 2, 10, 20,
            ),
            commands,
        )
    }

    @Test
    fun writesFillRectHueBlendModeRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 22f,
                paint = Paint().apply {
                    color = Color.Red
                    alpha = 0.5f
                    blendMode = BlendMode.Hue
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 9, 1, 1,
                41, 36, 1, Color.Red.copy(alpha = 0.25f).toArgb(), 14, 1, 2, 10, 20,
            ),
            commands,
        )
    }

    @Test
    fun writesFillRectSaturationBlendModeRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 22f,
                paint = Paint().apply {
                    color = Color.Red
                    alpha = 0.5f
                    blendMode = BlendMode.Saturation
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 9, 1, 1,
                41, 36, 1, Color.Red.copy(alpha = 0.25f).toArgb(), 15, 1, 2, 10, 20,
            ),
            commands,
        )
    }

    @Test
    fun writesFillRectColorBlendModeRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 22f,
                paint = Paint().apply {
                    color = Color.Red
                    alpha = 0.5f
                    blendMode = BlendMode.Color
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 9, 1, 1,
                41, 36, 1, Color.Red.copy(alpha = 0.25f).toArgb(), 16, 1, 2, 10, 20,
            ),
            commands,
        )
    }

    @Test
    fun writesFillRectLuminosityBlendModeRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 22f,
                paint = Paint().apply {
                    color = Color.Red
                    alpha = 0.5f
                    blendMode = BlendMode.Luminosity
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 9, 1, 1,
                41, 36, 1, Color.Red.copy(alpha = 0.25f).toArgb(), 17, 1, 2, 10, 20,
            ),
            commands,
        )
    }

    @Test
    fun writesFillRectTintColorFilterRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 3f,
                top = 4f,
                right = 13f,
                bottom = 24f,
                paint = Paint().apply {
                    color = Color.Magenta
                    colorFilter = ColorFilter.tint(Color.Cyan)
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 10, 1, 1,
                42, 40, 1, Color.Magenta.toArgb(), Color.Cyan.toArgb(), 2, 3, 4, 10, 20,
            ),
            commands,
        )
    }

    @Test
    fun writesFillRectTintColorFilterHandleRecord() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        withColorFilterHandles {
            val commands = JbrSkiaCommandRecorder.record {
                JbrSkiaCommandRecorder.drawRect(
                    left = 3f,
                    top = 4f,
                    right = 13f,
                    bottom = 24f,
                    paint = Paint().apply {
                        color = Color.Magenta
                        colorFilter = ColorFilter.tint(Color.Cyan)
                    },
                )
            }

            assertArrayEquals(
                intArrayOf(
                    1246972723, 77, 0, 20, 1, 1,
                    49, 40, 0, Color.Cyan.toArgb(), 2, 1, 1, 2, Color.Cyan.toArgb(), 2,
                    47, 40, 1, Color.Magenta.toArgb(), Color.Cyan.toArgb(), 2, 3, 4, 10, 20,
                ),
                commands,
            )
        }
    }

    @Test
    fun writesFillRectColorMatrixFilterHandleRecord() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val matrix = ColorMatrix()
        matrix[0, 4] = 64f

        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 3f,
                top = 4f,
                right = 13f,
                bottom = 24f,
                paint = Paint().apply {
                    color = Color.Magenta
                    colorFilter = ColorFilter.colorMatrix(matrix)
                },
            )
        }!!

        assertEquals(44, commands.size)
        assertArrayEquals(
            intArrayOf(1246972723, 77, 0, 38, 1, 1, 49, 112, 0),
            commands.copyOfRange(0, 9),
        )
        assertEquals(2, commands[11])
        assertEquals(1, commands[12])
        assertEquals(20, commands[13])
        val expectedMatrix = ColorMatrix().also { it[0, 4] = 64f }.values.copyOf()
        expectedMatrix[4] *= 1f / 255f
        expectedMatrix[9] *= 1f / 255f
        expectedMatrix[14] *= 1f / 255f
        expectedMatrix[19] *= 1f / 255f
        assertArrayEquals(IntArray(20) { expectedMatrix[it].toRawBits() }, commands.copyOfRange(14, 34))
        assertArrayEquals(
            intArrayOf(47, 40, 1, Color.Magenta.toArgb(), commands[9], commands[10], 3, 4, 10, 20),
            commands.copyOfRange(34, 44),
        )
    }

    @Test
    fun writesFillRectLightingFilterHandleRecord() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()

        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 3f,
                top = 4f,
                right = 13f,
                bottom = 24f,
                paint = Paint().apply {
                    color = Color.Magenta
                    colorFilter = ColorFilter.lighting(multiply = Color(0xFFB0D0FF), add = Color(0xFF101820))
                },
            )
        }!!

        assertEquals(26, commands.size)
        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 20, 1, 1,
                49, 40, 0, commands[9], commands[10], 3, 1, 2, 0xFFB0D0FF.toInt(), 0xFF101820.toInt(),
                47, 40, 1, Color.Magenta.toArgb(), commands[9], commands[10], 3, 4, 10, 20,
            ),
            commands,
        )
    }

    @Test
    fun reusesTintColorFilterHandleAcrossFrames() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        withColorFilterHandles {
            JbrSkiaCommandRecorder.record {
                JbrSkiaCommandRecorder.drawRect(
                    left = 3f,
                    top = 4f,
                    right = 13f,
                    bottom = 24f,
                    paint = Paint().apply {
                        color = Color.Magenta
                        colorFilter = ColorFilter.tint(Color.Cyan)
                    },
                )
            }

            val commands = JbrSkiaCommandRecorder.record {
                JbrSkiaCommandRecorder.drawRect(
                    left = 5f,
                    top = 6f,
                    right = 15f,
                    bottom = 26f,
                    paint = Paint().apply {
                        color = Color.Magenta
                        colorFilter = ColorFilter.tint(Color.Cyan)
                    },
                )
            }

            assertArrayEquals(
                intArrayOf(
                    1246972723, 77, 0, 10, 1, 1,
                    47, 40, 1, Color.Magenta.toArgb(), Color.Cyan.toArgb(), 2, 5, 6, 10, 20,
                ),
                commands,
            )
        }
    }

    @Test
    fun writesDashedStrokeLineRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawLine(
                p1 = Offset(1f, 2f),
                p2 = Offset(11f, 12f),
                paint = Paint().apply {
                    color = Color.White
                    strokeWidth = 8f
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f), 3f)
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 16, 1, 1,
                43, 64, 1, Color.White.toArgb(), 1, 2, 11, 12, 8, 0, 1, 0, 3000, 2, 16000, 10000,
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
                1246972723, 77, 0, 12, 1, 1,
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
                1246972723, 77, 0, 29, 1, 1,
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
                1246972723, 77, 0, 16, 1, 1,
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
                1246972723, 77, 0, 19, 1, 1,
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
    fun writesRoundedClipPathRecord() {
        val path = Path().apply {
            addOutline(Outline.Rounded(RoundRect(1f, 2f, 21f, 22f, 4f, 4f)))
        }

        val recording = JbrSkiaCommandRecorder.recordFrame {
            JbrSkiaCommandRecorder.clipPath(path, ClipOp.Intersect)
        }

        assertTrue(recording.commands != null)
        assertEquals(0, recording.unsupportedCount)
        assertTrue(recording.commandWordCount > 0)
    }

    @Test
    fun writesDrawPathRecord() {
        val path = Path().apply {
            moveTo(1f, 2f)
            lineTo(11f, 12f)
            lineTo(21f, 2f)
            close()
        }

        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawPath(path, Paint().apply { color = Color.Green })
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 24, 1, 1,
                21, 96, 1, 0, Color.Green.toArgb(), 0, 0, 0, 0, 0, 13,
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
    fun writesLinearGradientPathRecord() {
        val path = Path().apply {
            moveTo(1f, 2f)
            lineTo(11f, 12f)
            lineTo(21f, 2f)
            close()
        }

        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawPath(
                path,
                Paint().apply {
                    alpha = 0.5f
                    shader = LinearGradientShader(
                        from = Offset(1f, 2f),
                        to = Offset(21f, 12f),
                        colors = listOf(Color.Red, Color.Blue),
                        colorStops = listOf(0.25f, 0.75f),
                        tileMode = TileMode.Mirror,
                    )
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 28, 1, 1,
                28, 112, 1, 0, 13,
                0, 1000, 2000,
                1, 11000, 12000,
                1, 21000, 2000,
                1, 1000, 2000,
                4,
                1000, 2000, 21000, 12000, 2, 2,
                Color.Red.copy(alpha = 0.5f).toArgb(), 250,
                Color.Blue.copy(alpha = 0.5f).toArgb(), 750,
            ),
            commands,
        )
    }

    @Test
    fun writesRadialGradientPathRecord() {
        val path = Path().apply {
            moveTo(1f, 2f)
            lineTo(11f, 12f)
            lineTo(21f, 2f)
            close()
        }

        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawPath(
                path,
                Paint().apply {
                    alpha = 0.5f
                    shader = RadialGradientShader(
                        center = Offset(11f, 7f),
                        radius = 13f,
                        colors = listOf(Color.Red, Color.Blue),
                        colorStops = listOf(0.25f, 0.75f),
                        tileMode = TileMode.Mirror,
                    )
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 27, 1, 1,
                29, 108, 1, 0, 13,
                0, 1000, 2000,
                1, 11000, 12000,
                1, 21000, 2000,
                1, 1000, 2000,
                4,
                11000, 7000, 13000, 2, 2,
                Color.Red.copy(alpha = 0.5f).toArgb(), 250,
                Color.Blue.copy(alpha = 0.5f).toArgb(), 750,
            ),
            commands,
        )
    }

    @Test
    fun writesSweepGradientPathRecord() {
        val path = Path().apply {
            moveTo(1f, 2f)
            lineTo(11f, 12f)
            lineTo(21f, 2f)
            close()
        }

        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawPath(
                path,
                Paint().apply {
                    alpha = 0.5f
                    shader = SweepGradientShader(
                        center = Offset(11f, 7f),
                        colors = listOf(Color.Red, Color.Blue),
                        colorStops = listOf(0.25f, 0.75f),
                    )
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 25, 1, 1,
                32, 100, 1, 0, 13,
                0, 1000, 2000,
                1, 11000, 12000,
                1, 21000, 2000,
                1, 1000, 2000,
                4,
                11000, 7000, 2,
                Color.Red.copy(alpha = 0.5f).toArgb(), 250,
                Color.Blue.copy(alpha = 0.5f).toArgb(), 750,
            ),
            commands,
        )
    }

    @Test
    fun writesDrawArcRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawArc(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 12f,
                startAngle = 45f,
                sweepAngle = 90f,
                useCenter = true,
                paint = Paint().apply { color = Color.Red },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 16, 1, 1,
                22, 64, 1, 0, Color.Red.toArgb(), 1000, 2000, 11000, 12000, 45000, 90000, 1, 0, 0, 0, 0,
            ),
            commands,
        )
    }

    @Test
    fun writesDrawRoundRectRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRoundRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 12f,
                radiusX = 3f,
                radiusY = 4f,
                paint = Paint().apply {
                    color = Color.Blue
                    style = PaintingStyle.Stroke
                    strokeWidth = 2f
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 15, 1, 1,
                23, 60, 1, 1, Color.Blue.toArgb(), 1000, 2000, 11000, 12000, 3000, 4000, 2, 0, 1, 0,
            ),
            commands,
        )
    }

    @Test
    fun writesLinearGradientRectRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 12f,
                paint = Paint().apply {
                    alpha = 0.5f
                    shader = LinearGradientShader(
                        from = Offset(1f, 2f),
                        to = Offset(11f, 12f),
                        colors = listOf(Color.Red, Color.Blue),
                        colorStops = listOf(0.25f, 0.75f),
                        tileMode = TileMode.Mirror,
                    )
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 17, 1, 1,
                24, 68, 1, 1000, 2000, 11000, 12000, 1000, 2000, 11000, 12000, 2, 2,
                Color.Red.copy(alpha = 0.5f).toArgb(), 250,
                Color.Blue.copy(alpha = 0.5f).toArgb(), 750,
            ),
            commands,
        )
    }

    @Test
    fun writesLinearGradientStrokeRectRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 12f,
                paint = Paint().apply {
                    alpha = 0.5f
                    style = PaintingStyle.Stroke
                    strokeWidth = 12f
                    shader = LinearGradientShader(
                        from = Offset(1f, 2f),
                        to = Offset(11f, 12f),
                        colors = listOf(Color.Red, Color.Blue),
                        colorStops = listOf(0.25f, 0.75f),
                        tileMode = TileMode.Mirror,
                    )
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 21, 1, 1,
                35, 84, 1, 1000, 2000, 11000, 12000, 12000, 0, 1, 0,
                1000, 2000, 11000, 12000, 2, 2,
                Color.Red.copy(alpha = 0.5f).toArgb(), 250,
                Color.Blue.copy(alpha = 0.5f).toArgb(), 750,
            ),
            commands,
        )
    }

    @Test
    fun writesLinearGradientStrokeRoundRectRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRoundRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 12f,
                radiusX = 3f,
                radiusY = 4f,
                paint = Paint().apply {
                    alpha = 0.5f
                    style = PaintingStyle.Stroke
                    strokeWidth = 12f
                    shader = LinearGradientShader(
                        from = Offset(1f, 2f),
                        to = Offset(11f, 12f),
                        colors = listOf(Color.Red, Color.Blue),
                        colorStops = listOf(0.25f, 0.75f),
                        tileMode = TileMode.Mirror,
                    )
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 23, 1, 1,
                36, 92, 1, 1000, 2000, 11000, 12000, 3000, 4000, 12000, 0, 1, 0,
                1000, 2000, 11000, 12000, 2, 2,
                Color.Red.copy(alpha = 0.5f).toArgb(), 250,
                Color.Blue.copy(alpha = 0.5f).toArgb(), 750,
            ),
            commands,
        )
    }

    @Test
    fun writesLinearGradientRoundRectRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRoundRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 12f,
                radiusX = 3f,
                radiusY = 4f,
                paint = Paint().apply {
                    alpha = 0.5f
                    shader = LinearGradientShader(
                        from = Offset(1f, 2f),
                        to = Offset(11f, 12f),
                        colors = listOf(Color.Red, Color.Blue),
                        colorStops = listOf(0.25f, 0.75f),
                        tileMode = TileMode.Mirror,
                    )
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 19, 1, 1,
                25, 76, 1, 1000, 2000, 11000, 12000, 3000, 4000, 1000, 2000, 11000, 12000, 2, 2,
                Color.Red.copy(alpha = 0.5f).toArgb(), 250,
                Color.Blue.copy(alpha = 0.5f).toArgb(), 750,
            ),
            commands,
        )
    }

    @Test
    fun writesRadialGradientRectRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 12f,
                paint = Paint().apply {
                    alpha = 0.5f
                    shader = RadialGradientShader(
                        center = Offset(6f, 7f),
                        radius = 8f,
                        colors = listOf(Color.Red, Color.Blue),
                        colorStops = listOf(0.25f, 0.75f),
                        tileMode = TileMode.Repeated,
                    )
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 16, 1, 1,
                26, 64, 1, 1000, 2000, 11000, 12000, 6000, 7000, 8000, 1, 2,
                Color.Red.copy(alpha = 0.5f).toArgb(), 250,
                Color.Blue.copy(alpha = 0.5f).toArgb(), 750,
            ),
            commands,
        )
    }

    @Test
    fun writesSweepGradientRectRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 12f,
                paint = Paint().apply {
                    alpha = 0.5f
                    shader = SweepGradientShader(
                        center = Offset(6f, 7f),
                        colors = listOf(Color.Red, Color.Blue),
                        colorStops = listOf(0.25f, 0.75f),
                    )
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 14, 1, 1,
                30, 56, 1, 1000, 2000, 11000, 12000, 6000, 7000, 2,
                Color.Red.copy(alpha = 0.5f).toArgb(), 250,
                Color.Blue.copy(alpha = 0.5f).toArgb(), 750,
            ),
            commands,
        )
    }

    @Test
    fun writesSweepGradientStrokeRectRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 12f,
                paint = Paint().apply {
                    alpha = 0.5f
                    style = PaintingStyle.Stroke
                    strokeWidth = 12f
                    shader = SweepGradientShader(
                        center = Offset(6f, 7f),
                        colors = listOf(Color.Red, Color.Blue),
                        colorStops = listOf(0.25f, 0.75f),
                    )
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 18, 1, 1,
                39, 72, 1, 1000, 2000, 11000, 12000, 12000, 0, 1, 0,
                6000, 7000, 2,
                Color.Red.copy(alpha = 0.5f).toArgb(), 250,
                Color.Blue.copy(alpha = 0.5f).toArgb(), 750,
            ),
            commands,
        )
    }

    @Test
    fun writesSweepGradientRoundRectRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRoundRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 12f,
                radiusX = 3f,
                radiusY = 4f,
                paint = Paint().apply {
                    alpha = 0.5f
                    shader = SweepGradientShader(
                        center = Offset(6f, 7f),
                        colors = listOf(Color.Red, Color.Blue),
                        colorStops = listOf(0.25f, 0.75f),
                    )
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 16, 1, 1,
                31, 64, 1, 1000, 2000, 11000, 12000, 3000, 4000, 6000, 7000, 2,
                Color.Red.copy(alpha = 0.5f).toArgb(), 250,
                Color.Blue.copy(alpha = 0.5f).toArgb(), 750,
            ),
            commands,
        )
    }

    @Test
    fun writesSweepGradientStrokeRoundRectRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRoundRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 12f,
                radiusX = 3f,
                radiusY = 4f,
                paint = Paint().apply {
                    alpha = 0.5f
                    style = PaintingStyle.Stroke
                    strokeWidth = 12f
                    shader = SweepGradientShader(
                        center = Offset(6f, 7f),
                        colors = listOf(Color.Red, Color.Blue),
                        colorStops = listOf(0.25f, 0.75f),
                    )
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 20, 1, 1,
                40, 80, 1, 1000, 2000, 11000, 12000, 3000, 4000, 12000, 0, 1, 0,
                6000, 7000, 2,
                Color.Red.copy(alpha = 0.5f).toArgb(), 250,
                Color.Blue.copy(alpha = 0.5f).toArgb(), 750,
            ),
            commands,
        )
    }

    @Test
    fun writesRadialGradientStrokeRectRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 12f,
                paint = Paint().apply {
                    alpha = 0.5f
                    style = PaintingStyle.Stroke
                    strokeWidth = 12f
                    shader = RadialGradientShader(
                        center = Offset(6f, 7f),
                        radius = 8f,
                        colors = listOf(Color.Red, Color.Blue),
                        colorStops = listOf(0.25f, 0.75f),
                        tileMode = TileMode.Repeated,
                    )
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 20, 1, 1,
                37, 80, 1, 1000, 2000, 11000, 12000, 12000, 0, 1, 0,
                6000, 7000, 8000, 1, 2,
                Color.Red.copy(alpha = 0.5f).toArgb(), 250,
                Color.Blue.copy(alpha = 0.5f).toArgb(), 750,
            ),
            commands,
        )
    }

    @Test
    fun writesRadialGradientRoundRectRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRoundRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 12f,
                radiusX = 3f,
                radiusY = 4f,
                paint = Paint().apply {
                    alpha = 0.5f
                    shader = RadialGradientShader(
                        center = Offset(6f, 7f),
                        radius = 8f,
                        colors = listOf(Color.Red, Color.Blue),
                        colorStops = listOf(0.25f, 0.75f),
                        tileMode = TileMode.Repeated,
                    )
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 18, 1, 1,
                27, 72, 1, 1000, 2000, 11000, 12000, 3000, 4000, 6000, 7000, 8000, 1, 2,
                Color.Red.copy(alpha = 0.5f).toArgb(), 250,
                Color.Blue.copy(alpha = 0.5f).toArgb(), 750,
            ),
            commands,
        )
    }

    @Test
    fun writesRadialGradientStrokeRoundRectRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRoundRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 12f,
                radiusX = 3f,
                radiusY = 4f,
                paint = Paint().apply {
                    alpha = 0.5f
                    style = PaintingStyle.Stroke
                    strokeWidth = 12f
                    shader = RadialGradientShader(
                        center = Offset(6f, 7f),
                        radius = 8f,
                        colors = listOf(Color.Red, Color.Blue),
                        colorStops = listOf(0.25f, 0.75f),
                        tileMode = TileMode.Repeated,
                    )
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 22, 1, 1,
                38, 88, 1, 1000, 2000, 11000, 12000, 3000, 4000, 12000, 0, 1, 0,
                6000, 7000, 8000, 1, 2,
                Color.Red.copy(alpha = 0.5f).toArgb(), 250,
                Color.Blue.copy(alpha = 0.5f).toArgb(), 750,
            ),
            commands,
        )
    }

    @Test
    fun rejectsUnknownOpaqueShaderInStrictMode() {
        withStrictCommandRecording {
            val commands = JbrSkiaCommandRecorder.record {
                JbrSkiaCommandRecorder.drawRect(
                    left = 1f,
                    top = 2f,
                    right = 11f,
                    bottom = 12f,
                    paint = Paint().apply {
                        shader = org.jetbrains.skia.Shader.makeColor(Color.Red.toArgb()).asComposeShader()
                    },
                )
            }

            assertNull(commands)
        }
    }

    @Test
    fun rejectsTransformedGradientShaderInStrictMode() {
        val transformedShader = TransformShader().apply {
            shader = LinearGradientShader(
                from = Offset(1f, 2f),
                to = Offset(11f, 12f),
                colors = listOf(Color.Red, Color.Blue),
                colorStops = listOf(0.25f, 0.75f),
                tileMode = TileMode.Clamp,
            )
            transform(Matrix().apply { translate(x = 3f, y = 4f) })
        }.shader

        withStrictCommandRecording {
            val commands = JbrSkiaCommandRecorder.record {
                JbrSkiaCommandRecorder.drawRect(
                    left = 1f,
                    top = 2f,
                    right = 11f,
                    bottom = 12f,
                    paint = Paint().apply {
                        shader = transformedShader
                    },
                )
            }

            assertNull(commands)
        }
    }

    @Test
    fun rejectsCompositeShaderInStrictMode() {
        val shader = CompositeShader(
            dst = LinearGradientShader(
                from = Offset(1f, 2f),
                to = Offset(11f, 12f),
                colors = listOf(Color.Red, Color.Blue),
                colorStops = listOf(0.25f, 0.75f),
                tileMode = TileMode.Clamp,
            ),
            src = RadialGradientShader(
                center = Offset(6f, 7f),
                radius = 8f,
                colors = listOf(Color.Green, Color.White),
                colorStops = listOf(0.2f, 0.8f),
                tileMode = TileMode.Clamp,
            ),
            blendMode = BlendMode.SrcOver,
        )

        withStrictCommandRecording {
            val commands = JbrSkiaCommandRecorder.record {
                JbrSkiaCommandRecorder.drawRect(
                    left = 1f,
                    top = 2f,
                    right = 11f,
                    bottom = 12f,
                    paint = Paint().apply {
                        this.shader = shader
                    },
                )
            }

            assertNull(commands)
        }
    }

    @Test
    fun rejectsGradientStrokePaintInStrictMode() {
        withStrictCommandRecording {
            val commands = JbrSkiaCommandRecorder.record {
                JbrSkiaCommandRecorder.drawRect(
                    left = 1f,
                    top = 2f,
                    right = 11f,
                    bottom = 12f,
                    paint = Paint().apply {
                        style = PaintingStyle.Stroke
                        shader = LinearGradientShader(
                            from = Offset(1f, 2f),
                            to = Offset(11f, 12f),
                            colors = listOf(Color.Red, Color.Blue),
                            colorStops = listOf(0.25f, 0.75f),
                            tileMode = TileMode.Clamp,
                        )
                    },
                )
            }

            assertNull(commands)
        }
    }

    @Test
    fun rejectsInvalidGradientPayloadsInStrictMode() {
        withStrictCommandRecording {
            assertNull(
                JbrSkiaCommandRecorder.record {
                    JbrSkiaCommandRecorder.drawRect(
                        left = 1f,
                        top = 2f,
                        right = 11f,
                        bottom = 12f,
                        paint = Paint().apply {
                            shader = LinearGradientShader(
                                from = Offset(1f, 2f),
                                to = Offset(11f, 12f),
                                colors = listOf(Color.Red, Color.Blue),
                                colorStops = listOf(0.75f, 0.25f),
                                tileMode = TileMode.Clamp,
                            )
                        },
                    )
                }
            )
            assertNull(
                JbrSkiaCommandRecorder.record {
                    JbrSkiaCommandRecorder.drawRect(
                        left = 1f,
                        top = 2f,
                        right = 11f,
                        bottom = 12f,
                        paint = Paint().apply {
                            shader = RadialGradientShader(
                                center = Offset(6f, 7f),
                                radius = 5f,
                                colors = List(17) { Color.Red },
                                colorStops = List(17) { it / 16f },
                                tileMode = TileMode.Clamp,
                            )
                        },
                    )
                }
            )
            assertNull(
                JbrSkiaCommandRecorder.record {
                    JbrSkiaCommandRecorder.drawPath(
                        Path().apply {
                            moveTo(1f, 2f)
                            lineTo(11f, 12f)
                            close()
                        },
                        Paint().apply {
                            shader = SweepGradientShader(
                                center = Offset(6f, 7f),
                                colors = listOf(Color.Red, Color.Blue),
                                colorStops = listOf(0.5f, 0.5f),
                            )
                        },
                    )
                }
            )
        }
    }

    @Test
    fun rejectsInvalidGradientGeometryInStrictMode() {
        withStrictCommandRecording {
            assertNull(
                JbrSkiaCommandRecorder.record {
                    JbrSkiaCommandRecorder.drawRoundRect(
                        left = 1f,
                        top = 2f,
                        right = 11f,
                        bottom = 12f,
                        radiusX = -3f,
                        radiusY = 4f,
                        paint = Paint().apply {
                            shader = LinearGradientShader(
                                from = Offset(1f, 2f),
                                to = Offset(11f, 12f),
                                colors = listOf(Color.Red, Color.Blue),
                                colorStops = listOf(0.25f, 0.75f),
                                tileMode = TileMode.Clamp,
                            )
                        },
                    )
                }
            )
            assertNull(
                JbrSkiaCommandRecorder.record {
                    JbrSkiaCommandRecorder.drawRoundRect(
                        left = 1f,
                        top = 2f,
                        right = 11f,
                        bottom = 12f,
                        radiusX = 3f,
                        radiusY = -4f,
                        paint = Paint().apply {
                            shader = RadialGradientShader(
                                center = Offset(6f, 7f),
                                radius = 5f,
                                colors = listOf(Color.Red, Color.Blue),
                                colorStops = listOf(0.25f, 0.75f),
                                tileMode = TileMode.Clamp,
                            )
                        },
                    )
                }
            )
            assertNull(
                JbrSkiaCommandRecorder.record {
                    JbrSkiaCommandRecorder.drawRoundRect(
                        left = 1f,
                        top = 2f,
                        right = 11f,
                        bottom = 12f,
                        radiusX = -3f,
                        radiusY = -4f,
                        paint = Paint().apply {
                            shader = SweepGradientShader(
                                center = Offset(6f, 7f),
                                colors = listOf(Color.Red, Color.Blue),
                                colorStops = listOf(0.25f, 0.75f),
                            )
                        },
                    )
                }
            )
        }
    }

    @Test
    fun rejectsNonFiniteGradientGeometryInStrictMode() {
        withStrictCommandRecording {
            assertNull(
                JbrSkiaCommandRecorder.record {
                    JbrSkiaCommandRecorder.drawRect(
                        left = 1f,
                        top = 2f,
                        right = 11f,
                        bottom = 12f,
                        paint = Paint().apply {
                            shader = linearGradientWithMetadata(
                                from = Offset(Float.NaN, 2f),
                                to = Offset(11f, 12f),
                            )
                        },
                    )
                }
            )
            assertNull(
                JbrSkiaCommandRecorder.record {
                    JbrSkiaCommandRecorder.drawRoundRect(
                        left = 1f,
                        top = 2f,
                        right = 11f,
                        bottom = 12f,
                        radiusX = 3f,
                        radiusY = 4f,
                        paint = Paint().apply {
                            shader = radialGradientWithMetadata(
                                center = Offset(6f, 7f),
                                radius = Float.POSITIVE_INFINITY,
                            )
                        },
                    )
                }
            )
            assertNull(
                JbrSkiaCommandRecorder.record {
                    JbrSkiaCommandRecorder.drawPath(
                        Path().apply {
                            moveTo(1f, 2f)
                            lineTo(11f, 12f)
                            close()
                        },
                        Paint().apply {
                            shader = sweepGradientWithMetadata(
                                center = Offset(6f, Float.NEGATIVE_INFINITY),
                            )
                        },
                    )
                }
            )
        }
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
                1246972723, 77, 0, 11, 1, 1,
                13, 32, 0, 1, 2, 10, 10, 360,
                8, 12, 0,
            ),
            commands,
        )
    }

    @Test
    fun writesSaveLayerTintColorFilterRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.saveLayer(
                bounds = Rect(1f, 2f, 11f, 12f),
                paint = Paint().apply {
                    color = Color.White.copy(alpha = 0.6f)
                    colorFilter = ColorFilter.tint(Color.Cyan)
                },
            )
            JbrSkiaCommandRecorder.restore()
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 13, 1, 1,
                44, 40, 0, 1, 2, 10, 10, 360, Color.Cyan.toArgb(), 2,
                8, 12, 0,
            ),
            commands,
        )
    }

    @Test
    fun writesSaveLayerBlendModeRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.saveLayer(
                bounds = Rect(1f, 2f, 11f, 12f),
                paint = Paint().apply {
                    color = Color.White.copy(alpha = 0.6f)
                    blendMode = BlendMode.Plus
                },
            )
            JbrSkiaCommandRecorder.restore()
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 12, 1, 1,
                50, 36, 0, 1, 2, 10, 10, 360, 1,
                8, 12, 0,
            ),
            commands,
        )
    }

    @Test
    fun rejectsUnsupportedGraphicsLayerDraw() {
        withStrictCommandRecording {
            val commands = JbrSkiaCommandRecorder.record {
                JbrSkiaCommandRecorder.markUnsupportedDraw("graphicsLayer")
            }

            assertNull(commands)
        }
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
                1246972723, 77, 0, 29, 1, 1,
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
    fun writesImageTintColorFilterRecord() {
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
                    alpha = 0.5f
                    colorFilter = ColorFilter.tint(Color.Cyan)
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 31, 1, 1,
                15, 48, 0, -1599677274, -472603669, 2, 2, 4,
                Color.Red.toArgb(), Color.Green.toArgb(), Color.Blue.toArgb(), Color.White.toArgb(),
                45, 76, 1,
                0, 0, 2000, 2000,
                10000, 20000, 30000, 40000,
                -1599677274, -472603669, 2, 2, 502, FilterQuality.Medium.value,
                Color.Cyan.toArgb(), 2,
            ),
            commands,
        )
    }

    @Test
    fun writesImageShaderRectRecord() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val image = onePixelImage(7)

        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 2f,
                top = 3f,
                right = 42f,
                bottom = 33f,
                paint = Paint().apply {
                    shader = ImageShader(image, TileMode.Repeated, TileMode.Mirror)
                    alpha = 0.75f
                    isAntiAlias = true
                },
            )
        }!!

        assertEquals(77, commands[1])
        assertEquals(1, commands.countCommand(15))
        assertEquals(1, commands.countCommand(34))
        assertTrue(commands.joinToString(), commands.containsSubsequence(34, 56, 1, 2000, 3000, 42000, 33000))
        assertTrue(commands.joinToString(), commands.containsSubsequence(1, 1, 1, 2, 749))
    }

    @Test
    fun evictsOldestImageCacheEntryBeforeRedefiningAfterThreshold() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()

        val commands = JbrSkiaCommandRecorder.record {
            repeat(1025) { index ->
                val image = onePixelImage(index)
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

        assertEquals(0, commands.countCommand(18))
        assertEquals(1, commands.countCommand(33))
    }

    @Test
    fun reusesStableImageCacheEntriesAcrossFrames() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val images = List(260) { onePixelImage(it) }

        val firstFrame = JbrSkiaCommandRecorder.record {
            images.forEach { drawOnePixelImage(it) }
        }!!
        val secondFrame = JbrSkiaCommandRecorder.record {
            images.forEach { drawOnePixelImage(it) }
        }!!

        assertEquals(260, firstFrame.countCommand(15))
        assertEquals(260, firstFrame.countCommand(16))
        assertEquals(0, firstFrame.countCommand(18))
        assertEquals(0, firstFrame.countCommand(33))
        assertEquals(0, secondFrame.countCommand(15))
        assertEquals(260, secondFrame.countCommand(16))
        assertEquals(0, secondFrame.countCommand(18))
        assertEquals(0, secondFrame.countCommand(33))
    }

    @Test
    fun writesSimpleTextRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawTextUtf16(
                text = "Hi",
                x = 1.25f,
                baseline = 18.5f,
                fontSize = 13f,
                fontFamily = "Inter",
                color = Color.White.toArgb(),
                antiAlias = true,
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 16, 1, 1,
                17, 64, 1, 1250, 18500, 13000, Color.White.toArgb(), 5,
                'I'.code, 'n'.code, 't'.code, 'e'.code, 'r'.code, 2, 'H'.code, 'i'.code,
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
                fontFamily = null,
                color = Color.White.toArgb(),
                antiAlias = true,
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 77, 0, 13, 1, 1,
                17, 52, 1, 1250, 18500, 13000, Color.White.toArgb(), 0, 4,
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
                fontFamily = "Inter",
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
                1246972723, 77, 0, 32, 1, 1,
                19, 128, 1, 1250, 2500, 120000, 13000, Color.White.toArgb(), 700, 5, 1,
                5, 'I'.code, 'n'.code, 't'.code, 'e'.code, 'r'.code,
                2, 1, 1500, 1, 1, 3, 2500, 1, Color.Red.toArgb(), 5,
                'H'.code, 'i'.code, ' '.code, 0xd83d, 0xde80,
            ),
            commands,
        )
    }

    private fun linearGradientWithMetadata(from: Offset, to: Offset): Shader =
        Shader(
            internalSkiaShader = LinearGradientShader(
                from = Offset(1f, 2f),
                to = Offset(11f, 12f),
                colors = listOf(Color.Red, Color.Blue),
                colorStops = listOf(0.25f, 0.75f),
                tileMode = TileMode.Clamp,
            ).skiaShader,
            jbrSkiaLinearGradient = JbrSkiaLinearGradientShader(
                from = from,
                to = to,
                colors = listOf(Color.Red, Color.Blue),
                colorStops = listOf(0.25f, 0.75f),
                tileMode = TileMode.Clamp,
            ),
        )

    private fun radialGradientWithMetadata(center: Offset, radius: Float): Shader =
        Shader(
            internalSkiaShader = RadialGradientShader(
                center = Offset(6f, 7f),
                radius = 8f,
                colors = listOf(Color.Red, Color.Blue),
                colorStops = listOf(0.25f, 0.75f),
                tileMode = TileMode.Clamp,
            ).skiaShader,
            jbrSkiaRadialGradient = JbrSkiaRadialGradientShader(
                center = center,
                radius = radius,
                colors = listOf(Color.Red, Color.Blue),
                colorStops = listOf(0.25f, 0.75f),
                tileMode = TileMode.Clamp,
            ),
        )

    private fun sweepGradientWithMetadata(center: Offset): Shader =
        Shader(
            internalSkiaShader = SweepGradientShader(
                center = Offset(6f, 7f),
                colors = listOf(Color.Red, Color.Blue),
                colorStops = listOf(0.25f, 0.75f),
            ).skiaShader,
            jbrSkiaSweepGradient = JbrSkiaSweepGradientShader(
                center = center,
                colors = listOf(Color.Red, Color.Blue),
                colorStops = listOf(0.25f, 0.75f),
            ),
        )

    private fun withStrictCommandRecording(block: () -> Unit) {
        val key = "compose.jbr.skia.command.strict"
        val previous = System.getProperty(key)
        System.setProperty(key, "true")
        try {
            block()
        } finally {
            if (previous == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, previous)
            }
        }
    }

    private fun withColorFilterHandles(block: () -> Unit) {
        val key = "compose.jbr.skia.command.colorFilterHandles"
        val previous = System.getProperty(key)
        System.setProperty(key, "true")
        try {
            block()
        } finally {
            if (previous == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, previous)
            }
        }
    }

    private fun onePixelImage(index: Int): ImageBitmap {
        val image = ImageBitmap(1, 1)
        Canvas(image).drawRect(0f, 0f, 1f, 1f, Paint().apply {
            color = Color(index or 0xff000000.toInt())
        })
        return image
    }

    private fun drawOnePixelImage(image: ImageBitmap) {
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

    private fun IntArray.countCommand(op: Int): Int {
        var count = 0
        var offset = 6
        while (offset < size) {
            if (this[offset] == op) count++
            offset += this[offset + 1] / Int.SIZE_BYTES
        }
        return count
    }

    private fun IntArray.commandRecords(): List<IntArray> {
        val records = mutableListOf<IntArray>()
        var offset = 6
        while (offset < size) {
            val recordLength = this[offset + 1] / Int.SIZE_BYTES
            records += copyOfRange(offset, offset + recordLength)
            offset += recordLength
        }
        return records
    }

    private fun IntArray.containsSubsequence(vararg values: Int): Boolean =
        indices.any { start ->
            start + values.size <= size && values.indices.all { index -> this[start + index] == values[index] }
        }
}
