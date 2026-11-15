package com.example.editor

enum class EditorLanguage(
    val displayName: String,
    val defaultIndentSize: Int,
    val usesSpaces: Boolean = true,
    val extensions: Set<String>
) {
    PYTHON("Python", 4, true, setOf("py", "pyw", "pyi")),
    HTML("HTML", 2, true, setOf("html", "htm", "xhtml")),
    JAVASCRIPT("JavaScript", 2, true, setOf("js", "mjs", "cjs", "jsx")),
    TYPESCRIPT("TypeScript", 2, true, setOf("ts", "tsx")),
    CSS("CSS", 2, true, setOf("css", "scss", "less")),
    KOTLIN("Kotlin", 4, true, setOf("kt", "kts")),
    JAVA("Java", 4, true, setOf("java")),
    JSON("JSON", 2, true, setOf("json", "jsonc")),
    XML("XML", 2, true, setOf("xml", "svg", "plist")),
    CPP("C/C++", 4, true, setOf("c", "cpp", "cc", "cxx", "h", "hpp")),
    PHP("PHP", 4, true, setOf("php")),
    YAML("YAML", 2, true, setOf("yml", "yaml")),
    MARKDOWN("Markdown", 2, true, setOf("md", "markdown")),
    SHELL("Shell", 2, true, setOf("sh", "bash", "zsh")),
    PLAIN_TEXT("Plain Text", 4, true, setOf("txt", "log", "env"))
}

data class LanguageRuleSet(
    val language: EditorLanguage,
    val indentUnit: String = " ".repeat(language.defaultIndentSize),
    val autoClosePairs: Map<Char, Char> = mapOf(
        '(' to ')',
        '{' to '}',
        '[' to ']',
        '"' to '"',
        '\'' to '\'',
        '`' to '`'
    ),
    val blockOpenChars: Set<Char> = setOf('{', '[', '('),
    val blockCloseChars: Set<Char> = setOf('}', ']', ')'),
    val keywords: Set<String> = emptySet(),
    val singleLineCommentPrefix: String? = null,
    val blockCommentStart: String? = null,
    val blockCommentEnd: String? = null,
    val htmlVoidTags: Set<String> = emptySet(),
    val pythonIndentTriggers: Set<String> = emptySet(),
    val pythonDedentStatements: Set<String> = emptySet(),
    val pythonMiddleBlockKeywords: Set<String> = emptySet()
)

object LanguageRegistry {

