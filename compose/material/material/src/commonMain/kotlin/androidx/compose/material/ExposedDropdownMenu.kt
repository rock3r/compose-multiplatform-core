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
package androidx.compose.material

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.internal.JvmDefaultWithCompatibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics

/**
 * [Material Design Exposed Dropdown Menu](https://material.io/components/menus#exposed-dropdown-menu).
 *
 * Box for Exposed Dropdown Menu. Expected to contain [TextField] and
 * [ExposedDropdownMenuBoxScope.ExposedDropdownMenu] as a content.
 *
 * An example of read-only Exposed Dropdown Menu:
 *
 * @sample androidx.compose.material.samples.ExposedDropdownMenuSample
 *
 * An example of editable Exposed Dropdown Menu:
 *
 * @sample androidx.compose.material.samples.EditableExposedDropdownMenuSample
 *
 * @param expanded Whether Dropdown Menu should be expanded or not.
 * @param onExpandedChange Executes when the user clicks on the ExposedDropdownMenuBox.
 * @param modifier The modifier to apply to this layout
 * @param content The content to be displayed inside ExposedDropdownMenuBox.
 */
@ExperimentalMaterialApi
@Composable
expect fun ExposedDropdownMenuBox(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ExposedDropdownMenuBoxScope.() -> Unit
)

@JvmDefaultWithCompatibility
/**
 * Scope for [ExposedDropdownMenuBox].
 */
@ExperimentalMaterialApi
interface ExposedDropdownMenuBoxScope {
    /**
     * Modifier which should be applied to an [ExposedDropdownMenu]
     * placed inside the scope. It's responsible for
     * setting the width of the [ExposedDropdownMenu], which
     * will match the width of the [TextField]
     * (if [matchTextFieldWidth] is set to true).
     * Also it'll change the height of [ExposedDropdownMenu], so
     * it'll take the largest possible height to not overlap
     * the [TextField] and the software keyboard.
     *
     * @param matchTextFieldWidth Whether menu should match
     * the width of the text field to which it's attached.
     * If set to true the width will match the width
     * of the text field.
     */
    fun Modifier.exposedDropdownSize(
        matchTextFieldWidth: Boolean = true
    ): Modifier

    /**
     * Popup which contains content for Exposed Dropdown Menu.
     * Should be used inside the content of [ExposedDropdownMenuBox].
     *
     * @param expanded Whether the menu is currently open and visible to the user
     * @param onDismissRequest Called when the user requests to dismiss the menu, such as by
     * tapping outside the menu's bounds
     * @param modifier The modifier to apply to this layout
     * @param scrollState a [ScrollState] to used by the menu's content for items vertical scrolling
     * @param content The content of the [ExposedDropdownMenu]
     */
    @Composable
    fun ExposedDropdownMenu(
        expanded: Boolean,
        onDismissRequest: () -> Unit,
        modifier: Modifier = Modifier,
        scrollState: ScrollState = rememberScrollState(),
        content: @Composable ColumnScope.() -> Unit
    ) {
        ExposedDropdownMenuDefaultImpl(
            expanded, onDismissRequest, modifier, scrollState, content
        )
    }
}

@Composable
internal expect fun ExposedDropdownMenuBoxScope.ExposedDropdownMenuDefaultImpl(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit
)

internal fun Modifier.expandable(
    onExpandedChange: () -> Unit,
    menuLabel: String
) = composed {
    val currentOnExpandedChange by rememberUpdatedState(onExpandedChange)
    pointerInput(Unit) {
        awaitEachGesture {
            // Must be PointerEventPass.Initial to observe events before the text field consumes them
            // in the Main pass
            awaitFirstDown(pass = PointerEventPass.Initial)
            val upEvent = waitForUpOrCancellation(pass = PointerEventPass.Initial)
            if (upEvent != null) {
                currentOnExpandedChange()
            }
        }
    }.semantics {
        contentDescription = menuLabel // this should be a localised string
        onClick {
            currentOnExpandedChange()
            true
        }
    }
}


/**
 * Contains default values used by Exposed Dropdown Menu.
 */
