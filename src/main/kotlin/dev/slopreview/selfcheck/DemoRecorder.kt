package dev.slopreview.selfcheck

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import dev.slopreview.editor.NewCommentInlayModel
import dev.slopreview.editor.ReviewUiController
import dev.slopreview.editor.ReviewViewSettings
import dev.slopreview.editor.ThreadInlayModel
import dev.slopreview.model.Author
import dev.slopreview.send.AgentSessions
import dev.slopreview.send.TerminalBridge
import dev.slopreview.store.ReviewStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.Window
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

/**
 * Запись демо для README (включается свойством `-Dslopreview.demo=<папка>`, см. build.gradle.kts).
 * Проходит сценарий в живой IDE с настоящим Claude Code во встроенном терминале и складывает кадры
 * по сценам: `<папка>/<сцена>/<номер>-<длительность, мс>.png`. Гифки из кадров собирает scripts/make-gifs.py.
 */
object DemoRecorder {

    private const val PROPERTY = "slopreview.demo"
    private const val FILE = "PriceCalculator.kt"
    private const val COMMENT = "Why is the discount hardcoded? Make it a parameter, default 0."
    private const val FOLLOW_UP = "Also check that the discount is between 0 and 1."

    fun isEnabled(): Boolean = System.getProperty(PROPERTY) != null

    private lateinit var out: Path
    private lateinit var project: Project
    private var scene = "0"
    private var counter = 0

