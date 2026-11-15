package com.example.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

object IndentationEngine {

    private val htmlOpenTagRegex = Regex("""<([a-zA-Z][a-zA-Z0-9:-]*)(?:\s[^<>]*?)?>\s*$""")
    private val htmlClosingTagPrefixRegex = Regex("""^\s*</([a-zA-Z][a-zA-Z0-9:-]*)>""")
    private val htmlJustTypedOpenTagRegex = Regex("""<([a-zA-Z][a-zA-Z0-9:-]*)(?:\s[^<>]*?)?>$""")

    /**
     * Performs a language-aware Enter operation at the current cursor/selection in [value].
     */
    fun handleEnter(value: TextFieldValue, language: EditorLanguage): TextFieldValue {
        val rules = LanguageRegistry.getRuleSet(language)
        val text = value.text
        val selStart = value.selection.min.coerceIn(0, text.length)
        val selEnd = value.selection.max.coerceIn(0, text.length)

        val beforeCursor = text.substring(0, selStart)
        val afterCursor = text.substring(selEnd)

        val lineStart = beforeCursor.lastIndexOf('\n').let { if (it == -1) 0 else it + 1 }
        val currentLineBeforeCursor = beforeCursor.substring(lineStart)
        val currentIndent = currentLineBeforeCursor.takeWhile { it == ' ' || it == '\t' }
            .replace("\t", rules.indentUnit)
        val trimmedLineBefore = currentLineBeforeCursor.trim()
        val trimmedAfterCursor = afterCursor.trimStart()

        // 1. Check HTML / XML tag split or open tag indentation
        if (language == EditorLanguage.HTML || language == EditorLanguage.XML) {
            // Check if inside <script> or <style> block
            val effectiveLanguage = detectEmbeddedHtmlLanguage(beforeCursor, afterCursor, language)
            if (effectiveLanguage != language) {
                return handleEnter(value, effectiveLanguage)
            }

            val openMatch = htmlOpenTagRegex.find(currentLineBeforeCursor)
            val tagName = openMatch?.groupValues?.getOrNull(1)?.lowercase()
            val isSelfClosing = currentLineBeforeCursor.trimEnd().endsWith("/>") ||
                (tagName != null && tagName in rules.htmlVoidTags)

            if (tagName != null && !isSelfClosing) {
                val closeMatch = htmlClosingTagPrefixRegex.find(afterCursor)
                val closingTagName = closeMatch?.groupValues?.getOrNull(1)?.lowercase()
                val innerIndent = currentIndent + rules.indentUnit

                return if (closingTagName == tagName) {
                    // Split `<tag>|</tag>` into:
                    // <tag>
                    //   |
                    // </tag>
                    val consumedAfter = afterCursor.trimStart { it == ' ' || it == '\t' }
                    val insertion = "\n$innerIndent\n$currentIndent"
                    val newText = beforeCursor + insertion + consumedAfter
                    val newCursor = beforeCursor.length + 1 + innerIndent.length
                    TextFieldValue(text = newText, selection = TextRange(newCursor))
                } else {
                    // Check if closing tag already exists later on the same line or document
                    val hasMatchingCloseSoon = hasClosingTagAhead(afterCursor, tagName)
                    if (!hasMatchingCloseSoon) {
                        val insertion = "\n$innerIndent\n$currentIndent</$tagName>"
                        val newText = beforeCursor + insertion + afterCursor
                        val newCursor = beforeCursor.length + 1 + innerIndent.length
                        TextFieldValue(text = newText, selection = TextRange(newCursor))
                    } else {
                        val insertion = "\n$innerIndent"
                        val newText = beforeCursor + insertion + afterCursor
                        val newCursor = beforeCursor.length + insertion.length
                        TextFieldValue(text = newText, selection = TextRange(newCursor))
                    }
                }
            }
        }

        // 2. Check brace/bracket split: `{|}`, `[|]`, `(|)`
        val lastCharBefore = currentLineBeforeCursor.trimEnd().lastOrNull()
        val firstCharAfter = trimmedAfterCursor.firstOrNull()
        if (lastCharBefore != null && lastCharBefore in rules.blockOpenChars) {
            val matchingClose = rules.autoClosePairs[lastCharBefore]
            val innerIndent = currentIndent + rules.indentUnit
            if (firstCharAfter != null && firstCharAfter == matchingClose) {
                val consumedAfter = afterCursor.trimStart { it == ' ' || it == '\t' }
                val insertion = "\n$innerIndent\n$currentIndent"
                val newText = beforeCursor + insertion + consumedAfter
                val newCursor = beforeCursor.length + 1 + innerIndent.length
                return TextFieldValue(text = newText, selection = TextRange(newCursor))
            } else {
                val insertion = "\n$innerIndent"
                val newText = beforeCursor + insertion + afterCursor
                val newCursor = beforeCursor.length + insertion.length
                return TextFieldValue(text = newText, selection = TextRange(newCursor))
            }
        }

        // 3. Language-specific rules (Python, YAML, Shell, Markdown)
        when (language) {
            EditorLanguage.PYTHON -> {
                val codeWithoutComment = stripPythonComment(trimmedLineBefore)
                if (codeWithoutComment.endsWith(":")) {
                    val innerIndent = currentIndent + rules.indentUnit
                    val insertion = "\n$innerIndent"
                    val newText = beforeCursor + insertion + afterCursor
                    return TextFieldValue(
                        text = newText,
                        selection = TextRange(beforeCursor.length + insertion.length)
                    )
                }
                if (isPythonDedentStatement(codeWithoutComment, rules.pythonDedentStatements)) {
                    val dedented = removeOneIndentLevel(currentIndent, rules.indentUnit.length)
                    val insertion = "\n$dedented"
                    val newText = beforeCursor + insertion + afterCursor
                    return TextFieldValue(
                        text = newText,
                        selection = TextRange(beforeCursor.length + insertion.length)
                    )
                }
            }

            EditorLanguage.YAML -> {
                val codeWithoutComment = trimmedLineBefore.substringBefore('#').trimEnd()
                if (codeWithoutComment.endsWith(":") || codeWithoutComment.startsWith("- ")) {
                    val innerIndent = currentIndent + rules.indentUnit
                    val insertion = "\n$innerIndent"
                    val newText = beforeCursor + insertion + afterCursor
                    return TextFieldValue(
                        text = newText,
                        selection = TextRange(beforeCursor.length + insertion.length)
                    )
                }
            }

            EditorLanguage.SHELL -> {
                val codeWithoutComment = trimmedLineBefore.substringBefore('#').trimEnd()
                if (codeWithoutComment.endsWith("then") ||
                    codeWithoutComment.endsWith("do") ||
                    codeWithoutComment.endsWith("{")
                ) {
                    val innerIndent = currentIndent + rules.indentUnit
                    val insertion = "\n$innerIndent"
                    val newText = beforeCursor + insertion + afterCursor
                    return TextFieldValue(
                        text = newText,
                        selection = TextRange(beforeCursor.length + insertion.length)
                    )
                }
            }

            EditorLanguage.MARKDOWN -> {
                val bulletMatch = Regex("""^(\s*)([-*+]|\d+\.)\s+(.+)$""").find(currentLineBeforeCursor)
                if (bulletMatch != null) {
                    val marker = bulletMatch.groupValues[2]
                    val nextMarker = if (marker.endsWith(".")) {
                        val num = marker.removeSuffix(".").toIntOrNull()
                        if (num != null) "${num + 1}." else marker
                    } else {
                        marker
                    }
                    val insertion = "\n$currentIndent$nextMarker "
                    val newText = beforeCursor + insertion + afterCursor
                    return TextFieldValue(
                        text = newText,
                        selection = TextRange(beforeCursor.length + insertion.length)
                    )
                }
            }

            else -> Unit
        }

        // 4. Default: Preserve current line's indentation
        val insertion = "\n$currentIndent"
        val newText = beforeCursor + insertion + afterCursor
        val newCursor = beforeCursor.length + insertion.length
        return TextFieldValue(text = newText, selection = TextRange(newCursor))
    }

