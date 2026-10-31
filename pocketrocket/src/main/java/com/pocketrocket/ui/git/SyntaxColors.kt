package com.pocketrocket.ui.git

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * Simple, line-at-a-time syntax colouring: comments, strings, numbers, keywords and capitalised type
 * names. It knows nothing about multi-line constructs beyond lines that look like block-comment lines;
 * good enough to read code on a phone, and cheap enough to run per visible line.
 */
object SyntaxColors {

    private val keywords = setOf(
        // Kotlin, Java, Swift, Dart, Scala
        "fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum", "override", "private", "public",
        "protected", "internal", "open", "abstract", "final", "static", "void", "import", "package", "return", "if",
        "else", "when", "for", "while", "do", "try", "catch", "finally", "throw", "throws", "new", "this", "super",
        "null", "true", "false", "is", "in", "as", "suspend", "companion", "const", "lateinit", "by", "extends",
        "implements", "switch", "case", "default", "break", "continue", "let", "func", "struct", "guard", "self",
        // JS, TS
        "function", "async", "await", "export", "from", "const", "typeof", "instanceof", "undefined", "type", "yield",
        // Python, Ruby, shell
        "def", "elif", "pass", "None", "True", "False", "lambda", "with", "not", "and", "or", "end", "then", "fi",
        "esac", "local", "echo", "raise", "except", "global",
        // Rust, Go, C
        "fn", "mut", "impl", "pub", "use", "mod", "crate", "match", "trait", "where", "loop", "go", "chan", "defer",
        "range", "map", "int", "char", "long", "float", "double", "bool", "unsigned", "include", "define",
    )

    private val token = Regex(
        """("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`[^`]*`)""" + // strings
            """|(\b\d[\d_.xXa-fA-F]*\b)""" + // numbers
            """|([A-Za-z_][A-Za-z0-9_]*)""", // words
    )

    /** Which comment marker the file uses, from its name. */
    fun lineComment(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "py", "sh", "bash", "zsh", "rb", "yml", "yaml", "toml", "conf", "properties", "gradle", "pl", "r", "mk" ->
            if (fileName.endsWith(".gradle")) "//" else "#"
        "md", "txt", "json", "xml", "html", "csv", "" -> if (fileName == "Makefile" || fileName == "Dockerfile") "#" else null
        "sql", "lua", "hs" -> "--"
        else -> "//"
    }

    /** Whether [fileName] looks like code worth colouring (not prose or data). */
    fun isCode(fileName: String): Boolean = lineComment(fileName) != null

    fun highlight(line: String, comment: String?, colors: GitColors): AnnotatedString = buildAnnotatedString {
        val trimmed = line.trimStart()
        if (comment != null && (trimmed.startsWith(comment) || trimmed.startsWith("/*") || trimmed.startsWith("* ") || trimmed == "*" || trimmed.startsWith("*/"))) {
            pushStyle(SpanStyle(color = colors.comment)); append(line); pop(); return@buildAnnotatedString
        }
        var i = 0
        while (i < line.length) {
            // A trailing line comment outside a string.
            if (comment != null && line.startsWith(comment, i)) {
                pushStyle(SpanStyle(color = colors.comment)); append(line.substring(i)); pop(); break
            }
            val m = token.find(line, i)
            if (m == null) { append(line.substring(i)); break }
            val commentAt = comment?.let { line.indexOf(it, i) }?.takeIf { it in i until m.range.first }
            if (commentAt != null) { append(line.substring(i, commentAt)); i = commentAt; continue }
            append(line.substring(i, m.range.first))
            val text = m.value
            val color = when {
                m.groups[1] != null -> colors.string
                m.groups[2] != null -> colors.number
                text in keywords -> colors.keyword
                text[0].isUpperCase() -> colors.type
                else -> null
            }
            if (color != null) { pushStyle(SpanStyle(color = color)); append(text); pop() } else append(text)
            i = m.range.last + 1
        }
    }
}
