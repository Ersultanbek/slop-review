package dev.aireview

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.aireview.editor.NewCommentInlayModel
import dev.aireview.editor.ReviewInlayModel
import dev.aireview.editor.ThreadInlayModel
import dev.aireview.model.Author
import dev.aireview.model.ReviewComment
import dev.aireview.model.ReviewSide
import dev.aireview.model.ReviewThread
import dev.aireview.ui.ReviewComponents.createRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.Component
import java.awt.Container
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Рисует компоненты ревью в PNG (build/ui-snapshots) — чтобы глазами проверить вёрстку без запуска IDE. */
class UiSnapshotTest : BasePlatformTestCase() {

    private val longReply = "Согласен, это опечатка. Исправил на `1` и добавил проверку границ, " +
        "чтобы значение не выходило за пределы допустимого диапазона при повторном вызове:\n" +
        "```kotlin\nval x = 1\nrequire(x in 0..limit) { \"x out of range\" }\n```\nТест `FooTest` обновил."

    override fun setUp() {
        RealIcons.activate()
        super.setUp()
    }

    fun testRenderSnapshots() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val open = thread(resolved = false, withReply = true)
            snapshot(scope, "thread-ai-replied", ThreadInlayModel(open, 0, outdated = true), 760)
            snapshot(scope, "thread-waiting", ThreadInlayModel(thread(resolved = false, withReply = false), 0, outdated = false), 760)
            snapshot(scope, "thread-resolved-collapsed", ThreadInlayModel(thread(resolved = true, withReply = true), 0, outdated = false), 760)
            snapshot(scope, "thread-narrow", ThreadInlayModel(open, 0, outdated = false), 380)
            snapshot(scope, "thread-waiting-narrow", ThreadInlayModel(thread(resolved = false, withReply = false), 0, outdated = false), 380)
            snapshot(scope, "new-comment", NewCommentInlayModel("k", ReviewSide.NEW, 11, 14, 0, { _, _ -> }, {}), 760)
        } finally {
            scope.cancel()
        }
    }

    fun testSendToAiLinkIsShownOnlyWhileThreadWaitsForAi() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            fun sendLink(thread: ReviewThread) =
                UIUtil.findComponentsOfType(with(scope) { createRenderer(project, ThreadInlayModel(thread, 0, outdated = false)) }.component,
                    com.intellij.ui.components.ActionLink::class.java).single { it.text == "Send to AI" }

            assertTrue(sendLink(thread(resolved = false, withReply = false)).isVisible)
            assertFalse(sendLink(thread(resolved = false, withReply = true)).isVisible)
            assertFalse(sendLink(thread(resolved = true, withReply = false)).isVisible)
        } finally {
            scope.cancel()
        }
    }

    fun testRenderToolWindow() {
        val store = dev.aireview.store.ReviewStore.getInstance(project)
        store.deleteAll()
        val base = project.basePath
        store.addThread("$base/src/main/kotlin/Foo.kt", ReviewSide.NEW, 11, 14, "val x = 2", null, "Почему здесь 2? По ТЗ должно быть 1.")
        store.addThread("$base/src/main/kotlin/Foo.kt", ReviewSide.NEW, 40, 40, "return x", null, "Добавь проверку границ")
        store.addThread("$base/src/main/kotlin/service/BarService.kt", ReviewSide.OLD, 7, 7, "log.info()", "abc123", "Зачем убрал логирование?")
        store.setResolved(store.threads.value[1].id, true)

        val panel = dev.aireview.toolwindow.AiReviewPanel(project, testRootDisposable)
        val width = 420
        val height = 260
        repeat(3) {
            panel.setSize(width, height)
            layoutDeep(panel)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
        val image = BufferedImage(width * 2, height * 2, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.scale(2.0, 2.0)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        panel.paint(g)
        g.dispose()
        ImageIO.write(image, "png", File(File("build/ui-snapshots").apply { mkdirs() }, "tool-window.png"))
        store.deleteAll()
    }

    private fun thread(resolved: Boolean, withReply: Boolean) = ReviewThread(
        id = "t1", file = "src/Foo.kt", side = ReviewSide.NEW, startLine = 2, endLine = 2, snippet = "val x = 2",
        revision = null, resolved = resolved,
        comments = listOfNotNull(
            ReviewComment("c1", Author.USER, null, "Почему здесь 2? По ТЗ должно быть 1.\nИ добавь проверку границ.", "2026-11-11T10:00:00Z"),
            ReviewComment("c2", Author.AI, "Claude", longReply, "2026-11-11T10:02:00Z").takeIf { withReply }
        )
    )

    private fun snapshot(scope: CoroutineScope, name: String, model: ReviewInlayModel, width: Int) {
        val component = with(scope) { createRenderer(project, model) }.component
        var height = 10
        repeat(3) {
            component.setSize(width, height)
            layoutDeep(component)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            height = component.preferredSize.height
        }
        component.setSize(width, height)
        layoutDeep(component)

        val scale = 2
        val image = BufferedImage(width * scale, (height + 24) * scale, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.scale(scale.toDouble(), scale.toDouble())
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = UIUtil.getPanelBackground()
        g.fillRect(0, 0, width, height + 24)
        g.translate(0, 12)
        component.paint(g)
        g.dispose()

        val dir = File("build/ui-snapshots").apply { mkdirs() }
        ImageIO.write(image, "png", File(dir, "$name.png"))
        println("SNAPSHOT $name ${width}x$height scale=${JBUI.scale(1)}")
        assertTrue("$name must have a sensible height", height in 30..900)
    }

    private fun layoutDeep(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layoutDeep)
        }
    }
}
