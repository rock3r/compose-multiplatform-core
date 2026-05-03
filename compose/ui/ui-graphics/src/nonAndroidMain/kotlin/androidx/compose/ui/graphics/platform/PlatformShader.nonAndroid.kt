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

package androidx.compose.ui.graphics.platform

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.JbrSkiaLinearGradientShader
import androidx.compose.ui.graphics.JbrSkiaImageShader
import androidx.compose.ui.graphics.JbrSkiaCompositeShader
import androidx.compose.ui.graphics.JbrSkiaRuntimeEffectShader
import androidx.compose.ui.graphics.JbrSkiaTransformedShader
import androidx.compose.ui.graphics.JbrSkiaColorShader
import androidx.compose.ui.graphics.JbrSkiaPerlinNoiseShader
import androidx.compose.ui.graphics.JbrSkiaRadialGradientShader
import androidx.compose.ui.graphics.JbrSkiaSweepGradientShader
import androidx.compose.ui.graphics.Shader

/**
 * Opaque platform binding held by [Shader] so ui-graphics stays decoupled from any concrete
 * graphics backend. The registered backend provides the implementation and the interop extensions
 * to and from its native shader type.
 */
@InternalComposeUiApi
interface PlatformShader {
    val jbrSkiaLinearGradient: JbrSkiaLinearGradientShader?
        get() = null

    val jbrSkiaRadialGradient: JbrSkiaRadialGradientShader?
        get() = null

    val jbrSkiaSweepGradient: JbrSkiaSweepGradientShader?
        get() = null

    val jbrSkiaImageShader: JbrSkiaImageShader?
        get() = null

    val jbrSkiaCompositeShader: JbrSkiaCompositeShader?
        get() = null

    val jbrSkiaRuntimeEffectShader: JbrSkiaRuntimeEffectShader?
        get() = null

    val jbrSkiaTransformedShader: JbrSkiaTransformedShader?
        get() = null

    val jbrSkiaColorShader: JbrSkiaColorShader?
        get() = null

    val jbrSkiaPerlinNoiseShader: JbrSkiaPerlinNoiseShader?
        get() = null
}