    /**
     * Handles Tab key or Tab button:
     * - If single cursor, inserts spaces up to the next tab stop for the language.
     * - If multi-line selection, indents every selected line by one indent level and preserves selection.
     */
    fun handleTab(value: TextFieldValue, language: EditorLanguage): TextFieldValue {
        val rules = LanguageRegistry.getRuleSet(language)
        val indentUnit = rules.indentUnit
        val text = value.text
        val selStart = value.selection.min.coerceIn(0, text.length)
        val selEnd = value.selection.max.coerceIn(0, text.length)

        if (selStart == selEnd || !text.substring(selStart, selEnd).contains('\n')) {
            val lineStart = text.lastIndexOf('\n', (selStart - 1).coerceAtLeast(0))
                .let { if (it == -1) 0 else it + 1 }
            val column = selStart - lineStart
            val indentSize = language.defaultIndentSize.coerceAtLeast(2)
            val spacesToInsert = indentSize - (column % indentSize)
            val pad = " ".repeat(spacesToInsert)
            val newText = text.substring(0, selStart) + pad + text.substring(selEnd)
            val newCursor = selStart + pad.length
            return TextFieldValue(text = newText, selection = TextRange(newCursor))
        }

        // Multi-line block indent
        val firstLineStart = text.lastIndexOf('\n', (selStart - 1).coerceAtLeast(0))
            .let { if (it == -1) 0 else it + 1 }
        val blockText = text.substring(firstLineStart, selEnd)
        val lines = blockText.split('\n')
        val indentedLines = lines.map { line -> indentUnit + line }
        val replacement = indentedLines.joinToString("\n")
        val newText = text.substring(0, firstLineStart) + replacement + text.substring(selEnd)
        val newStart = selStart + indentUnit.length
        val newEnd = firstLineStart + replacement.length
        return TextFieldValue(text = newText, selection = TextRange(newStart, newEnd))
    }

