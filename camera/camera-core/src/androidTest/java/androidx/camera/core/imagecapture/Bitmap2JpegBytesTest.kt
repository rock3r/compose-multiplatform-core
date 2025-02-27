/*
 * Copyright 2022 The Android Open Source Project
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

package androidx.camera.core.imagecapture

import android.graphics.BitmapFactory.decodeByteArray
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.imagecapture.Utils.CAMERA_CAPTURE_RESULT
import androidx.camera.core.imagecapture.Utils.HEIGHT
import androidx.camera.core.imagecapture.Utils.WIDTH
import androidx.camera.core.processing.Packet
import androidx.camera.testing.ExifUtil.createExif
import androidx.camera.testing.TestImageUtil.createBitmap
import androidx.camera.testing.TestImageUtil.createJpegBytes
import androidx.camera.testing.TestImageUtil.getAverageDiff
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [JpegBytes2CroppedBitmap].
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 21)
class Bitmap2JpegBytesTest {

    private val operation = Bitmap2JpegBytes()
    @Test
    fun process_verifyOutput() {
        // Arrange.
        val bitmap = createBitmap(WIDTH, HEIGHT)
        val inputPacket = Packet.of(
            bitmap,
            createExif(createJpegBytes(WIDTH, HEIGHT)),
            Rect(0, 0, WIDTH, HEIGHT),
            90,
            Matrix(),
            CAMERA_CAPTURE_RESULT
        )
        val input = Bitmap2JpegBytes.In.of(inputPacket, 100)

        // Act.
        val output = operation.apply(input)

        // Assert
        val restoredBitmap = decodeByteArray(output.data, 0, output.data.size)
        assertThat(getAverageDiff(bitmap, restoredBitmap)).isEqualTo(0)
        assertThat(output.cameraCaptureResult).isEqualTo(CAMERA_CAPTURE_RESULT)
    }
}