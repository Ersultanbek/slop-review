package dev.slopreview.editor

import com.intellij.collaboration.ui.codereview.editor.CodeReviewInlayModel
import dev.slopreview.model.ReviewSide
import dev.slopreview.model.ReviewThread
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface ReviewInlayModel : CodeReviewInlayModel

/** Существующий тред под кодом. Объект живёт, пока тред виден в редакторе; данные обновляются через flow. */
class ThreadInlayModel(thread: ReviewThread, line: Int?, outdated: Boolean) : ReviewInlayModel {
    override val key: Any = thread.id
    val thread = MutableStateFlow(thread)
    override val line = MutableStateFlow(line)
    override val isVisible = MutableStateFlow(true)
    val outdated = MutableStateFlow(outdated)

    /** Решённые треды свёрнуты в одну строку, как на GitHub. */
    val collapsed = MutableStateFlow(thread.resolved)
}

/** Поле ввода нового комментария. Строки — 0-based в содержимом стороны [side]. */
class NewCommentInlayModel(
    override val key: Any,
    val side: ReviewSide,
    val startLine: Int,
    val endLine: Int,
    line: Int?,
    private val onSubmit: (NewCommentInlayModel, String) -> Unit,
    private val onCancel: (NewCommentInlayModel) -> Unit
) : ReviewInlayModel {
    override val line = MutableStateFlow(line)
    override val isVisible = MutableStateFlow(true)

    fun submit(text: String) = onSubmit(this, text)
    fun cancel() = onCancel(this)
}