    /**
     * Handles Shift+Tab (unindent/dedent) on current line or multi-line selection.
     */
    fun handleShiftTab(value: TextFieldValue, language: EditorLanguage): TextFieldValue {
        val indentSize = language.defaultIndentSize.coerceAtLeast(2)
        val text = value.text
        val selStart = value.selection.min.coerceIn(0, text.length)
        val selEnd = value.selection.max.coerceIn(0, text.length)

        val firstLineStart = text.lastIndexOf('\n', (selStart - 1).coerceAtLeast(0))
            .let { if (it == -1) 0 else it + 1 }
        val currentLineEnd = text.indexOf('\n', selEnd).let { if (it == -1) text.length else it }

        val blockText = text.substring(firstLineStart, currentLineEnd)
        val lines = blockText.split('\n')
        var firstLineRemoved = 0
        var totalRemoved = 0

        val dedentedLines = lines.mapIndexed { index, line ->
            val removedCount = countLeadingSpacesToRemove(line, indentSize)
            if (index == 0) firstLineRemoved = removedCount
            totalRemoved += removedCount
            line.substring(removedCount)
        }

        if (totalRemoved == 0) return value

        val replacement = dedentedLines.joinToString("\n")
        val newText = text.substring(0, firstLineStart) + replacement + text.substring(currentLineEnd)
        val newStart = (selStart - firstLineRemoved).coerceAtLeast(firstLineStart)
        val newEnd = (selEnd - totalRemoved).coerceAtLeast(newStart)
        return TextFieldValue(
            text = newText,
            selection = if (selStart == selEnd) TextRange(newStart) else TextRange(newStart, newEnd)
        )
    }

    /**
     * Smart Backspace:
     * 1. If selection is non-empty, deletes selected text.
     * 2. If cursor is between an empty auto-closed pair `(|)`, `{|}`, `[|]`, `"|"`, `'|'`, deletes both.
     * 3. If cursor is in leading whitespace of a line, deletes back to the previous indentation level.
     * 4. Otherwise deletes one character before the cursor.
     */
    fun handleBackspace(value: TextFieldValue, language: EditorLanguage): TextFieldValue {
        val rules = LanguageRegistry.getRuleSet(language)
        val text = value.text
        val selStart = value.selection.min.coerceIn(0, text.length)
        val selEnd = value.selection.max.coerceIn(0, text.length)

        if (selStart != selEnd) {
            val newText = text.substring(0, selStart) + text.substring(selEnd)
            return TextFieldValue(text = newText, selection = TextRange(selStart))
        }

        if (selStart == 0) return value

        val charBefore = text[selStart - 1]
        val charAfter = text.getOrNull(selStart)

        // Check empty auto-close pair
        if (charAfter != null && rules.autoClosePairs[charBefore] == charAfter) {
            val newText = text.substring(0, selStart - 1) + text.substring(selStart + 1)
            return TextFieldValue(text = newText, selection = TextRange(selStart - 1))
        }

        // Check if cursor is in leading indentation
        val lineStart = text.lastIndexOf('\n', (selStart - 1).coerceAtLeast(0))
            .let { if (it == -1) 0 else it + 1 }
        val prefixOnLine = text.substring(lineStart, selStart)
        if (prefixOnLine.isNotEmpty() && prefixOnLine.all { it == ' ' }) {
            val indentSize = language.defaultIndentSize.coerceAtLeast(2)
            val removeCount = ((prefixOnLine.length - 1) % indentSize) + 1
            val newCursor = selStart - removeCount
            val newText = text.substring(0, newCursor) + text.substring(selStart)
            return TextFieldValue(text = newText, selection = TextRange(newCursor))
        }

        // Standard single-character delete
        val newText = text.substring(0, selStart - 1) + text.substring(selStart)
        return TextFieldValue(text = newText, selection = TextRange(selStart - 1))
    }

