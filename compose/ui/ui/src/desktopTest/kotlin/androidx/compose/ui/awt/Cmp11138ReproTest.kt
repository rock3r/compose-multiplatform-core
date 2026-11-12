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

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import java.awt.BorderLayout
import java.awt.Dimension
import java.lang.ref.WeakReference
import javax.swing.JFrame
import javax.swing.JPanel
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skiko.MainUIDispatcher
import org.junit.Test

class Cmp11138ReproTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun detachedComposePanelLeaksGlobalSnapshotInvalidations() = runBlocking(MainUIDispatcher) {
        class Payload(val bytes: ByteArray = ByteArray(1024))

        val panelBTrigger = mutableStateOf(0)
        val panelBStates = ArrayList<MutableState<Payload?>>(2000)
        val panelA = ComposePanel().apply {
            isDisposeOnRemove = false
            preferredSize = Dimension(200, 100)
            setContent { }
        }
        val panelB = ComposePanel().apply {
            preferredSize = Dimension(200, 100)
            setContent {
                panelBTrigger.value
                for (state in panelBStates) state.value
            }
        }
        val host = JPanel(BorderLayout()).apply {
            add(panelA, BorderLayout.NORTH)
            add(panelB, BorderLayout.SOUTH)
        }
        val frame = JFrame().apply {
            isUndecorated = true
            contentPane.add(host)
            pack()
            isVisible = true
        }
        try {
            delay(300)
            panelA.probeSetPauseCompositionWhenNotRendering(true)
            host.remove(panelA)
            delay(200)
            repeat(2000) { panelBStates.add(mutableStateOf(Payload())) }
            panelBTrigger.value++
            Snapshot.sendApplyNotifications()
            delay(250)
            val weakPayloads = panelBStates.map { state ->
                Payload().also { state.value = it }.let(::WeakReference)
            }
            panelBStates.clear()
            panelBTrigger.value++
            Snapshot.sendApplyNotifications()
            delay(250)
            repeat(5) {
                System.gc()
                delay(50)
            }
            assertEquals(0, weakPayloads.count { it.get() != null })
        } finally {
            frame.dispose()
            panelA.dispose()
            panelB.dispose()
        }
    }
}
