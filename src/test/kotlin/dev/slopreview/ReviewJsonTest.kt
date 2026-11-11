package dev.slopreview

import dev.slopreview.model.Author
import dev.slopreview.model.ReviewComment
import dev.slopreview.model.ReviewJson
import dev.slopreview.model.ReviewSide
import dev.slopreview.model.ReviewThread
import dev.slopreview.model.ThreadState
import dev.slopreview.ui.MiniMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewJsonTest {

    private val thread = ReviewThread(
        id = "t1", file = "src/Foo.kt", side = ReviewSide.NEW, startLine = 3, endLine = 4,
        snippet = "val a = \"<b>\"\nreturn a", revision = null, resolved = false,
        comments = listOf(ReviewComment("c1", Author.USER, null, "почему так?", "2026-11-11T10:00:00Z"))
    )

    @Test
    fun `round trip keeps everything`() {
        assertEquals(listOf(thread), ReviewJson.parse(ReviewJson.serialize(listOf(thread))))
    }

    @Test
    fun `reply appended by the agent is read`() {
        val json = ReviewJson.serialize(listOf(thread)).replace(
            "\"text\": \"почему так?\"\n        }",
            "\"text\": \"почему так?\"\n        },\n        { \"id\": \"x\", \"author\": \"ai\", \"name\": \"Claude\", \"createdAt\": \"2026-11-11T10:01:00Z\", \"text\": \"Исправил\" }"
        )
        val parsed = ReviewJson.parse(json).single()
        assertEquals(2, parsed.comments.size)
        assertEquals(Author.AI, parsed.comments[1].author)
        assertEquals("Claude", parsed.comments[1].name)
        assertEquals(ThreadState.AI_REPLIED, parsed.state)
    }

    @Test
    fun `tolerates missing optional fields`() {
        val parsed = ReviewJson.parse("""{"threads":[{"id":"t","file":"a.kt","comments":[{"text":"hi"}]}]}""").single()
        assertEquals(ReviewSide.NEW, parsed.side)
        assertEquals(1, parsed.startLine)
        assertEquals(1, parsed.endLine)
        assertEquals(Author.AI, parsed.comments.single().author)
        assertEquals(false, parsed.resolved)
    }

    @Test
    fun `empty or foreign json gives no threads`() {
        assertTrue(ReviewJson.parse("{}").isEmpty())
    }

    @Test
    fun `markdown keeps line breaks and escapes html`() {
        assertEquals("<div>a &lt;b&gt;<br>&bull;&nbsp;<code>x</code> <b>y</b></div>", MiniMarkdown.toHtml("a <b>\n- `x` **y**"))
        assertEquals("<div>see</div><div class=\"code\">val a = 1 &lt; 2<br>&nbsp;&nbsp;b</div><div>ok</div>", MiniMarkdown.toHtml("see\n```kotlin\nval a = 1 < 2\n  b\n```\nok"))
    }
}
