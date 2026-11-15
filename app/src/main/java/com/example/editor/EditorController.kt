package com.example.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

data class CursorPosition(
    val line: Int = 1,
    val column: Int = 1,
    val totalLines: Int = 1
)

/**
 * Manages the active code editor document state, cursor/selection-aware Undo/Redo stack,
 * diff detection between consecutive [TextFieldValue] emissions from [BasicTextField],
 * and delegation to [IndentationEngine].
 */
class EditorController(
    private val maxHistorySize: Int = 150
) {
    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()

    fun resetHistory(initialValue: TextFieldValue) {
        undoStack.clear()
        redoStack.clear()
        undoStack.addLast(initialValue)
    }

    fun canUndo(): Boolean = undoStack.size > 1
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun recordSnapshot(value: TextFieldValue) {
        val last = undoStack.lastOrNull()
        if (last != null && last.text == value.text && last.selection == value.selection) {
            return
        }
        if (last != null && last.text == value.text) {
            // Update selection on top snapshot without creating a duplicate text entry
            undoStack.removeLast()
            undoStack.addLast(value)
            return
        }
        undoStack.addLast(value)
        if (undoStack.size > maxHistorySize) {
            undoStack.removeFirst()
        }
        redoStack.clear()
    }

    fun undo(current: TextFieldValue): TextFieldValue? {
        if (undoStack.size <= 1) return null
        val popped = undoStack.removeLast()
        redoStack.addLast(popped)
        return undoStack.lastOrNull() ?: current
    }

    fun redo(): TextFieldValue? {
        if (redoStack.isEmpty()) return null
        val next = redoStack.removeLast()
        undoStack.addLast(next)
        return next
    }

    fun calculateCursorPosition(value: TextFieldValue): CursorPosition {
        val text = value.text
        val offset = value.selection.start.coerceIn(0, text.length)
        var line = 1
        var lastNewlineIndex = -1
        for (i in 0 until offset) {
            if (text[i] == '\n') {
                line++
                lastNewlineIndex = i
            }
        }
        val column = offset - lastNewlineIndex
        val totalLines = text.count { it == '\n' } + 1
        return CursorPosition(line = line, column = column, totalLines = totalLines)
    }

    /**
     * Intercepts soft-keyboard and hardware-keyboard changes emitted by `BasicTextField`
     * and applies language-aware Enter, Backspace, Tab, character pairing, and multi-line paste.
     */
    fun processValueChange(
        oldValue: TextFieldValue,
        newValue: TextFieldValue,
        language: EditorLanguage
    ): TextFieldValue {
        // Pure cursor / selection movement
        if (oldValue.text == newValue.text) {
            recordSnapshot(newValue)
            return newValue
        }

        val oldText = oldValue.text
        val newText = newValue.text
        val oldSelStart = oldValue.selection.min.coerceIn(0, oldText.length)
        val oldSelEnd = oldValue.selection.max.coerceIn(0, oldText.length)
        val selectedLen = oldSelEnd - oldSelStart

        // Compute common prefix & suffix relative to old selection
        var prefixLen = 0
        val maxPrefix = minOf(oldSelStart, newText.length)
        while (prefixLen < maxPrefix && oldText[prefixLen] == newText[prefixLen]) {
            prefixLen++
        }

        var suffixLen = 0
        val maxSuffix = minOf(oldText.length - oldSelEnd, newText.length - prefixLen)
        while (
            suffixLen < maxSuffix &&
            oldText[oldText.length - 1 - suffixLen] == newText[newText.length - 1 - suffixLen]
        ) {
            suffixLen++
        }

        val insertedSegment = newText.substring(prefixLen, newText.length - suffixLen)
        val deletedLen = oldText.length - prefixLen - suffixLen

        val transformed = when {
            // 1. User pressed Enter (inserted a single '\n' replacing selection or at cursor)
            insertedSegment == "\n" && prefixLen == oldSelStart && deletedLen == selectedLen -> {
                IndentationEngine.handleEnter(oldValue, language)
            }

            // 2. User pressed Tab on soft/hardware keyboard
            insertedSegment == "\t" && prefixLen == oldSelStart && deletedLen == selectedLen -> {
                IndentationEngine.handleTab(oldValue, language)
            }

            // 3. User pressed Backspace (deleted 1 character right before cursor)
            insertedSegment.isEmpty() && selectedLen == 0 && deletedLen == 1 && prefixLen == oldSelStart - 1 -> {
                IndentationEngine.handleBackspace(oldValue, language)
            }

            // 4. User typed a single character
            insertedSegment.length == 1 && prefixLen == oldSelStart && deletedLen == selectedLen -> {
                IndentationEngine.handleCharacterTyped(oldValue, insertedSegment[0], language)
            }

            // 5. Multi-line paste
            insertedSegment.contains('\n') && insertedSegment.length > 1 && prefixLen == oldSelStart -> {
                IndentationEngine.normalizeMultiLinePaste(oldValue, insertedSegment, language)
            }

            // 6. Standard IME composition / replacement
            else -> newValue
        }

        recordSnapshot(transformed)
        return transformed
    }

    fun performEnter(value: TextFieldValue, language: EditorLanguage): TextFieldValue {
        val updated = IndentationEngine.handleEnter(value, language)
        recordSnapshot(updated)
        return updated
    }

    fun performTab(value: TextFieldValue, language: EditorLanguage): TextFieldValue {
        val updated = IndentationEngine.handleTab(value, language)
        recordSnapshot(updated)
        return updated
    }

    fun performShiftTab(value: TextFieldValue, language: EditorLanguage): TextFieldValue {
        val updated = IndentationEngine.handleShiftTab(value, language)
        recordSnapshot(updated)
        return updated
    }

    fun performBackspace(value: TextFieldValue, language: EditorLanguage): TextFieldValue {
        val updated = IndentationEngine.handleBackspace(value, language)
        recordSnapshot(updated)
        return updated
    }

    fun performInsertSymbol(
        value: TextFieldValue,
        symbol: String,
        language: EditorLanguage
    ): TextFieldValue {
        val updated = IndentationEngine.insertSymbol(value, symbol, language)
        recordSnapshot(updated)
        return updated
    }

    fun moveCursor(value: TextFieldValue, delta: Int): TextFieldValue {
        val nextPos = (value.selection.end + delta).coerceIn(0, value.text.length)
        return value.copy(selection = TextRange(nextPos))
    }
}
