package dev.slopreview.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import java.awt.Component
import dev.slopreview.model.ThreadState
import dev.slopreview.send.AgentSessions
import dev.slopreview.send.ReviewSender
import dev.slopreview.store.ReviewStore

class SendReviewAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val pending = project?.let { p -> ReviewStore.getInstance(p).threads.value.count { it.state == ThreadState.WAITING_FOR_AI } } ?: 0
        e.presentation.isEnabled = pending > 0
        e.presentation.text = if (pending > 0) "Send to AI ($pending)" else "Send to AI"
        e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        send(project, e.inputEvent?.component)
    }

    companion object {
        /** Общая точка входа для тулбара панели и ссылки «Send to AI» в треде. */
        fun send(project: Project, component: Component?) {
            val sessions = AgentSessions.getInstance(project)
            // Сессия ещё не выбрана, а вкладки терминала есть — сначала спрашиваем, куда отправлять
            if (sessions.attached() == null && component != null && sessions.available().isNotEmpty()) {
                AttachSessionAction.showChooser(project, component) { ReviewSender.send(project) }
                return
            }
            ReviewSender.send(project)
        }
    }
}
