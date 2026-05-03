/*
 * Copyright 2020 The Android Open Source Project
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
import org.jetbrains.skia.Color4f
import org.jetbrains.skia.Data
import org.jetbrains.skia.Gradient
import org.jetbrains.skia.ISize
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.impl.use
import org.jetbrains.skia.Shader as SkShader

actual class Shader internal constructor(
    internal val internalSkiaShader: SkShader,
    internal val jbrSkiaLinearGradient: JbrSkiaLinearGradientShader? = null,
    internal val jbrSkiaRadialGradient: JbrSkiaRadialGradientShader? = null,
    internal val jbrSkiaSweepGradient: JbrSkiaSweepGradientShader? = null,
    internal val jbrSkiaImageShader: JbrSkiaImageShader? = null,
    internal val jbrSkiaCompositeShader: JbrSkiaCompositeShader? = null,
    internal val jbrSkiaRuntimeEffectShader: JbrSkiaRuntimeEffectShader? = null,
    internal val jbrSkiaTransformedShader: JbrSkiaTransformedShader? = null,
    internal val jbrSkiaColorShader: JbrSkiaColorShader? = null,
    internal val jbrSkiaPerlinNoiseShader: JbrSkiaPerlinNoiseShader? = null,
)

/**
 * Convert the [org.jetbrains.skia.Shader] instance into a Compose-compatible Shader
 */
fun SkShader.asComposeShader(): Shader = Shader(internalSkiaShader = this)

fun ColorShader(color: Color): Shader =
    Shader(
        internalSkiaShader = SkShader.makeColor(color.toArgb()),
        jbrSkiaColorShader = JbrSkiaColorShader(color),
    )

fun FractalNoiseShader(
    baseFrequencyX: Float,
    baseFrequencyY: Float,
    numOctaves: Int,
    seed: Float,
    tileWidth: Int = 0,
    tileHeight: Int = 0,
): Shader {
    val tileSize = ISize.make(tileWidth, tileHeight)
    return Shader(
        internalSkiaShader = SkShader.makeFractalNoise(baseFrequencyX, baseFrequencyY, numOctaves, seed, tileSize),
        jbrSkiaPerlinNoiseShader = JbrSkiaPerlinNoiseShader(
            kind = JbrSkiaPerlinNoiseKind.FractalNoise,
            baseFrequencyX = baseFrequencyX,
            baseFrequencyY = baseFrequencyY,
            numOctaves = numOctaves,
            seed = seed,
            tileWidth = tileWidth,
            tileHeight = tileHeight,
        ),
    )
}

fun TurbulenceShader(
    baseFrequencyX: Float,
    baseFrequencyY: Float,
    numOctaves: Int,
    seed: Float,
    tileWidth: Int = 0,
    tileHeight: Int = 0,
): Shader {
    val tileSize = ISize.make(tileWidth, tileHeight)
    return Shader(
        internalSkiaShader = SkShader.makeTurbulence(baseFrequencyX, baseFrequencyY, numOctaves, seed, tileSize),
        jbrSkiaPerlinNoiseShader = JbrSkiaPerlinNoiseShader(
            kind = JbrSkiaPerlinNoiseKind.Turbulence,
            baseFrequencyX = baseFrequencyX,
            baseFrequencyY = baseFrequencyY,
            numOctaves = numOctaves,
            seed = seed,
            tileWidth = tileWidth,
            tileHeight = tileHeight,
        ),
    )
}

internal data class JbrSkiaColorShader(
    val color: Color,
)

internal enum class JbrSkiaPerlinNoiseKind(val commandValue: Int) {
    FractalNoise(0),
    Turbulence(1),
}

internal data class JbrSkiaPerlinNoiseShader(
    val kind: JbrSkiaPerlinNoiseKind,
    val baseFrequencyX: Float,
    val baseFrequencyY: Float,
    val numOctaves: Int,
    val seed: Float,
    val tileWidth: Int,
    val tileHeight: Int,
)

internal data class JbrSkiaLinearGradientShader(
    val from: Offset,
    val to: Offset,
    val colors: List<Color>,
    val colorStops: List<Float>?,
    val tileMode: TileMode,
)

internal data class JbrSkiaRadialGradientShader(
    val center: Offset,
    val radius: Float,
    val colors: List<Color>,
    val colorStops: List<Float>?,
    val tileMode: TileMode,
)

