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
import org.junit.Assert.assertNotNull
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
                1246972723, 106, 0, 18, 1, 1,
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
                fontWeight = 700,
                fontWidth = 5,
                fontSlant = 1,
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
    fun writesFontDataRecord() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val commands = JbrSkiaCommandRecorder.record {
            assertTrue(JbrSkiaCommandRecorder.defineFontData(0x123456780abcdef0L, byteArrayOf(0, 1, -2, -1)))
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 106, 0, 10, 1, 1,
                66, 40, 0, 0x12345678, 0x0abcdef0, 4, 0, 1, 254, 255,
            ),
            commands,
        )
    }

    @Test
    fun deduplicatesFontDataRecordsUntilCacheClear() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val first = JbrSkiaCommandRecorder.record {
            assertTrue(JbrSkiaCommandRecorder.defineFontData(0x123456780abcdef0L, byteArrayOf(0, 1, -2, -1)))
        }
        val second = JbrSkiaCommandRecorder.record {
            assertTrue(JbrSkiaCommandRecorder.defineFontData(0x123456780abcdef0L, byteArrayOf(0, 1, -2, -1)))
        }

        assertTrue(first!!.contains(66))
        assertFalse(second!!.contains(66))

        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val afterClear = JbrSkiaCommandRecorder.record {
            assertTrue(JbrSkiaCommandRecorder.defineFontData(0x123456780abcdef0L, byteArrayOf(0, 1, -2, -1)))
        }

        assertTrue(afterClear!!.contains(66))
    }

    @Test
    fun evictsOldestFontDataHandleBeforeRedefiningAfterThreshold() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()

        val commands = JbrSkiaCommandRecorder.record {
            assertTrue(JbrSkiaCommandRecorder.defineFontData(1L, byteArrayOf(1)))
            assertTrue(JbrSkiaCommandRecorder.defineFontData(1L, byteArrayOf(1)))
            for (handle in 2L..1025L) {
                assertTrue(JbrSkiaCommandRecorder.defineFontData(handle, byteArrayOf(handle.toByte())))
            }
            assertTrue(JbrSkiaCommandRecorder.defineFontData(1L, byteArrayOf(1)))
        }!!

        assertEquals(1026, commands.countCommand(66))
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
    fun nestedRecordingReplaysLayerMatrixTransformBeforeSaveLayer() {
        val matrix = Matrix().apply {
            values[Matrix.ScaleX] = 1.1f
            values[Matrix.SkewX] = 0.2f
            values[Matrix.TranslateX] = 3f
            values[Matrix.SkewY] = -0.1f
            values[Matrix.ScaleY] = 0.9f
            values[Matrix.TranslateY] = 4f
            values[Matrix.Perspective0] = 0.001f
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
                    clipPath = null,
                    blendMode = null,
                    transformMatrix = matrix,
                )
            )
        }

        val records = recording.commands!!.commandRecords()
        assertEquals(7, records[0][0])
        assertEquals(10, records[1][0])
        assertEquals(100000, records[1][3])
        assertEquals(200000, records[1][4])
        assertEquals(63, records[2][0])
        assertEquals(1.1f.toRawBits(), records[2][3])
        assertEquals(0.2f.toRawBits(), records[2][4])
        assertEquals(3f.toRawBits(), records[2][5])
        assertEquals((-0.1f).toRawBits(), records[2][6])
        assertEquals(0.9f.toRawBits(), records[2][7])
        assertEquals(4f.toRawBits(), records[2][8])
        assertEquals(0.001f.toRawBits(), records[2][9])
        assertEquals(13, records[3][0])
        assertEquals(2, records[4][0])
        assertEquals(Color.Red.toArgb(), records[4][3])
        assertEquals(8, records[5][0])
        assertEquals(8, records[6][0])
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
    fun saveLayerRejectsRawColorFilter() {
        val rawColorFilter = org.jetbrains.skia.ColorFilter.makeBlend(
            Color.Cyan.toArgb(),
            org.jetbrains.skia.BlendMode.SRC_IN,
        ).asComposeColorFilter()
        val recording = JbrSkiaCommandRecorder.recordFrame {
            JbrSkiaCommandRecorder.saveLayer(
                Rect(10f, 20f, 110f, 120f),
                Paint().apply {
                    color = Color.White
                    colorFilter = rawColorFilter
                },
            )
            JbrSkiaCommandRecorder.drawRect(
                left = 20f,
                top = 30f,
                right = 80f,
                bottom = 90f,
                paint = Paint().apply {
                    color = Color.Red
                },
            )
            JbrSkiaCommandRecorder.restore()
        }

        assertEquals(2, recording.unsupportedCount)
    }

    @Test
    fun nestedRecordingReplaysLayerColorMatrixFilterHandle() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val matrix = ColorMatrix().also { it[0, 4] = 64f }
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
                    colorFilter = ColorFilter.colorMatrix(matrix),
                )
            )
        }

        val records = recording.commands!!.commandRecords()
        val descriptorIndex = records.indexOfFirst { it[0] == 49 }
        val saveLayerColorFilterRefIndex = records.indexOfFirst { it[0] == 52 }
        val drawRectIndex = records.indexOfFirst { it[0] == 2 && it[3] == Color.Red.toArgb() }
        assertTrue(descriptorIndex >= 0)
        assertTrue(saveLayerColorFilterRefIndex > descriptorIndex)
        assertTrue(drawRectIndex > saveLayerColorFilterRefIndex)
        assertEquals(2, records[descriptorIndex][5])
        assertEquals(records[descriptorIndex][3], records[saveLayerColorFilterRefIndex][8])
        assertEquals(records[descriptorIndex][4], records[saveLayerColorFilterRefIndex][9])
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
    fun nestedRecordingReplaysLayerBlendModeAndColorMatrixFilterHandle() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val matrix = ColorMatrix().also { it[0, 4] = 64f }
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
                    colorFilter = ColorFilter.colorMatrix(matrix),
                )
            )
        }

        val records = recording.commands!!.commandRecords()
        val descriptorIndex = records.indexOfFirst { it[0] == 49 }
        val saveLayerBlendColorFilterRefIndex = records.indexOfFirst { it[0] == 54 }
        val drawRectIndex = records.indexOfFirst { it[0] == 2 && it[3] == Color.Red.toArgb() }
        assertTrue(descriptorIndex >= 0)
        assertTrue(saveLayerBlendColorFilterRefIndex > descriptorIndex)
        assertTrue(drawRectIndex > saveLayerBlendColorFilterRefIndex)
        assertEquals(2, records[descriptorIndex][5])
        assertEquals(1, records[saveLayerBlendColorFilterRefIndex][8])
        assertEquals(records[descriptorIndex][3], records[saveLayerBlendColorFilterRefIndex][9])
        assertEquals(records[descriptorIndex][4], records[saveLayerBlendColorFilterRefIndex][10])
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun nestedRecordingReplaysLayerNestedImageFilterHandle() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val blur = JbrSkiaCommandRecorder.ImageFilterDescriptor.Blur(
            sigmaX = 2f,
            sigmaY = 3f,
            tileMode = 0,
        )
        val offset = JbrSkiaCommandRecorder.ImageFilterDescriptor.Offset(
            dx = 4f,
            dy = 5f,
            input = blur,
        )
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
                    imageFilter = offset,
                )
            )
        }

        val records = recording.commands!!.commandRecords()
        val descriptors = records.filter { it[0] == 49 }
        val saveLayerImageFilterRefIndex = records.indexOfFirst { it[0] == 55 }
        val drawRectIndex = records.indexOfFirst { it[0] == 2 && it[3] == Color.Red.toArgb() }
        assertEquals(2, descriptors.size)
        assertEquals(4, descriptors[0][5])
        assertEquals(3, descriptors[0][7])
        assertEquals(7, descriptors[1][5])
        assertEquals(4, descriptors[1][7])
        assertEquals(descriptors[0][3], descriptors[1][8])
        assertEquals(descriptors[0][4], descriptors[1][9])
        assertTrue(saveLayerImageFilterRefIndex > records.indexOf(descriptors[1]))
        assertTrue(drawRectIndex > saveLayerImageFilterRefIndex)
        assertEquals(descriptors[1][3], records[saveLayerImageFilterRefIndex][8])
        assertEquals(descriptors[1][4], records[saveLayerImageFilterRefIndex][9])
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun reusesLayerImageFilterHandleAcrossFrames() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val blur = JbrSkiaCommandRecorder.ImageFilterDescriptor.Blur(
            sigmaX = 2f,
            sigmaY = 3f,
            tileMode = 0,
        )

        JbrSkiaCommandRecorder.recordFrame {
            replayRedLayerWithImageFilter(blur)
        }
        val recording = JbrSkiaCommandRecorder.recordFrame {
            replayRedLayerWithImageFilter(blur)
        }

        assertEquals(0, recording.commands!!.countCommand(49))
        assertEquals(1, recording.commands.countCommand(55))
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun clearsLayerImageFilterHandleCacheForInteropSurfaceChange() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val blur = JbrSkiaCommandRecorder.ImageFilterDescriptor.Blur(
            sigmaX = 2f,
            sigmaY = 3f,
            tileMode = 0,
        )

        JbrSkiaCommandRecorder.recordFrame {
            replayRedLayerWithImageFilter(blur)
        }
        JbrSkiaCommandRecorder.clearInteropCachesForSurfaceChange()
        val recording = JbrSkiaCommandRecorder.recordFrame {
            replayRedLayerWithImageFilter(blur)
        }

        assertEquals(1, recording.commands!!.countCommand(49))
        assertEquals(1, recording.commands.countCommand(55))
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun nestedRecordingReplaysLayerImageFilterThenColorFilter() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val blur = JbrSkiaCommandRecorder.ImageFilterDescriptor.Blur(
            sigmaX = 2f,
            sigmaY = 3f,
            tileMode = 0,
        )
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
                    colorFilter = ColorFilter.tint(Color.Cyan, BlendMode.SrcIn),
                    imageFilter = blur,
                )
            )
        }

        val records = recording.commands!!.commandRecords()
        val descriptorIndex = records.indexOfFirst { it[0] == 49 }
        val saveLayerImageFilterRefIndex = records.indexOfFirst { it[0] == 55 }
        val saveLayerColorFilterIndex = records.indexOfFirst { it[0] == 44 }
        val drawRectIndex = records.indexOfFirst { it[0] == 2 && it[3] == Color.Red.toArgb() }
        assertTrue(descriptorIndex >= 0)
        assertTrue(saveLayerImageFilterRefIndex > descriptorIndex)
        assertTrue(saveLayerColorFilterIndex > saveLayerImageFilterRefIndex)
        assertTrue(drawRectIndex > saveLayerColorFilterIndex)
        assertEquals(records[descriptorIndex][3], records[saveLayerImageFilterRefIndex][8])
        assertEquals(records[descriptorIndex][4], records[saveLayerImageFilterRefIndex][9])
        assertEquals(500, records[saveLayerImageFilterRefIndex][7])
        assertEquals(Color.Cyan.toArgb(), records[saveLayerColorFilterIndex][8])
        assertEquals(2, records[saveLayerColorFilterIndex][9])
        assertEquals(1000, records[saveLayerColorFilterIndex][7])
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun nestedRecordingReplaysLayerImageFilterThenBlendMode() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val blur = JbrSkiaCommandRecorder.ImageFilterDescriptor.Blur(
            sigmaX = 2f,
            sigmaY = 3f,
            tileMode = 0,
        )
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
                    imageFilter = blur,
                )
            )
        }

        val records = recording.commands!!.commandRecords()
        val descriptorIndex = records.indexOfFirst { it[0] == 49 }
        val saveLayerImageFilterRefIndex = records.indexOfFirst { it[0] == 55 }
        val saveLayerBlendModeIndex = records.indexOfFirst { it[0] == 50 }
        val drawRectIndex = records.indexOfFirst { it[0] == 2 && it[3] == Color.Red.toArgb() }
        assertTrue(descriptorIndex >= 0)
        assertTrue(saveLayerImageFilterRefIndex > descriptorIndex)
        assertTrue(saveLayerBlendModeIndex > saveLayerImageFilterRefIndex)
        assertTrue(drawRectIndex > saveLayerBlendModeIndex)
        assertEquals(records[descriptorIndex][3], records[saveLayerImageFilterRefIndex][8])
        assertEquals(records[descriptorIndex][4], records[saveLayerImageFilterRefIndex][9])
        assertEquals(500, records[saveLayerImageFilterRefIndex][7])
        assertEquals(1000, records[saveLayerBlendModeIndex][7])
        assertEquals(1, records[saveLayerBlendModeIndex][8])
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun nestedRecordingReplaysLayerImageFilterThenColorMatrixFilter() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val blur = JbrSkiaCommandRecorder.ImageFilterDescriptor.Blur(
            sigmaX = 2f,
            sigmaY = 3f,
            tileMode = 0,
        )
        val matrix = ColorMatrix().also { it[0, 4] = 64f }
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
                    colorFilter = ColorFilter.colorMatrix(matrix),
                    imageFilter = blur,
                )
            )
        }

        val records = recording.commands!!.commandRecords()
        val imageFilterDescriptorIndex = records.indexOfFirst { it[0] == 49 && it[5] == 4 }
        val colorFilterDescriptorIndex = records.indexOfFirst { it[0] == 49 && it[5] == 2 }
        val saveLayerImageFilterRefIndex = records.indexOfFirst { it[0] == 55 }
        val saveLayerColorFilterRefIndex = records.indexOfFirst { it[0] == 52 }
        val drawRectIndex = records.indexOfFirst { it[0] == 2 && it[3] == Color.Red.toArgb() }
        assertTrue(imageFilterDescriptorIndex >= 0)
        assertTrue(colorFilterDescriptorIndex >= 0)
        assertTrue(saveLayerImageFilterRefIndex > imageFilterDescriptorIndex)
        assertTrue(saveLayerColorFilterRefIndex > saveLayerImageFilterRefIndex)
        assertTrue(drawRectIndex > saveLayerColorFilterRefIndex)
        assertEquals(records[imageFilterDescriptorIndex][3], records[saveLayerImageFilterRefIndex][8])
        assertEquals(records[imageFilterDescriptorIndex][4], records[saveLayerImageFilterRefIndex][9])
        assertEquals(records[colorFilterDescriptorIndex][3], records[saveLayerColorFilterRefIndex][8])
        assertEquals(records[colorFilterDescriptorIndex][4], records[saveLayerColorFilterRefIndex][9])
        assertEquals(500, records[saveLayerImageFilterRefIndex][7])
        assertEquals(1000, records[saveLayerColorFilterRefIndex][7])
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun nestedRecordingReplaysLayerImageFilterThenBlendModeAndColorMatrixFilter() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val blur = JbrSkiaCommandRecorder.ImageFilterDescriptor.Blur(
            sigmaX = 2f,
            sigmaY = 3f,
            tileMode = 0,
        )
        val matrix = ColorMatrix().also { it[0, 4] = 64f }
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
                    colorFilter = ColorFilter.colorMatrix(matrix),
                    imageFilter = blur,
                )
            )
        }

        val records = recording.commands!!.commandRecords()
        val imageFilterDescriptorIndex = records.indexOfFirst { it[0] == 49 && it[5] == 4 }
        val colorFilterDescriptorIndex = records.indexOfFirst { it[0] == 49 && it[5] == 2 }
        val saveLayerImageFilterRefIndex = records.indexOfFirst { it[0] == 55 }
        val saveLayerBlendColorFilterRefIndex = records.indexOfFirst { it[0] == 54 }
        val drawRectIndex = records.indexOfFirst { it[0] == 2 && it[3] == Color.Red.toArgb() }
        assertTrue(imageFilterDescriptorIndex >= 0)
        assertTrue(colorFilterDescriptorIndex >= 0)
        assertTrue(saveLayerImageFilterRefIndex > imageFilterDescriptorIndex)
        assertTrue(saveLayerBlendColorFilterRefIndex > saveLayerImageFilterRefIndex)
        assertTrue(drawRectIndex > saveLayerBlendColorFilterRefIndex)
        assertEquals(records[imageFilterDescriptorIndex][3], records[saveLayerImageFilterRefIndex][8])
        assertEquals(records[imageFilterDescriptorIndex][4], records[saveLayerImageFilterRefIndex][9])
        assertEquals(500, records[saveLayerImageFilterRefIndex][7])
        assertEquals(1000, records[saveLayerBlendColorFilterRefIndex][7])
        assertEquals(1, records[saveLayerBlendColorFilterRefIndex][8])
        assertEquals(records[colorFilterDescriptorIndex][3], records[saveLayerBlendColorFilterRefIndex][9])
        assertEquals(records[colorFilterDescriptorIndex][4], records[saveLayerBlendColorFilterRefIndex][10])
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun nestedRecordingReplaysLayerNestedImageFilterThenBlendModeAndColorMatrixFilter() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val blur = JbrSkiaCommandRecorder.ImageFilterDescriptor.Blur(
            sigmaX = 2f,
            sigmaY = 3f,
            tileMode = 0,
        )
        val offset = JbrSkiaCommandRecorder.ImageFilterDescriptor.Offset(
            dx = 4f,
            dy = 5f,
            input = blur,
        )
        val matrix = ColorMatrix().also { it[0, 4] = 64f }
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
                    colorFilter = ColorFilter.colorMatrix(matrix),
                    imageFilter = offset,
                )
            )
        }

        val records = recording.commands!!.commandRecords()
        val blurDescriptorIndex = records.indexOfFirst { it[0] == 49 && it[5] == 4 }
        val offsetDescriptorIndex = records.indexOfFirst { it[0] == 49 && it[5] == 7 }
        val colorFilterDescriptorIndex = records.indexOfFirst { it[0] == 49 && it[5] == 2 }
        val saveLayerImageFilterRefIndex = records.indexOfFirst { it[0] == 55 }
        val saveLayerBlendColorFilterRefIndex = records.indexOfFirst { it[0] == 54 }
        val drawRectIndex = records.indexOfFirst { it[0] == 2 && it[3] == Color.Red.toArgb() }
        assertTrue(blurDescriptorIndex >= 0)
        assertTrue(offsetDescriptorIndex > blurDescriptorIndex)
        assertTrue(colorFilterDescriptorIndex > offsetDescriptorIndex)
        assertTrue(saveLayerImageFilterRefIndex > offsetDescriptorIndex)
        assertTrue(saveLayerBlendColorFilterRefIndex > saveLayerImageFilterRefIndex)
        assertTrue(drawRectIndex > saveLayerBlendColorFilterRefIndex)
        assertEquals(records[blurDescriptorIndex][3], records[offsetDescriptorIndex][8])
        assertEquals(records[blurDescriptorIndex][4], records[offsetDescriptorIndex][9])
        assertEquals(records[offsetDescriptorIndex][3], records[saveLayerImageFilterRefIndex][8])
        assertEquals(records[offsetDescriptorIndex][4], records[saveLayerImageFilterRefIndex][9])
        assertEquals(500, records[saveLayerImageFilterRefIndex][7])
        assertEquals(1000, records[saveLayerBlendColorFilterRefIndex][7])
        assertEquals(1, records[saveLayerBlendColorFilterRefIndex][8])
        assertEquals(records[colorFilterDescriptorIndex][3], records[saveLayerBlendColorFilterRefIndex][9])
        assertEquals(records[colorFilterDescriptorIndex][4], records[saveLayerBlendColorFilterRefIndex][10])
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun nestedRecordingReplaysRectangularLayerShadow() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val recording = JbrSkiaCommandRecorder.recordFrame(
            shadowContext = JbrSkiaCommandShadowContext(
                lightX = 123f,
                lightY = -45f,
                lightZ = 678f,
                lightRadius = 901f,
                ambientShadowAlpha = 0.07f,
                spotShadowAlpha = 0.27f,
            ),
        ) {
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
                    shadowElevation = 8f,
                    ambientShadowColor = Color.Black,
                    spotShadowColor = Color.Black,
                )
            )
        }

        val records = recording.commands!!.commandRecords()
        val shadowPathIndex = records.indexOfFirst { it[0] == 64 }
        val contentLayerIndex = records.indexOfFirst { it[0] == 13 }
        val contentRectIndex = records.indexOfFirst { it[0] == 2 && it[3] == Color.Red.toArgb() }
        assertTrue(shadowPathIndex >= 0)
        assertEquals(Color.Black.copy(alpha = 0.07f * 0.5f).toArgb(), records[shadowPathIndex][3])
        assertEquals(Color.Black.copy(alpha = 0.27f * 0.5f).toArgb(), records[shadowPathIndex][4])
        assertEquals(8f.toRawBits(), records[shadowPathIndex][7])
        assertEquals(123f.toRawBits(), records[shadowPathIndex][8])
        assertEquals((-45f).toRawBits(), records[shadowPathIndex][9])
        assertEquals(678f.toRawBits(), records[shadowPathIndex][10])
        assertEquals(901f.toRawBits(), records[shadowPathIndex][11])
        assertEquals(1, records[shadowPathIndex][12])
        assertEquals(0, records[shadowPathIndex][13])
        assertTrue(records[shadowPathIndex][14] > 0)
        assertTrue(contentLayerIndex > shadowPathIndex)
        assertTrue(contentRectIndex > contentLayerIndex)
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun nestedRecordingReplaysLayerShadowPathBeforeShadowFill() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val shadowPath = Path().apply {
            moveTo(0f, 0f)
            lineTo(30f, 0f)
            lineTo(30f, 40f)
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
                    clipPath = null,
                    blendMode = null,
                    shadowElevation = 8f,
                    ambientShadowColor = Color.Black,
                    spotShadowColor = Color.Black,
                    shadowPath = shadowPath,
                )
            )
        }

        val records = recording.commands!!.commandRecords()
        val shadowPathIndex = records.indexOfFirst { it[0] == 64 }
        val contentLayerIndex = records.indexOfFirst { it[0] == 13 }
        assertTrue(shadowPathIndex >= 0)
        assertEquals(0, records[shadowPathIndex][13])
        assertTrue(records[shadowPathIndex][14] > 0)
        assertTrue(contentLayerIndex > shadowPathIndex)
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun nestedRecordingReplaysOffscreenLayerBoundsClipBeforeContent() {
        val recording = JbrSkiaCommandRecorder.recordFrame {
            val nested = JbrSkiaCommandRecorder.recordNested {
                JbrSkiaCommandRecorder.drawRect(
                    left = -20f,
                    top = -10f,
                    right = 50f,
                    bottom = 60f,
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
                    shadowElevation = 0f,
                    ambientShadowColor = Color.Black,
                    spotShadowColor = Color.Black,
                    shadowPath = null,
                    clipToLayerBounds = true,
                )
            )
        }

        val records = recording.commands!!.commandRecords()
        val contentLayerIndex = records.indexOfFirst { it[0] == 13 }
        val boundsClipIndex = records.indexOfFirst {
            it[0] == 9 && it[3] == 0 && it[4] == 0 && it[5] == 30 && it[6] == 40 && it[7] == 0
        }
        val childFillIndex = records.indexOfFirst { it[0] == 2 && it[3] == Color.Red.toArgb() }
        assertTrue(contentLayerIndex >= 0)
        assertTrue(boundsClipIndex > contentLayerIndex)
        assertTrue(childFillIndex > boundsClipIndex)
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
                1246972723, 106, 0, 9, 1, 1,
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
                1246972723, 106, 0, 9, 1, 1,
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
                1246972723, 106, 0, 9, 1, 1,
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
                1246972723, 106, 0, 9, 1, 1,
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
                1246972723, 106, 0, 9, 1, 1,
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
                1246972723, 106, 0, 9, 1, 1,
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
                1246972723, 106, 0, 9, 1, 1,
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
                1246972723, 106, 0, 9, 1, 1,
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
                1246972723, 106, 0, 9, 1, 1,
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
                1246972723, 106, 0, 9, 1, 1,
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
                1246972723, 106, 0, 9, 1, 1,
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
                1246972723, 106, 0, 9, 1, 1,
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
                1246972723, 106, 0, 9, 1, 1,
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
                1246972723, 106, 0, 9, 1, 1,
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
                1246972723, 106, 0, 9, 1, 1,
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
                1246972723, 106, 0, 9, 1, 1,
                41, 36, 1, Color.Red.copy(alpha = 0.25f).toArgb(), 17, 1, 2, 10, 20,
            ),
            commands,
        )
    }

    @Test
    fun wrapsSolidLineBlendModeInLayerRecord() {
        val recording = JbrSkiaCommandRecorder.recordFrame {
            JbrSkiaCommandRecorder.drawLine(
                p1 = Offset(1f, 2f),
                p2 = Offset(11f, 2f),
                paint = Paint().apply {
                    color = Color.Red
                    strokeWidth = 4f
                    blendMode = BlendMode.Plus
                },
            )
        }

        val records = recording.commands!!.commandRecords()
        assertArrayEquals(intArrayOf(50, 36, 0, -2, -1, 16, 6, 1000, 1), records[0])
        assertEquals(3, records[1][0])
        assertArrayEquals(intArrayOf(8, 12, 0), records[2])
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun wrapsSolidStrokeRectBlendModeInLayerRecord() {
        val recording = JbrSkiaCommandRecorder.recordFrame {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 11f,
                bottom = 22f,
                paint = Paint().apply {
                    color = Color.Red
                    style = PaintingStyle.Stroke
                    strokeWidth = 4f
                    blendMode = BlendMode.Plus
                },
            )
        }

        val records = recording.commands!!.commandRecords()
        assertArrayEquals(intArrayOf(50, 36, 0, -2, -1, 16, 26, 1000, 1), records[0])
        assertEquals(3, records[1][0])
        assertEquals(3, records[2][0])
        assertEquals(3, records[3][0])
        assertEquals(3, records[4][0])
        assertArrayEquals(intArrayOf(8, 12, 0), records[5])
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun wrapsSolidRoundRectOvalAndArcBlendModesInLayerRecords() {
        val recording = JbrSkiaCommandRecorder.recordFrame {
            val paint = Paint().apply {
                color = Color.Green
                blendMode = BlendMode.Plus
            }
            JbrSkiaCommandRecorder.drawRoundRect(1f, 2f, 11f, 22f, 3f, 4f, paint)
            JbrSkiaCommandRecorder.drawOval(3f, 4f, 13f, 24f, paint)
            JbrSkiaCommandRecorder.drawArc(5f, 6f, 15f, 26f, 0f, 90f, true, paint)
        }

        val records = recording.commands!!.commandRecords()
        assertArrayEquals(intArrayOf(50, 36, 0, 1, 2, 10, 20, 1000, 1), records[0])
        assertEquals(23, records[1][0])
        assertArrayEquals(intArrayOf(8, 12, 0), records[2])
        assertArrayEquals(intArrayOf(50, 36, 0, 3, 4, 10, 20, 1000, 1), records[3])
        assertEquals(4, records[4][0])
        assertArrayEquals(intArrayOf(8, 12, 0), records[5])
        assertArrayEquals(intArrayOf(50, 36, 0, 5, 6, 10, 20, 1000, 1), records[6])
        assertEquals(22, records[7][0])
        assertArrayEquals(intArrayOf(8, 12, 0), records[8])
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun wrapsDashedPrimitiveBlendModesInLayerRecords() {
        val path = Path().apply {
            moveTo(1f, 2f)
            lineTo(11f, 12f)
            lineTo(21f, 2f)
            close()
        }
        val paint = Paint().apply {
            color = Color.Red
            style = PaintingStyle.Stroke
            strokeWidth = 4f
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f), 0f)
            blendMode = BlendMode.Plus
        }

        val recording = JbrSkiaCommandRecorder.recordFrame {
            JbrSkiaCommandRecorder.drawLine(Offset(1f, 2f), Offset(11f, 2f), paint)
            JbrSkiaCommandRecorder.drawRect(1f, 2f, 11f, 22f, paint)
            JbrSkiaCommandRecorder.drawRoundRect(3f, 4f, 13f, 24f, 2f, 2f, paint)
            JbrSkiaCommandRecorder.drawPath(path, paint)
        }

        val records = recording.commands!!.commandRecords()
        assertArrayEquals(intArrayOf(50, 36, 0, -2, -1, 16, 6, 1000, 1), records[0])
        assertEquals(43, records[1][0])
        assertArrayEquals(intArrayOf(8, 12, 0), records[2])
        assertArrayEquals(intArrayOf(50, 36, 0, -2, -1, 16, 26, 1000, 1), records[3])
        assertEquals(59, records[4][0])
        assertArrayEquals(intArrayOf(8, 12, 0), records[5])
        assertArrayEquals(intArrayOf(50, 36, 0, 0, 1, 16, 26, 1000, 1), records[6])
        assertEquals(60, records[7][0])
        assertArrayEquals(intArrayOf(8, 12, 0), records[8])
        assertArrayEquals(intArrayOf(50, 36, 0, -2, -1, 26, 16, 1000, 1), records[9])
        assertEquals(61, records[10][0])
        assertArrayEquals(intArrayOf(8, 12, 0), records[11])
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun wrapsSolidPathBlendModeInLayerRecord() {
        val path = Path().apply {
            moveTo(1f, 2f)
            lineTo(11f, 12f)
            lineTo(21f, 2f)
            close()
        }

        val recording = JbrSkiaCommandRecorder.recordFrame {
            JbrSkiaCommandRecorder.drawPath(
                path,
                Paint().apply {
                    color = Color.Blue
                    blendMode = BlendMode.Plus
                },
            )
        }

        val records = recording.commands!!.commandRecords()
        assertArrayEquals(intArrayOf(50, 36, 0, 1, 2, 20, 10, 1000, 1), records[0])
        assertEquals(21, records[1][0])
        assertArrayEquals(intArrayOf(8, 12, 0), records[2])
        assertEquals(0, recording.unsupportedCount)
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
                1246972723, 106, 0, 10, 1, 1,
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
                    1246972723, 106, 0, 20, 1, 1,
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
            intArrayOf(1246972723, 106, 0, 38, 1, 1, 49, 112, 0),
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
                1246972723, 106, 0, 20, 1, 1,
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
                    1246972723, 106, 0, 10, 1, 1,
                    47, 40, 1, Color.Magenta.toArgb(), Color.Cyan.toArgb(), 2, 5, 6, 10, 20,
                ),
                commands,
            )
        }
    }

    @Test
    fun clearsTintColorFilterHandleCacheForInteropSurfaceChange() {
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

            JbrSkiaCommandRecorder.clearInteropCachesForSurfaceChange()

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
            }!!

            assertTrue(commands.contains(49))
        }
    }

    @Test
    fun evictsOldestColorFilterHandleBeforeRedefiningAfterThreshold() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        withColorFilterHandles {
            val firstColor = Color(0xff000000.toInt())

            val commands = JbrSkiaCommandRecorder.record {
                repeat(1025) { index ->
                    JbrSkiaCommandRecorder.drawRect(
                        left = 3f,
                        top = 4f,
                        right = 13f,
                        bottom = 24f,
                        paint = Paint().apply {
                            color = Color.Magenta
                            colorFilter = ColorFilter.tint(Color(0xff000000.toInt() or index))
                        },
                    )
                }
                JbrSkiaCommandRecorder.drawRect(
                    left = 5f,
                    top = 6f,
                    right = 15f,
                    bottom = 26f,
                    paint = Paint().apply {
                        color = Color.Magenta
                        colorFilter = ColorFilter.tint(firstColor)
                    },
                )
            }!!

            assertEquals(1026, commands.countCommand(49))
            assertEquals(2, commands.countCommand(48))
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
                1246972723, 106, 0, 16, 1, 1,
                43, 64, 1, Color.White.toArgb(), 1, 2, 11, 12, 8, 0, 1, 0, 3000, 2, 16000, 10000,
            ),
            commands,
        )
    }

    @Test
    fun writesDashedStrokeRectRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRect(
                left = 1f,
                top = 2f,
                right = 21f,
                bottom = 32f,
                paint = Paint().apply {
                    color = Color.White
                    style = PaintingStyle.Stroke
                    strokeWidth = 8f
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f), 3f)
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 106, 0, 16, 1, 1,
                59, 64, 1, Color.White.toArgb(), 1, 2, 20, 30, 8, 0, 1, 0, 3000, 2, 16000, 10000,
            ),
            commands,
        )
    }

    @Test
    fun writesDashedStrokeRoundRectRecord() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRoundRect(
                left = 1f,
                top = 2f,
                right = 21f,
                bottom = 32f,
                radiusX = 4f,
                radiusY = 5f,
                paint = Paint().apply {
                    color = Color.White
                    style = PaintingStyle.Stroke
                    strokeWidth = 8f
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f), 3f)
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 106, 0, 18, 1, 1,
                60, 72, 1, Color.White.toArgb(), 1000, 2000, 21000, 32000, 4000, 5000,
                8, 0, 1, 0, 3000, 2, 16000, 10000,
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
                1246972723, 106, 0, 12, 1, 1,
                3, 48, 1, Color.White.toArgb(), 1, 2, 11, 12, 3, 1, 2, 4500,
            ),
            commands,
        )
    }

    @Test
    fun writesPointLineRecords() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawPointLines(
                points = listOf(
                    Offset(1f, 2f),
                    Offset(11f, 12f),
                    Offset(21f, 22f),
                    Offset(31f, 32f),
                    Offset(41f, 42f),
                ),
                paint = Paint().apply {
                    color = Color.White
                    strokeWidth = 3f
                    strokeCap = StrokeCap.Round
                    strokeJoin = StrokeJoin.Bevel
                    strokeMiterLimit = 4.5f
                },
                stepBy = 2,
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 106, 0, 24, 1, 1,
                3, 48, 1, Color.White.toArgb(), 1, 2, 11, 12, 3, 1, 2, 4500,
                3, 48, 1, Color.White.toArgb(), 21, 22, 31, 32, 3, 1, 2, 4500,
            ),
            commands,
        )
    }

    @Test
    fun writesRawPointPolygonRecords() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawRawPointLines(
                points = floatArrayOf(1f, 2f, 11f, 12f, 21f, 22f),
                paint = Paint().apply {
                    color = Color.White
                    strokeWidth = 3f
                    strokeCap = StrokeCap.Round
                    strokeJoin = StrokeJoin.Bevel
                    strokeMiterLimit = 4.5f
                },
                stepBy = 1,
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 106, 0, 24, 1, 1,
                3, 48, 1, Color.White.toArgb(), 1, 2, 11, 12, 3, 1, 2, 4500,
                3, 48, 1, Color.White.toArgb(), 11, 12, 21, 22, 3, 1, 2, 4500,
            ),
            commands,
        )
    }

    @Test
    fun writesPointRecords() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.drawPoints(
                points = listOf(
                    Offset(1.25f, 2.5f),
                    Offset(11.75f, 12.125f),
                ),
                paint = Paint().apply {
                    color = Color.White
                    strokeWidth = 3.5f
                    strokeCap = StrokeCap.Round
                    strokeJoin = StrokeJoin.Bevel
                    strokeMiterLimit = 4.5f
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 106, 0, 13, 1, 1,
                65, 52, 1, Color.White.toArgb(), 4, 1, 2, 4500, 2, 1, 3, 12, 12,
            ),
            commands,
        )
    }

    @Test
    fun wrapsSolidPointsBlendModeInLayerRecords() {
        val recording = JbrSkiaCommandRecorder.recordFrame {
            val paint = Paint().apply {
                color = Color.White
                strokeWidth = 4f
                blendMode = BlendMode.Plus
            }
            JbrSkiaCommandRecorder.drawPoints(listOf(Offset(1f, 2f), Offset(11f, 2f)), paint)
            JbrSkiaCommandRecorder.drawRawPoints(floatArrayOf(5f, 6f, 15f, 6f), paint)
        }

        val records = recording.commands!!.commandRecords()
        assertArrayEquals(intArrayOf(50, 36, 0, -2, -1, 16, 6, 1000, 1), records[0])
        assertEquals(65, records[1][0])
        assertArrayEquals(intArrayOf(8, 12, 0), records[2])
        assertArrayEquals(intArrayOf(50, 36, 0, 2, 3, 16, 6, 1000, 1), records[3])
        assertEquals(65, records[4][0])
        assertArrayEquals(intArrayOf(8, 12, 0), records[5])
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun writesVerticesRecords() {
        val vertices = Vertices(
            vertexMode = VertexMode.Triangles,
            positions = listOf(Offset(1.25f, 2.5f), Offset(11.75f, 12.125f), Offset(4f, 20f)),
            textureCoordinates = listOf(Offset.Zero, Offset(1f, 0f), Offset(0f, 1f)),
            colors = listOf(Color.Red, Color.Green, Color.Blue),
            indices = listOf(0, 1, 2),
        )

        val commands = JbrSkiaCommandRecorder.record {
            assertTrue(
                JbrSkiaCommandRecorder.drawVertices(
                    vertices = vertices,
                    blendMode = BlendMode.SrcOver,
                    paint = Paint().apply { color = Color.White },
                )
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 106, 0, 26, 1, 1,
                67, 104, 1, 0, 18, Color.White.toArgb(), 3, 3,
                1, 3, 12, 12, 4, 20,
                0f.toRawBits(), 0f.toRawBits(), 1f.toRawBits(), 0f.toRawBits(), 0f.toRawBits(), 1f.toRawBits(),
                Color.Red.toArgb(), Color.Green.toArgb(), Color.Blue.toArgb(),
                0, 1, 2,
            ),
            commands,
        )
    }

    @Test
    fun wrapsVerticesPaintBlendModeInLayerRecord() {
        val vertices = Vertices(
            vertexMode = VertexMode.Triangles,
            positions = listOf(Offset(1f, 2f), Offset(11f, 12f), Offset(4f, 20f)),
            textureCoordinates = listOf(Offset.Zero, Offset(1f, 0f), Offset(0f, 1f)),
            colors = listOf(Color.Red, Color.Green, Color.Blue),
            indices = listOf(0, 1, 2),
        )

        val recording = JbrSkiaCommandRecorder.recordFrame {
            assertTrue(
                JbrSkiaCommandRecorder.drawVertices(
                    vertices = vertices,
                    blendMode = BlendMode.SrcOver,
                    paint = Paint().apply {
                        color = Color.White
                        blendMode = BlendMode.Plus
                    },
                )
            )
        }

        val records = recording.commands!!.commandRecords()
        assertArrayEquals(intArrayOf(50, 36, 0, 1, 2, 10, 18, 1000, 1), records[0])
        assertEquals(67, records[1][0])
        assertArrayEquals(intArrayOf(8, 12, 0), records[2])
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun recordsCanvasDrawVertices() {
        val vertices = Vertices(
            vertexMode = VertexMode.Triangles,
            positions = listOf(Offset(1f, 2f), Offset(11f, 12f), Offset(4f, 20f)),
            textureCoordinates = listOf(Offset.Zero, Offset(1f, 0f), Offset(0f, 1f)),
            colors = listOf(Color.Red, Color.Green, Color.Blue),
            indices = listOf(0, 1, 2),
        )

        val commands = JbrSkiaCommandRecorder.record {
            Canvas(ImageBitmap(1, 1)).drawVertices(
                vertices = vertices,
                blendMode = BlendMode.SrcOver,
                paint = Paint().apply { color = Color.White },
            )
        }

        assertEquals(0, JbrSkiaCommandRecorder.recordFrame {
            Canvas(ImageBitmap(1, 1)).drawVertices(vertices, BlendMode.SrcOver, Paint())
        }.unsupportedCount)
        assertArrayEquals(
            intArrayOf(
                1246972723, 106, 0, 26, 1, 1,
                67, 104, 1, 0, 18, Color.White.toArgb(), 3, 3,
                1, 2, 11, 12, 4, 20,
                0f.toRawBits(), 0f.toRawBits(), 1f.toRawBits(), 0f.toRawBits(), 0f.toRawBits(), 1f.toRawBits(),
                Color.Red.toArgb(), Color.Green.toArgb(), Color.Blue.toArgb(),
                0, 1, 2,
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
                1246972723, 106, 0, 29, 1, 1,
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
    fun writesConcatMatrix33Record() {
        val matrix = Matrix().apply {
            values[Matrix.ScaleX] = 1.25f
            values[Matrix.SkewX] = 0.5f
            values[Matrix.TranslateX] = 7f
            values[Matrix.SkewY] = -0.25f
            values[Matrix.ScaleY] = 0.75f
            values[Matrix.TranslateY] = 9f
            values[Matrix.Perspective0] = 0.001f
            values[Matrix.Perspective1] = -0.002f
        }

        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.concat(matrix)
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 106, 0, 12, 1, 1,
                63, 48, 0,
                1.25f.toRawBits(),
                0.5f.toRawBits(),
                7f.toRawBits(),
                (-0.25f).toRawBits(),
                0.75f.toRawBits(),
                9f.toRawBits(),
                0.001f.toRawBits(),
                (-0.002f).toRawBits(),
                1f.toRawBits(),
            ),
            commands,
        )
    }

    @Test
    fun writesSkewAsConcatMatrix33Record() {
        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.skew(0.5f, -0.25f)
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 106, 0, 12, 1, 1,
                63, 48, 0,
                1f.toRawBits(),
                0.5f.toRawBits(),
                0f.toRawBits(),
                (-0.25f).toRawBits(),
                1f.toRawBits(),
                0f.toRawBits(),
                0f.toRawBits(),
                0f.toRawBits(),
                1f.toRawBits(),
            ),
            commands,
        )
    }

    @Test
    fun recordsCanvasSkewTransform() {
        val commands = JbrSkiaCommandRecorder.record {
            Canvas(ImageBitmap(1, 1)).skew(0.5f, -0.25f)
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 106, 0, 12, 1, 1,
                63, 48, 0,
                1f.toRawBits(),
                0.5f.toRawBits(),
                0f.toRawBits(),
                (-0.25f).toRawBits(),
                1f.toRawBits(),
                0f.toRawBits(),
                0f.toRawBits(),
                0f.toRawBits(),
                1f.toRawBits(),
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
                1246972723, 106, 0, 16, 1, 1,
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
                1246972723, 106, 0, 19, 1, 1,
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
                1246972723, 106, 0, 24, 1, 1,
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
    fun writesDashedStrokePathRecord() {
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
                    color = Color.Green
                    style = PaintingStyle.Stroke
                    strokeWidth = 8f
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f), 3f)
                },
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 106, 0, 27, 1, 1,
                61, 108, 1, Color.Green.toArgb(), 8, 0, 1, 0, 3000, 2, 16000, 10000, 0, 13,
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
    fun writesCornerPathEffectDescriptorPathRecord() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
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
                    color = Color.Green
                    style = PaintingStyle.Stroke
                    strokeWidth = 8f
                    pathEffect = PathEffect.cornerPathEffect(6f)
                },
            )
        }!!

        assertEquals(41, commands.size)
        assertArrayEquals(
            intArrayOf(1246972723, 106, 0, 35, 1, 1, 49, 36, 0),
            commands.copyOfRange(0, 9),
        )
        assertEquals(9, commands[11])
        assertEquals(1, commands[12])
        assertEquals(1, commands[13])
        assertEquals(6f.toRawBits(), commands[14])
        assertArrayEquals(
            intArrayOf(
                62, 104, 1, 1, Color.Green.toArgb(), 8, 0, 1, 0, commands[9], commands[10], 0, 13,
                0, 1000, 2000,
                1, 11000, 12000,
                1, 21000, 2000,
                1, 1000, 2000,
                4,
            ),
            commands.copyOfRange(15, 41),
        )
    }

    @Test
    fun wrapsPathEffectDescriptorBlendModeInLayerRecord() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val path = Path().apply {
            moveTo(1f, 2f)
            lineTo(11f, 12f)
            lineTo(21f, 2f)
            close()
        }

        val recording = JbrSkiaCommandRecorder.recordFrame {
            JbrSkiaCommandRecorder.drawPath(
                path,
                Paint().apply {
                    color = Color.Green
                    style = PaintingStyle.Stroke
                    strokeWidth = 8f
                    pathEffect = PathEffect.cornerPathEffect(6f)
                    blendMode = BlendMode.Plus
                },
            )
        }

        val records = recording.commands!!.commandRecords()
        assertEquals(49, records[0][0])
        assertArrayEquals(intArrayOf(50, 36, 0, -4, -3, 30, 20, 1000, 1), records[1])
        assertEquals(62, records[2][0])
        assertArrayEquals(intArrayOf(8, 12, 0), records[3])
        assertEquals(0, recording.unsupportedCount)
    }

    @Test
    fun rejectsPathEffectDescriptorColorFilterInStrictMode() {
        val path = Path().apply {
            moveTo(1f, 2f)
            lineTo(11f, 12f)
            lineTo(21f, 2f)
            close()
        }

        withStrictCommandRecording {
            val commands = JbrSkiaCommandRecorder.record {
                JbrSkiaCommandRecorder.drawPath(
                    path,
                    Paint().apply {
                        color = Color.Green
                        style = PaintingStyle.Stroke
                        strokeWidth = 8f
                        colorFilter = ColorFilter.tint(Color.Cyan, BlendMode.SrcIn)
                        pathEffect = PathEffect.cornerPathEffect(6f)
                    },
                )
            }

            assertNull(commands)
        }
    }

    @Test
    fun writesStampedPathEffectDescriptorPathRecord() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val stamp = Path().apply {
            moveTo(0f, -5f)
            lineTo(5f, 5f)
            lineTo(-5f, 5f)
            close()
        }
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
                    color = Color.Green
                    style = PaintingStyle.Stroke
                    strokeWidth = 8f
                    pathEffect = PathEffect.stampedPathEffect(stamp, 18f, 3f, StampedPathEffectStyle.Rotate)
                },
            )
        }!!

        assertEquals(58, commands.size)
        assertArrayEquals(
            intArrayOf(1246972723, 106, 0, 52, 1, 1, 49, 104, 0),
            commands.copyOfRange(0, 9),
        )
        assertEquals(10, commands[11])
        assertEquals(1, commands[12])
        assertEquals(18, commands[13])
        assertArrayEquals(
            intArrayOf(
                18f.toRawBits(), 3f.toRawBits(), 1, 0, 13,
                0, 0, -5000,
                1, 5000, 5000,
                1, -5000, 5000,
                1, 0, -5000,
                4,
            ),
            commands.copyOfRange(14, 32),
        )
        assertArrayEquals(
            intArrayOf(
                62, 104, 1, 1, Color.Green.toArgb(), 8, 0, 1, 0, commands[9], commands[10], 0, 13,
                0, 1000, 2000,
                1, 11000, 12000,
                1, 21000, 2000,
                1, 1000, 2000,
                4,
            ),
            commands.copyOfRange(32, 58),
        )
    }

    @Test
    fun writesChainedPathEffectDescriptorPathRecord() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val stamp = Path().apply {
            moveTo(0f, -5f)
            lineTo(5f, 5f)
            lineTo(-5f, 5f)
            close()
        }
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
                    color = Color.Green
                    style = PaintingStyle.Stroke
                    strokeWidth = 8f
                    pathEffect = PathEffect.chainPathEffect(
                        outer = PathEffect.cornerPathEffect(6f),
                        inner = PathEffect.stampedPathEffect(stamp, 18f, 3f, StampedPathEffectStyle.Rotate),
                    )
                },
            )
        }!!

        assertEquals(79, commands.size)
        assertArrayEquals(intArrayOf(1246972723, 106, 0, 73, 1, 1), commands.copyOfRange(0, 6))
        assertArrayEquals(intArrayOf(49, 36, 0), commands.copyOfRange(6, 9))
        assertEquals(9, commands[11])
        assertArrayEquals(intArrayOf(49, 104, 0), commands.copyOfRange(15, 18))
        assertEquals(10, commands[20])
        assertArrayEquals(intArrayOf(49, 48, 0), commands.copyOfRange(41, 44))
        assertEquals(11, commands[46])
        assertArrayEquals(
            intArrayOf(commands[9], commands[10], commands[18], commands[19]),
            commands.copyOfRange(49, 53),
        )
        assertArrayEquals(
            intArrayOf(62, 104, 1, 1, Color.Green.toArgb(), 8, 0, 1, 0, commands[44], commands[45], 0, 13),
            commands.copyOfRange(53, 66),
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
                1246972723, 106, 0, 28, 1, 1,
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
                1246972723, 106, 0, 27, 1, 1,
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
                1246972723, 106, 0, 25, 1, 1,
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
                1246972723, 106, 0, 16, 1, 1,
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
                1246972723, 106, 0, 15, 1, 1,
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
                1246972723, 106, 0, 17, 1, 1,
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
                1246972723, 106, 0, 21, 1, 1,
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
                1246972723, 106, 0, 23, 1, 1,
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
                1246972723, 106, 0, 19, 1, 1,
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
                1246972723, 106, 0, 16, 1, 1,
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
                1246972723, 106, 0, 14, 1, 1,
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
                1246972723, 106, 0, 18, 1, 1,
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
                1246972723, 106, 0, 16, 1, 1,
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
                1246972723, 106, 0, 20, 1, 1,
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
                1246972723, 106, 0, 20, 1, 1,
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
                1246972723, 106, 0, 18, 1, 1,
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
                1246972723, 106, 0, 22, 1, 1,
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
    fun rejectsShaderDescriptorStrokeRectInStrictMode() {
        withStrictCommandRecording {
            val commands = JbrSkiaCommandRecorder.record {
                JbrSkiaCommandRecorder.drawRect(
                    left = 1f,
                    top = 2f,
                    right = 11f,
                    bottom = 12f,
                    paint = Paint().apply {
                        style = PaintingStyle.Stroke
                        strokeWidth = 2f
                        shader = ColorShader(Color.Red)
                    },
                )
            }

            assertNull(commands)
        }
    }

    @Test
    fun rejectsImageShaderStrokeRectInStrictMode() {
        withStrictCommandRecording {
            val image = onePixelImage(0x22)
            val commands = JbrSkiaCommandRecorder.record {
                JbrSkiaCommandRecorder.drawRect(
                    left = 1f,
                    top = 2f,
                    right = 11f,
                    bottom = 12f,
                    paint = Paint().apply {
                        style = PaintingStyle.Stroke
                        strokeWidth = 2f
                        shader = ImageShader(image, TileMode.Repeated, TileMode.Mirror)
                    },
                )
            }

            assertNull(commands)
        }
    }

    @Test
    fun writesColorShaderDescriptorRectInStrictMode() {
        withStrictCommandRecording {
            val commands = JbrSkiaCommandRecorder.record {
                JbrSkiaCommandRecorder.drawRect(
                    left = 1f,
                    top = 2f,
                    right = 11f,
                    bottom = 12f,
                    paint = Paint().apply {
                        shader = ColorShader(Color.Red)
                    },
                )
            }

            assertNotNull(commands)
            commands!!
            assertEquals(1, commands.countCommand(56))
            assertEquals(1, commands.countCommand(58))
            val descriptor = commands.commandRecords().single { it[0] == 56 }
            assertEquals(9, descriptor[5])
            assertEquals(1, descriptor[6])
            assertEquals(1, descriptor[7])
            assertEquals(Color.Red.toArgb(), descriptor[8])
        }
    }

    @Test
    fun reusesColorShaderHandleAcrossFrames() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val shader = ColorShader(Color.Red)

        withStrictCommandRecording {
            JbrSkiaCommandRecorder.record {
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

            val commands = JbrSkiaCommandRecorder.record {
                JbrSkiaCommandRecorder.drawRect(
                    left = 3f,
                    top = 4f,
                    right = 13f,
                    bottom = 14f,
                    paint = Paint().apply {
                        this.shader = shader
                    },
                )
            }!!

            assertEquals(0, commands.countCommand(56))
            assertEquals(1, commands.countCommand(58))
        }
    }

    @Test
    fun clearsColorShaderHandleCacheForInteropSurfaceChange() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val shader = ColorShader(Color.Red)

        withStrictCommandRecording {
            JbrSkiaCommandRecorder.record {
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

            JbrSkiaCommandRecorder.clearInteropCachesForSurfaceChange()

            val commands = JbrSkiaCommandRecorder.record {
                JbrSkiaCommandRecorder.drawRect(
                    left = 3f,
                    top = 4f,
                    right = 13f,
                    bottom = 14f,
                    paint = Paint().apply {
                        this.shader = shader
                    },
                )
            }!!

            assertEquals(1, commands.countCommand(56))
            assertEquals(1, commands.countCommand(58))
        }
    }

    @Test
    fun writesPerlinNoiseShaderDescriptorRectInStrictMode() {
        withStrictCommandRecording {
            val commands = JbrSkiaCommandRecorder.record {
                JbrSkiaCommandRecorder.drawRect(
                    left = 1f,
                    top = 2f,
                    right = 11f,
                    bottom = 12f,
                    paint = Paint().apply {
                        shader = TurbulenceShader(
                            baseFrequencyX = 0.035f,
                            baseFrequencyY = 0.055f,
                            numOctaves = 3,
                            seed = 7.25f,
                        )
                    },
                )
            }

            assertNotNull(commands)
            commands!!
            assertEquals(1, commands.countCommand(56))
            assertEquals(1, commands.countCommand(58))
            val descriptor = commands.commandRecords().single { it[0] == 56 }
            assertEquals(10, descriptor[5])
            assertEquals(1, descriptor[6])
            assertEquals(7, descriptor[7])
            assertEquals(1, descriptor[8])
            assertEquals(35000, descriptor[9])
            assertEquals(55000, descriptor[10])
            assertEquals(3, descriptor[11])
            assertEquals(7250, descriptor[12])
            assertEquals(0, descriptor[13])
            assertEquals(0, descriptor[14])
        }
    }

    @Test
    fun writesTransformedGradientShaderDescriptorRectInStrictMode() {
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

            assertNotNull(commands)
            commands!!
            assertEquals(2, commands.countCommand(56))
            assertEquals(1, commands.countCommand(58))

            val shaderDescriptors = commands.commandRecords().filter { it[0] == 56 }
            val childDescriptor = shaderDescriptors.first()
            val transformDescriptor = shaderDescriptors.last()
            assertEquals(1, childDescriptor[5])
            assertEquals(8, transformDescriptor[5])
            assertEquals(1, transformDescriptor[6])
            assertEquals(11, transformDescriptor[7])
            assertEquals(childDescriptor[3], transformDescriptor[8])
            assertEquals(childDescriptor[4], transformDescriptor[9])
            assertEquals(1000, transformDescriptor[10])
            assertEquals(0, transformDescriptor[11])
            assertEquals(3000, transformDescriptor[12])
            assertEquals(0, transformDescriptor[13])
            assertEquals(1000, transformDescriptor[14])
            assertEquals(4000, transformDescriptor[15])
            assertEquals(0, transformDescriptor[16])
            assertEquals(0, transformDescriptor[17])
            assertEquals(1000, transformDescriptor[18])
        }
    }

    @Test
    fun writesCompositeShaderDescriptorRectInStrictMode() {
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

            assertNotNull(commands)
            commands!!
            assertEquals(3, commands.countCommand(56))
            assertEquals(1, commands.countCommand(58))
            assertTrue(commands.joinToString(), commands.containsSubsequence(58, 40, 1))
        }
    }

    @Test
    fun writesCompositeShaderWithColorFilterDescriptorRectInStrictMode() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
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
                        colorFilter = ColorFilter.tint(Color.Cyan, BlendMode.SrcIn)
                    },
                )
            }

            assertNotNull(commands)
            commands!!
            assertEquals(1, commands.countCommand(49))
            assertEquals(4, commands.countCommand(56))
            assertEquals(1, commands.countCommand(58))
            val shaderDescriptors = commands.commandRecords().filter { it[0] == 56 }
            assertEquals(5, shaderDescriptors[2][5])
            assertEquals(7, shaderDescriptors[3][5])
        }
    }

    @Test
    fun compositeShaderKeepsJbrSkiaMetadataForStructuredChildren() {
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

        val composite = shader.jbrSkiaCompositeShader
        assertEquals(BlendMode.SrcOver, composite?.blendMode)
        assertTrue(composite?.dst?.jbrSkiaLinearGradient != null)
        assertTrue(composite?.src?.jbrSkiaRadialGradient != null)
    }

    @Test
    fun compositeShaderKeepsJbrSkiaMetadataForPerlinNoiseChildren() {
        val shader = CompositeShader(
            dst = FractalNoiseShader(
                baseFrequencyX = 0.03f,
                baseFrequencyY = 0.04f,
                numOctaves = 2,
                seed = 7f,
                tileWidth = 64,
                tileHeight = 64,
            ),
            src = TurbulenceShader(
                baseFrequencyX = 0.05f,
                baseFrequencyY = 0.06f,
                numOctaves = 3,
                seed = 11f,
                tileWidth = 32,
                tileHeight = 32,
            ),
            blendMode = BlendMode.SrcOver,
        )

        val composite = shader.jbrSkiaCompositeShader
        assertEquals(BlendMode.SrcOver, composite?.blendMode)
        assertTrue(composite?.dst?.jbrSkiaPerlinNoiseShader != null)
        assertTrue(composite?.src?.jbrSkiaPerlinNoiseShader != null)
    }

    @Test
    fun writesCompositePerlinNoiseShaderDescriptorRectInStrictMode() {
        val shader = CompositeShader(
            dst = FractalNoiseShader(
                baseFrequencyX = 0.03f,
                baseFrequencyY = 0.04f,
                numOctaves = 2,
                seed = 7f,
                tileWidth = 64,
                tileHeight = 64,
            ),
            src = TurbulenceShader(
                baseFrequencyX = 0.05f,
                baseFrequencyY = 0.06f,
                numOctaves = 3,
                seed = 11f,
                tileWidth = 32,
                tileHeight = 32,
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

            assertNotNull(commands)
            commands!!
            assertEquals(3, commands.countCommand(56))
            assertEquals(1, commands.countCommand(58))
            val shaderDescriptors = commands.commandRecords().filter { it[0] == 56 }
            assertEquals(10, shaderDescriptors[0][5])
            assertEquals(10, shaderDescriptors[1][5])
            assertEquals(5, shaderDescriptors[2][5])
        }
    }

    @Test
    fun writesLinearGradientShaderWithColorFilterDescriptorRectInStrictMode() {
        val shader = LinearGradientShader(
            from = Offset(1f, 2f),
            to = Offset(11f, 12f),
            colors = listOf(Color.Red, Color.Blue),
            colorStops = listOf(0.25f, 0.75f),
            tileMode = TileMode.Clamp,
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
                        colorFilter = ColorFilter.tint(Color.Cyan, BlendMode.SrcIn)
                    },
                )
            }

            assertNotNull(commands)
            commands!!
            assertEquals(1, commands.countCommand(49))
            assertEquals(2, commands.countCommand(56))
            assertEquals(1, commands.countCommand(58))
            val shaderDescriptors = commands.commandRecords().filter { it[0] == 56 }
            assertEquals(1, shaderDescriptors.first()[5])
            assertEquals(7, shaderDescriptors.last()[5])
            assertEquals(1, shaderDescriptors.last()[6])
            assertEquals(4, shaderDescriptors.last()[7])
        }
    }

    @OptIn(ExperimentalGraphicsApi::class)
    @Test
    fun runtimeEffectShaderKeepsJbrSkiaMetadata() {
        val shader = RuntimeEffectShader(
            sksl = """
                uniform float red;
                half4 main(float2 p) {
                    return half4(red, 0.25, 0.75, 1.0);
                }
            """.trimIndent(),
            uniforms = floatArrayOf(0.5f),
            uniformSchema = listOf(RuntimeEffectUniform("red", 0, 1)),
        )

        val runtimeEffect = shader.jbrSkiaRuntimeEffectShader
        assertEquals(floatArrayOf(0.5f).toList(), runtimeEffect?.uniforms?.toList())
        assertEquals(listOf(RuntimeEffectUniform("red", 0, 1)), runtimeEffect?.uniformSchema)
        assertEquals(emptyList<RuntimeEffectChild>(), runtimeEffect?.namedChildren)
        assertTrue(runtimeEffect?.sksl?.contains("uniform float red") == true)
    }

    @OptIn(ExperimentalGraphicsApi::class)
    @Test
    fun runtimeEffectColorFilterKeepsJbrSkiaMetadata() {
        val colorFilter = RuntimeEffectColorFilter(
            sksl = """
                uniform colorFilter content;
                uniform float phase;
                half4 main(half4 inColor) {
                    return content.eval(half4(inColor.r * phase, inColor.g, inColor.b, inColor.a));
                }
            """.trimIndent(),
            uniforms = floatArrayOf(0.5f),
            uniformSchema = listOf(RuntimeEffectUniform("phase", 0, 1)),
            namedChildren = listOf(RuntimeEffectColorFilterChild("content", ColorFilter.tint(Color.Red))),
        )

        val runtimeEffect = (colorFilter as? JbrSkiaRuntimeEffectColorFilterHolder)?.jbrSkiaRuntimeEffectColorFilter
        assertEquals(floatArrayOf(0.5f).toList(), runtimeEffect?.uniforms?.toList())
        assertEquals(listOf(RuntimeEffectUniform("phase", 0, 1)), runtimeEffect?.uniformSchema)
        assertEquals(emptyList<ColorFilter>(), runtimeEffect?.children)
        assertEquals(1, runtimeEffect?.namedChildren?.size)
        assertEquals("content", runtimeEffect?.namedChildren?.singleOrNull()?.name)
        assertTrue(runtimeEffect?.sksl?.contains("uniform float phase") == true)
    }

    @OptIn(ExperimentalGraphicsApi::class)
    @Test
    fun writesRuntimeEffectColorFilterDescriptorRectInStrictMode() {
        val sksl = """
            uniform float phase;
            half4 main(half4 inColor) {
                return half4(inColor.r * phase, inColor.g, inColor.b, inColor.a);
            }
        """.trimIndent()
        val colorFilter = RuntimeEffectColorFilter(
            sksl = sksl,
            uniforms = floatArrayOf(0.5f),
            uniformSchema = listOf(RuntimeEffectUniform("phase", 0, 1)),
        )

        withStrictCommandRecording {
            val commands = JbrSkiaCommandRecorder.record {
                JbrSkiaCommandRecorder.drawRect(
                    left = 1f,
                    top = 2f,
                    right = 11f,
                    bottom = 12f,
                    paint = Paint().apply {
                        this.colorFilter = colorFilter
                    },
                )
            }

            assertNotNull(commands)
            commands!!
            assertEquals(1, commands.countCommand(49))
            assertEquals(1, commands.countCommand(47))
            val descriptor = commands.commandRecords().single { it[0] == 49 }
            assertEquals(8, descriptor[5])
            assertEquals(1, descriptor[6])
            assertTrue(descriptor[8] > 0)
            assertEquals(1, descriptor[9])
            assertEquals(0, descriptor[10])
            assertEquals(1, descriptor[11])
            assertEquals(0, descriptor[12])
            assertEquals(sksl.shaderSourceHash().highInt(), descriptor[13])
            assertEquals(sksl.shaderSourceHash().lowInt(), descriptor[14])
            assertEquals(0, descriptor[15])
            assertEquals(1, descriptor[16])
            assertEquals(5, descriptor[17])
            assertEquals("phase".map { it.code }, descriptor.copyOfRange(18, 23).toList())
        }
    }

    @OptIn(ExperimentalGraphicsApi::class)
    @Test
    fun writesRuntimeEffectColorFilterDescriptorWithNamedChildInStrictMode() {
        val sksl = """
            uniform colorFilter content;
            half4 main(half4 inColor) {
                return content.eval(inColor).bgra;
            }
        """.trimIndent()
        val colorFilter = RuntimeEffectColorFilter(
            sksl = sksl,
            namedChildren = listOf(RuntimeEffectColorFilterChild("content", ColorFilter.tint(Color.Red))),
        )

        withStrictCommandRecording {
            val commands = JbrSkiaCommandRecorder.record {
                JbrSkiaCommandRecorder.drawRect(
                    left = 1f,
                    top = 2f,
                    right = 11f,
                    bottom = 12f,
                    paint = Paint().apply {
                        this.colorFilter = colorFilter
                    },
                )
            }

            assertNotNull(commands)
            commands!!
            assertEquals(2, commands.countCommand(49))
            assertEquals(1, commands.countCommand(47))
            val runtimeDescriptor = commands.commandRecords().last { it[0] == 49 }
            assertEquals(8, runtimeDescriptor[5])
            assertEquals(1, runtimeDescriptor[6])
            assertEquals(1, runtimeDescriptor[10])
            assertEquals(0, runtimeDescriptor[11])
            assertEquals(1, runtimeDescriptor[12])
            assertEquals(sksl.shaderSourceHash().highInt(), runtimeDescriptor[13])
            assertEquals(sksl.shaderSourceHash().lowInt(), runtimeDescriptor[14])
            assertTrue(runtimeDescriptor[15] != 0 || runtimeDescriptor[16] != 0)
            assertEquals(0, runtimeDescriptor[17])
            assertEquals(7, runtimeDescriptor[18])
            assertEquals("content".map { it.code }, runtimeDescriptor.copyOfRange(19, 26).toList())
        }
    }

    @OptIn(ExperimentalGraphicsApi::class)
    @Test
    fun rejectsInvalidRuntimeEffectColorFilterUniformSchemaInStrictMode() {
        val sksl = """
            uniform float phase;
            half4 main(half4 inColor) {
                return half4(inColor.r * phase, inColor.g, inColor.b, inColor.a);
            }
        """.trimIndent()

        withStrictCommandRecording {
            listOf(
                RuntimeEffectUniform("1phase", 0, 1),
                RuntimeEffectUniform("phase", Int.MAX_VALUE, 1),
                RuntimeEffectUniform("phase", 1, 1),
            ).forEach { uniform ->
                val colorFilter = RuntimeEffectColorFilter(
                    sksl = sksl,
                    uniforms = floatArrayOf(0.5f),
                    uniformSchema = listOf(uniform),
                )
                assertNull(
                    JbrSkiaCommandRecorder.record {
                        JbrSkiaCommandRecorder.drawRect(
                            left = 1f,
                            top = 2f,
                            right = 11f,
                            bottom = 12f,
                            paint = Paint().apply {
                                this.colorFilter = colorFilter
                            },
                        )
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalGraphicsApi::class)
    @Test
    fun rejectsInvalidRuntimeEffectColorFilterNamedChildSchemaInStrictMode() {
        val sksl = """
            uniform colorFilter content;
            half4 main(half4 inColor) {
                return content.eval(inColor).bgra;
            }
        """.trimIndent()

        withStrictCommandRecording {
            val colorFilter = RuntimeEffectColorFilter(
                sksl = sksl,
                namedChildren = listOf(
                    RuntimeEffectColorFilterChild("1content", ColorFilter.tint(Color.Red))
                ),
            )
            assertNull(
                JbrSkiaCommandRecorder.record {
                    JbrSkiaCommandRecorder.drawRect(
                        left = 1f,
                        top = 2f,
                        right = 11f,
                        bottom = 12f,
                        paint = Paint().apply {
                            this.colorFilter = colorFilter
                        },
                    )
                }
            )
        }
    }

    @OptIn(ExperimentalGraphicsApi::class)
    @Test
    fun rejectsInvalidNestedRuntimeEffectColorFilterChildInStrictMode() {
        val childSksl = """
            uniform float phase;
            half4 main(half4 inColor) {
                return half4(inColor.r * phase, inColor.g, inColor.b, inColor.a);
            }
        """.trimIndent()
        val parentSksl = """
            uniform colorFilter content;
            half4 main(half4 inColor) {
                return content.eval(inColor).bgra;
            }
        """.trimIndent()

        withStrictCommandRecording {
            val childColorFilter = RuntimeEffectColorFilter(
                sksl = childSksl,
                uniforms = floatArrayOf(0.5f),
                uniformSchema = listOf(RuntimeEffectUniform("1phase", 0, 1)),
            )
            val colorFilter = RuntimeEffectColorFilter(
                sksl = parentSksl,
                namedChildren = listOf(RuntimeEffectColorFilterChild("content", childColorFilter)),
            )
            assertNull(
                JbrSkiaCommandRecorder.record {
                    JbrSkiaCommandRecorder.drawRect(
                        left = 1f,
                        top = 2f,
                        right = 11f,
                        bottom = 12f,
                        paint = Paint().apply {
                            this.colorFilter = colorFilter
                        },
                    )
                }
            )
        }
    }

    @OptIn(ExperimentalGraphicsApi::class)
    @Test
    fun writesRuntimeEffectShaderWithColorFilterDescriptorRectInStrictMode() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val sksl = """
            half4 main(float2 p) {
                return half4(0.25, 0.50, 0.75, 1.0);
            }
        """.trimIndent()
        val shader = RuntimeEffectShader(sksl = sksl)
        val colorFilter = ColorFilter.tint(Color.Red)

        withStrictCommandRecording {
            val commands = JbrSkiaCommandRecorder.record {
                JbrSkiaCommandRecorder.drawRect(
                    left = 1f,
                    top = 2f,
                    right = 11f,
                    bottom = 12f,
                    paint = Paint().apply {
                        this.shader = shader
                        this.colorFilter = colorFilter
                    },
                )
            }

            assertNotNull(commands)
            commands!!
            assertEquals(1, commands.countCommand(49))
            assertEquals(2, commands.countCommand(56))
            assertEquals(1, commands.countCommand(58))
            val colorFilterDescriptor = commands.commandRecords().single { it[0] == 49 }
            val shaderDescriptor = commands.commandRecords().last { it[0] == 56 }
            assertEquals(1, colorFilterDescriptor[5])
            assertEquals(7, shaderDescriptor[5])
            assertEquals(1, shaderDescriptor[6])
            assertEquals(4, shaderDescriptor[7])
            assertTrue(shaderDescriptor[8] != 0 || shaderDescriptor[9] != 0)
            assertEquals(colorFilterDescriptor[3], shaderDescriptor[10])
            assertEquals(colorFilterDescriptor[4], shaderDescriptor[11])
        }
    }

    @OptIn(ExperimentalGraphicsApi::class)
    @Test
    fun writesRuntimeEffectShaderDescriptorRectInStrictMode() {
        val sksl = """
            uniform float red;
            half4 main(float2 p) {
                return half4(red, 0.25, 0.75, 1.0);
            }
        """.trimIndent()
        val shader = RuntimeEffectShader(
            sksl = sksl,
            uniforms = floatArrayOf(0.5f),
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

            assertNotNull(commands)
            commands!!
            assertEquals(1, commands.countCommand(56))
            assertEquals(1, commands.countCommand(58))
            val descriptor = commands.commandRecords().single { it[0] == 56 }
            assertEquals(6, descriptor[5])
            assertEquals(1, descriptor[6])
            assertTrue(descriptor[8] > 0)
            assertEquals(0, descriptor[11])
            assertEquals(0, descriptor[12])
            assertEquals(sksl.shaderSourceHash().highInt(), descriptor[13])
            assertEquals(sksl.shaderSourceHash().lowInt(), descriptor[14])
        }
    }

    @OptIn(ExperimentalGraphicsApi::class)
    @Test
    fun writesRuntimeEffectNamedUniformSchemaInStrictMode() {
        val sksl = """
            uniform float phase;
            half4 main(float2 p) {
                return half4(phase, 0.25, 0.75, 1.0);
            }
        """.trimIndent()
        val shader = RuntimeEffectShader(
            sksl = sksl,
            uniforms = floatArrayOf(0.5f),
            uniformSchema = listOf(RuntimeEffectUniform("phase", 0, 1)),
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

            assertNotNull(commands)
            commands!!
            val descriptor = commands.commandRecords().single { it[0] == 56 }
            assertEquals(6, descriptor[5])
            assertEquals(1, descriptor[11])
            assertEquals(0, descriptor[12])
            assertEquals(sksl.shaderSourceHash().highInt(), descriptor[13])
            assertEquals(sksl.shaderSourceHash().lowInt(), descriptor[14])
            assertEquals(0, descriptor[15])
            assertEquals(1, descriptor[16])
            assertEquals(5, descriptor[17])
            assertEquals("phase".map { it.code }, descriptor.copyOfRange(18, 23).toList())
        }
    }

    @OptIn(ExperimentalGraphicsApi::class)
    @Test
    fun rejectsInvalidRuntimeEffectUniformSchemaInStrictMode() {
        val sksl = """
            uniform float phase;
            half4 main(float2 p) {
                return half4(phase, 0.25, 0.75, 1.0);
            }
        """.trimIndent()

        withStrictCommandRecording {
            listOf(
                RuntimeEffectUniform("1phase", 0, 1),
                RuntimeEffectUniform("phase", Int.MAX_VALUE, 1),
                RuntimeEffectUniform("phase", 1, 1),
            ).forEach { uniform ->
                val shader = RuntimeEffectShader(
                    sksl = sksl,
                    uniforms = floatArrayOf(0.5f),
                    uniformSchema = listOf(uniform),
                )
                assertNull(
                    JbrSkiaCommandRecorder.record {
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
                )
            }
        }
    }

    @OptIn(ExperimentalGraphicsApi::class)
    @Test
    fun writesRuntimeEffectShaderDescriptorWithChildShaderInStrictMode() {
        val child = LinearGradientShader(
            from = Offset(1f, 2f),
            to = Offset(11f, 12f),
            colors = listOf(Color.Red, Color.Blue),
            colorStops = listOf(0.25f, 0.75f),
            tileMode = TileMode.Clamp,
        )
        val sksl = """
            uniform shader content;
            half4 main(float2 p) {
                return content.eval(p);
            }
        """.trimIndent()
        val shader = RuntimeEffectShader(
            sksl = sksl,
            children = listOf(child),
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

            assertNotNull(commands)
            commands!!
            assertTrue(commands.countCommand(56) >= 1)
            assertEquals(1, commands.countCommand(58))
            val runtimeDescriptor = commands.commandRecords().last { it[0] == 56 }
            assertEquals(6, runtimeDescriptor[5])
            assertEquals(1, runtimeDescriptor[10])
            assertEquals(0, runtimeDescriptor[11])
            assertEquals(0, runtimeDescriptor[12])
            assertEquals(sksl.shaderSourceHash().highInt(), runtimeDescriptor[13])
            assertEquals(sksl.shaderSourceHash().lowInt(), runtimeDescriptor[14])
        }
    }

    @OptIn(ExperimentalGraphicsApi::class)
    @Test
    fun writesRuntimeEffectNamedChildSchemaInStrictMode() {
        val child = LinearGradientShader(
            from = Offset(1f, 2f),
            to = Offset(11f, 12f),
            colors = listOf(Color.Red, Color.Blue),
            colorStops = listOf(0.25f, 0.75f),
            tileMode = TileMode.Clamp,
        )
        val sksl = """
            uniform shader content;
            half4 main(float2 p) {
                return content.eval(p);
            }
        """.trimIndent()
        val shader = RuntimeEffectShader(
            sksl = sksl,
            namedChildren = listOf(RuntimeEffectChild("content", child)),
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

            assertNotNull(commands)
            commands!!
            val runtimeDescriptor = commands.commandRecords().last { it[0] == 56 }
            assertEquals(6, runtimeDescriptor[5])
            assertEquals(1, runtimeDescriptor[10])
            assertEquals(0, runtimeDescriptor[11])
            assertEquals(1, runtimeDescriptor[12])
            assertEquals(sksl.shaderSourceHash().highInt(), runtimeDescriptor[13])
            assertEquals(sksl.shaderSourceHash().lowInt(), runtimeDescriptor[14])
            assertEquals(0, runtimeDescriptor[17])
            assertEquals(7, runtimeDescriptor[18])
            assertEquals("content".map { it.code }, runtimeDescriptor.copyOfRange(19, 26).toList())
        }
    }

    @OptIn(ExperimentalGraphicsApi::class)
    @Test
    fun rejectsInvalidRuntimeEffectNamedChildSchemaInStrictMode() {
        val child = LinearGradientShader(
            from = Offset(1f, 2f),
            to = Offset(11f, 12f),
            colors = listOf(Color.Red, Color.Blue),
            colorStops = listOf(0.25f, 0.75f),
            tileMode = TileMode.Clamp,
        )
        val sksl = """
            uniform shader content;
            half4 main(float2 p) {
                return content.eval(p);
            }
        """.trimIndent()

        withStrictCommandRecording {
            val shader = RuntimeEffectShader(
                sksl = sksl,
                namedChildren = listOf(RuntimeEffectChild("1content", child)),
            )
            assertNull(
                JbrSkiaCommandRecorder.record {
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
            )
        }
    }

    @OptIn(ExperimentalGraphicsApi::class)
    @Test
    fun rejectsInvalidNestedRuntimeEffectShaderChildInStrictMode() {
        val childSksl = """
            uniform float phase;
            half4 main(float2 p) {
                return half4(phase, 0.25, 0.75, 1.0);
            }
        """.trimIndent()
        val parentSksl = """
            uniform shader content;
            half4 main(float2 p) {
                return content.eval(p);
            }
        """.trimIndent()

        withStrictCommandRecording {
            val child = RuntimeEffectShader(
                sksl = childSksl,
                uniforms = floatArrayOf(0.5f),
                uniformSchema = listOf(RuntimeEffectUniform("1phase", 0, 1)),
            )
            val shader = RuntimeEffectShader(
                sksl = parentSksl,
                namedChildren = listOf(RuntimeEffectChild("content", child)),
            )
            assertNull(
                JbrSkiaCommandRecorder.record {
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
            )
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
                1246972723, 106, 0, 11, 1, 1,
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
                1246972723, 106, 0, 13, 1, 1,
                44, 40, 0, 1, 2, 10, 10, 360, Color.Cyan.toArgb(), 2,
                8, 12, 0,
            ),
            commands,
        )
    }

    @Test
    fun writesSaveLayerColorMatrixFilterHandleRecord() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val matrix = ColorMatrix().also { it[0, 4] = 64f }

        val commands = JbrSkiaCommandRecorder.record {
            JbrSkiaCommandRecorder.saveLayer(
                bounds = Rect(1f, 2f, 11f, 12f),
                paint = Paint().apply {
                    color = Color.White.copy(alpha = 0.6f)
                    colorFilter = ColorFilter.colorMatrix(matrix)
                },
            )
            JbrSkiaCommandRecorder.restore()
        }!!

        val records = commands.commandRecords()
        assertEquals(49, records[0][0])
        assertEquals(2, records[0][5])
        assertEquals(52, records[1][0])
        assertEquals(1, records[1][3])
        assertEquals(2, records[1][4])
        assertEquals(10, records[1][5])
        assertEquals(10, records[1][6])
        assertEquals(360, records[1][7])
        assertEquals(records[0][3], records[1][8])
        assertEquals(records[0][4], records[1][9])
        assertEquals(8, records[2][0])
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
                1246972723, 106, 0, 12, 1, 1,
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
                1246972723, 106, 0, 29, 1, 1,
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
    fun rejectsImagePathEffectInStrictMode() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val image = onePixelImage(0x44)

        withStrictCommandRecording {
            val commands = JbrSkiaCommandRecorder.record {
                JbrSkiaCommandRecorder.drawImageRect(
                    image = image,
                    srcLeft = 0f,
                    srcTop = 0f,
                    srcRight = 1f,
                    srcBottom = 1f,
                    dstLeft = 10f,
                    dstTop = 20f,
                    dstRight = 30f,
                    dstBottom = 40f,
                    paint = Paint().apply {
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 1f), 0f)
                    },
                )
            }

            assertNull(commands)
        }
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
                1246972723, 106, 0, 31, 1, 1,
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
    fun writesImageColorMatrixFilterHandleRecord() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val image = ImageBitmap(2, 2)
        Canvas(image).run {
            drawRect(0f, 0f, 1f, 1f, Paint().apply { color = Color.Red })
            drawRect(1f, 0f, 2f, 1f, Paint().apply { color = Color.Green })
            drawRect(0f, 1f, 1f, 2f, Paint().apply { color = Color.Blue })
            drawRect(1f, 1f, 2f, 2f, Paint().apply { color = Color.White })
        }
        val matrix = ColorMatrix().also { it[0, 4] = 64f }

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
                    colorFilter = ColorFilter.colorMatrix(matrix)
                },
            )
        }!!

        assertEquals(65, commands.size)
        assertArrayEquals(
            intArrayOf(
                1246972723, 106, 0, 59, 1, 1,
                15, 48, 0, -1599677274, -472603669, 2, 2, 4,
                Color.Red.toArgb(), Color.Green.toArgb(), Color.Blue.toArgb(), Color.White.toArgb(),
                49, 112, 0,
            ),
            commands.copyOfRange(0, 21),
        )
        assertEquals(2, commands[23])
        assertEquals(1, commands[24])
        assertEquals(20, commands[25])
        val expectedMatrix = ColorMatrix().also { it[0, 4] = 64f }.values.copyOf()
        expectedMatrix[4] *= 1f / 255f
        expectedMatrix[9] *= 1f / 255f
        expectedMatrix[14] *= 1f / 255f
        expectedMatrix[19] *= 1f / 255f
        assertArrayEquals(IntArray(20) { expectedMatrix[it].toRawBits() }, commands.copyOfRange(26, 46))
        assertArrayEquals(
            intArrayOf(
                53, 76, 1,
                0, 0, 2000, 2000,
                10000, 20000, 30000, 40000,
                -1599677274, -472603669, 2, 2, 502, FilterQuality.Medium.value,
                commands[21], commands[22],
            ),
            commands.copyOfRange(46, 65),
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

        assertEquals(106, commands[1])
        assertEquals(1, commands.countCommand(15))
        assertEquals(1, commands.countCommand(34))
        assertTrue(commands.joinToString(), commands.containsSubsequence(34, 56, 1, 2000, 3000, 42000, 33000))
        assertTrue(commands.joinToString(), commands.containsSubsequence(1, 1, 1, 2, 749))
    }

    @Test
    fun writesImageShaderWithColorFilterDescriptorRectInStrictMode() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val image = onePixelImage(7)

        withStrictCommandRecording {
            val commands = JbrSkiaCommandRecorder.record {
                JbrSkiaCommandRecorder.drawRect(
                    left = 2f,
                    top = 3f,
                    right = 42f,
                    bottom = 33f,
                    paint = Paint().apply {
                        shader = ImageShader(image, TileMode.Repeated, TileMode.Mirror)
                        colorFilter = ColorFilter.tint(Color.Cyan, BlendMode.SrcIn)
                        alpha = 0.75f
                        isAntiAlias = true
                    },
                )
            }

            assertNotNull(commands)
            commands!!
            assertEquals(1, commands.countCommand(15))
            assertEquals(1, commands.countCommand(49))
            assertEquals(2, commands.countCommand(56))
            assertEquals(1, commands.countCommand(58))
            val shaderDescriptors = commands.commandRecords().filter { it[0] == 56 }
            assertEquals(4, shaderDescriptors.first()[5])
            assertEquals(7, shaderDescriptors.last()[5])
        }
    }

    @Test
    fun evictsOldestShaderHandleBeforeRedefiningAfterThreshold() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val firstShader = ColorShader(Color(0xff000000.toInt()))

        withStrictCommandRecording {
            val commands = JbrSkiaCommandRecorder.record {
                repeat(1025) { index ->
                    JbrSkiaCommandRecorder.drawRect(
                        left = 2f,
                        top = 3f,
                        right = 42f,
                        bottom = 33f,
                        paint = Paint().apply {
                            shader = ColorShader(Color(0xff000000.toInt() or index))
                        },
                    )
                }
                JbrSkiaCommandRecorder.drawRect(
                    left = 2f,
                    top = 3f,
                    right = 42f,
                    bottom = 33f,
                    paint = Paint().apply {
                        shader = firstShader
                    },
                )
            }!!

            assertEquals(1026, commands.countCommand(56))
            assertEquals(2, commands.countCommand(57))
        }
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
    fun emitsImageCacheClearForInteropSurfaceChange() {
        JbrSkiaCommandRecorder.clearImageCacheForTesting()
        val image = onePixelImage(7)

        val firstFrame = JbrSkiaCommandRecorder.record {
            drawOnePixelImage(image)
        }!!
        JbrSkiaCommandRecorder.clearInteropCachesForSurfaceChange()
        val afterClear = JbrSkiaCommandRecorder.recordFrame {
            drawOnePixelImage(image)
        }

        assertEquals(1, firstFrame.countCommand(15))
        assertEquals(0, firstFrame.countCommand(18))
        assertEquals(1, afterClear.imageCacheClearCount)
        assertEquals(1, afterClear.imageDefineCount)
        assertEquals(1, afterClear.commands!!.countCommand(18))
        assertEquals(1, afterClear.commands.countCommand(15))
        assertArrayEquals(intArrayOf(18, 12, 0), afterClear.commands.commandRecords().first())
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
                fontWeight = 700,
                fontWidth = 5,
                fontSlant = 1,
                color = Color.White.toArgb(),
                antiAlias = true,
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 106, 0, 19, 1, 1,
                17, 76, 1, 1250, 18500, 13000, Color.White.toArgb(), 700, 5, 1, 5,
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
                fontWeight = 400,
                fontWidth = 5,
                fontSlant = 0,
                color = Color.White.toArgb(),
                antiAlias = true,
            )
        }

        assertArrayEquals(
            intArrayOf(
                1246972723, 106, 0, 16, 1, 1,
                17, 64, 1, 1250, 18500, 13000, Color.White.toArgb(), 400, 5, 0, 0, 4,
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
                1246972723, 106, 0, 32, 1, 1,
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

    private fun replayRedLayerWithImageFilter(imageFilter: JbrSkiaCommandRecorder.ImageFilterDescriptor) {
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
                imageFilter = imageFilter,
            )
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

    private fun String.shaderSourceHash(): Long {
        var hash = -3750763034362895579L
        forEach { char ->
            hash = hash xor char.code.toLong()
            hash *= 1099511628211L
        }
        return hash
    }

    private fun Long.highInt(): Int = (this ushr 32).toInt()

    private fun Long.lowInt(): Int = toInt()
}
