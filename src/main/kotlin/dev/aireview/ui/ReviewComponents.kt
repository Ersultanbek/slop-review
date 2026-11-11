package dev.aireview.ui

import com.intellij.collaboration.ui.codereview.comment.CodeReviewCommentUIUtil
import com.intellij.collaboration.ui.codereview.editor.CodeReviewComponentInlayRenderer
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.aireview.actions.SendReviewAction
import dev.aireview.editor.NewCommentInlayModel
import dev.aireview.editor.ReviewInlayModel
import dev.aireview.editor.ThreadInlayModel
import dev.aireview.model.Author
import dev.aireview.model.ReviewComment
import dev.aireview.model.ReviewSide
import dev.aireview.model.ReviewThread
import dev.aireview.model.ThreadState
import dev.aireview.store.ReviewStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.time.Instant
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLEditorKit

/** Собирает компоненты тредов и поля нового комментария, которые встраиваются под код. */
object ReviewComponents {

    private const val ICON_INDENT = 24 // иконка автора 16 + отступ 8
    private val aiRepliedColor: Color = JBColor(0x208A3C, 0x5FAD65)

    fun CoroutineScope.createRenderer(project: Project, model: ReviewInlayModel): CodeReviewComponentInlayRenderer =
        when (model) {
            is ThreadInlayModel -> CodeReviewComponentInlayRenderer(createThread(project, model))
            is NewCommentInlayModel -> CodeReviewComponentInlayRenderer(createNewComment(model))
        }

    // ---------- новый комментарий ----------

