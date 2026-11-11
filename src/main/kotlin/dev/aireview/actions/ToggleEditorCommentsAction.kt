package dev.aireview.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import dev.aireview.editor.ReviewViewSettings

/** Показывает или прячет треды в обычном редакторе — как «Annotate with Git Blame» в меню гаттера. */
class ToggleEditorCommentsAction : ToggleAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun isSelected(e: AnActionEvent): Boolean =
        e.project?.let { ReviewViewSettings.getInstance(it).showInEditor.value } ?: true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        e.project?.let { ReviewViewSettings.getInstance(it).setShowInEditor(state) }
    }
}
