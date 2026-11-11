package dev.aireview.send

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project

/** Вкладка встроенного терминала, в которой работает агент. */
data class AgentSession(val id: String, val tabName: String, val title: String?, val busy: Boolean) {
    val label: String get() = if (title == null) tabName else "$tabName — $title"
}

/**
 * Привязка ревью к одной сессии агента. Пока сессия привязана, каждая отправка уходит именно в неё,
 * поэтому вся дискуссия по ревью остаётся в одном контексте агента.
 */
@Service(Service.Level.PROJECT)
class AgentSessions(private val project: Project) {

    @Volatile
    private var attachedId: String? = null

    fun available(): List<AgentSession> {
        if (!terminalAvailable()) return emptyList()
        return try {
            TerminalBridge.sessions(project)
        } catch (e: Throwable) {
            LOG.warn("Cannot list terminal sessions", e)
            emptyList()
        }
    }

    /** Привязанная сессия; null, если не привязана или её вкладку закрыли. */
    fun attached(): AgentSession? {
        val id = attachedId ?: return null
        val session = available().firstOrNull { it.id == id }
        if (session == null) attachedId = null
        return session
    }

    fun attach(session: AgentSession) {
        attachedId = session.id
    }

    fun detach() {
        attachedId = null
    }

    /** @return false, если сессия не привязана или отправить не удалось */
    fun sendToAttached(text: String): Boolean {
        val session = attached() ?: return false
        return try {
            TerminalBridge.send(project, session.id, text)
        } catch (e: Throwable) {
            LOG.warn("Cannot send to the attached session", e)
            false
        }
    }

    private fun terminalAvailable(): Boolean =
        PluginManagerCore.isPluginInstalled(PluginId.getId("org.jetbrains.plugins.terminal"))

    companion object {
        private val LOG = logger<AgentSessions>()
        fun getInstance(project: Project): AgentSessions = project.service()
    }
}
