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

@file:OptIn(InternalComposeUiApi::class)
@file:JvmName("SkiaShader_skikoKt")

package androidx.compose.ui.graphics

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.platform.PlatformShader
import kotlin.jvm.JvmName
import org.jetbrains.skia.Data
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.impl.use
import org.jetbrains.skia.Shader as SkShader

internal class SkikoShader(
    val skiaShader: SkShader,
    override val jbrSkiaLinearGradient: JbrSkiaLinearGradientShader? = null,
    override val jbrSkiaRadialGradient: JbrSkiaRadialGradientShader? = null,
    override val jbrSkiaSweepGradient: JbrSkiaSweepGradientShader? = null,
    override val jbrSkiaImageShader: JbrSkiaImageShader? = null,
    override val jbrSkiaCompositeShader: JbrSkiaCompositeShader? = null,
    override val jbrSkiaRuntimeEffectShader: JbrSkiaRuntimeEffectShader? = null,
) : PlatformShader

@ExperimentalGraphicsApi
fun RuntimeEffectShader(
    sksl: String,
    uniforms: FloatArray = FloatArray(0),
    uniformSchema: List<RuntimeEffectUniform> = emptyList(),
    children: List<Shader> = emptyList(),
): Shader {
    val uniformCopy = uniforms.copyOf()
    val skiaShader = RuntimeEffect.makeForShader(sksl).use { effect ->
        uniformCopy.toUniformData().use { uniformData ->
            effect.makeShader(uniformData, children.map { it.skiaShader }.toTypedArray(), null)
        }
    }
    return Shader(
        SkikoShader(
            skiaShader = skiaShader,
            jbrSkiaRuntimeEffectShader = JbrSkiaRuntimeEffectShader(
                sksl = sksl,
                uniforms = uniformCopy,
                uniformSchema = uniformSchema.toList(),
                children = children.toList(),
            ),
        ),
    )
}

private fun FloatArray.toUniformData(): Data {
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

/** Convert the [org.jetbrains.skia.Shader] instance into a Compose-compatible Shader */
fun SkShader.asComposeShader(): Shader = Shader(SkikoShader(this))

/** Provides access to the underlying [org.jetbrains.skia.Shader] instance. */
val Shader.skiaShader: SkShader
    get() {
        val platform = platformShader
        require(platform is SkikoShader) {
            "Extracting the Skia shader reference is only supported from Shaders created by the " +
                "registered Skiko implementation (registerSkikoComposeImplementation()), but the " +
                "binding was ${platform::class}"
        }
        return platform.skiaShader
    }

internal fun transformSkikoShader(shader: Shader, matrix: Matrix): Shader =
    shader.skiaShader
        .makeWithLocalMatrix(identityMatrix33().apply { setFrom(matrix) })
        .asComposeShader()
