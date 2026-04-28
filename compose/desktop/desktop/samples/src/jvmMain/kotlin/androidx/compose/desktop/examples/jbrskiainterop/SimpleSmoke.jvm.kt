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

package androidx.compose.desktop.examples.jbrskiainterop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import java.awt.BorderLayout
import java.awt.Color as AwtColor
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

fun main() {
    SwingUtilities.invokeLater(::showSmokeWindow)
}

private fun showSmokeWindow() {
    val window = JFrame("JbrSkiaSmokeWindow")
    window.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
    window.contentPane.add(
        ComposePanel().apply {
            background = AwtColor.BLACK
            setContent {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color(55, 155, 55), size = Size(size.width, size.height / 2f))
                    drawRect(
                        Color(55, 55, 155),
                        topLeft = Offset(0f, size.height / 2f),
                        size = Size(size.width, size.height / 2f)
                    )
                    drawRect(
                        Color(118, 0, 238),
                        topLeft = Offset(size.width * 0.15f, size.height * 0.18f),
                        size = Size(size.width * 0.25f, size.height * 0.16f)
                    )
                    drawRect(
                        Color(118, 0, 238),
                        topLeft = Offset(size.width * 0.6f, size.height * 0.66f),
                        size = Size(size.width * 0.25f, size.height * 0.16f)
                    )
                }
            }
        },
        BorderLayout.CENTER
    )
    window.setSize(640, 480)
    window.isVisible = true
}
