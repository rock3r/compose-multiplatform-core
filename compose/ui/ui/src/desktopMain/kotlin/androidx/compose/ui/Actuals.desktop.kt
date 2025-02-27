/*
 * Copyright 2022 The Android Open Source Project
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

package androidx.compose.ui

import androidx.compose.ui.input.key.NativeKeyEvent
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import java.awt.Toolkit
import org.jetbrains.skiko.SkiaLayer

internal actual fun createSkiaLayer(): SkiaLayer = SkiaLayer()

private fun getLockingKeyStateSafe(
    mask: Int
): Boolean = try {
    Toolkit.getDefaultToolkit().getLockingKeyState(mask)
} catch (_: Exception) {
    false
}
internal actual fun NativeKeyEvent.toPointerKeyboardModifiers(): PointerKeyboardModifiers {
    val awtEventOrNull = this as? java.awt.event.KeyEvent
    return PointerKeyboardModifiers(
        isCtrlPressed = awtEventOrNull?.isControlDown ?:  false,
        isShiftPressed = awtEventOrNull?.isShiftDown ?:  false,
        isAltPressed = awtEventOrNull?.isAltDown ?:  false,
        isAltGraphPressed = awtEventOrNull?.isAltGraphDown ?: false,
        isMetaPressed = awtEventOrNull?.isMetaDown ?: false,
        isSymPressed = false, // no sym in awtEvent?
        isFunctionPressed = false, // no Fn in awtEvent?
        isCapsLockOn = getLockingKeyStateSafe(java.awt.event.KeyEvent.VK_CAPS_LOCK),
        isScrollLockOn = getLockingKeyStateSafe(java.awt.event.KeyEvent.VK_SCROLL_LOCK),
        isNumLockOn = getLockingKeyStateSafe(java.awt.event.KeyEvent.VK_NUM_LOCK)
    )
}
