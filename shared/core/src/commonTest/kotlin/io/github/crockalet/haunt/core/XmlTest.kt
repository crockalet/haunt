package io.github.crockalet.haunt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XmlTest {
    @Test
    fun parsesElementsAttributesAndText() {
        val doc = Xml.parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE note [ <!ENTITY x "y"> ]>
            <!-- a comment with <tags> -->
            <ns:root xmlns:ns="urn:x" a="1" b='two' c=bare ns:d="4">
              <item>Fish &amp; chips &lt;3 &#65;&#x42; &#x1F600;</item>
              <item><![CDATA[<raw> & stuff]]></item>
              <empty/>
              <ns:inner><deep>t</deep></ns:inner>
            </ns:root>
            """.trimIndent(),
        )
        val root = doc.elements.single()
        assertEquals("root", root.name)
        assertEquals("ns", root.prefix)
        assertEquals(mapOf("a" to "1", "b" to "two", "c" to "bare", "d" to "4"), root.attributes)
        val items = root.children("item")
        assertEquals("Fish & chips <3 AB 😀", items[0].text)
        assertEquals("<raw> & stuff", items[1].text)
        assertEquals("", root.child("empty")!!.text)
        assertEquals("t", root.child("inner")!!.childText("deep"))
        assertEquals(1, root.descendants("deep").size)
        assertNull(root.child("missing"))
    }

    @Test
    fun forgivingOfMalformedInput() {
        val doc = Xml.parse("﻿<a><b>one</c></b><b>two &bogus; a < b<b>three")
        val a = doc.elements.single()
        assertEquals(2, a.children("b").size)
        assertEquals("one", a.children("b")[0].text)
        val second = a.children("b")[1]
        assertEquals("two &bogus; a < bthree", second.text)
    }

    @Test
    fun escapeRoundTrip() {
        val s = "a<b>&\"c'"
        assertEquals(s, Xml.decodeEntities(Xml.escape(s)))
    }
}