    suspend fun run(project: Project) {
        this.project = project
        out = Path.of(System.getProperty(PROPERTY))
        Files.createDirectories(out)
        val log = out.resolve("result.txt")
        Files.writeString(log, "")
        fun note(text: String) {
            Files.writeString(log, Files.readString(log) + text + "\n")
        }

        try {
            delay(6000)
            val base = project.basePath!!
            val store = ReviewStore.getInstance(project)
            val sessions = AgentSessions.getInstance(project)
            val settings = ReviewViewSettings.getInstance(project)
            store.deleteAll()
            withContext(Dispatchers.EDT) {
                settings.setShowInEditor(true)
                ProjectLevelVcsManager.getInstance(project).directoryMappings = listOf(VcsDirectoryMapping(base, "Git"))
                // Claude Code стартует заранее, чтобы к моменту отправки уже ждал ввода
                TerminalBridge.openTab(project, base, "claude", "env -u CLAUDE_CODE_CHILD_SESSION claude")
            }
            val change = poll(90) {
                VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
                ChangeListManager.getInstance(project).allChanges.firstOrNull { it.virtualFile?.name == FILE }
            } ?: return note("FAIL no change")
            delay(10000)
            val target = withContext(Dispatchers.EDT) { sessions.available().firstOrNull { it.tabName == "claude" } }
                ?: return note("FAIL no terminal tab")
            // Новая папка: Claude Code спрашивает, доверять ли ей (по умолчанию выбран отказ) — стрелка вниз и Enter
            withContext(Dispatchers.EDT) { TerminalBridge.send(project, target.id, "\u001b[B") }
            delay(8000)
            withContext(Dispatchers.EDT) {
                // Дерево проекта в демо не нужно — отдаём левую колонку панели ревью
                ToolWindowManager.getInstance(project).getToolWindow("Project")?.hide()
                ToolWindowManager.getInstance(project).getToolWindow("Slop Review")?.activate(null)
                ShowDiffAction.showDiffForChange(project, listOf(change))
            }
            val editor = poll(40) {
                withContext(Dispatchers.EDT) {
                    EditorFactory.getInstance().allEditors.filterIsInstance<EditorEx>().firstOrNull {
                        it.editorKind == EditorKind.DIFF && it.getUserData(ReviewUiController.MODEL_KEY) != null &&
                            FileDocumentManager.getInstance().getFile(it.document)?.isInLocalFileSystem == true
                    }
                }
            } ?: return note("FAIL no diff editor")
            val model = editor.getUserData(ReviewUiController.MODEL_KEY)!!
            delay(3000)

            // ---------- сцена 1: комментарий в diff ----------
            scene("1-comment")
            frame(1200)
            val gutter = editor.gutterComponentEx
            val line = 2
            val (x, y) = withContext(Dispatchers.EDT) {
                (gutter.lineMarkerAreaOffset + 6) to (editor.logicalPositionToXY(LogicalPosition(line, 0)).y + editor.lineHeight / 2)
            }
            withContext(Dispatchers.EDT) { mouse(gutter, MouseEvent.MOUSE_MOVED, x, y) }
            delay(500)
            frame(1000)
            withContext(Dispatchers.EDT) {
                mouse(gutter, MouseEvent.MOUSE_PRESSED, x, y)
                mouse(gutter, MouseEvent.MOUSE_RELEASED, x, y)
                mouse(gutter, MouseEvent.MOUSE_CLICKED, x, y)
            }
            val draft = poll(10) { model.inlays.value.filterIsInstance<NewCommentInlayModel>().firstOrNull() }
                ?: return note("FAIL no comment field")
            delay(800)
            frame(800)
            val input = poll(10) {
                withContext(Dispatchers.EDT) { UIUtil.findComponentsOfType(editor.contentComponent, JBTextArea::class.java).firstOrNull { it.isShowing } }
            } ?: return note("FAIL no text area")
            for (end in 3..COMMENT.length step 3) {
                withContext(Dispatchers.EDT) { input.text = COMMENT.substring(0, end) }
                delay(60)
                frame(70)
            }
            withContext(Dispatchers.EDT) { input.text = COMMENT }
            delay(200)
            frame(900)
            withContext(Dispatchers.EDT) {
                mouse(gutter, MouseEvent.MOUSE_EXITED, x, y)
                draft.submit(COMMENT)
            }
            val threadModel = poll(10) { model.inlays.value.filterIsInstance<ThreadInlayModel>().firstOrNull() }
                ?: return note("FAIL no thread")
            delay(1200)
            frame(2500)
            note("OK scene 1")

            // ---------- сцена 2: привязка сессии и отправка ----------
            scene("2-send")
            frame(1200)
            withContext(Dispatchers.EDT) { link("Attach Session")?.doClick() }
            delay(1200)
            frame(1500)
            withContext(Dispatchers.EDT) {
                closePopups()
                sessions.attach(target)
            }
            delay(1500)
            frame(1500)
            val threadId = threadModel.thread.value.id
            suspend fun aiReplies() = store.threads.value.firstOrNull { it.id == threadId }?.comments?.count { it.author == Author.AI } ?: 0
            suspend fun sendAndWait(expectedReplies: Int): Boolean {
                val send = poll(10) { withContext(Dispatchers.EDT) { link("Send to AI") } } ?: return false
                withContext(Dispatchers.EDT) { send.doClick() }
                delay(700)
                frame(900)
                // Пока агент работает, снимаем раз в 1,5 секунды; одинаковые кадры потом склеиваются
                var done = false
                repeat(120) {
                    if (done) return@repeat
                    delay(1500)
                    frame(350)
                    if (aiReplies() >= expectedReplies) done = true
                }
                delay(4000)
                frame(3000)
                return done
            }
            note(if (sendAndWait(1)) "OK scene 2" else "FAIL scene 2: no AI reply")

            // ---------- сцена 3: продолжение дискуссии в той же сессии ----------
            scene("3-discussion")
            frame(1200)
            val reply = poll(5) { withContext(Dispatchers.EDT) { link("Reply") } }
            withContext(Dispatchers.EDT) { reply?.doClick() }
            delay(600)
            val replyInput = poll(5) {
                withContext(Dispatchers.EDT) { UIUtil.findComponentsOfType(editor.contentComponent, JBTextArea::class.java).firstOrNull { it.isShowing } }
            }
            if (replyInput != null) {
                frame(700)
                for (end in 3..FOLLOW_UP.length step 3) {
                    withContext(Dispatchers.EDT) { replyInput.text = FOLLOW_UP.substring(0, end) }
                    delay(60)
                    frame(70)
                }
                withContext(Dispatchers.EDT) { replyInput.text = FOLLOW_UP }
                delay(200)
                frame(900)
            }
            // Настоящая кнопка «Reply» под полем ввода: она же возвращает ссылки треда
            val submit = withContext(Dispatchers.EDT) {
                UIUtil.findComponentsOfType(editor.contentComponent, javax.swing.JButton::class.java).firstOrNull { it.isShowing && it.text == "Reply" }
            }
            withContext(Dispatchers.EDT) { if (submit != null) submit.doClick() else store.reply(threadId, FOLLOW_UP) }
            delay(1200)
            frame(1500)
            note(if (sendAndWait(2)) "OK scene 3" else "FAIL scene 3: no AI reply")

            // ---------- сцена 4: обычный редактор, треды прячутся как Git Blame ----------
            scene("4-hide")
            val plain = withContext(Dispatchers.EDT) {
                FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, change.virtualFile!!, 0, 0), true) as? EditorEx
            } ?: return note("FAIL no plain editor")
            val plainModel = poll(10) { plain.getUserData(ReviewUiController.MODEL_KEY) } ?: return note("FAIL no plain model")
            poll(10) { plainModel.inlays.value.filterIsInstance<ThreadInlayModel>().firstOrNull() }
            delay(2000)
            frame(2000)
            withContext(Dispatchers.EDT) { settings.setShowInEditor(false) }
            delay(1200)
            frame(2000)
            val threadLine = plainModel.inlays.value.filterIsInstance<ThreadInlayModel>().firstOrNull()?.line?.value
            if (threadLine != null) {
                withContext(Dispatchers.EDT) { plainModel.toggleComments(threadLine) }
                delay(1200)
                frame(2000)
                withContext(Dispatchers.EDT) { plainModel.toggleComments(threadLine) }
                delay(1200)
                frame(1500)
            }
            withContext(Dispatchers.EDT) { settings.setShowInEditor(true) }
            delay(1200)
            frame(2000)
            note("OK scene 4")

