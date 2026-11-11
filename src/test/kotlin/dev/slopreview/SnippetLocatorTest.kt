package dev.slopreview

import dev.slopreview.anchor.Placement
import dev.slopreview.anchor.SnippetLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnippetLocatorTest {

    private val lines = listOf("fun a() {", "    val x = 1", "    return x", "}", "", "fun b() {", "    val x = 1", "}")

    @Test
    fun `exact position wins`() {
        assertEquals(Placement(1, 2, false), locate("    val x = 1\n    return x", 1, 2))
    }

    @Test
    fun `finds snippet after lines shifted`() {
        assertEquals(Placement(1, 2, false), locate("val x = 1\nreturn x", 4, 5))
    }

    @Test
    fun `picks occurrence nearest to the hint`() {
        assertEquals(Placement(6, 6, false), locate("val x = 1", 7, 7))
        assertEquals(Placement(1, 1, false), locate("val x = 1", 0, 0))
    }

    @Test
    fun `ignores indentation changes`() {
        assertEquals(Placement(2, 2, false), locate("\t\treturn x", 2, 2))
    }

    @Test
    fun `changed code falls back to original lines as outdated`() {
        assertEquals(Placement(1, 2, true), locate("val y = 2\nreturn y", 1, 2))
    }

    @Test
    fun `changed code is not placed without fallback`() {
        assertNull(SnippetLocator.locate(lines, "val y = 2", 1, 1, allowFallback = false))
    }

    @Test
    fun `fallback is clamped to the text`() {
        assertEquals(Placement(7, 7, true), locate("gone", 40, 42))
    }

    @Test
    fun `blank line is matched by position only`() {
        assertEquals(Placement(4, 4, false), locate("", 4, 4))
        assertEquals(Placement(2, 2, true), locate("", 2, 2))
    }

    private fun locate(snippet: String, start: Int, end: Int) =
        SnippetLocator.locate(lines, snippet, start, end, allowFallback = true)
}
