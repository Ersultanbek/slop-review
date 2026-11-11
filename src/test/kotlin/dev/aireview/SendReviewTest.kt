package dev.aireview

import com.intellij.diff.DiffExtension
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.aireview.editor.AiReviewDiffExtension
import dev.aireview.model.ReviewSide
import dev.aireview.send.ReviewSender
import dev.aireview.store.ReviewStore
import java.awt.datatransfer.DataFlavor
import java.nio.file.Files

class SendReviewTest : BasePlatformTestCase() {

    fun testPluginXmlRegistersExtensionsAndActions() {
        assertNotNull(ActionManager.getInstance().getAction("AiReview.Send"))
        assertNotNull(ActionManager.getInstance().getAction("AiReview.AddComment"))
        assertTrue(DiffExtension.EP_NAME.extensionList.any { it is AiReviewDiffExtension })
        // Переключатель показа тредов лежит в меню гаттера — там же, где «Annotate with Git Blame»
        val toggle = ActionManager.getInstance().getAction("AiReview.ToggleEditorComments")
        val gutterMenu = ActionManager.getInstance().getAction("EditorGutterPopupMenu") as com.intellij.openapi.actionSystem.DefaultActionGroup
        assertTrue(gutterMenu.getChildActionsOrStubs().any { it === toggle || ActionManager.getInstance().getId(it) == "AiReview.ToggleEditorComments" })
    }

    fun testSendInstallsClaudeCommandAndCopiesIt() {
        val store = ReviewStore.getInstance(project)
        store.deleteAll()
        store.addThread(project.basePath + "/Foo.kt", ReviewSide.NEW, 0, 0, "val x = 2", null, "почему 2?")
        val claudeHome = Files.createTempDirectory("claude-home")

        ReviewSender.send(project, claudeHome)

        val command = claudeHome.resolve("commands/ai-review.md")
        assertTrue(Files.exists(command))
        val text = Files.readString(command)
        assertTrue(text.startsWith("---\ndescription:"))
        assertTrue(text.contains(".ai-review/review.json"))
        assertEquals("/ai-review", clipboard())
        store.deleteAll()
    }

    fun testWithoutClaudeCodeTheFullPromptIsCopied() {
        val store = ReviewStore.getInstance(project)
        store.deleteAll()
        store.addThread(project.basePath + "/Foo.kt", ReviewSide.NEW, 0, 0, "val x = 2", null, "почему 2?")
        val missing = Files.createTempDirectory("no-claude").resolve("absent")

        ReviewSender.send(project, missing)

        assertTrue(clipboard().contains(".ai-review/review.json"))
        assertFalse(Files.exists(missing))
        store.deleteAll()
    }

    private fun clipboard(): String =
        CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor).orEmpty()
}
