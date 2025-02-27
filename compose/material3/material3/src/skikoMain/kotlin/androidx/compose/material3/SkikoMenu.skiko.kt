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

package androidx.compose.material3

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * <a href="https://m3.material.io/components/menus/overview" class="external" target="_blank">Material Design dropdown menu</a>.
 *
 * Menus display a list of choices on a temporary surface. They appear when users interact with a
 * button, action, or other control.
 *
 * ![Dropdown menu image](https://developer.android.com/images/reference/androidx/compose/material3/menu.png)
 *
 * A [DropdownMenu] behaves similarly to a [Popup], and will use the position of the parent layout
 * to position itself on screen. Commonly a [DropdownMenu] will be placed in a [Box] with a sibling
 * that will be used as the 'anchor'. Note that a [DropdownMenu] by itself will not take up any
 * space in a layout, as the menu is displayed in a separate window, on top of other content.
 *
 * The [content] of a [DropdownMenu] will typically be [DropdownMenuItem]s, as well as custom
 * content. Using [DropdownMenuItem]s will result in a menu that matches the Material
 * specification for menus. Also note that the [content] is placed inside a scrollable [Column],
 * so using a [LazyColumn] as the root layout inside [content] is unsupported.
 *
 * [onDismissRequest] will be called when the menu should close - for example when there is a
 * tap outside the menu, or when the back key is pressed.
 *
 * [DropdownMenu] changes its positioning depending on the available space, always trying to be
 * fully visible. It will try to expand horizontally, depending on layout direction, to the end of
 * its parent, then to the start of its parent, and then screen end-aligned. Vertically, it will
 * try to expand to the bottom of its parent, then from the top of its parent, and then screen
 * top-aligned. An [offset] can be provided to adjust the positioning of the menu for cases when
 * the layout bounds of its parent do not coincide with its visual bounds. Note the offset will
 * be applied in the direction in which the menu will decide to expand.
 *
 * Example usage:
 * @sample androidx.compose.material3.samples.MenuSample
 *
 * @param expanded whether the menu is expanded or not
 * @param onDismissRequest called when the user requests to dismiss the menu, such as by tapping
 * outside the menu's bounds
 * @param offset [DpOffset] to be added to the position of the menu
 */
@Deprecated(
    "Replaced by DropdownMenu with properties parameter",
    ReplaceWith("DropdownMenu(expanded, onDismissRequest, modifier, offset, " +
        "androidx.compose.ui.window.PopupProperties(focusable = focusable), " +
        "content)"),
    level = DeprecationLevel.WARNING // TODO: Change to DeprecationLevel.HIDDEN in 1.6
)
@Suppress("ModifierParameter")
@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    focusable: Boolean = true,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit
) = DropdownMenu(
    expanded = expanded,
    onDismissRequest = onDismissRequest,
    modifier = modifier,
    offset = offset,
    properties = PopupProperties(focusable = focusable),
    content = content
)

// Workaround for `Overload resolution ambiguity` between old and new overload.
// TODO: Deprecate with DeprecationLevel.HIDDEN in 1.6
@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit
) = DropdownMenu(
    expanded = expanded,
    onDismissRequest = onDismissRequest,
    modifier = modifier,
    offset = offset,
    properties = PopupProperties(focusable = true),
    content = content
)

/**
 * <a href="https://m3.material.io/components/menus/overview" class="external" target="_blank">Material Design dropdown menu</a>.
 *
 * Menus display a list of choices on a temporary surface. They appear when users interact with a
 * button, action, or other control.
 *
 * ![Dropdown menu image](https://developer.android.com/images/reference/androidx/compose/material3/menu.png)
 *
 * A [DropdownMenu] behaves similarly to a [Popup], and will use the position of the parent layout
 * to position itself on screen. Commonly a [DropdownMenu] will be placed in a [Box] with a sibling
 * that will be used as the 'anchor'. Note that a [DropdownMenu] by itself will not take up any
 * space in a layout, as the menu is displayed in a separate window, on top of other content.
 *
 * The [content] of a [DropdownMenu] will typically be [DropdownMenuItem]s, as well as custom
 * content. Using [DropdownMenuItem]s will result in a menu that matches the Material
 * specification for menus. Also note that the [content] is placed inside a scrollable [Column],
 * so using a [LazyColumn] as the root layout inside [content] is unsupported.
 *
 * [onDismissRequest] will be called when the menu should close - for example when there is a
 * tap outside the menu, or when the back key is pressed.
 *
 * [DropdownMenu] changes its positioning depending on the available space, always trying to be
 * fully visible. It will try to expand horizontally, depending on layout direction, to the end of
 * its parent, then to the start of its parent, and then screen end-aligned. Vertically, it will
 * try to expand to the bottom of its parent, then from the top of its parent, and then screen
 * top-aligned. An [offset] can be provided to adjust the positioning of the menu for cases when
 * the layout bounds of its parent do not coincide with its visual bounds. Note the offset will
 * be applied in the direction in which the menu will decide to expand.
 *
 * Example usage:
 * @sample androidx.compose.material3.samples.MenuSample
 *
 * @param expanded whether the menu is expanded or not
 * @param onDismissRequest called when the user requests to dismiss the menu, such as by tapping
 * outside the menu's bounds
 * @param offset [DpOffset] to be added to the position of the menu
 */
