/*
 * Copyright 2021 The Android Open Source Project
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

import org.jetbrains.skia.ColorFilter as SkColorFilter
import org.jetbrains.skia.ColorMatrix as SkColorMatrix
import org.jetbrains.skia.RuntimeEffect

internal actual typealias NativeColorFilter = SkColorFilter

/**
 * Obtain a [org.jetbrains.skia.ColorFilter] instance from this [ColorFilter]
 */
fun ColorFilter.asSkiaColorFilter(): SkColorFilter = nativeColorFilter

/**
 * Create a [ColorFilter] from the given [org.jetbrains.skia.ColorFilter] instance
 */
fun SkColorFilter.asComposeColorFilter(): ColorFilter = ColorFilter(this)

@OptIn(ExperimentalGraphicsApi::class)
internal data class JbrSkiaRuntimeEffectColorFilter(
    val sksl: String,
    val uniforms: FloatArray,
    val uniformSchema: List<RuntimeEffectUniform>,
    val children: List<ColorFilter>,
    val namedChildren: List<RuntimeEffectColorFilterChild>,
) {
    override fun equals(other: Any?): Boolean =
        other is JbrSkiaRuntimeEffectColorFilter &&
            sksl == other.sksl &&
            uniforms.contentEquals(other.uniforms) &&
            uniformSchema == other.uniformSchema &&
            children == other.children &&
            namedChildren == other.namedChildren

    override fun hashCode(): Int =
        31 * (31 * (31 * (31 * sksl.hashCode() + uniforms.contentHashCode()) + uniformSchema.hashCode()) +
            children.hashCode()) +
            namedChildren.hashCode()
}

@ExperimentalGraphicsApi
data class RuntimeEffectColorFilterChild(
    val name: String,
    val colorFilter: ColorFilter,
)

@OptIn(ExperimentalGraphicsApi::class)
internal class JbrSkiaRuntimeEffectColorFilterHolder(
    nativeColorFilter: NativeColorFilter,
    val jbrSkiaRuntimeEffectColorFilter: JbrSkiaRuntimeEffectColorFilter,
) : ColorFilter(nativeColorFilter)

@ExperimentalGraphicsApi
fun RuntimeEffectColorFilter(
    sksl: String,
    uniforms: FloatArray = FloatArray(0),
    uniformSchema: List<RuntimeEffectUniform> = emptyList(),
    children: List<ColorFilter> = emptyList(),
    namedChildren: List<RuntimeEffectColorFilterChild> = emptyList(),
): ColorFilter {
    val uniformCopy = uniforms.copyOf()
    val childColorFilters = children + namedChildren.map { it.colorFilter }
    val skiaColorFilter = RuntimeEffect.makeForColorFilter(sksl).use { effect ->
        uniformCopy.toUniformData().use { uniformData ->
            effect.makeColorFilter(uniformData, childColorFilters.map { it.nativeColorFilter }.toTypedArray())
        }
    }
    return JbrSkiaRuntimeEffectColorFilterHolder(
        nativeColorFilter = skiaColorFilter,
        jbrSkiaRuntimeEffectColorFilter = JbrSkiaRuntimeEffectColorFilter(
            sksl = sksl,
            uniforms = uniformCopy,
            uniformSchema = uniformSchema.toList(),
            children = children.toList(),
            namedChildren = namedChildren.toList(),
        ),
    )
}

internal actual fun actualTintColorFilter(color: Color, blendMode: BlendMode): NativeColorFilter =
    SkColorFilter.makeBlend(color.toArgb(), blendMode.toSkia())

/**
 * Remaps compose [ColorMatrix] to [org.jetbrains.skia.ColorMatrix] and returns [ColorFilter]
 * applying this matrix to draw color result
 */
internal actual fun actualColorMatrixColorFilter(colorMatrix: ColorMatrix): NativeColorFilter {
    val remappedValues = colorMatrix.values.copyOf()
    remappedValues[4] *= (1f / 255f)
    remappedValues[9] *= (1f / 255f)
    remappedValues[14] *= (1f / 255f)
    remappedValues[19] *= (1f / 255f)

    return SkColorFilter.makeMatrix(
        SkColorMatrix(remappedValues)
    )
}

internal actual fun actualLightingColorFilter(multiply: Color, add: Color): NativeColorFilter =
    SkColorFilter.makeLighting(multiply.toArgb(), add.toArgb())

// TODO: https://youtrack.jetbrains.com/issue/CMP-739
internal actual fun actualColorMatrixFromFilter(filter: NativeColorFilter): ColorMatrix =
    ColorMatrix()
