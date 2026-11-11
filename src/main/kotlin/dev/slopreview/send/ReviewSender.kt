package dev.slopreview.send

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import dev.slopreview.model.ThreadState
import dev.slopreview.store.ReviewStore
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path

/** Передаёт ревью агенту: сохраняет файлы, ставит команду `/slop-review` в Claude Code и отправляет её в привязанную сессию. */
object ReviewSender {

    private const val COMMAND = "/slop-review"
    private const val COMMAND_FILE = "commands/slop-review.md"
    private const val REVIEW_PATH = ".slop-review/review.json"
    private const val NOTIFICATION_GROUP = "Slop Review"
    private val LOG = logger<ReviewSender>()

    fun send(project: Project, claudeHome: Path = Path.of(System.getProperty("user.home"), ".claude")) {
        val store = ReviewStore.getInstance(project)
        // Агент читает файлы с диска — несохранённые правки и сдвинувшиеся строки должны туда попасть
        FileDocumentManager.getInstance().saveAllDocuments()
        store.flushAnchors()

        val pending = store.threads.value.count { it.state == ThreadState.WAITING_FOR_AI }
        if (pending == 0) {
            notify(project, "Nothing to send", "There are no open comments waiting for the AI.", NotificationType.INFORMATION)
            return
        }
        val comments = if (pending == 1) "1 comment" else "$pending comments"

        val installed = installClaudeCommand(claudeHome)
        if (installed == Install.NO_CLAUDE) {
            // Claude Code не установлен — отдаём самодостаточный промпт для любого агента
            copy(prompt())
            notify(project, "Review prompt copied", "$comments ready. Paste the prompt into your coding agent.", NotificationType.INFORMATION)
            return
        }
        // Уже запущенная сессия узнаёт о новой команде не мгновенно: если файл появился только что,
        // в этот раз просим агента словами — иначе он ответит «Unknown command»
        val message = if (installed == Install.JUST_NOW) {
            "Address the code review comments in $REVIEW_PATH by following the instructions in ${claudeHome.resolve(COMMAND_FILE)}"
        } else COMMAND

        copy(message)
        val session = AgentSessions.getInstance(project).attached()
        when {
            session == null -> notify(
                project, "Review ready",
                "$comments ready. No Claude Code session is attached, so <code>$COMMAND</code> is copied — " +
                    "paste it into Claude Code, or attach a terminal session in the Slop Review panel.",
                NotificationType.INFORMATION
            )
            // В простаивающем shell slash-команда бессмысленна: агент в этой вкладке уже не запущен
            !session.busy -> notify(
                project, "Claude Code is not running",
                "Nothing is running in the attached tab <b>${session.tabName}</b>. Start Claude Code there and send again; " +
                    "<code>$COMMAND</code> is copied.",
                NotificationType.WARNING
            )
            AgentSessions.getInstance(project).sendToAttached(message) -> notify(
                project, "Review sent",
                "$comments sent to <b>${session.tabName}</b> as <code>$COMMAND</code>.",
                NotificationType.INFORMATION
            )
            else -> notify(
                project, "Review ready",
                "Could not reach <b>${session.tabName}</b>. <code>$COMMAND</code> is copied — paste it into Claude Code.",
                NotificationType.WARNING
            )
        }
    }

    /** Самодостаточный промпт — для агентов без поддержки slash-команд. */
    fun copyPrompt(project: Project) {
        FileDocumentManager.getInstance().saveAllDocuments()
        ReviewStore.getInstance(project).flushAnchors()
        copy(prompt())
        notify(project, "Review prompt copied", "Paste it into your coding agent.", NotificationType.INFORMATION)
    }

    private fun prompt(): String =
        ReviewSender::class.java.getResource("/prompts/slop-review.md")!!.readText()

    enum class Install { NO_CLAUDE, JUST_NOW, PRESENT }

    /** Ставит slash-команду в Claude Code. Вызывается и при открытии проекта, чтобы к первой отправке команда уже была. */
    fun installClaudeCommand(claudeHome: Path = Path.of(System.getProperty("user.home"), ".claude")): Install {
        return try {
            if (!Files.isDirectory(claudeHome)) return Install.NO_CLAUDE
            val file = claudeHome.resolve(COMMAND_FILE)
            val content = "---\ndescription: Address the code review comments left in the IDE (Slop Review)\n---\n\n" + prompt()
            if (Files.exists(file) && Files.readString(file) == content) return Install.PRESENT
            Files.createDirectories(file.parent)
            Files.writeString(file, content)
            Install.JUST_NOW
        } catch (e: Exception) {
            LOG.warn("Cannot install Claude Code command", e)
            Install.NO_CLAUDE
        }
    }

    private fun copy(text: String) = CopyPasteManager.getInstance().setContents(StringSelection(text))

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(project)
    }
}
