package dev.aireview.send

import com.intellij.openapi.project.Project
import com.intellij.ui.content.Content
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.util.concurrent.TimeUnit

/** Работа со встроенным терминалом. Класс загружается, только если плагин Terminal установлен. */
internal object TerminalBridge {

    private const val ENTER_DELAY_MS = 250L

    /** Вкладки терминала, в которые можно отправить ревью. */
    fun sessions(project: Project): List<AgentSession> =
        contents(project).map { content ->
            val widget = TerminalToolWindowManager.findWidgetByContent(content)
            val title = runCatching { widget?.terminalTitle?.buildTitle() }.getOrNull().orEmpty()
            AgentSession(
                id = idOf(content),
                tabName = content.displayName.orEmpty(),
                // Claude Code пишет в заголовок терминала, чем занят, — по нему сессию проще узнать
                title = title.takeIf { it.isNotBlank() && it != content.displayName },
                busy = runCatching { widget?.isCommandRunning() }.getOrNull() == true
            )
        }

    /**
     * Печатает текст в сессию и нажимает Enter отдельным нажатием: текст вместе с переводом строки
     * агент принял бы за вставку и не отправил бы сообщение.
     */
    fun send(project: Project, sessionId: String, text: String): Boolean {
        val content = contents(project).firstOrNull { idOf(it) == sessionId } ?: return false
        val widget = TerminalToolWindowManager.findWidgetByContent(content) ?: return false
        val tty = widget.ttyConnector
        if (tty == null) {
            widget.sendCommandToExecute(text)
        } else {
            tty.write(text)
            AppExecutorUtil.getAppScheduledExecutorService().schedule({ runCatching { tty.write("\r") } }, ENTER_DELAY_MS, TimeUnit.MILLISECONDS)
        }
        val toolWindow = TerminalToolWindowManager.getInstance(project).toolWindow
        toolWindow?.contentManager?.setSelectedContent(content)
        toolWindow?.show()
        return true
    }

    /** Открывает вкладку терминала и запускает в ней команду. Используется самопроверкой. */
    fun openTab(project: Project, workingDirectory: String, tabName: String, command: String) {
        val widget = TerminalToolWindowManager.getInstance(project).createShellWidget(workingDirectory, tabName, true, true)
        widget.sendCommandToExecute(command)
    }

    private fun contents(project: Project): List<Content> =
        TerminalToolWindowManager.getInstance(project).toolWindow?.contentManager?.contents?.toList().orEmpty()

    private fun idOf(content: Content): String = System.identityHashCode(content).toString()
}
