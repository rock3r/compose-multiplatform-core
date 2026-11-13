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
package androidx.compose.foundation

import androidx.compose.runtime.Recomposer
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test

/** Verifies Recomposer uniqueness and compatibility at the Foundation resolution root. */
class Cmp11138RuntimeLinkageTest {
    @Test
    fun foundationGraphHasOnlyCanonicalRecomposer() {
        val classEntry = "androidx/compose/runtime/Recomposer.class"
        val classpath = System.getProperty("java.class.path").split(File.pathSeparator)
        val providers = classpath.filter { entry ->
            val file = File(entry)
            when {
                file.isDirectory -> File(file, classEntry).exists()
                file.isFile && file.extension == "jar" ->
                    ZipFile(file).use { zip -> zip.getEntry(classEntry) != null }
                else -> false
            }
        }
        println("CMP11138_FOUNDATION_RUNTIME_PROVIDERS=$providers")
        assertEquals(1, providers.size, "Recomposer providers must be unique: $providers")
        assertNotNull(Recomposer::class.java.getMethod("pauseRecomposition"))
        assertNotNull(Recomposer::class.java.getMethod("resumeRecomposition"))
    }
}