    /**
     * Handles single-character typing (auto-pairing, closing bracket skip-over,
     * auto-dedent on closing brace `}`, HTML `>` auto-closing tag, Python `else:`/`elif:` dedent).
     */
    fun handleCharacterTyped(
        oldValue: TextFieldValue,
        typedChar: Char,
        language: EditorLanguage
    ): TextFieldValue {
        val rules = LanguageRegistry.getRuleSet(language)
        val text = oldValue.text
        val selStart = oldValue.selection.min.coerceIn(0, text.length)
        val selEnd = oldValue.selection.max.coerceIn(0, text.length)

        // 1. If user selected text and typed an opening bracket or quote, wrap the selection!
        if (selStart != selEnd && rules.autoClosePairs.containsKey(typedChar)) {
            val closeChar = rules.autoClosePairs.getValue(typedChar)
            val selectedText = text.substring(selStart, selEnd)
            val wrapped = "$typedChar$selectedText$closeChar"
            val newText = text.substring(0, selStart) + wrapped + text.substring(selEnd)
            return TextFieldValue(
                text = newText,
                selection = TextRange(selStart + 1, selEnd + 1)
            )
        }

        val before = text.substring(0, selStart)
        val after = text.substring(selEnd)
        val nextChar = after.firstOrNull()

        // 2. Skip-over if user types a closing bracket/quote that is already immediately under the cursor
        if (selStart == selEnd && nextChar == typedChar &&
            (typedChar in rules.blockCloseChars || typedChar == '"' || typedChar == '\'' || typedChar == '`')
        ) {
            return TextFieldValue(text = text, selection = TextRange(selStart + 1))
        }

        // 3. Auto-dedent when typing closing brace `}`, `]`, `)` on an otherwise whitespace-only line
        if (selStart == selEnd && typedChar in rules.blockCloseChars) {
            val lineStart = before.lastIndexOf('\n').let { if (it == -1) 0 else it + 1 }
            val lineBeforeCursor = before.substring(lineStart)
            if (lineBeforeCursor.isNotEmpty() && lineBeforeCursor.all { it == ' ' || it == '\t' }) {
                val targetIndent = findMatchingOpenBracketIndent(before, typedChar, rules)
                    ?: removeOneIndentLevel(lineBeforeCursor, language.defaultIndentSize)
                val newBefore = before.substring(0, lineStart) + targetIndent + typedChar
                val newText = newBefore + after
                return TextFieldValue(text = newText, selection = TextRange(newBefore.length))
            }
        }

        // 4. Auto-close pair insertion when typing `(`, `{`, `[`, `"`, `'`, `` ` ``
        if (selStart == selEnd && rules.autoClosePairs.containsKey(typedChar)) {
            val closeChar = rules.autoClosePairs.getValue(typedChar)
            val shouldAutoClose = when (typedChar) {
                '"', '\'', '`' -> {
                    val prevChar = before.lastOrNull()
                    val isEscaped = prevChar == '\\'
                    val isWordAdjacent = (prevChar != null && prevChar.isLetterOrDigit()) ||
                        (nextChar != null && nextChar.isLetterOrDigit())
                    !isEscaped && !isWordAdjacent
                }
                else -> nextChar == null || nextChar.isWhitespace() || nextChar in rules.blockCloseChars ||
                    nextChar == ',' || nextChar == ';' || nextChar == ')'
            }

            if (shouldAutoClose) {
                val newText = "$before$typedChar$closeChar$after"
                return TextFieldValue(text = newText, selection = TextRange(before.length + 1))
            }
        }

        // 5. HTML / XML auto-closing tag when typing `>`
        if (selStart == selEnd && typedChar == '>' &&
            (language == EditorLanguage.HTML || language == EditorLanguage.XML)
        ) {
            val candidateBefore = "$before>"
            val lineStart = candidateBefore.lastIndexOf('\n').let { if (it == -1) 0 else it + 1 }
            val lineText = candidateBefore.substring(lineStart)
            val tagMatch = htmlJustTypedOpenTagRegex.find(lineText)
            val tagName = tagMatch?.groupValues?.getOrNull(1)
            val lowerTag = tagName?.lowercase()
            if (tagName != null &&
                lowerTag !in rules.htmlVoidTags &&
                !lineText.endsWith("/>") &&
                !hasClosingTagAhead(after, lowerTag!!)
            ) {
                val closeTag = "</$tagName>"
                val newText = candidateBefore + closeTag + after
                return TextFieldValue(text = newText, selection = TextRange(candidateBefore.length))
            }
        }

        // 6. Python middle-block keyword auto-dedent when typing `:` (e.g., `else:`, `elif x:`, `except:`, `finally:`)
        if (selStart == selEnd && typedChar == ':' && language == EditorLanguage.PYTHON) {
            val lineStart = before.lastIndexOf('\n').let { if (it == -1) 0 else it + 1 }
            val currentLine = before.substring(lineStart)
            val currentIndent = currentLine.takeWhile { it == ' ' }
            val trimmed = currentLine.trim()
            val firstWord = trimmed.takeWhile { it.isLetter() }
            if (firstWord in rules.pythonMiddleBlockKeywords && currentIndent.length >= rules.indentUnit.length) {
                val previousLineIndent = findPreviousNonEmptyLineIndent(before.substring(0, lineStart))
                if (previousLineIndent != null && currentIndent.length >= previousLineIndent.length && currentIndent.isNotEmpty()) {
                    val dedentedIndent = removeOneIndentLevel(currentIndent, rules.indentUnit.length)
                    val newLine = "$dedentedIndent$trimmed:"
                    val newBefore = before.substring(0, lineStart) + newLine
                    return TextFieldValue(
                        text = newBefore + after,
                        selection = TextRange(newBefore.length)
                    )
                }
            }
        }

        // Default insertion of typedChar
        val newText = "$before$typedChar$after"
        return TextFieldValue(text = newText, selection = TextRange(before.length + 1))
    }

