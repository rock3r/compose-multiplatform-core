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

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.platform.PlatformColorFilter
import androidx.compose.ui.graphics.platform.PlatformGraphicsRegistry

@OptIn(InternalComposeUiApi::class)
internal actual typealias NativeColorFilter = PlatformColorFilter

@InternalComposeUiApi
@OptIn(ExperimentalGraphicsApi::class)
data class JbrSkiaRuntimeEffectColorFilter(
    val sksl: String,
    val uniforms: FloatArray,
    val uniformSchema: List<RuntimeEffectUniform>,
) {
    override fun equals(other: Any?): Boolean =
        other is JbrSkiaRuntimeEffectColorFilter &&
            sksl == other.sksl &&
            uniforms.contentEquals(other.uniforms) &&
            uniformSchema == other.uniformSchema

    override fun hashCode(): Int =
        31 * (31 * sksl.hashCode() + uniforms.contentHashCode()) + uniformSchema.hashCode()
}

@OptIn(InternalComposeUiApi::class)
internal val ColorFilter.jbrSkiaRuntimeEffectColorFilter: JbrSkiaRuntimeEffectColorFilter?
    get() = nativeColorFilter.jbrSkiaRuntimeEffectColorFilter

@OptIn(InternalComposeUiApi::class)
internal actual fun actualTintColorFilter(color: Color, blendMode: BlendMode): NativeColorFilter =
    PlatformGraphicsRegistry.requireCurrent()
        .createTintColorFilter(color, blendMode)

@OptIn(InternalComposeUiApi::class)
internal actual fun actualColorMatrixColorFilter(colorMatrix: ColorMatrix): NativeColorFilter =
    PlatformGraphicsRegistry.requireCurrent()
        .createColorMatrixColorFilter(colorMatrix)

@OptIn(InternalComposeUiApi::class)
internal actual fun actualLightingColorFilter(multiply: Color, add: Color): NativeColorFilter =
        PlatformGraphicsRegistry.requireCurrent()
            .createLightingColorFilter(multiply, add)

@OptIn(InternalComposeUiApi::class)
internal actual fun actualColorMatrixFromFilter(filter: NativeColorFilter): ColorMatrix =
    PlatformGraphicsRegistry.requireCurrent()
        .colorMatrixFromFilter(filter)
