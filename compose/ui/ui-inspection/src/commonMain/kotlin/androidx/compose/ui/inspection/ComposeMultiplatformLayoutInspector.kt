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

package androidx.compose.ui.inspection

import androidx.compose.ui.inspection.inspector.InspectorNode
import kotlinx.coroutines.flow.StateFlow

interface ComposeMultiplatformLayoutInspector {

    fun attachToCurrentProcess(): LayoutInspector

    interface LayoutInspector {
        /**
         * Platform-neutral snapshot keyed by platform view/panel id.
         */
        val layoutInfos: StateFlow<Map<Long, List<InspectorNode>>>

        fun detach()
    }

    companion object {
        // Used in JVM module
    }
}