internal data class JbrSkiaSweepGradientShader(
    val center: Offset,
    val colors: List<Color>,
    val colorStops: List<Float>?,
)

internal data class JbrSkiaImageShader(
    val image: ImageBitmap,
    val tileModeX: TileMode,
    val tileModeY: TileMode,
)

internal data class JbrSkiaCompositeShader(
    val dst: Shader,
    val src: Shader,
    val blendMode: BlendMode,
)

internal data class JbrSkiaTransformedShader(
    val shader: Shader,
    val matrix: FloatArray,
) {
    override fun equals(other: Any?): Boolean =
        other is JbrSkiaTransformedShader &&
            shader == other.shader &&
            matrix.contentEquals(other.matrix)

    override fun hashCode(): Int = 31 * shader.hashCode() + matrix.contentHashCode()
}

@OptIn(ExperimentalGraphicsApi::class)
internal data class JbrSkiaRuntimeEffectShader(
    val sksl: String,
    val uniforms: FloatArray,
    val uniformSchema: List<RuntimeEffectUniform>,
    val children: List<Shader>,
    val namedChildren: List<RuntimeEffectChild>,
) {
    override fun equals(other: Any?): Boolean =
        other is JbrSkiaRuntimeEffectShader &&
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
data class RuntimeEffectUniform(
    val name: String,
    val floatOffset: Int,
    val floatCount: Int,
)

@ExperimentalGraphicsApi
data class RuntimeEffectChild(
    val name: String,
    val shader: Shader,
)

/**
 * Provides access to the underlying [org.jetbrains.skia.Shader] instance.
 */
val Shader.skiaShader: SkShader
    get() = internalSkiaShader

@ExperimentalGraphicsApi
fun RuntimeEffectShader(
    sksl: String,
    uniforms: FloatArray = FloatArray(0),
    uniformSchema: List<RuntimeEffectUniform> = emptyList(),
    children: List<Shader> = emptyList(),
    namedChildren: List<RuntimeEffectChild> = emptyList(),
): Shader {
    val uniformCopy = uniforms.copyOf()
    val childShaders = children + namedChildren.map { it.shader }
    val skiaShader = RuntimeEffect.makeForShader(sksl).use { effect ->
        uniformCopy.toUniformData().use { uniformData ->
            effect.makeShader(uniformData, childShaders.map { it.skiaShader }.toTypedArray(), null)
        }
    }
    return Shader(
        internalSkiaShader = skiaShader,
        jbrSkiaRuntimeEffectShader = JbrSkiaRuntimeEffectShader(
            sksl = sksl,
            uniforms = uniformCopy,
            uniformSchema = uniformSchema.toList(),
            children = children.toList(),
            namedChildren = namedChildren.toList(),
        ),
    )
}

internal actual class TransformShader {
    private var _shader: Shader? = null
    private var _wrapper: Shader? = null
    private var _matrix: Matrix33? = null

    actual fun transform(matrix: Matrix?) {
        _matrix = if (matrix != null) {
            Matrix33.makeTranslate(0f, 0f).apply { setFrom(matrix) }
        } else null
        _wrapper = null
    }

    actual var shader: Shader?
        get() {
            val matrix = _matrix ?: return _shader
            if (_wrapper == null) {
                _wrapper = _shader?.let { shader ->
                    Shader(
                        internalSkiaShader = shader.skiaShader.makeWithLocalMatrix(matrix),
                        jbrSkiaTransformedShader = JbrSkiaTransformedShader(shader, matrix.mat.copyOf()),
                    )
                }
            }
            return _wrapper
        }
        set(value) {
            _shader = value
            _wrapper = null
        }
}

internal actual fun ActualLinearGradientShader(
    from: Offset,
    to: Offset,
    colors: List<Color>,
    colorStops: List<Float>?,
    tileMode: TileMode
): Shader {
    validateColorStops(colors, colorStops)
    return Shader(
        internalSkiaShader = SkShader.makeLinearGradient(
            from.x,
            from.y,
            to.x,
            to.y,
            colors.toSkiaGradient(
                colorStops = colorStops,
                tileMode = tileMode
            )
        ),
        jbrSkiaLinearGradient = JbrSkiaLinearGradientShader(
            from = from,
            to = to,
            colors = colors,
            colorStops = colorStops,
            tileMode = tileMode,
        ),
    )
}

internal actual fun ActualRadialGradientShader(
    center: Offset,
    radius: Float,
    colors: List<Color>,
    colorStops: List<Float>?,
    tileMode: TileMode
): Shader {
    validateColorStops(colors, colorStops)
    return Shader(
        internalSkiaShader = SkShader.makeRadialGradient(
            center.x,
            center.y,
            radius,
            colors.toSkiaGradient(
                colorStops = colorStops,
                tileMode = tileMode
            )
        ),
        jbrSkiaRadialGradient = JbrSkiaRadialGradientShader(
            center = center,
            radius = radius,
            colors = colors,
            colorStops = colorStops,
            tileMode = tileMode,
        ),
    )
}

internal actual fun ActualSweepGradientShader(
    center: Offset,
    colors: List<Color>,
    colorStops: List<Float>?
): Shader {
    validateColorStops(colors, colorStops)
    return Shader(
        internalSkiaShader = SkShader.makeSweepGradient(
            center.x,
            center.y,
            colors.toSkiaGradient(colorStops = colorStops)
        ),
        jbrSkiaSweepGradient = JbrSkiaSweepGradientShader(
            center = center,
            colors = colors,
            colorStops = colorStops,
        ),
    )
}

internal actual fun ActualImageShader(
    image: ImageBitmap,
    tileModeX: TileMode,
    tileModeY: TileMode
): Shader {
    return Shader(
        internalSkiaShader = image.asSkiaBitmap().makeShader(
            tileModeX.toSkiaTileMode(),
            tileModeY.toSkiaTileMode()
        ),
        jbrSkiaImageShader = JbrSkiaImageShader(
            image = image,
            tileModeX = tileModeX,
            tileModeY = tileModeY,
        ),
    )
}

internal actual fun ActualCompositeShader(dst: Shader, src: Shader, blendMode: BlendMode): Shader =
    Shader(
        internalSkiaShader = SkShader.makeBlend(
            mode = blendMode.toSkia(),
            dst = dst.skiaShader,
            src = src.skiaShader
        ),
        jbrSkiaCompositeShader = if (dst.hasJbrSkiaShaderMetadata && src.hasJbrSkiaShaderMetadata) {
            JbrSkiaCompositeShader(dst = dst, src = src, blendMode = blendMode)
        } else {
            null
        },
    )

private val Shader.hasJbrSkiaShaderMetadata: Boolean
    get() = jbrSkiaLinearGradient != null ||
        jbrSkiaRadialGradient != null ||
        jbrSkiaSweepGradient != null ||
        jbrSkiaImageShader != null ||
        jbrSkiaCompositeShader != null ||
        jbrSkiaRuntimeEffectShader != null ||
        jbrSkiaTransformedShader != null ||
        jbrSkiaColorShader != null

internal fun FloatArray.toUniformData(): Data {
    if (isEmpty()) return Data.makeEmpty()
    val bytes = ByteArray(size * 4)
    forEachIndexed { index, value ->
        val bits = value.toRawBits()
        val offset = index * 4
        bytes[offset] = bits.toByte()
        bytes[offset + 1] = (bits ushr 8).toByte()
        bytes[offset + 2] = (bits ushr 16).toByte()
        bytes[offset + 3] = (bits ushr 24).toByte()
    }
    return Data.makeFromBytes(bytes)
}

private fun List<Color>.toSkiaGradient(
    colorStops: List<Float>?,
    tileMode: TileMode = TileMode.Clamp
): Gradient = Gradient(
    colors = Gradient.Colors(
        colors = toColor4fArray(),
        positions = colorStops?.toFloatArray(),
        tileMode = tileMode.toSkiaTileMode()
    ),
    interpolation = Gradient.Interpolation(
        inPremul = Gradient.Interpolation.InPremul.YES
    )
)

private fun List<Color>.toColor4fArray(): Array<Color4f> =
    Array(size) { i ->
        val color = this[i]
        Color4f(color.red, color.green, color.blue, color.alpha)
    }

private fun validateColorStops(colors: List<Color>, colorStops: List<Float>?) {
    if (colorStops == null) {
        if (colors.size < 2) {
            throw IllegalArgumentException(
                "colors must have length of at least 2 if colorStops " +
                    "is omitted."
            )
        }
    } else if (colors.size != colorStops.size) {
        throw IllegalArgumentException(
            "colors and colorStops arguments must have" +
                " equal length."
        )
    }
}
