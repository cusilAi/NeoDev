package com.cusil.neodev

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

private class Lang(
    val keywords: Set<String>,
    val line: String?,
    val blockStart: String?,
    val blockEnd: String?,
    val quotes: String
)

object Highlighter {
    private val cKeywords = setOf(
        "abstract", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
        "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "if",
        "implements", "import", "instanceof", "int", "interface", "long", "new", "null", "package",
        "private", "protected", "public", "return", "short", "static", "super", "switch", "this",
        "throw", "throws", "try", "var", "void", "while", "true", "false", "function", "let", "def",
        "async", "await", "export", "from", "of", "typeof", "yield", "task", "plugins", "dependencies"
    )
    private val ktKeywords = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
        "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
        "val", "var", "when", "while", "by", "catch", "constructor", "finally", "import", "init",
        "override", "private", "public", "protected", "internal", "open", "abstract", "sealed", "data",
        "enum", "companion", "suspend", "lateinit", "const", "inline", "reified", "operator", "where"
    )
    private val pyKeywords = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
        "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda",
        "None", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield",
        "True", "False", "self", "print"
    )
    private val skKeywords = setOf(
        "command", "trigger", "on", "if", "else", "else if", "loop", "while", "set", "add", "remove",
        "delete", "send", "broadcast", "cancel", "event", "function", "return", "options", "variables",
        "player", "victim", "attacker", "to", "from", "is", "not", "true", "false", "stop", "wait",
        "execute", "console", "give", "teleport", "message", "permission", "aliases", "usage", "every",
        "all", "in", "world", "of", "at", "the", "and", "or"
    )
    private val jsonKeywords = setOf("true", "false", "null")

    private val slash = "\""
    private fun langFor(ext: String): Lang = when (ext) {
        "kt", "kts" -> Lang(ktKeywords, "//", "/*", "*/", "\"'")
        "py" -> Lang(pyKeywords, "#", null, null, "\"'")
        "sk" -> Lang(skKeywords, "#", null, null, "\"")
        "yml", "yaml", "toml", "properties", "sh", "txt", "md" ->
            Lang(jsonKeywords, "#", null, null, "\"'")
        "json" -> Lang(jsonKeywords, null, null, null, "\"")
        "xml", "html", "svg" -> Lang(emptySet(), null, "<!--", "-->", "\"'")
        else -> Lang(cKeywords, "//", "/*", "*/", "\"'")
    }

    private val cComment = SpanStyle(color = Color(0xFF6A9955))
    private val cString = SpanStyle(color = Color(0xFFE9A86B))
    private val cNumber = SpanStyle(color = Color(0xFF8FD3FF))
    private val cKeyword = SpanStyle(color = Color(0xFFC792EA))
    private val cAnnotation = SpanStyle(color = Color(0xFFFFD866))

    fun build(text: String, ext: String): AnnotatedString {
        if (text.length > 120_000) return AnnotatedString(text)
        val lang = langFor(ext)
        val b = AnnotatedString.Builder(text)
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            if (lang.line != null && text.startsWith(lang.line, i)) {
                var e = text.indexOf('\n', i)
                if (e < 0) e = n
                b.addStyle(cComment, i, e)
                i = e
                continue
            }
            if (lang.blockStart != null && lang.blockEnd != null && text.startsWith(lang.blockStart, i)) {
                var e = text.indexOf(lang.blockEnd, i + lang.blockStart.length)
                e = if (e < 0) n else e + lang.blockEnd.length
                b.addStyle(cComment, i, e)
                i = e
                continue
            }
            if (lang.quotes.indexOf(c) >= 0) {
                if (text.startsWith("\"\"\"", i)) {
                    var e = text.indexOf("\"\"\"", i + 3)
                    e = if (e < 0) n else e + 3
                    b.addStyle(cString, i, e)
                    i = e
                    continue
                }
                var j = i + 1
                while (j < n && text[j] != c && text[j] != '\n') {
                    if (text[j] == '\\' && j + 1 < n) j++
                    j++
                }
                val e = if (j < n && text[j] == c) j + 1 else j
                b.addStyle(cString, i, e)
                i = e
                continue
            }
            if (c.isDigit() && (i == 0 || !(text[i - 1].isLetterOrDigit() || text[i - 1] == '_'))) {
                var j = i + 1
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == '_')) j++
                b.addStyle(cNumber, i, j)
                i = j
                continue
            }
            if (c == '@' && i + 1 < n && text[i + 1].isLetter() && (ext == "java" || ext == "kt" || ext == "kts")) {
                var j = i + 1
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                b.addStyle(cAnnotation, i, j)
                i = j
                continue
            }
            if (c.isLetter() || c == '_') {
                var j = i + 1
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                if (lang.keywords.contains(text.substring(i, j))) b.addStyle(cKeyword, i, j)
                i = j
                continue
            }
            i++
        }
        return b.toAnnotatedString()
    }
}

class SyntaxTransformation(private val ext: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(Highlighter.build(text.text, ext), OffsetMapping.Identity)
}
