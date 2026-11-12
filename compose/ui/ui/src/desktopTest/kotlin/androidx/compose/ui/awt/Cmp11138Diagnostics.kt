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
package androidx.compose.ui.awt

import androidx.compose.runtime.Recomposer
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.ComposeSceneMediator
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import javax.management.ObjectName
import kotlinx.coroutines.delay

internal class RetentionPayload(val size: Int) {
    val bytes = ByteArray(size)
}

internal data class RecomposerSample(
    val snapshotInvalidations: Int,
    val compositionInvalidations: Int,
    val paused: Boolean,
    val state: String,
    val changeCount: Long,
    val runningRecomposers: Int,
    val usedHeap: Long,
    val cpuMs: Long,
)

@OptIn(InternalComposeUiApi::class)
internal fun ComposePanel.probeFrameRecomposer(): FrameRecomposer {
    val composeContainer = readProbeField(this, "_composeContainer")
    val mediator = readProbeField(composeContainer, "mediator")
    return readProbeField(mediator, "frameRecomposer") as FrameRecomposer
}

internal fun ComposePanel.probeScene(): Any {
    val composeContainer = readProbeField(this, "_composeContainer")
    val mediator = readProbeField(composeContainer, "mediator")
    val method = mediator.javaClass.getDeclaredMethod("getScene")
    method.isAccessible = true
    return method.invoke(mediator)
}

internal fun ComposePanel.probeContainer(): Any = readProbeField(this, "_composeContainer")

internal fun ComposePanel.probeMediator(): Any {
    val composeContainer = readProbeField(this, "_composeContainer")
    return readProbeField(composeContainer, "mediator")
}

internal fun ComposePanel.probeSetPauseCompositionWhenNotRendering(enabled: Boolean) {
    (probeMediator() as ComposeSceneMediator).pauseCompositionWhenNotRendering = enabled
}

internal fun readProbeField(target: Any, name: String): Any {
    var type: Class<*>? = target.javaClass
    while (type != null) {
        try {
            val field = type.getDeclaredField(name)
            field.isAccessible = true
            return field.get(target)
        } catch (_: NoSuchFieldException) {
            type = type.superclass
        }
    }
    error("Field $name not found on ${target.javaClass.name}")
}

internal fun probeSize(container: Any): Int {
    val method = container.javaClass.getMethod("getSize")
    method.isAccessible = true
    return method.invoke(container) as Int
}

internal fun Recomposer.probeSample(): RecomposerSample {
    val snapshotInvalidations = readProbeField(this, "snapshotInvalidations")
    val compositionInvalidations = readProbeField(this, "compositionInvalidations")
    val runtime = Runtime.getRuntime()
    return RecomposerSample(
        snapshotInvalidations = probeSize(snapshotInvalidations),
        compositionInvalidations = probeSize(compositionInvalidations),
        paused = readProbeField(this, "frameClockPaused") as Boolean,
        state = currentState.value.name,
        changeCount = changeCount,
        runningRecomposers = Recomposer.runningRecomposers.value.size,
        usedHeap = runtime.totalMemory() - runtime.freeMemory(),
        cpuMs = processCpuMs(),
    )
}

internal fun processCpuMs(): Long =
    ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).toMillis()

internal suspend fun forceProbeGc() {
    repeat(6) {
        System.gc()
        delay(50)
    }
}

internal fun probeOutputDir(): Path {
    val path = Path.of(System.getenv("CMP11138_OUTPUT_DIR") ?: ".cmp11138-output")
    Files.createDirectories(path)
    return path
}

internal fun probeVariant(): String = System.getenv("CMP11138_VARIANT") ?: "baseline"

internal fun appendProbeCsv(file: String, header: String, row: String) {
    val path = probeOutputDir().resolve(file)
    if (!Files.exists(path)) {
        Files.writeString(path, "$header\n")
    }
    Files.writeString(
        path,
        "$row\n",
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND,
    )
}

internal fun writeProbeHistogram(file: String) {
    val histogram = ManagementFactory.getPlatformMBeanServer()
        .invoke(
            ObjectName("com.sun.management:type=DiagnosticCommand"),
            "gcClassHistogram",
            arrayOf<Any?>(null),
            arrayOf("[Ljava.lang.String;"),
        ) as String
    Files.writeString(probeOutputDir().resolve(file), histogram)
}

internal fun csvValue(value: Any?): String {
    val text = value.toString()
    return "\"${text.replace("\"", "\"\"")}\""
}