@Suppress("ModifierParameter")
@Composable
actual fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    offset: DpOffset,
    properties: PopupProperties,
    content: @Composable ColumnScope.() -> Unit
) {
    val expandedStates = remember { MutableTransitionState(false) }
    expandedStates.targetState = expanded

    if (expandedStates.currentState || expandedStates.targetState) {
        val transformOriginState = remember { mutableStateOf(TransformOrigin.Center) }
        val density = LocalDensity.current
        val popupPositionProvider = DropdownMenuPositionProvider(
            offset,
            density
        ) { parentBounds, menuBounds ->
            transformOriginState.value = calculateTransformOrigin(parentBounds, menuBounds)
        }

        var focusManager: FocusManager? by mutableStateOf(null)
        var inputModeManager: InputModeManager? by mutableStateOf(null)
        Popup(
            onDismissRequest = onDismissRequest,
            popupPositionProvider = popupPositionProvider,
            properties = properties,
            onKeyEvent = {
                handlePopupOnKeyEvent(it, focusManager, inputModeManager)
            },
        ) {
            focusManager = LocalFocusManager.current
            inputModeManager = LocalInputModeManager.current

            DropdownMenuContent(
                expandedStates = expandedStates,
                transformOriginState = transformOriginState,
                modifier = modifier,
                content = content
            )
        }
    }
}

/**
 * <a href="https://m3.material.io/components/menus/overview" class="external" target="_blank">Material Design dropdown menu</a> item.
 *
 * Menus display a list of choices on a temporary surface. They appear when users interact with a
 * button, action, or other control.
 *
 * ![Dropdown menu image](https://developer.android.com/images/reference/androidx/compose/material3/menu.png)
 *
 * Example usage:
 * @sample androidx.compose.material3.samples.MenuSample
 *
 * @param text text of the menu item
 * @param onClick called when this menu item is clicked
 * @param modifier the [Modifier] to be applied to this menu item
 * @param leadingIcon optional leading icon to be displayed at the beginning of the item's text
 * @param trailingIcon optional trailing icon to be displayed at the end of the item's text. This
 * trailing icon slot can also accept [Text] to indicate a keyboard shortcut.
 * @param enabled controls the enabled state of this menu item. When `false`, this component will
 * not respond to user input, and it will appear visually disabled and disabled to accessibility
 * services.
 * @param colors [MenuItemColors] that will be used to resolve the colors used for this menu item in
 * different states. See [MenuDefaults.itemColors].
 * @param contentPadding the padding applied to the content of this menu item
 * @param interactionSource the [MutableInteractionSource] representing the stream of [Interaction]s
 * for this menu item. You can create and pass in your own `remember`ed instance to observe
 * [Interaction]s and customize the appearance / behavior of this menu item in different states.
 */
@Composable
actual fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier,
    leadingIcon: @Composable (() -> Unit)?,
    trailingIcon: @Composable (() -> Unit)?,
    enabled: Boolean,
    colors: MenuItemColors,
    contentPadding: PaddingValues,
    interactionSource: MutableInteractionSource,
) {
    DropdownMenuItemContent(
        text = text,
        onClick = onClick,
        modifier = modifier,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        colors = colors,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
private fun handlePopupOnKeyEvent(
    keyEvent: KeyEvent,
    focusManager: FocusManager?,
    inputModeManager: InputModeManager?
): Boolean = if (keyEvent.type == KeyEventType.KeyDown) {
    when (keyEvent.key) {
        Key.DirectionDown -> {
            inputModeManager?.requestInputMode(InputMode.Keyboard)
            focusManager?.moveFocus(FocusDirection.Next)
            true
        }
        Key.DirectionUp -> {
            inputModeManager?.requestInputMode(InputMode.Keyboard)
            focusManager?.moveFocus(FocusDirection.Previous)
            true
        }
        else -> false
    }
} else {
    false
}