@ExperimentalMaterialApi
object ExposedDropdownMenuDefaults {
    /**
     * Default trailing icon for Exposed Dropdown Menu.
     *
     * @param expanded Whether [ExposedDropdownMenuBoxScope.ExposedDropdownMenu]
     * is expanded or not. Affects the appearance of the icon.
     * @param onIconClick Called when the icon was clicked.
     */
    @ExperimentalMaterialApi
    @Composable
    fun TrailingIcon(
        expanded: Boolean,
        onIconClick: () -> Unit = {}
    ) {
        // Clear semantics here as otherwise icon will be a11y focusable but without an
        // action. When there's an API to check if Talkback is on, developer will be able to
        // expand the menu on icon click in a11y mode only esp. if using their own custom
        // trailing icon.
        IconButton(onClick = onIconClick, modifier = Modifier.clearAndSetSemantics { }) {
            Icon(
                Icons.Filled.ArrowDropDown,
                "Trailing icon for exposed dropdown menu",
                Modifier.rotate(
                    if (expanded)
                        180f
                    else
                        360f
                )
            )
        }
    }

    /**
     * Creates a [TextFieldColors] that represents the default input text, background and content
     * (including label, placeholder, leading and trailing icons) colors used in a [TextField].
     *
     * @param textColor Represents the color used for the input text of this text field.
     * @param disabledTextColor Represents the color used for the input text of this text field
     * when it's disabled.
     * @param backgroundColor Represents the background color for this text field.
     * @param cursorColor Represents the cursor color for this text field.
     * @param errorCursorColor Represents the cursor color for this text field
     * when it's in error state.
     * @param focusedIndicatorColor Represents the indicator color for this text field
     * when it's focused.
     * @param unfocusedIndicatorColor Represents the indicator color for this text field
     * when it's not focused.
     * @param disabledIndicatorColor Represents the indicator color for this text field
     * when it's disabled.
     * @param errorIndicatorColor Represents the indicator color for this text field
     * when it's in error state.
     * @param leadingIconColor Represents the leading icon color for this text field.
     * @param disabledLeadingIconColor Represents the leading icon color for this text field
     * when it's disabled.
     * @param errorLeadingIconColor Represents the leading icon color for this text field
     * when it's in error state.
     * @param trailingIconColor Represents the trailing icon color for this text field.
     * @param focusedTrailingIconColor Represents the trailing icon color for this text field
     * when it's focused.
     * @param disabledTrailingIconColor Represents the trailing icon color for this text field
     * when it's disabled.
     * @param errorTrailingIconColor Represents the trailing icon color for this text field
     * when it's in error state.
     * @param focusedLabelColor Represents the label color for this text field
     * when it's focused.
     * @param unfocusedLabelColor Represents the label color for this text field
     * when it's not focused.
     * @param disabledLabelColor Represents the label color for this text field
     * when it's disabled.
     * @param errorLabelColor Represents the label color for this text field
     * when it's in error state.
     * @param placeholderColor Represents the placeholder color for this text field.
     * @param disabledPlaceholderColor Represents the placeholder color for this text field
     * when it's disabled.
     */
    @Composable
    fun textFieldColors(
        textColor: Color = LocalContentColor.current.copy(LocalContentAlpha.current),
        disabledTextColor: Color = textColor.copy(ContentAlpha.disabled),
        backgroundColor: Color =
            MaterialTheme.colors.onSurface.copy(alpha = TextFieldDefaults.BackgroundOpacity),
        cursorColor: Color = MaterialTheme.colors.primary,
        errorCursorColor: Color = MaterialTheme.colors.error,
        focusedIndicatorColor: Color =
            MaterialTheme.colors.primary.copy(alpha = ContentAlpha.high),
        unfocusedIndicatorColor: Color =
            MaterialTheme.colors.onSurface.copy(
                alpha = TextFieldDefaults.UnfocusedIndicatorLineOpacity
            ),
        disabledIndicatorColor: Color = unfocusedIndicatorColor.copy(alpha = ContentAlpha.disabled),
        errorIndicatorColor: Color = MaterialTheme.colors.error,
        leadingIconColor: Color =
            MaterialTheme.colors.onSurface.copy(alpha = TextFieldDefaults.IconOpacity),
        disabledLeadingIconColor: Color = leadingIconColor.copy(alpha = ContentAlpha.disabled),
        errorLeadingIconColor: Color = leadingIconColor,
        trailingIconColor: Color =
            MaterialTheme.colors.onSurface.copy(alpha = TextFieldDefaults.IconOpacity),
        focusedTrailingIconColor: Color =
            MaterialTheme.colors.primary.copy(alpha = ContentAlpha.high),
        disabledTrailingIconColor: Color = trailingIconColor.copy(alpha = ContentAlpha.disabled),
        errorTrailingIconColor: Color = MaterialTheme.colors.error,
        focusedLabelColor: Color =
            MaterialTheme.colors.primary.copy(alpha = ContentAlpha.high),
        unfocusedLabelColor: Color = MaterialTheme.colors.onSurface.copy(ContentAlpha.medium),
        disabledLabelColor: Color = unfocusedLabelColor.copy(ContentAlpha.disabled),
        errorLabelColor: Color = MaterialTheme.colors.error,
        placeholderColor: Color = MaterialTheme.colors.onSurface.copy(ContentAlpha.medium),
        disabledPlaceholderColor: Color = placeholderColor.copy(ContentAlpha.disabled)
    ): TextFieldColors =
        DefaultTextFieldForExposedDropdownMenusColors(
            textColor = textColor,
            disabledTextColor = disabledTextColor,
            cursorColor = cursorColor,
            errorCursorColor = errorCursorColor,
            focusedIndicatorColor = focusedIndicatorColor,
            unfocusedIndicatorColor = unfocusedIndicatorColor,
            errorIndicatorColor = errorIndicatorColor,
            disabledIndicatorColor = disabledIndicatorColor,
            leadingIconColor = leadingIconColor,
            disabledLeadingIconColor = disabledLeadingIconColor,
            errorLeadingIconColor = errorLeadingIconColor,
            trailingIconColor = trailingIconColor,
            focusedTrailingIconColor = focusedTrailingIconColor,
            disabledTrailingIconColor = disabledTrailingIconColor,
            errorTrailingIconColor = errorTrailingIconColor,
            backgroundColor = backgroundColor,
            focusedLabelColor = focusedLabelColor,
            unfocusedLabelColor = unfocusedLabelColor,
            disabledLabelColor = disabledLabelColor,
            errorLabelColor = errorLabelColor,
            placeholderColor = placeholderColor,
            disabledPlaceholderColor = disabledPlaceholderColor
        )

