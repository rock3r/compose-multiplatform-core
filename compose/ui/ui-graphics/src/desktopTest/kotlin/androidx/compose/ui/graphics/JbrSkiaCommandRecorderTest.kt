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

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class JbrSkiaCommandRecorderTest {
    @Test
    fun writesAbi9AntialiasRecordFlag() {
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
                1246972723, 9, 0, 18, 1, 1,
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
                1246972723, 9, 0, 12, 1, 1,
                3, 48, 1, Color.White.toArgb(), 1, 2, 11, 12, 3, 1, 2, 4500,
            ),
            commands,
        )
    }
}