    /**
     * Inserts a symbol or snippet from the quick-symbol bar with smart pairing / selection wrapping.
     */
    fun insertSymbol(
        value: TextFieldValue,
        symbol: String,
        language: EditorLanguage
    ): TextFieldValue {
        if (symbol == "Tab" || symbol == "    " || symbol == "\t") {
            return handleTab(value, language)
        }
        if (symbol.length == 1) {
            return handleCharacterTyped(value, symbol[0], language)
        }
        val text = value.text
        val selStart = value.selection.min.coerceIn(0, text.length)
        val selEnd = value.selection.max.coerceIn(0, text.length)
        val newText = text.substring(0, selStart) + symbol + text.substring(selEnd)
        return TextFieldValue(text = newText, selection = TextRange(selStart + symbol.length))
    }

    /**
     * Normalizes pasted multi-line code so tabs and relative indentation stay clean.
     */
    fun normalizeMultiLinePaste(
        oldValue: TextFieldValue,
        pastedText: String,
        language: EditorLanguage
    ): TextFieldValue {
        val rules = LanguageRegistry.getRuleSet(language)
        val cleanPaste = pastedText.replace("\r\n", "\n").replace("\r", "\n")
            .replace("\t", rules.indentUnit)
        val text = oldValue.text
        val selStart = oldValue.selection.min.coerceIn(0, text.length)
        val selEnd = oldValue.selection.max.coerceIn(0, text.length)
        val before = text.substring(0, selStart)
        val after = text.substring(selEnd)

        val newText = before + cleanPaste + after
        val newCursor = before.length + cleanPaste.length
        return TextFieldValue(text = newText, selection = TextRange(newCursor))
    }

