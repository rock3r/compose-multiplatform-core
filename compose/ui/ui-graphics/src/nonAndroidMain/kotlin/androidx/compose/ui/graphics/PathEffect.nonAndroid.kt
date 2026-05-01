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
import androidx.compose.ui.graphics.platform.PlatformGraphicsRegistry

@InternalComposeUiApi
data class JbrSkiaDashPathEffect(
    val intervals: FloatArray,
    val phase: Float,
)

@InternalComposeUiApi
sealed interface JbrSkiaPathEffectDescriptor {
    data class Corner(val radius: Float) : JbrSkiaPathEffectDescriptor
    data class Stamped(
        val shape: Path,
        val advance: Float,
        val phase: Float,
        val style: StampedPathEffectStyle,
    ) : JbrSkiaPathEffectDescriptor
}

@InternalComposeUiApi
interface JbrSkiaPathEffectMetadata {
    val jbrSkiaDashPathEffect: JbrSkiaDashPathEffect?
        get() = null

    val jbrSkiaPathEffectDescriptor: JbrSkiaPathEffectDescriptor?
        get() = null
}

@OptIn(InternalComposeUiApi::class)
internal actual fun actualCornerPathEffect(radius: Float): PathEffect =
    PlatformGraphicsRegistry.requireCurrent().createCornerPathEffect(radius)

@OptIn(InternalComposeUiApi::class)
internal actual fun actualDashPathEffect(
    intervals: FloatArray,
    phase: Float,
): PathEffect =
    PlatformGraphicsRegistry.requireCurrent().createDashPathEffect(intervals, phase)

@OptIn(InternalComposeUiApi::class)
internal actual fun actualChainPathEffect(outer: PathEffect, inner: PathEffect): PathEffect =
    PlatformGraphicsRegistry.requireCurrent().createChainPathEffect(outer, inner)

@OptIn(InternalComposeUiApi::class)
internal actual fun actualStampedPathEffect(
    shape: Path,
    advance: Float,
    phase: Float,
    style: StampedPathEffectStyle,
): PathEffect =
    PlatformGraphicsRegistry.requireCurrent().createStampedPathEffect(
        shape = shape,
        advance = advance,
        phase = phase,
        style = style,
    )