    /**
     * Creates a [TextFieldColors] that represents the default input text, background and content
     * (including label, placeholder, leading and trailing icons) colors used in an
     * [OutlinedTextField].
     *
     * @param textColor Represents the color used for the input text of this text field.
     * @param disabledTextColor Represents the color used for the input text of this text field
     * when it's disabled.
     * @param backgroundColor Represents the background color for this text field.
     * @param cursorColor Represents the cursor color for this text field.
     * @param errorCursorColor Represents the cursor color for this text field
     * when it's in error state.
     * @param focusedBorderColor Represents the border color for this text field
     * when it's focused.
     * @param unfocusedBorderColor Represents the border color for this text field
     * when it's not focused.
     * @param disabledBorderColor Represents the border color for this text field
     * when it's disabled.
     * @param errorBorderColor Represents the border color for this text field
     * when it's in error state.
     * @param leadingIconColor Represents the leading icon color for this text field.
     * @param disabledLeadingIconColor Represents the leading icon color for this text field
     * when it's disabled.
     * @param errorLeadingIconColor Represents the leading icon color for this text field
     * when it's in error state.
     * @param trailingIconColor Represents the trailing icon color for this text field.
     * @param focusedTrailingIconColor Represents the trailing icon color for this text field
     * when it's focused.
     * @param disabledTrailingIconColor Represents the trailing icon color for this text field
     * when it's disabled.
     * @param errorTrailingIconColor Represents the trailing icon color for this text field
     * when it's in error state.
     * @param focusedLabelColor Represents the label color for this text field
     * when it's focused.
     * @param unfocusedLabelColor Represents the label color for this text field
     * when it's not focused.
     * @param disabledLabelColor Represents the label color for this text field
     * when it's disabled.
     * @param errorLabelColor Represents the label color for this text field
     * when it's in error state.
     * @param placeholderColor Represents the placeholder color for this text field.
     * @param disabledPlaceholderColor Represents the placeholder color for this text field
     * when it's disabled.
     */
    @Composable
    fun outlinedTextFieldColors(
        textColor: Color = LocalContentColor.current.copy(LocalContentAlpha.current),
        disabledTextColor: Color = textColor.copy(ContentAlpha.disabled),
        backgroundColor: Color = Color.Transparent,
        cursorColor: Color = MaterialTheme.colors.primary,
        errorCursorColor: Color = MaterialTheme.colors.error,
        focusedBorderColor: Color =
            MaterialTheme.colors.primary.copy(alpha = ContentAlpha.high),
        unfocusedBorderColor: Color =
            MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.disabled),
        disabledBorderColor: Color = unfocusedBorderColor.copy(alpha = ContentAlpha.disabled),
        errorBorderColor: Color = MaterialTheme.colors.error,
        leadingIconColor: Color =
            MaterialTheme.colors.onSurface.copy(alpha = TextFieldDefaults.IconOpacity),
        disabledLeadingIconColor: Color = leadingIconColor.copy(alpha = ContentAlpha.disabled),
        errorLeadingIconColor: Color = leadingIconColor,
        trailingIconColor: Color =
            MaterialTheme.colors.onSurface.copy(alpha = TextFieldDefaults.IconOpacity),
        focusedTrailingIconColor: Color =
            MaterialTheme.colors.primary.copy(alpha = ContentAlpha.high),
        disabledTrailingIconColor: Color = trailingIconColor.copy(alpha = ContentAlpha.disabled),
        errorTrailingIconColor: Color = MaterialTheme.colors.error,
        focusedLabelColor: Color =
            MaterialTheme.colors.primary.copy(alpha = ContentAlpha.high),
        unfocusedLabelColor: Color = MaterialTheme.colors.onSurface.copy(ContentAlpha.medium),
        disabledLabelColor: Color = unfocusedLabelColor.copy(ContentAlpha.disabled),
        errorLabelColor: Color = MaterialTheme.colors.error,
        placeholderColor: Color = MaterialTheme.colors.onSurface.copy(ContentAlpha.medium),
        disabledPlaceholderColor: Color = placeholderColor.copy(ContentAlpha.disabled)
    ): TextFieldColors =
        DefaultTextFieldForExposedDropdownMenusColors(
            textColor = textColor,
            disabledTextColor = disabledTextColor,
            cursorColor = cursorColor,
            errorCursorColor = errorCursorColor,
            focusedIndicatorColor = focusedBorderColor,
            unfocusedIndicatorColor = unfocusedBorderColor,
            errorIndicatorColor = errorBorderColor,
            disabledIndicatorColor = disabledBorderColor,
            leadingIconColor = leadingIconColor,
            disabledLeadingIconColor = disabledLeadingIconColor,
            errorLeadingIconColor = errorLeadingIconColor,
            trailingIconColor = trailingIconColor,
            focusedTrailingIconColor = focusedTrailingIconColor,
            disabledTrailingIconColor = disabledTrailingIconColor,
            errorTrailingIconColor = errorTrailingIconColor,
            backgroundColor = backgroundColor,
            focusedLabelColor = focusedLabelColor,
            unfocusedLabelColor = unfocusedLabelColor,
            disabledLabelColor = disabledLabelColor,
            errorLabelColor = errorLabelColor,
            placeholderColor = placeholderColor,
            disabledPlaceholderColor = disabledPlaceholderColor
        )
}

