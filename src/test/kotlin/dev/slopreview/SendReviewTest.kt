package dev.slopreview

import com.intellij.diff.DiffExtension
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.slopreview.editor.SlopReviewDiffExtension
import dev.slopreview.model.ReviewSide
import dev.slopreview.send.ReviewSender
import dev.slopreview.store.ReviewStore
import java.awt.datatransfer.DataFlavor
import java.nio.file.Files

class SendReviewTest : BasePlatformTestCase() {

    fun testPluginXmlRegistersExtensionsAndActions() {
        assertNotNull(ActionManager.getInstance().getAction("SlopReview.Send"))
        assertNotNull(ActionManager.getInstance().getAction("SlopReview.AddComment"))
        assertTrue(DiffExtension.EP_NAME.extensionList.any { it is SlopReviewDiffExtension })
        // Переключатель показа тредов лежит в меню гаттера — там же, где «Annotate with Git Blame»
        val toggle = ActionManager.getInstance().getAction("SlopReview.ToggleEditorComments")
        val gutterMenu = ActionManager.getInstance().getAction("EditorGutterPopupMenu") as com.intellij.openapi.actionSystem.DefaultActionGroup
        assertTrue(gutterMenu.getChildActionsOrStubs().any { it === toggle || ActionManager.getInstance().getId(it) == "SlopReview.ToggleEditorComments" })
    }

    fun testSendInstallsClaudeCommandAndCopiesIt() {
        val store = ReviewStore.getInstance(project)
        store.deleteAll()
        store.addThread(project.basePath + "/Foo.kt", ReviewSide.NEW, 0, 0, "val x = 2", null, "почему 2?")
        val claudeHome = Files.createTempDirectory("claude-home")

        ReviewSender.send(project, claudeHome)

        val command = claudeHome.resolve("commands/slop-review.md")
        assertTrue(Files.exists(command))
        val text = Files.readString(command)
        assertTrue(text.startsWith("---\ndescription:"))
        assertTrue(text.contains(".slop-review/review.json"))
        // Команда появилась только что — запущенная сессия может её ещё не знать, поэтому первый раз просим словами
        assertTrue(clipboard().startsWith("Address the code review comments in .slop-review/review.json"))
        ReviewSender.send(project, claudeHome)
        assertEquals("/slop-review", clipboard())
        store.deleteAll()
    }

    fun testWithoutClaudeCodeTheFullPromptIsCopied() {
        val store = ReviewStore.getInstance(project)
        store.deleteAll()
        store.addThread(project.basePath + "/Foo.kt", ReviewSide.NEW, 0, 0, "val x = 2", null, "почему 2?")
        val missing = Files.createTempDirectory("no-claude").resolve("absent")

        ReviewSender.send(project, missing)

        assertTrue(clipboard().contains(".slop-review/review.json"))
        assertFalse(Files.exists(missing))
        store.deleteAll()
    }

    private fun clipboard(): String =
        CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor).orEmpty()
}
