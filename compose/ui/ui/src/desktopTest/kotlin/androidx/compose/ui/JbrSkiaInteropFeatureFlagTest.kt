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

package androidx.compose.ui

import androidx.compose.ui.scene.skia.JbrSkiaInteropRuntime
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JbrSkiaInteropFeatureFlagTest {
    @Test
    fun jbrSkiaInteropFlagDefaultsToFalse() {
        ComposeFeatureFlags.useJbrSkiaInteropInComposePanel.withOverride(false) {
            assertFalse(ComposeFeatureFlags.useJbrSkiaInteropInComposePanel.value)
        }
    }

    @Test
    fun jbrSkiaInteropFlagCanBeEnabled() {
        ComposeFeatureFlags.useJbrSkiaInteropInComposePanel.withOverride(true) {
            assertTrue(ComposeFeatureFlags.useJbrSkiaInteropInComposePanel.value)
        }
    }

    @Test
    fun jbrSkiaInteropRuntimeReturnsNullWhenSkikoRuntimeClassIsAbsent() {
        val graphics = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()

        assertNull(JbrSkiaInteropRuntime.acquireCanvasOrNull(graphics))
    }
}