            // ---------- сцена 5: resolve ----------
            scene("5-resolve")
            frame(1200)
            withContext(Dispatchers.EDT) { link("Resolve")?.doClick() }
            delay(1500)
            frame(2500)
            note("OK scene 5")
        } catch (e: Throwable) {
            val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
            note("FAIL crashed: " + trace.lineSequence().take(12).joinToString(" | "))
        } finally {
            note("finished")
            withContext(Dispatchers.EDT) { ApplicationManager.getApplication().exit(true, true, false) }
        }
    }

    private fun scene(name: String) {
        scene = name
        counter = 0
        Files.createDirectories(out.resolve(name))
    }

    /** Кадр окна IDE вместе с всплывающими окнами; [millis] — сколько его показывать в гифке. */
    private suspend fun frame(millis: Int) = withContext(Dispatchers.EDT) {
        val root = WindowManager.getInstance().getFrame(project)?.rootPane ?: return@withContext
        if (root.width <= 0 || root.height <= 0) return@withContext
        val image = BufferedImage(root.width, root.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        root.paint(g)
        val origin = root.locationOnScreen
        for (window in Window.getWindows()) {
            if (!window.isShowing || window !is RootPaneContainer || SwingUtilities.getWindowAncestor(root) === window) continue
            val location = window.locationOnScreen
            val gg = g.create(location.x - origin.x, location.y - origin.y, window.width, window.height)
            window.rootPane.paint(gg)
            gg.dispose()
        }
        g.dispose()
        val name = "%04d-%d.png".format(counter++, millis)
        ImageIO.write(image, "png", out.resolve(scene).resolve(name).toFile())
    }

    private fun link(text: String): ActionLink? =
        Window.getWindows().asSequence()
            .filterIsInstance<RootPaneContainer>()
            .flatMap { UIUtil.findComponentsOfType(it.rootPane, ActionLink::class.java).asSequence() }
            .firstOrNull { it.text == text && it.isShowing }

    private fun closePopups() {
        val root = WindowManager.getInstance().getFrame(project)?.rootPane ?: return
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().getChildPopups(root).forEach { it.cancel() }
    }

    private suspend fun <T : Any> poll(seconds: Int, probe: suspend () -> T?): T? {
        repeat(seconds * 2) {
            probe()?.let { return it }
            delay(500)
        }
        return null
    }

    private fun mouse(component: Component, id: Int, x: Int, y: Int) {
        val clicks = if (id == MouseEvent.MOUSE_MOVED || id == MouseEvent.MOUSE_EXITED) 0 else 1
        val button = if (clicks == 0) MouseEvent.NOBUTTON else MouseEvent.BUTTON1
        component.dispatchEvent(MouseEvent(component, id, System.currentTimeMillis(), 0, x, y, clicks, false, button))
    }
}
