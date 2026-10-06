package io.github.crockalet.haunt.core

/**
 * A tiny, forgiving XML reader: enough for GPX/KML without a dependency.
 *
 * Handles prolog/processing instructions, DOCTYPE (incl. internal subset), comments, CDATA,
 * character and the predefined entities, namespace prefixes (exposed as [XmlElement.prefix],
 * matched by local name), single/double/unquoted attribute values and self-closing tags.
 * Malformed input never throws: stray end tags are ignored and unclosed elements are closed at EOF.
 */
internal sealed interface XmlNode

internal class XmlText(val text: String) : XmlNode

internal class XmlElement(
    val prefix: String?,
    val name: String,
    /** Attributes by local name (prefixes dropped; `xmlns` declarations omitted). */
    val attributes: Map<String, String>,
) : XmlNode {
    val children: MutableList<XmlNode> = mutableListOf()

    val elements: List<XmlElement> get() = children.filterIsInstance<XmlElement>()

    fun child(localName: String): XmlElement? = children.firstOrNull { it is XmlElement && it.name == localName } as XmlElement?

    fun children(localName: String): List<XmlElement> = elements.filter { it.name == localName }

    /** All descendant elements with [localName], in document order. */
    fun descendants(localName: String): List<XmlElement> {
        val out = mutableListOf<XmlElement>()
        fun walk(e: XmlElement) {
            for (c in e.children) if (c is XmlElement) {
                if (c.name == localName) out += c
                walk(c)
            }
        }
        walk(this)
        return out
    }

    /** Concatenated text of this element and its descendants. */
    val text: String
        get() = buildString {
            fun walk(e: XmlElement) {
                for (c in e.children) when (c) {
                    is XmlText -> append(c.text)
                    is XmlElement -> walk(c)
                }
            }
            walk(this@XmlElement)
        }

    /** Trimmed text of the first child element [localName], or null if missing/blank. */
    fun childText(localName: String): String? = child(localName)?.text?.trim()?.ifEmpty { null }
}

internal object Xml {

    /** Parses [input] into a synthetic root element (name `#document`) holding the top-level nodes. */
    fun parse(input: String): XmlElement {
        val root = XmlElement(null, "#document", emptyMap())
        val stack = ArrayList<XmlElement>().apply { add(root) }
        val s = input.removePrefix("﻿")
        var i = 0
        val text = StringBuilder()

        fun flushText() {
            if (text.isNotEmpty()) {
                stack.last().children += XmlText(text.toString())
                text.clear()
            }
        }

        while (i < s.length) {
            val c = s[i]
            if (c != '<') {
                val end = s.indexOf('<', i).let { if (it < 0) s.length else it }
                text.append(decodeEntities(s.substring(i, end)))
                i = end
                continue
            }
            when {
                s.startsWith("<!--", i) -> {
                    val end = s.indexOf("-->", i + 4)
                    i = if (end < 0) s.length else end + 3
                }
                s.startsWith("<![CDATA[", i) -> {
                    val end = s.indexOf("]]>", i + 9)
                    text.append(s, i + 9, if (end < 0) s.length else end)
                    i = if (end < 0) s.length else end + 3
                }
                s.startsWith("<?", i) -> {
                    val end = s.indexOf("?>", i + 2)
                    i = if (end < 0) s.length else end + 2
                }
                s.startsWith("<!", i) -> {
                    // DOCTYPE and friends; skip, honouring an internal [ ... ] subset.
                    var depth = 0
                    var j = i + 2
                    while (j < s.length) {
                        val ch = s[j]
                        if (ch == '[') depth++ else if (ch == ']') depth-- else if (ch == '>' && depth <= 0) break
                        j++
                    }
                    i = j + 1
                }
                s.startsWith("</", i) -> {
                    val end = s.indexOf('>', i + 2).let { if (it < 0) s.length else it }
                    val qName = s.substring(i + 2, end).trim()
                    val local = qName.substringAfter(':')
                    flushText()
                    val idx = stack.indexOfLast { it.name == local }
                    if (idx > 0) while (stack.size > idx) stack.removeAt(stack.lastIndex)
                    i = end + 1
                }
                i + 1 < s.length && isNameStart(s[i + 1]) -> {
                    flushText()
                    i = parseStartTag(s, i + 1, stack)
                }
                else -> { // A bare '<' in text: keep it.
                    text.append('<')
                    i++
                }
            }
        }
        flushText()
        return root
    }

    private fun isNameStart(c: Char) = c.isLetter() || c == '_' || c == ':'

    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == ':' || c == '-' || c == '.'

    /** Parses a start tag whose name begins at [start]; returns the index after the tag. */
    private fun parseStartTag(s: String, start: Int, stack: MutableList<XmlElement>): Int {
        var i = start
        while (i < s.length && isNameChar(s[i])) i++
        val qName = s.substring(start, i)
        val attributes = LinkedHashMap<String, String>()
        var selfClosing = false
        while (i < s.length) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) break
            if (s[i] == '>') { i++; break }
            if (s[i] == '/' && i + 1 < s.length && s[i + 1] == '>') { selfClosing = true; i += 2; break }
            val nameStart = i
            while (i < s.length && !s[i].isWhitespace() && s[i] != '=' && s[i] != '>' && !(s[i] == '/' && s.getOrNull(i + 1) == '>')) i++
            val attrName = s.substring(nameStart, i)
            if (attrName.isEmpty()) { i++; continue }
            while (i < s.length && s[i].isWhitespace()) i++
            var value = ""
            if (i < s.length && s[i] == '=') {
                i++
                while (i < s.length && s[i].isWhitespace()) i++
                if (i < s.length && (s[i] == '"' || s[i] == '\'')) {
                    val quote = s[i]
                    val end = s.indexOf(quote, i + 1).let { if (it < 0) s.length else it }
                    value = s.substring(i + 1, end)
                    i = end + 1
                } else {
                    val vStart = i
                    while (i < s.length && !s[i].isWhitespace() && s[i] != '>' && !(s[i] == '/' && s.getOrNull(i + 1) == '>')) i++
                    value = s.substring(vStart, i)
                }
            }
            if (attrName == "xmlns" || attrName.startsWith("xmlns:")) continue
            val local = attrName.substringAfter(':')
            if (local !in attributes) attributes[local] = decodeEntities(value)
        }
        val prefix = qName.substringBefore(':', "").ifEmpty { null }
        val element = XmlElement(prefix, qName.substringAfter(':'), attributes)
        stack.last().children += element
        if (!selfClosing) stack += element
        return i
    }

    fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i + 1)
                if (semi in (i + 2)..(i + 12)) {
                    val name = s.substring(i + 1, semi)
                    val decoded = when {
                        name == "lt" -> "<"
                        name == "gt" -> ">"
                        name == "amp" -> "&"
                        name == "quot" -> "\""
                        name == "apos" -> "'"
                        name == "nbsp" -> " "
                        name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)?.let(::codePoint)
                        name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePoint)
                        else -> null
                    }
                    if (decoded != null) {
                        out.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun codePoint(cp: Int): String? = when {
        cp < 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF -> null
        cp < 0x10000 -> cp.toChar().toString()
        else -> {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
    }

    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}