    private fun stripPythonComment(line: String): String {
        var inSingleQuote = false
        var inDoubleQuote = false
        for (i in line.indices) {
            val c = line[i]
            val prev = if (i > 0) line[i - 1] else '\u0000'
            if (c == '\'' && prev != '\\' && !inDoubleQuote) inSingleQuote = !inSingleQuote
            else if (c == '"' && prev != '\\' && !inSingleQuote) inDoubleQuote = !inDoubleQuote
            else if (c == '#' && !inSingleQuote && !inDoubleQuote) {
                return line.substring(0, i).trimEnd()
            }
        }
        return line.trimEnd()
    }

    private fun isPythonDedentStatement(
        codeLine: String,
        dedentKeywords: Set<String>
    ): Boolean {
        if (codeLine.isEmpty()) return false
        val firstToken = codeLine.takeWhile { it.isLetter() }
        if (firstToken !in dedentKeywords) return false
        val rest = codeLine.substring(firstToken.length)
        return rest.isEmpty() || rest.first().isWhitespace() || rest.first() == '('
    }

    private fun removeOneIndentLevel(indent: String, indentSize: Int): String {
        if (indent.isEmpty()) return ""
        val removeCount = indent.length.coerceAtMost(indentSize)
        return indent.substring(0, indent.length - removeCount)
    }

    private fun countLeadingSpacesToRemove(line: String, indentSize: Int): Int {
        var count = 0
        for (ch in line) {
            if (ch == ' ') {
                count++
                if (count >= indentSize) break
            } else if (ch == '\t') {
                count++
                break
            } else {
                break
            }
        }
        return count
    }

    private fun findPreviousNonEmptyLineIndent(textBeforeCurrentLine: String): String? {
        val lines = textBeforeCurrentLine.lines()
        for (i in lines.lastIndex downTo 0) {
            val line = lines[i]
            if (line.isNotBlank()) {
                return line.takeWhile { it == ' ' }
            }
        }
        return null
    }

    private fun findMatchingOpenBracketIndent(
        textBeforeCursor: String,
        closeBracket: Char,
        rules: LanguageRuleSet
    ): String? {
        val openBracket = rules.autoClosePairs.entries.firstOrNull { it.value == closeBracket }?.key
            ?: return null
        var depth = 1
        for (i in textBeforeCursor.lastIndex downTo 0) {
            val ch = textBeforeCursor[i]
            if (ch == closeBracket) depth++
            else if (ch == openBracket) {
                depth--
                if (depth == 0) {
                    val lineStart = textBeforeCursor.lastIndexOf('\n', (i - 1).coerceAtLeast(0))
                        .let { if (it == -1) 0 else it + 1 }
                    return textBeforeCursor.substring(lineStart, i).takeWhile { it == ' ' || it == '\t' }
                        .replace("\t", rules.indentUnit)
                }
            }
        }
        return null
    }

    private fun hasClosingTagAhead(afterCursor: String, tagName: String): Boolean {
        val firstLineAfter = afterCursor.lineSequence().firstOrNull().orEmpty()
        return firstLineAfter.contains("</$tagName>", ignoreCase = true)
    }

    private fun detectEmbeddedHtmlLanguage(
        beforeCursor: String,
        afterCursor: String,
        fallback: EditorLanguage
    ): EditorLanguage {
        val lowerBefore = beforeCursor.lowercase()
        val lastScriptOpen = lowerBefore.lastIndexOf("<script")
        val lastScriptClose = lowerBefore.lastIndexOf("</script>")
        if (lastScriptOpen != -1 && (lastScriptClose == -1 || lastScriptOpen > lastScriptClose)) {
            val tagEnd = lowerBefore.indexOf('>', lastScriptOpen)
            if (tagEnd != -1 && tagEnd < beforeCursor.length) {
                return EditorLanguage.JAVASCRIPT
            }
        }

        val lastStyleOpen = lowerBefore.lastIndexOf("<style")
        val lastStyleClose = lowerBefore.lastIndexOf("</style>")
        if (lastStyleOpen != -1 && (lastStyleClose == -1 || lastStyleOpen > lastStyleClose)) {
            val tagEnd = lowerBefore.indexOf('>', lastStyleOpen)
            if (tagEnd != -1 && tagEnd < beforeCursor.length) {
                return EditorLanguage.CSS
            }
        }
        return fallback
    }
}
