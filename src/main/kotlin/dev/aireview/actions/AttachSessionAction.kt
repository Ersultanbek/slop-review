package dev.aireview.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import dev.aireview.send.AgentSessions
import java.awt.Component
import java.awt.Point

/** Кнопка в панели: к какой сессии Claude Code (вкладке терминала) привязано ревью. */
class AttachSessionAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val session = e.project?.let { AgentSessions.getInstance(it).attached() }
        e.presentation.icon = if (session != null) AllIcons.Debugger.ThreadRunning else AllIcons.Debugger.ThreadSuspended
        e.presentation.text = session?.tabName?.let { "Session: $it" } ?: "Attach Session"
        e.presentation.description = "Choose the terminal tab with Claude Code that receives this review"
        e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val component = e.inputEvent?.component ?: return
        showChooser(project, component)
    }

    companion object {
        fun showChooser(project: Project, component: Component, onAttached: () -> Unit = {}) {
            val sessions = AgentSessions.getInstance(project)
            val attached = sessions.attached()
            val group = DefaultActionGroup()
            val available = sessions.available()
            for (session in available) {
                val icon = if (session.id == attached?.id) AllIcons.Actions.Checked else null
                val text = session.label + if (session.busy) "  (running)" else ""
                group.add(object : DumbAwareAction(text, "Send the review to this terminal tab", icon) {
                    override fun actionPerformed(e: AnActionEvent) {
                        sessions.attach(session)
                        onAttached()
                    }
                })
            }
            if (available.isEmpty()) {
                group.add(object : DumbAwareAction("No terminal tabs — start Claude Code in the IDE terminal first") {
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = false
                    }

                    override fun actionPerformed(e: AnActionEvent) = Unit
                })
            }
            if (attached != null) {
                group.add(Separator.getInstance())
                group.add(object : DumbAwareAction("Detach") {
                    override fun actionPerformed(e: AnActionEvent) = sessions.detach()
                })
            }
            JBPopupFactory.getInstance()
                .createActionGroupPopup("Claude Code Session", group, com.intellij.ide.DataManager.getInstance().getDataContext(component),
                    JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
                .show(RelativePoint(component, Point(0, component.height)))
        }
    }
}
