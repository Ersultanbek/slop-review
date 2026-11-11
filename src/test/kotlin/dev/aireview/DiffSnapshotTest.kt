package dev.aireview

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffVcsDataKeys
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.simple.SimpleDiffTool
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.vcsUtil.VcsUtil
import dev.aireview.editor.AiReviewDiffExtension
import dev.aireview.editor.NewCommentInlayModel
import dev.aireview.editor.ReviewUiController
import dev.aireview.editor.ThreadInlayModel
import dev.aireview.store.ReviewStore
import java.awt.Component
import java.awt.Container
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Настоящий diff-просмотрщик платформы: наводим мышь на гаттер, кликаем «+», пишем комментарий,
 * получаем ответ агента — и на каждом шаге сохраняем снимок в build/ui-snapshots.
 */
class DiffSnapshotTest : BasePlatformTestCase() {

    private val oldText = "class Calc {\n    fun total(items: List<Int>): Int {\n        return items.sum()\n    }\n}\n"
    private val newText = "class Calc {\n    fun total(items: List<Int>): Int {\n        val limit = 2\n        return items.take(limit).sum()\n    }\n\n    fun reset() = Unit\n}\n"

    override fun setUp() {
        RealIcons.activate()
        super.setUp()
    }

    fun testGutterPlusAndThreadInRealDiffViewer() {
        val base = Path.of(project.basePath!!)
        Files.createDirectories(base)
        val path = base.resolve("Calc.kt")
        Files.writeString(path, newText)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val store = ReviewStore.getInstance(project)
        store.deleteAll()

        val left = DiffContentFactory.getInstance().create(project, oldText, file.fileType)
        left.putUserData(DiffVcsDataKeys.REVISION_INFO, Pair.create(VcsUtil.getFilePath(file), Revision("abc123")))
        val right = DiffContentFactory.getInstance().create(project, file)
        val request = SimpleDiffRequest("Calc.kt", left, right, "HEAD", "Your version")
        val context = Context(project)
        val viewer = SimpleDiffTool.INSTANCE.createComponent(context, request) as SimpleDiffViewer
        Disposer.register(testRootDisposable, viewer)
        AiReviewDiffExtension().onViewerCreated(viewer, context, request)
        viewer.init()

        val editor = viewer.editor2
        await("model") { editor.getUserData(ReviewUiController.MODEL_KEY) != null }
        val model = editor.getUserData(ReviewUiController.MODEL_KEY)!!
        layout(viewer)

        // --- наведение на гаттер: должен появиться «+» ---
        val gutter = editor.gutterComponentEx
        val x = gutter.lineMarkerAreaOffset + 6
        val y = editor.logicalPositionToXY(LogicalPosition(2, 0)).y + editor.lineHeight / 2
        mouse(gutter, MouseEvent.MOUSE_MOVED, x, y)
        snapshot(viewer, "diff-1-hover")

        // --- клик по «+» открывает поле ввода под строкой ---
        mouse(gutter, MouseEvent.MOUSE_PRESSED, x, y)
        mouse(gutter, MouseEvent.MOUSE_RELEASED, x, y)
        mouse(gutter, MouseEvent.MOUSE_CLICKED, x, y)
        await("click on + opens a new comment") { model.inlays.value.any { it is NewCommentInlayModel } }
        val draft = model.inlays.value.filterIsInstance<NewCommentInlayModel>().single()
        assertEquals(2, draft.startLine)
        await("draft inlay") { blockInlays(editor) == 1 }
        snapshot(viewer, "diff-2-new-comment")

        // --- комментарий и ответ агента ---
        draft.submit("Почему лимит зашит в код? Вынеси в параметр.")
        await("thread") { model.inlays.value.singleOrNull() is ThreadInlayModel }
        val reviewFile = store.reviewFile!!
        Files.writeString(
            reviewFile,
            Files.readString(reviewFile).replace(
                "Вынеси в параметр.\"\n        }",
                "Вынеси в параметр.\"\n        },\n        { \"id\": \"r1\", \"author\": \"ai\", \"name\": \"Claude\", \"createdAt\": \"${java.time.Instant.now()}\", " +
                    "\"text\": \"Вынес `limit` в параметр функции со значением по умолчанию:\\n```kotlin\\nfun total(items: List<Int>, limit: Int = 2): Int\\n```\" }"
            )
        )
        await("agent reply") { model.inlays.value.filterIsInstance<ThreadInlayModel>().single().thread.value.comments.size == 2 }
        mouse(gutter, MouseEvent.MOUSE_EXITED, x, y)
        snapshot(viewer, "diff-3-thread")
        store.deleteAll()
    }

    private fun mouse(component: Component, id: Int, x: Int, y: Int) {
        val clicks = if (id == MouseEvent.MOUSE_MOVED || id == MouseEvent.MOUSE_EXITED) 0 else 1
        val button = if (clicks == 0) MouseEvent.NOBUTTON else MouseEvent.BUTTON1
        component.dispatchEvent(MouseEvent(component, id, System.currentTimeMillis(), 0, x, y, clicks, false, button))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun layout(viewer: SimpleDiffViewer) {
        repeat(4) {
            viewer.component.setSize(WIDTH, HEIGHT)
            layoutDeep(viewer.component)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
    }

    private fun snapshot(viewer: SimpleDiffViewer, name: String) {
        layout(viewer)
        // Рисуем правую сторону diff: разделитель между сторонами требует настоящего экрана
        val target = viewer.editor2.component
        val image = BufferedImage(target.width * 2, target.height * 2, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.scale(2.0, 2.0)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        target.paint(g)
        g.dispose()
        ImageIO.write(image, "png", File(File("build/ui-snapshots").apply { mkdirs() }, "$name.png"))
    }

    private fun layoutDeep(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layoutDeep)
        }
    }

    private fun blockInlays(editor: EditorEx): Int =
        editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).size

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for: $what")
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
    }

    private class Revision(private val id: String) : VcsRevisionNumber {
        override fun asString(): String = id
        override fun compareTo(other: VcsRevisionNumber): Int = id.compareTo(other.asString())
    }

    private class Context(private val project: Project) : DiffContext() {
        override fun getProject(): Project = project
        override fun isWindowFocused(): Boolean = true
        override fun isFocusedInWindow(): Boolean = true
        override fun requestFocusInWindow() = Unit
    }

    companion object {
        private const val WIDTH = 1500
        private const val HEIGHT = 420
    }
}
