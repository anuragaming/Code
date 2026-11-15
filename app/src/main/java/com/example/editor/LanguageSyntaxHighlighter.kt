package com.example.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Multi-language syntax highlighter that strictly preserves 1:1 character offsets
 * (`OffsetMapping.Identity`) so cursor and selection positions never drift.
 */
class LanguageSyntaxHighlighter(
    private val language: EditorLanguage,
    private val isDarkTheme: Boolean = true
) : VisualTransformation {

    private val keywordColor = if (isDarkTheme) Color(0xFFFF7B72) else Color(0xFFD73A49)
    private val stringColor = if (isDarkTheme) Color(0xFFA5D6FF) else Color(0xFF032F62)
    private val commentColor = if (isDarkTheme) Color(0xFF8B949E) else Color(0xFF6A737D)
    private val numberColor = if (isDarkTheme) Color(0xFF79C0FF) else Color(0xFF005CC5)
    private val functionColor = if (isDarkTheme) Color(0xFFD2A8FF) else Color(0xFF6F42C1)
    private val tagColor = if (isDarkTheme) Color(0xFF7EE787) else Color(0xFF22863A)
    private val attributeColor = if (isDarkTheme) Color(0xFFFFA657) else Color(0xFFE36209)
    private val bracketColor = if (isDarkTheme) Color(0xFFE6EDF3) else Color(0xFF24292E)

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        // Skip heavy regex passes on very large files (> 80 KB) to prevent UI frame drops
        if (raw.isEmpty() || raw.length > 80_000) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val rules = LanguageRegistry.getRuleSet(language)
        val annotated = buildAnnotatedString {
            append(raw)

            // 1. Brackets & Braces
            val bracketRegex = Regex("""[\{\}\[\]\(\)]""")
            bracketRegex.findAll(raw).forEach { m ->
                addStyle(SpanStyle(color = bracketColor, fontWeight = FontWeight.Medium), m.range.first, m.range.last + 1)
            }

            // 2. Numbers
            val numberRegex = Regex("""\b\d+(\.\d+)?\b""")
            numberRegex.findAll(raw).forEach { m ->
                addStyle(SpanStyle(color = numberColor), m.range.first, m.range.last + 1)
            }

            // 3. Language Keywords
            if (rules.keywords.isNotEmpty()) {
                val escapedKeywords = rules.keywords.joinToString("|") { Regex.escape(it) }
                val keywordPattern = Regex("""\b($escapedKeywords)\b""")
                keywordPattern.findAll(raw).forEach { m ->
                    addStyle(
                        SpanStyle(color = keywordColor, fontWeight = FontWeight.SemiBold),
                        m.range.first,
                        m.range.last + 1
                    )
                }
            }

            // 4. Function definitions / calls
            val funcRegex = Regex("""\b([a-zA-Z_][a-zA-Z0-9_]*)\s*(?=\()""")
            funcRegex.findAll(raw).forEach { m ->
                val name = m.groupValues[1]
                if (name !in rules.keywords) {
                    addStyle(SpanStyle(color = functionColor), m.groups[1]!!.range.first, m.groups[1]!!.range.last + 1)
                }
            }

            // 5. Decorators / Annotations (@...)
            val annotationRegex = Regex("""@[a-zA-Z_][a-zA-Z0-9_.]*""")
            annotationRegex.findAll(raw).forEach { m ->
                addStyle(SpanStyle(color = attributeColor, fontWeight = FontWeight.Medium), m.range.first, m.range.last + 1)
            }

            // 6. HTML / XML tags and attributes
            if (language == EditorLanguage.HTML || language == EditorLanguage.XML) {
                val htmlTagRegex = Regex("""</?[a-zA-Z!][a-zA-Z0-9:-]*|/?>""")
                htmlTagRegex.findAll(raw).forEach { m ->
                    addStyle(SpanStyle(color = tagColor, fontWeight = FontWeight.SemiBold), m.range.first, m.range.last + 1)
                }
                val attrRegex = Regex("""\s([a-zA-Z_:][a-zA-Z0-9_.:-]*)\s*=""")
                attrRegex.findAll(raw).forEach { m ->
                    val range = m.groups[1]?.range
                    if (range != null) {
                        addStyle(SpanStyle(color = attributeColor), range.first, range.last + 1)
                    }
                }
            }

            // 7. CSS selectors & properties
            if (language == EditorLanguage.CSS) {
                val propRegex = Regex("""([a-zA-Z-]+)\s*:""")
                propRegex.findAll(raw).forEach { m ->
                    val range = m.groups[1]?.range
                    if (range != null) {
                        addStyle(SpanStyle(color = attributeColor), range.first, range.last + 1)
                    }
                }
            }

            // 8. Strings (single, double, backtick)
            val stringRegex = Regex(""""(\\.|[^"\\\n])*"|'(\\.|[^'\\\n])*'|`(\\.|[^`\\])*`""")
            stringRegex.findAll(raw).forEach { m ->
                addStyle(SpanStyle(color = stringColor), m.range.first, m.range.last + 1)
            }

            // 9. Comments (applied last so comments override tokens inside them)
            if (rules.singleLineCommentPrefix == "#") {
                Regex("""#.*""").findAll(raw).forEach { m ->
                    addStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic), m.range.first, m.range.last + 1)
                }
            } else if (rules.singleLineCommentPrefix == "//") {
                Regex("""//.*""").findAll(raw).forEach { m ->
                    addStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic), m.range.first, m.range.last + 1)
                }
            }

            if (rules.blockCommentStart == "/*" && rules.blockCommentEnd == "*/") {
                Regex("""/\*[\s\S]*?\*/""").findAll(raw).forEach { m ->
                    addStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic), m.range.first, m.range.last + 1)
                }
            } else if (rules.blockCommentStart == "<!--" && rules.blockCommentEnd == "-->") {
                Regex("""<!--[\s\S]*?-->""").findAll(raw).forEach { m ->
                    addStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic), m.range.first, m.range.last + 1)
                }
            }
        }

        return TransformedText(annotated, OffsetMapping.Identity)
    }
}
