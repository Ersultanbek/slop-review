package dev.slopreview.selfcheck

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.ui.UIUtil
import dev.slopreview.editor.NewCommentInlayModel
import dev.slopreview.editor.ReviewUiController
import dev.slopreview.editor.ThreadInlayModel
import dev.slopreview.send.AgentSessions
import dev.slopreview.send.ReviewSender
import dev.slopreview.send.TerminalBridge
import dev.slopreview.store.ReviewStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.imageio.ImageIO

/**
 * Самопроверка в живой IDE (включается только свойством `-Dslopreview.selfCheck=<папка>`, см. build.gradle.kts).
 * Проходит весь путь пользователя на проекте с незакоммиченным изменением: открывает настоящий diff Local Changes,
 * наводит мышь на гаттер, кликает «+», оставляет комментарий, получает ответ агента, открывает панель
 * и отправляет команду в программу, запущенную во встроенном терминале. Снимки окна и отчёт пишет в папку.
 */
object SelfCheck {

    private const val PROPERTY = "slopreview.selfCheck"

    fun isEnabled(): Boolean = System.getProperty(PROPERTY) != null

    suspend fun run(project: Project) {
        val out = Path.of(System.getProperty(PROPERTY))
        Files.createDirectories(out)
        val report = out.resolve("result.txt")
        Files.writeString(report, "")
        fun step(name: String, ok: Boolean, detail: String = "") {
            Files.writeString(report, Files.readString(report) + (if (ok) "OK   " else "FAIL ") + name + (if (detail.isEmpty()) "" else " — $detail") + "\n")
        }

        try {
            delay(6000)
            val base = project.basePath!!
            val store = ReviewStore.getInstance(project)
            store.deleteAll()
            withContext(Dispatchers.EDT) {
                ProjectLevelVcsManager.getInstance(project).directoryMappings = listOf(VcsDirectoryMapping(base, "Git"))
            }

            // --- настоящий diff незакоммиченного изменения ---
            val change = poll(90) {
                VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
                ChangeListManager.getInstance(project).allChanges.firstOrNull { it.virtualFile?.name == "Calc.kt" }
            }
            step("git change is detected", change != null)
            if (change == null) return
            withContext(Dispatchers.EDT) { ShowDiffAction.showDiffForChange(project, listOf(change)) }

            val editor = poll(40) {
                withContext(Dispatchers.EDT) {
                    EditorFactory.getInstance().allEditors.filterIsInstance<EditorEx>().firstOrNull {
                        it.editorKind == EditorKind.DIFF && it.getUserData(ReviewUiController.MODEL_KEY) != null &&
                            FileDocumentManager.getInstance().getFile(it.document)?.isInLocalFileSystem == true
                    }
                }
            }
            step("review is attached to the Local Changes diff", editor != null)
            if (editor == null) {
                capture(project, out, "live-0-no-diff")
                return
            }
            val model = editor.getUserData(ReviewUiController.MODEL_KEY)!!
            delay(2500)

            // --- «+» в гаттере ---
            val gutter = editor.gutterComponentEx
            val line = 2
            val (x, y) = withContext(Dispatchers.EDT) {
                (gutter.lineMarkerAreaOffset + 6) to (editor.logicalPositionToXY(LogicalPosition(line, 0)).y + editor.lineHeight / 2)
            }
            withContext(Dispatchers.EDT) { mouse(gutter, MouseEvent.MOUSE_MOVED, x, y) }
            delay(500)
            capture(project, out, "live-1-hover")

            withContext(Dispatchers.EDT) {
                mouse(gutter, MouseEvent.MOUSE_PRESSED, x, y)
                mouse(gutter, MouseEvent.MOUSE_RELEASED, x, y)
                mouse(gutter, MouseEvent.MOUSE_CLICKED, x, y)
            }
            val draft = poll(10) { model.inlays.value.filterIsInstance<NewCommentInlayModel>().firstOrNull() }
            step("click on + in the gutter opens the comment field", draft != null && draft.startLine == line)
            if (draft == null) return
            delay(800)
            capture(project, out, "live-2-new-comment")

            // --- комментарий и ответ агента ---
            withContext(Dispatchers.EDT) { draft.submit("Почему лимит зашит в код? Вынеси в параметр.") }
            val threadModel = poll(10) { model.inlays.value.filterIsInstance<ThreadInlayModel>().firstOrNull() }
            step("comment becomes a thread and is saved to review.json", threadModel != null && Files.exists(store.reviewFile!!))
            if (threadModel == null) return
            val reviewFile = store.reviewFile!!
            withContext(Dispatchers.IO) {
                Files.writeString(
                    reviewFile,
                    Files.readString(reviewFile).replace(
                        "Вынеси в параметр.\"\n        }",
                        "Вынеси в параметр.\"\n        },\n        { \"id\": \"r1\", \"author\": \"ai\", \"name\": \"Claude\", \"createdAt\": \"${Instant.now()}\", " +
                            "\"text\": \"Вынес `limit` в параметр функции со значением по умолчанию:\\n```kotlin\\nfun total(items: List<Int>, limit: Int = 2): Int\\n```\" }"
                    )
                )
            }
            val replied = poll(10) { threadModel.thread.value.comments.size.takeIf { it == 2 } }
            step("agent reply written to the file appears in the thread", replied != null)
            withContext(Dispatchers.EDT) { mouse(gutter, MouseEvent.MOUSE_EXITED, x, y) }
            delay(1000)
            capture(project, out, "live-3-thread")

            // --- панель ---
            val toolWindow = withContext(Dispatchers.EDT) {
                ToolWindowManager.getInstance(project).getToolWindow("Slop Review")?.also { it.activate(null) }
            }
            step("Slop Review tool window is registered", toolWindow != null)
            delay(2500)
            capture(project, out, "live-4-tool-window")

            // --- привязка к сессии в терминале и отправка ---
            // По умолчанию вместо агента запускаем cat, который пишет полученное в файл;
            // с -Dslopreview.selfCheck.agent=claude — настоящий Claude Code.
            val useClaude = System.getProperty("$PROPERTY.agent") == "claude"
            val received = out.resolve("terminal-received.txt")
            Files.deleteIfExists(received)
            val claudeHome = out.resolve("claude-home")
            Files.createDirectories(claudeHome)
            val sessions = AgentSessions.getInstance(project)
            withContext(Dispatchers.EDT) {
                store.reply(threadModel.thread.value.id, "И добавь тест на это.")
                TerminalBridge.openTab(project, base, "claude-session", if (useClaude) "claude" else "cat > '$received'")
                // Вторая вкладка остаётся выбранной: проверяем, что отправка идёт в привязанную, а не в активную
                TerminalBridge.openTab(project, base, "other-shell", "echo other")
            }
            delay(if (useClaude) 12000 else 8000)
            val listed = withContext(Dispatchers.EDT) { sessions.available() }
            val target = listed.firstOrNull { it.tabName == "claude-session" }
            step("terminal sessions are listed for attaching", target != null, listed.joinToString { it.label + if (it.busy) " (running)" else "" })
            if (target == null) return
            if (useClaude) {
                // Первый запуск в новой папке: Claude Code спрашивает, доверять ли ей — подтверждаем Enter
                // (по умолчанию выбран отказ, поэтому сначала стрелка вниз на «Yes, I trust this folder»)
                withContext(Dispatchers.EDT) { TerminalBridge.send(project, target.id, "\u001b[B") }
                delay(8000)
                capture(project, out, "live-5a-claude-started")
            }
            withContext(Dispatchers.EDT) {
                sessions.attach(target)
                if (useClaude) ReviewSender.send(project) else ReviewSender.send(project, claudeHome)
            }
            delay(if (useClaude) 25000 else 4000)
            capture(project, out, "live-5-terminal")

            if (useClaude) {
                delay(25000)
                capture(project, out, "live-6-claude-working")
                step("review sent to a real Claude Code session (see live-5/6 screenshots)", true)
            } else {
                // Второй круг дискуссии уходит в ту же сессию
                // ...и отправляется уже ссылкой «Send to AI» из самого треда в diff
                withContext(Dispatchers.EDT) { store.reply(threadModel.thread.value.id, "И переименуй limit в maxItems.") }
                val sendLink = poll(10) {
                    withContext(Dispatchers.EDT) {
                        java.awt.Window.getWindows().asSequence()
                            .filterIsInstance<javax.swing.RootPaneContainer>()
                            .flatMap { UIUtil.findComponentsOfType(it.rootPane, com.intellij.ui.components.ActionLink::class.java).asSequence() }
                            .firstOrNull { it.text == "Send to AI" && it.isShowing }
                    }
                }
                step("thread waiting for the AI shows the Send to AI link", sendLink != null)
                capture(project, out, "live-6-send-link")
                withContext(Dispatchers.EDT) { sendLink?.doClick() }
                delay(4000)
                capture(project, out, "live-7-second-round")
                val text = if (Files.exists(received)) Files.readString(received) else ""
                val rounds = text.lines().count { it.isNotBlank() }
                step("both rounds are delivered to the attached session, not to the selected tab", rounds == 2, "received: '${text.trim().replace("\n", " | ")}'")
                step("Claude Code command file is installed", Files.exists(claudeHome.resolve("commands/slop-review.md")))
            }

            // --- обычный редактор: треды можно спрятать, как Git Blame; остаётся значок в гаттере ---
            val settings = dev.slopreview.editor.ReviewViewSettings.getInstance(project)
            val plain = withContext(Dispatchers.EDT) {
                settings.setShowInEditor(true)
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                    .openTextEditor(com.intellij.openapi.fileEditor.OpenFileDescriptor(project, change.virtualFile!!, 0, 0), true) as? EditorEx
            }
            fun blocks() = plain?.inlayModel?.getBlockElementsInRange(0, plain.document.textLength)?.size ?: -1
            val shown = poll(10) { withContext(Dispatchers.EDT) { blocks().takeIf { it > 0 } } }
            step("thread is shown in the regular editor", shown != null)
            delay(1500)
            capture(project, out, "live-8-editor-shown")
            withContext(Dispatchers.EDT) { settings.setShowInEditor(false) }
            val hidden = poll(10) { withContext(Dispatchers.EDT) { blocks().takeIf { it == 0 } } }
            step("Show Slop Review Comments off hides the thread in the regular editor", hidden != null)
            delay(1500)
            capture(project, out, "live-9-editor-hidden")
            withContext(Dispatchers.EDT) { settings.setShowInEditor(true) }

            // --- строка сессии в панели: Change открывает список вкладок, Detach отвязывает ---
            suspend fun link(text: String) = poll(5) {
                withContext(Dispatchers.EDT) {
                    java.awt.Window.getWindows().asSequence()
                        .filterIsInstance<javax.swing.RootPaneContainer>()
                        .flatMap { UIUtil.findComponentsOfType(it.rootPane, com.intellij.ui.components.ActionLink::class.java).asSequence() }
                        .firstOrNull { it.text == text && it.isShowing }
                }
            }
            val changeLink = link("Change")
            step("session bar shows the attached session with Change", changeLink != null && withContext(Dispatchers.EDT) { sessions.attached()?.tabName } == "claude-session")
            withContext(Dispatchers.EDT) { changeLink?.doClick() }
            delay(1500)
            capture(project, out, "live-10-session-chooser")
            withContext(Dispatchers.EDT) {
                com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().getChildPopups(WindowManager.getInstance().getFrame(project)!!.rootPane).forEach { it.cancel() }
            }
            val detach = link("Detach")
            withContext(Dispatchers.EDT) { detach?.doClick() }
            val attachAgain = link("Attach Session")
            step("Detach in the session bar detaches the session", attachAgain != null && withContext(Dispatchers.EDT) { sessions.attached() } == null)
            delay(1000)
            capture(project, out, "live-11-detached")
        } catch (e: Throwable) {
            val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
            step("self-check crashed", false, trace.lineSequence().take(12).joinToString(" | "))
        } finally {
            step("finished", true)
            withContext(Dispatchers.EDT) { ApplicationManager.getApplication().exit(true, true, false) }
        }
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

    /** Окно IDE рисует само себя в картинку — снимок экрана средствами ОС не нужен. */
    private suspend fun capture(project: Project, out: Path, name: String) = withContext(Dispatchers.EDT) {
        val root = WindowManager.getInstance().getFrame(project)?.rootPane ?: return@withContext
        if (root.width <= 0 || root.height <= 0) return@withContext
        val image = BufferedImage(root.width, root.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        root.paint(g)
        g.dispose()
        ImageIO.write(image, "png", out.resolve("$name.png").toFile())
    }
}