    private val defaultHtmlVoidTags = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr", "!doctype"
    )

    private val pythonKeywords = setOf(
        "def", "class", "if", "elif", "else", "for", "while", "try", "except",
        "finally", "with", "async", "await", "return", "yield", "import", "from",
        "as", "pass", "break", "continue", "raise", "in", "is", "not", "and", "or",
        "lambda", "True", "False", "None", "global", "nonlocal", "assert", "del", "match", "case"
    )

    private val jsTsKeywords = setOf(
        "function", "const", "let", "var", "if", "else", "for", "while", "do",
        "switch", "case", "break", "continue", "return", "class", "extends",
        "new", "this", "super", "import", "export", "from", "default", "async",
        "await", "try", "catch", "finally", "throw", "typeof", "instanceof",
        "true", "false", "null", "undefined", "interface", "type", "enum", "implements"
    )

    private val kotlinJavaKeywords = setOf(
        "package", "import", "class", "interface", "object", "fun", "val", "var",
        "if", "else", "when", "for", "while", "do", "try", "catch", "finally",
        "return", "break", "continue", "throw", "override", "private", "public",
        "protected", "internal", "open", "data", "sealed", "enum", "companion",
        "suspend", "inline", "true", "false", "null", "this", "super", "is", "in",
        "void", "static", "final", "extends", "implements", "new", "synchronized"
    )

    private val cppKeywords = setOf(
        "int", "long", "double", "float", "char", "void", "bool", "auto", "const",
        "static", "struct", "class", "enum", "namespace", "using", "template",
        "typename", "public", "private", "protected", "virtual", "override",
        "if", "else", "switch", "case", "for", "while", "do", "return", "break",
        "continue", "new", "delete", "nullptr", "true", "false", "#include", "#define"
    )

    private val cssKeywords = setOf(
        "display", "position", "top", "left", "right", "bottom", "width", "height",
        "margin", "padding", "border", "background", "background-color", "color",
        "font-size", "font-weight", "font-family", "flex", "grid", "align-items",
        "justify-content", "gap", "transition", "transform", "opacity", "z-index",
        "@media", "@keyframes", "@import", "@supports", "important"
    )

    fun detectLanguage(filePath: String, content: String = ""): EditorLanguage {
        val cleanName = filePath.substringAfterLast('/').lowercase()
        val ext = cleanName.substringAfterLast('.', "")
        if (ext.isNotEmpty()) {
            EditorLanguage.entries.firstOrNull { ext in it.extensions }?.let { return it }
        }
        val firstLine = content.lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine.startsWith("#!")) {
            if (firstLine.contains("python")) return EditorLanguage.PYTHON
            if (firstLine.contains("node") || firstLine.contains("deno")) return EditorLanguage.JAVASCRIPT
            if (firstLine.contains("bash") || firstLine.contains("sh")) return EditorLanguage.SHELL
        }
        if (firstLine.startsWith("<!DOCTYPE html", ignoreCase = true) || firstLine.startsWith("<html", ignoreCase = true)) {
            return EditorLanguage.HTML
        }
        return EditorLanguage.PLAIN_TEXT
    }

    fun getRuleSet(language: EditorLanguage): LanguageRuleSet {
        return when (language) {
            EditorLanguage.PYTHON -> LanguageRuleSet(
                language = language,
                keywords = pythonKeywords,
                singleLineCommentPrefix = "#",
                pythonIndentTriggers = setOf(
                    "def", "class", "if", "elif", "else", "for", "while",
                    "try", "except", "finally", "with", "async", "match", "case"
                ),
                pythonDedentStatements = setOf("return", "break", "continue", "pass", "raise"),
                pythonMiddleBlockKeywords = setOf("elif", "else", "except", "finally", "case")
            )

            EditorLanguage.HTML -> LanguageRuleSet(
                language = language,
                autoClosePairs = mapOf(
                    '(' to ')',
                    '{' to '}',
                    '[' to ']',
                    '"' to '"',
                    '\'' to '\''
                ),
                blockStartComment = "<!--",
                blockEndComment = "-->",
                htmlVoidTags = defaultHtmlVoidTags,
                keywords = setOf(
                    "html", "head", "body", "div", "span", "p", "a", "ul", "ol", "li",
                    "header", "footer", "main", "section", "article", "nav", "aside",
                    "form", "input", "button", "label", "select", "option", "textarea",
                    "script", "style", "link", "meta", "title", "h1", "h2", "h3", "h4", "table", "tr", "td", "th"
                )
            )

            EditorLanguage.XML -> LanguageRuleSet(
                language = language,
                autoClosePairs = mapOf('"' to '"', '\'' to '\''),
                blockStartComment = "<!--",
                blockEndComment = "-->",
                htmlVoidTags = setOf("?xml")
            )

            EditorLanguage.JAVASCRIPT, EditorLanguage.TYPESCRIPT -> LanguageRuleSet(
                language = language,
                keywords = jsTsKeywords,
                singleLineCommentPrefix = "//",
                blockStartComment = "/*",
                blockEndComment = "*/"
            )

            EditorLanguage.CSS -> LanguageRuleSet(
                language = language,
                keywords = cssKeywords,
                blockStartComment = "/*",
                blockEndComment = "*/"
            )

            EditorLanguage.KOTLIN, EditorLanguage.JAVA -> LanguageRuleSet(
                language = language,
                keywords = kotlinJavaKeywords,
                singleLineCommentPrefix = "//",
                blockStartComment = "/*",
                blockEndComment = "*/"
            )

            EditorLanguage.CPP -> LanguageRuleSet(
                language = language,
                keywords = cppKeywords,
                singleLineCommentPrefix = "//",
                blockStartComment = "/*",
                blockEndComment = "*/"
            )

            EditorLanguage.PHP -> LanguageRuleSet(
                language = language,
                keywords = jsTsKeywords + setOf("echo", "namespace", "use", "function", "public", "private", "protected"),
                singleLineCommentPrefix = "//",
                blockStartComment = "/*",
                blockEndComment = "*/"
            )

            EditorLanguage.JSON -> LanguageRuleSet(
                language = language,
                autoClosePairs = mapOf('{' to '}', '[' to ']', '"' to '"'),
                keywords = setOf("true", "false", "null")
            )

            EditorLanguage.YAML -> LanguageRuleSet(
                language = language,
                autoClosePairs = mapOf('[' to ']', '{' to '}', '"' to '"', '\'' to '\''),
                singleLineCommentPrefix = "#",
                keywords = setOf("true", "false", "null", "yes", "no")
            )

            EditorLanguage.MARKDOWN -> LanguageRuleSet(
                language = language,
                autoClosePairs = mapOf('(' to ')', '[' to ']', '`' to '`', '"' to '"')
            )

            EditorLanguage.SHELL -> LanguageRuleSet(
                language = language,
                singleLineCommentPrefix = "#",
                keywords = setOf("if", "then", "else", "elif", "fi", "for", "in", "do", "done", "case", "esac", "while", "function", "echo", "export", "local", "return", "exit")
            )

            EditorLanguage.PLAIN_TEXT -> LanguageRuleSet(language = language)
        }
    }

    private fun LanguageRuleSet(
        language: EditorLanguage,
        indentUnit: String = " ".repeat(language.defaultIndentSize),
        autoClosePairs: Map<Char, Char> = mapOf(
            '(' to ')',
            '{' to '}',
            '[' to ']',
            '"' to '"',
            '\'' to '\'',
            '`' to '`'
        ),
        keywords: Set<String> = emptySet(),
        singleLineCommentPrefix: String? = null,
        blockStartComment: String? = null,
        blockEndComment: String? = null,
        htmlVoidTags: Set<String> = emptySet(),
        pythonIndentTriggers: Set<String> = emptySet(),
        pythonDedentStatements: Set<String> = emptySet(),
        pythonMiddleBlockKeywords: Set<String> = emptySet()
    ): LanguageRuleSet = LanguageRuleSet(
        language = language,
        indentUnit = indentUnit,
        autoClosePairs = autoClosePairs,
        blockOpenChars = setOf('{', '[', '('),
        blockCloseChars = setOf('}', ']', ')'),
        keywords = keywords,
        singleLineCommentPrefix = singleLineCommentPrefix,
        blockCommentStart = blockStartComment,
        blockCommentEnd = blockEndComment,
        htmlVoidTags = htmlVoidTags,
        pythonIndentTriggers = pythonIndentTriggers,
        pythonDedentStatements = pythonDedentStatements,
        pythonMiddleBlockKeywords = pythonMiddleBlockKeywords
    )
}