@OptIn(ExperimentalMaterialApi::class)
@Immutable
private class DefaultTextFieldForExposedDropdownMenusColors(
    private val textColor: Color,
    private val disabledTextColor: Color,
    private val cursorColor: Color,
    private val errorCursorColor: Color,
    private val focusedIndicatorColor: Color,
    private val unfocusedIndicatorColor: Color,
    private val errorIndicatorColor: Color,
    private val disabledIndicatorColor: Color,
    private val leadingIconColor: Color,
    private val disabledLeadingIconColor: Color,
    private val errorLeadingIconColor: Color,
    private val trailingIconColor: Color,
    private val focusedTrailingIconColor: Color,
    private val disabledTrailingIconColor: Color,
    private val errorTrailingIconColor: Color,
    private val backgroundColor: Color,
    private val focusedLabelColor: Color,
    private val unfocusedLabelColor: Color,
    private val disabledLabelColor: Color,
    private val errorLabelColor: Color,
    private val placeholderColor: Color,
    private val disabledPlaceholderColor: Color
) : TextFieldColorsWithIcons {

    @Composable
    override fun leadingIconColor(enabled: Boolean, isError: Boolean): State<Color> {
        return rememberUpdatedState(
            when {
                !enabled -> disabledLeadingIconColor
                isError -> errorLeadingIconColor
                else -> leadingIconColor
            }
        )
    }

    @Composable
    override fun trailingIconColor(enabled: Boolean, isError: Boolean): State<Color> {
        return rememberUpdatedState(
            when {
                !enabled -> disabledTrailingIconColor
                isError -> errorTrailingIconColor
                else -> trailingIconColor
            }
        )
    }

    @Composable
    override fun trailingIconColor(
        enabled: Boolean,
        isError: Boolean,
        interactionSource: InteractionSource
    ): State<Color> {
        val focused by interactionSource.collectIsFocusedAsState()

        return rememberUpdatedState(
            when {
                !enabled -> disabledTrailingIconColor
                isError -> errorTrailingIconColor
                focused -> focusedTrailingIconColor
                else -> trailingIconColor
            }
        )
    }

    @Composable
    override fun indicatorColor(
        enabled: Boolean,
        isError: Boolean,
        interactionSource: InteractionSource
    ): State<Color> {
        val focused by interactionSource.collectIsFocusedAsState()

        val targetValue = when {
            !enabled -> disabledIndicatorColor
            isError -> errorIndicatorColor
            focused -> focusedIndicatorColor
            else -> unfocusedIndicatorColor
        }
        return if (enabled) {
            animateColorAsState(targetValue, tween(durationMillis = AnimationDuration))
        } else {
            rememberUpdatedState(targetValue)
        }
    }

    @Composable
    override fun backgroundColor(enabled: Boolean): State<Color> {
        return rememberUpdatedState(backgroundColor)
    }

    @Composable
    override fun placeholderColor(enabled: Boolean): State<Color> {
        return rememberUpdatedState(if (enabled) placeholderColor else disabledPlaceholderColor)
    }

    @Composable
    override fun labelColor(
        enabled: Boolean,
        error: Boolean,
        interactionSource: InteractionSource
    ): State<Color> {
        val focused by interactionSource.collectIsFocusedAsState()

        val targetValue = when {
            !enabled -> disabledLabelColor
            error -> errorLabelColor
            focused -> focusedLabelColor
            else -> unfocusedLabelColor
        }
        return rememberUpdatedState(targetValue)
    }

    @Composable
    override fun textColor(enabled: Boolean): State<Color> {
        return rememberUpdatedState(if (enabled) textColor else disabledTextColor)
    }

    @Composable
    override fun cursorColor(isError: Boolean): State<Color> {
        return rememberUpdatedState(if (isError) errorCursorColor else cursorColor)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as DefaultTextFieldForExposedDropdownMenusColors

        if (textColor != other.textColor) return false
        if (disabledTextColor != other.disabledTextColor) return false
        if (cursorColor != other.cursorColor) return false
        if (errorCursorColor != other.errorCursorColor) return false
        if (focusedIndicatorColor != other.focusedIndicatorColor) return false
        if (unfocusedIndicatorColor != other.unfocusedIndicatorColor) return false
        if (errorIndicatorColor != other.errorIndicatorColor) return false
        if (disabledIndicatorColor != other.disabledIndicatorColor) return false
        if (leadingIconColor != other.leadingIconColor) return false
        if (disabledLeadingIconColor != other.disabledLeadingIconColor) return false
        if (errorLeadingIconColor != other.errorLeadingIconColor) return false
        if (trailingIconColor != other.trailingIconColor) return false
        if (focusedTrailingIconColor != other.focusedTrailingIconColor) return false
        if (disabledTrailingIconColor != other.disabledTrailingIconColor) return false
        if (errorTrailingIconColor != other.errorTrailingIconColor) return false
        if (backgroundColor != other.backgroundColor) return false
        if (focusedLabelColor != other.focusedLabelColor) return false
        if (unfocusedLabelColor != other.unfocusedLabelColor) return false
        if (disabledLabelColor != other.disabledLabelColor) return false
        if (errorLabelColor != other.errorLabelColor) return false
        if (placeholderColor != other.placeholderColor) return false
        if (disabledPlaceholderColor != other.disabledPlaceholderColor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = textColor.hashCode()
        result = 31 * result + disabledTextColor.hashCode()
        result = 31 * result + cursorColor.hashCode()
        result = 31 * result + errorCursorColor.hashCode()
        result = 31 * result + focusedIndicatorColor.hashCode()
        result = 31 * result + unfocusedIndicatorColor.hashCode()
        result = 31 * result + errorIndicatorColor.hashCode()
        result = 31 * result + disabledIndicatorColor.hashCode()
        result = 31 * result + leadingIconColor.hashCode()
        result = 31 * result + disabledLeadingIconColor.hashCode()
        result = 31 * result + errorLeadingIconColor.hashCode()
        result = 31 * result + trailingIconColor.hashCode()
        result = 31 * result + focusedTrailingIconColor.hashCode()
        result = 31 * result + disabledTrailingIconColor.hashCode()
        result = 31 * result + errorTrailingIconColor.hashCode()
        result = 31 * result + backgroundColor.hashCode()
        result = 31 * result + focusedLabelColor.hashCode()
        result = 31 * result + unfocusedLabelColor.hashCode()
        result = 31 * result + disabledLabelColor.hashCode()
        result = 31 * result + errorLabelColor.hashCode()
        result = 31 * result + placeholderColor.hashCode()
        result = 31 * result + disabledPlaceholderColor.hashCode()
        return result
    }
}
