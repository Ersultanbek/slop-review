package dev.aireview

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffVcsDataKeys
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.simple.SimpleDiffTool
import com.intellij.diff.tools.simple.SimpleDiffViewer
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
import dev.aireview.editor.ReviewEditorModel
import dev.aireview.editor.ReviewUiController
import dev.aireview.editor.ReviewViewSettings
import dev.aireview.editor.ThreadInlayModel
import dev.aireview.model.ReviewJson
import dev.aireview.model.ReviewSide
import dev.aireview.model.ThreadState
import dev.aireview.store.ReviewStore
import java.nio.file.Files
import java.nio.file.Path

/** Сквозная проверка: комментарий в diff → тред под кодом → файл ревью → ответ агента → обновление треда. */
class DiffReviewTest : BasePlatformTestCase() {

    private val oldText = "fun a() {\n    return 1\n}\n"
    private val newText = "fun a() {\n    val x = 2\n    return x\n}\n"

    fun testCommentThreadLifecycleInDiff() {
        val base = Path.of(project.basePath!!)
        Files.createDirectories(base)
        val path = base.resolve("Foo.kt")
        Files.writeString(path, newText)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val store = ReviewStore.getInstance(project)
        store.deleteAll()

        val left = DiffContentFactory.getInstance().create(project, oldText)
        left.putUserData(DiffVcsDataKeys.REVISION_INFO, Pair.create(VcsUtil.getFilePath(file), TestRevision("abc123")))
        val right = DiffContentFactory.getInstance().create(project, file)
        val request = SimpleDiffRequest("Foo.kt", left, right, "HEAD", "Local")
        val context = TestDiffContext(project)

        val viewer = SimpleDiffTool.INSTANCE.createComponent(context, request) as SimpleDiffViewer
        Disposer.register(testRootDisposable, viewer)
        AiReviewDiffExtension().onViewerCreated(viewer, context, request)
        viewer.init()

        // --- новый комментарий на правой стороне (рабочий файл) ---
        val rightModel = awaitModel(viewer.editor2)
        rightModel.requestNewComment(1)
        awaitTrue("new comment inlay") { blockInlays(viewer.editor2) == 1 }
        val draft = rightModel.inlays.value.filterIsInstance<NewCommentInlayModel>().single()
        draft.submit("почему 2?")

        awaitTrue("thread inlay") {
            rightModel.inlays.value.singleOrNull() is ThreadInlayModel && blockInlays(viewer.editor2) == 1
        }
        val thread = store.threads.value.single()
        assertEquals("Foo.kt", thread.file)
        assertEquals(ReviewSide.NEW, thread.side)
        assertEquals(2, thread.startLine)
        assertEquals("    val x = 2", thread.snippet)
        assertNull(thread.revision)
        assertEquals(ThreadState.WAITING_FOR_AI, thread.state)
        assertTrue(Files.exists(store.reviewFile!!))

        // --- комментарий на левой стороне (удалённый код ревизии) ---
        val leftModel = awaitModel(viewer.editor1)
        leftModel.requestNewComment(1)
        awaitTrue("left draft") { leftModel.inlays.value.any { it is NewCommentInlayModel } }
        leftModel.inlays.value.filterIsInstance<NewCommentInlayModel>().single().submit("зачем убрал?")
        awaitTrue("left thread") { store.threads.value.size == 2 }
        val oldThread = store.threads.value.first { it.side == ReviewSide.OLD }
        assertEquals("abc123", oldThread.revision)
        assertEquals("    return 1", oldThread.snippet)
        awaitTrue("one thread inlay on each side") { blockInlays(viewer.editor1) == 1 && blockInlays(viewer.editor2) == 1 }

        // --- агент отвечает, дописывая файл ревью на диске ---
        val file2 = store.reviewFile!!
        val fromDisk = ReviewJson.parse(Files.readString(file2))
        val json = Files.readString(file2).replace(
            "\"text\": \"почему 2?\"\n        }",
            "\"text\": \"почему 2?\"\n        },\n        { \"id\": \"r1\", \"author\": \"ai\", \"name\": \"Claude\", \"createdAt\": \"2026-11-11T10:01:00Z\", \"text\": \"Опечатка, исправил на 1\" }"
        )
        assertEquals(2, fromDisk.size)
        Files.writeString(file2, json)
        awaitTrue("agent reply is picked up") {
            store.threads.value.first { it.id == thread.id }.state == ThreadState.AI_REPLIED
        }
        val threadModel = rightModel.inlays.value.filterIsInstance<ThreadInlayModel>().single()
        awaitTrue("thread model updated") { threadModel.thread.value.comments.size == 2 }

        // --- код над тредом сдвигается: тред едет вместе с кодом ---
        val document = viewer.editor2.document
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(0, "// header\n// more\n")
        }
        awaitTrue("thread follows the code") { threadModel.line.value == 3 }
        store.reply(thread.id, "ок")
        assertEquals(4, store.threads.value.first { it.id == thread.id }.startLine)

        // --- тот же тред виден в обычном редакторе файла ---
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            .openTextEditor(com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file), false) as EditorEx
        // Тестовый менеджер редакторов создаёт редактор без типа MAIN_EDITOR, поэтому подключаемся напрямую
        ReviewUiController.getInstance(project).attachToEditor(editor, file)
        val editorModel = awaitModel(editor)
        awaitTrue("thread in plain editor") {
            editorModel.inlays.value.filterIsInstance<ThreadInlayModel>().singleOrNull()?.line?.value == 3 && blockInlays(editor) == 1
        }
        assertFalse("no + on hover in a plain editor", editorModel.gutterControlsState.value!!.isLineCommentable(0))
        assertTrue(rightModel.gutterControlsState.value!!.isLineCommentable(0))

        // --- переключатель прячет треды в обычном редакторе, но не в diff; значок в гаттере остаётся ---
        val settings = ReviewViewSettings.getInstance(project)
        val editorThread = editorModel.inlays.value.filterIsInstance<ThreadInlayModel>().single()
        settings.setShowInEditor(false)
        awaitTrue("thread hidden in plain editor") { !editorThread.isVisible.value && blockInlays(editor) == 0 }
        assertTrue(threadModel.isVisible.value)
        assertEquals(setOf(3), editorModel.gutterControlsState.value!!.linesWithComments)
        editorModel.toggleComments(3) // клик по значку в гаттере открывает тред этой строки
        awaitTrue("thread opened from the gutter") { editorThread.isVisible.value && blockInlays(editor) == 1 }
        settings.setShowInEditor(true)
        assertTrue(editorThread.isVisible.value)

        // --- resolve сворачивает тред ---
        store.setResolved(thread.id, true)
        awaitTrue("collapsed") { threadModel.collapsed.value }
    }

    private fun awaitModel(editor: EditorEx): ReviewEditorModel {
        awaitTrue("review model in editor") { editor.getUserData(ReviewUiController.MODEL_KEY) != null }
        return editor.getUserData(ReviewUiController.MODEL_KEY)!!
    }

    private fun blockInlays(editor: EditorEx): Int =
        editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).size

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for: $what")
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
    }

    private class TestRevision(private val id: String) : VcsRevisionNumber {
        override fun asString(): String = id
        override fun compareTo(other: VcsRevisionNumber): Int = id.compareTo(other.asString())
    }

    private class TestDiffContext(private val project: Project) : DiffContext() {
        override fun getProject(): Project = project
        override fun isWindowFocused(): Boolean = true
        override fun isFocusedInWindow(): Boolean = true
        override fun requestFocusInWindow() = Unit
    }
}
