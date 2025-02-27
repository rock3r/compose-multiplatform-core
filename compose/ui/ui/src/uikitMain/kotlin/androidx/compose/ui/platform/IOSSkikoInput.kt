/*
 * Copyright 2023 The Android Open Source Project
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

package androidx.compose.ui.platform

import platform.UIKit.UITextDirection
import platform.UIKit.UITextGranularity

internal interface IOSSkikoInput {

    /**
     * A Boolean value that indicates whether the text-entry object has any text.
     * https://developer.apple.com/documentation/uikit/uikeyinput/1614457-hastext
     */
    fun hasText(): Boolean

    /**
     * Inserts a character into the displayed text.
     * Add the character text to your class’s backing store at the index corresponding to the cursor and redisplay the text.
     * https://developer.apple.com/documentation/uikit/uikeyinput/1614543-inserttext
     * @param text A string object representing the character typed on the system keyboard.
     */
    fun insertText(text: String)

    /**
     * Deletes a character from the displayed text.
     * Remove the character just before the cursor from your class’s backing store and redisplay the text.
     * https://developer.apple.com/documentation/uikit/uikeyinput/1614572-deletebackward
     */
    fun deleteBackward()

    /**
     * The text position for the end of a document.
     * https://developer.apple.com/documentation/uikit/uitextinput/1614555-endofdocument
     */
    fun endOfDocument(): Long

    /**
     * The range of selected text in a document.
     * If the text range has a length, it indicates the currently selected text.
     * If it has zero length, it indicates the caret (insertion point).
     * If the text-range object is nil, it indicates that there is no current selection.
     * https://developer.apple.com/documentation/uikit/uitextinput/1614541-selectedtextrange
     */
    fun getSelectedTextRange(): IntRange?

    fun setSelectedTextRange(range: IntRange?)

    fun selectAll()

    /**
     * Returns the text in the specified range.
     * https://developer.apple.com/documentation/uikit/uitextinput/1614527-text
     * @param range A range of text in a document.
     * @return A substring of a document that falls within the specified range.
     */
    fun textInRange(range: IntRange): String

    /**
     * Replaces the text in a document that is in the specified range.
     * https://developer.apple.com/documentation/uikit/uitextinput/1614558-replace
     * @param range A range of text in a document.
     * @param text A string to replace the text in range.
     */
    fun replaceRange(range: IntRange, text: String)

    /**
     * Inserts the provided text and marks it to indicate that it is part of an active input session.
     * Setting marked text either replaces the existing marked text or,
     * if none is present, inserts it in place of the current selection.
     * https://developer.apple.com/documentation/uikit/uitextinput/1614465-setmarkedtext
     * @param markedText The text to be marked.
     * @param selectedRange A range within markedText that indicates the current selection.
     * This range is always relative to markedText.
     */
    fun setMarkedText(markedText: String?, selectedRange: IntRange)

    /**
     * The range of currently marked text in a document.
     * If there is no marked text, the value of the property is nil.
     * Marked text is provisionally inserted text that requires user confirmation;
     * it occurs in multistage text input.
     * The current selection, which can be a caret or an extended range, always occurs within the marked text.
     * https://developer.apple.com/documentation/uikit/uitextinput/1614489-markedtextrange
     */
    fun markedTextRange(): IntRange?

    /**
     * Unmarks the currently marked text.
     * After this method is called, the value of markedTextRange is nil.
     * https://developer.apple.com/documentation/uikit/uitextinput/1614512-unmarktext
     */
    fun unmarkText()

    /**
     * Returns the text position at a specified offset from another text position.
     */
    fun positionFromPosition(position: Long, offset: Long): Long

    /**
     * Return the range for the text enclosing a text position in a text unit of a given granularity in a given direction.
     * https://developer.apple.com/documentation/uikit/uitextinputtokenizer/1614464-rangeenclosingposition?language=objc
     * @param position
     * A text-position object that represents a location in a document.
     * @param granularity
     * A constant that indicates a certain granularity of text unit.
     * @param direction
     * A constant that indicates a direction relative to position. The constant can be of type UITextStorageDirection or UITextLayoutDirection.
     * @return
     * A text-range representing a text unit of the given granularity in the given direction, or nil if there is no such enclosing unit.
     * Whether a boundary position is enclosed depends on the given direction, using the same rule as the isPosition:withinTextUnit:inDirection: method.
     */
    fun rangeEnclosingPosition(position: Int, withGranularity: UITextGranularity, inDirection: UITextDirection): IntRange?

    /**
     * Return whether a text position is at a boundary of a text unit of a specified granularity in a specified direction.
     * https://developer.apple.com/documentation/uikit/uitextinputtokenizer/1614553-isposition?language=objc
     * @param position
     * A text-position object that represents a location in a document.
     * @param granularity
     * A constant that indicates a certain granularity of text unit.
     * @param direction
     * A constant that indicates a direction relative to position. The constant can be of type UITextStorageDirection or UITextLayoutDirection.
     * @return
     * TRUE if the text position is at the given text-unit boundary in the given direction; FALSE if it is not at the boundary.
     */
    fun isPositionAtBoundary(position: Int, atBoundary: UITextGranularity, inDirection: UITextDirection): Boolean

    /**
     * Return whether a text position is within a text unit of a specified granularity in a specified direction.
     * https://developer.apple.com/documentation/uikit/uitextinputtokenizer/1614491-isposition?language=objc
     * @param position
     * A text-position object that represents a location in a document.
     * @param granularity
     * A constant that indicates a certain granularity of text unit.
     * @param direction
     * A constant that indicates a direction relative to position. The constant can be of type UITextStorageDirection or UITextLayoutDirection.
     * @return
     * TRUE if the text position is within a text unit of the specified granularity in the specified direction; otherwise, return FALSE.
     * If the text position is at a boundary, return TRUE only if the boundary is part of the text unit in the given direction.
     */
    fun isPositionWithingTextUnit(position: Int, withinTextUnit: UITextGranularity, inDirection: UITextDirection): Boolean

    object Empty : IOSSkikoInput {
        override fun hasText(): Boolean = false
        override fun insertText(text: String) = Unit
        override fun deleteBackward() = Unit
        override fun endOfDocument(): Long = 0L
        override fun getSelectedTextRange(): IntRange? = null
        override fun setSelectedTextRange(range: IntRange?) = Unit
        override fun selectAll() = Unit
        override fun textInRange(range: IntRange): String = ""
        override fun replaceRange(range: IntRange, text: String) = Unit
        override fun setMarkedText(markedText: String?, selectedRange: IntRange) = Unit
        override fun markedTextRange(): IntRange? = null
        override fun unmarkText() = Unit
        override fun positionFromPosition(position: Long, offset: Long): Long = 0
        override fun rangeEnclosingPosition(
            position: Int,
            withGranularity: UITextGranularity,
            inDirection: UITextDirection
        ): IntRange? = null

        override fun isPositionAtBoundary(
            position: Int,
            atBoundary: UITextGranularity,
            inDirection: UITextDirection
        ): Boolean = false

        override fun isPositionWithingTextUnit(
            position: Int,
            withinTextUnit: UITextGranularity,
            inDirection: UITextDirection
        ): Boolean = false
    }
}