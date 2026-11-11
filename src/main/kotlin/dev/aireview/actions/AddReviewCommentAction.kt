package dev.aireview.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import dev.aireview.editor.ReviewUiController

/** Комментарий к строке под курсором или к выделению — в diff и в обычном редакторе. */
class AddReviewCommentAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val model = editor?.getUserData(ReviewUiController.MODEL_KEY)
        e.presentation.isEnabledAndVisible = model != null && model.canCreateComment(editor.caretModel.logicalPosition.line)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val model = editor.getUserData(ReviewUiController.MODEL_KEY) ?: return
        model.requestNewComment(editor.caretModel.logicalPosition.line)
    }
}