    private fun createNewComment(model: NewCommentInlayModel): JComponent {
        val lines = if (model.startLine == model.endLine) "line ${model.startLine + 1}"
        else "lines ${model.startLine + 1}–${model.endLine + 1}"
        val suffix = if (model.side == ReviewSide.OLD) " (removed code)" else ""

        val input = CommentInput(
            initialText = "",
            placeholder = "Leave a comment for the AI…",
            submitText = "Comment",
            onSubmit = model::submit,
            onCancel = model::cancel
        )
        val content = JPanel(ColumnLayout(JBUI.scale(8))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, 12)
            add(secondaryLabel("Comment on $lines$suffix"))
            add(input)
        }
        input.focusInput()
        return CodeReviewCommentUIUtil.createEditorInlayPanel(content)
    }

    // ---------- тред ----------

    private fun CoroutineScope.createThread(project: Project, model: ThreadInlayModel): JComponent {
        val store = ReviewStore.getInstance(project)
        val threadId = model.thread.value.id

        val comments = JPanel(ColumnLayout(JBUI.scale(6))).apply { isOpaque = false }
        val footer = JPanel(ColumnLayout()).apply { isOpaque = false }
        val content = JPanel(ColumnLayout(JBUI.scale(10))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, 12)
            add(comments)
            add(footer)
        }

        // --- подвал: ссылки действий либо поле ответа ---
        val resolveLink = ActionLink("Resolve") {
            store.setResolved(threadId, !model.thread.value.resolved)
        }.apply { isFocusPainted = false }
        val hideLink = ActionLink("Hide") { model.collapsed.value = true }.apply { isFocusPainted = false }
        val statusLabel = JLabel().apply { font = JBUI.Fonts.smallFont() }
        val actionsRow = JPanel(BorderLayout()).apply { isOpaque = false }

        lateinit var replyInput: CommentInput
        fun showReply(show: Boolean) {
            replyInput.isVisible = show
            actionsRow.isVisible = !show
            if (show) replyInput.focusInput()
            content.revalidate()
            content.repaint()
        }
        replyInput = CommentInput(
            initialText = "",
            placeholder = "Reply…",
            submitText = "Reply",
            onSubmit = { text ->
                replyInput.text = ""
                showReply(false)
                store.reply(threadId, text)
            },
            onCancel = { showReply(false) }
        ).apply {
            isVisible = false
            border = JBUI.Borders.emptyLeft(ICON_INDENT)
        }
        val replyLink = ActionLink("Reply") { showReply(true) }.apply { isFocusPainted = false }
        // Уходит всё ревью целиком: агент обрабатывает все треды, ждущие ответа, в одном контексте
        val sendLink = ActionLink("Send to AI") { e -> SendReviewAction.send(project, e.source as? Component) }.apply {
            isFocusPainted = false
            toolTipText = "Send all comments waiting for the AI to the attached Claude Code session"
        }

        actionsRow.add(JPanel(HorizontalLayout(JBUI.scale(14))).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(ICON_INDENT)
            add(replyLink)
            add(resolveLink)
            add(sendLink)
            add(hideLink)
        }, BorderLayout.WEST)
        actionsRow.add(statusLabel, BorderLayout.EAST)
        footer.add(actionsRow)
        footer.add(replyInput)

        // --- комментарии ---
        var editingId: String? = null
        var editInput: CommentInput? = null
        lateinit var rebuild: () -> Unit

        fun stopEditing() {
            editingId = null
            editInput = null
            rebuild()
        }

        fun commentHeader(comment: ReviewComment): JComponent {
            val isUser = comment.author == Author.USER
            val title = JPanel(HorizontalLayout(JBUI.scale(8))).apply {
                isOpaque = false
                add(JLabel(if (isUser) AllIcons.General.User else AiReviewIcons.Ai))
                add(JLabel(comment.name ?: if (isUser) "You" else "AI").apply { font = JBUI.Fonts.label().asBold() })
                formatTime(comment.createdAt)?.let { add(secondaryLabel(it)) }
            }
            val header = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(title, BorderLayout.WEST)
            }
            if (isUser) {
                header.add(JPanel(HorizontalLayout(JBUI.scale(6))).apply {
                    isOpaque = false
                    add(CodeReviewCommentUIUtil.createEditButton {
                        editingId = comment.id
                        editInput = null
                        rebuild()
                    })
                    add(CodeReviewCommentUIUtil.createDeleteCommentIconButton {
                        store.deleteComment(threadId, comment.id)
                    })
                }, BorderLayout.EAST)
            }
            return header
        }

        fun commentBody(comment: ReviewComment): JComponent {
            if (comment.id != editingId) {
                return htmlPane(MiniMarkdown.toHtml(comment.text)).apply { border = JBUI.Borders.emptyLeft(ICON_INDENT) }
            }
            val input = editInput ?: CommentInput(
                initialText = comment.text,
                placeholder = "",
                submitText = "Save",
                onSubmit = { text ->
                    stopEditing()
                    store.editComment(threadId, comment.id, text)
                },
                onCancel = { stopEditing() }
            ).apply { border = JBUI.Borders.emptyLeft(ICON_INDENT) }
            if (editInput == null) input.focusInput()
            editInput = input
            return input
        }

        fun collapsedRow(thread: ReviewThread): JComponent {
            val preview = thread.comments.firstOrNull()?.text?.lineSequence()?.firstOrNull().orEmpty()
            return JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(JLabel("Resolved", AllIcons.RunConfigurations.TestPassed, JLabel.LEADING).apply {
                    foreground = UIUtil.getContextHelpForeground()
                }, BorderLayout.WEST)
                add(secondaryLabel(preview), BorderLayout.CENTER)
                add(ActionLink("Show") { model.collapsed.value = false }.apply { isFocusPainted = false }, BorderLayout.EAST)
            }
        }

        rebuild = {
            val thread = model.thread.value
            val collapsed = model.collapsed.value
            comments.removeAll()
            if (collapsed) {
                comments.add(collapsedRow(thread))
            } else {
                if (model.outdated.value) {
                    comments.add(JLabel("The code has changed since this comment", AllIcons.General.Information, JLabel.LEADING).apply {
                        foreground = UIUtil.getContextHelpForeground()
                        font = JBUI.Fonts.smallFont()
                    })
                }
                for (comment in thread.comments) {
                    comments.add(commentHeader(comment))
                    comments.add(commentBody(comment))
                }
            }
            footer.isVisible = !collapsed
            resolveLink.text = if (thread.resolved) "Unresolve" else "Resolve"
            hideLink.isVisible = thread.resolved
            sendLink.isVisible = thread.state == ThreadState.WAITING_FOR_AI
            when (thread.state) {
                ThreadState.WAITING_FOR_AI -> {
                    statusLabel.text = "Waiting for AI"
                    statusLabel.foreground = UIUtil.getContextHelpForeground()
                }
                ThreadState.AI_REPLIED -> {
                    statusLabel.text = "AI replied"
                    statusLabel.foreground = aiRepliedColor
                }
                ThreadState.RESOLVED -> {
                    statusLabel.text = "Resolved"
                    statusLabel.foreground = UIUtil.getContextHelpForeground()
                }
            }
            content.revalidate()
            content.repaint()
        }

        launch(start = CoroutineStart.UNDISPATCHED) {
            combine(model.thread, model.outdated, model.collapsed) { _, _, _ -> }.collect { rebuild() }
        }
        return CodeReviewCommentUIUtil.createEditorInlayPanel(content)
    }

    // ---------- мелочи ----------

    private fun secondaryLabel(text: String) = JLabel(text).apply { foreground = UIUtil.getContextHelpForeground() }

    private fun formatTime(iso: String): String? = try {
        DateFormatUtil.formatPrettyDateTime(Instant.parse(iso).toEpochMilli())
    } catch (e: Exception) {
        null
    }

    private fun htmlPane(body: String): JEditorPane {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val codeBackground = ColorUtil.toHtmlColor(JBColor.namedColor("Review.ChatItem.BubblePanel.Border", JBColor(0xEBECF0, 0x393B40)))
        val kit: HTMLEditorKit = HTMLEditorKitBuilder().build()
        kit.styleSheet.apply {
            addRule("body { margin: 0; padding: 0; }")
            addRule("div { margin: 0; padding: 0; }")
            addRule("code { font-family: '${scheme.editorFontName}'; }")
            addRule(".code { font-family: '${scheme.editorFontName}'; background-color: $codeBackground; margin-top: 4px; margin-bottom: 4px; padding: 6px; }")
        }
        return JEditorPane().apply {
            editorKit = kit
            isEditable = false
            isOpaque = false
            margin = JBUI.emptyInsets()
            font = JBUI.Fonts.label()
            foreground = UIUtil.getLabelForeground()
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            text = "<html><body>$body</body></html>"
            addHyperlinkListener { if (it.eventType == HyperlinkEvent.EventType.ACTIVATED) it.url?.let(BrowserUtil::browse) }
        }
    }
}
