package dev.aireview.ui

import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.update.UiNotifyConnector
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/** Поле ввода комментария с кнопками. Отправка — Cmd/Ctrl+Enter, отмена — Esc. */
class CommentInput(
    initialText: String,
    placeholder: String,
    submitText: String,
    private val onSubmit: (String) -> Unit,
    private val onCancel: () -> Unit
) : JPanel(ColumnLayout(JBUI.scale(8))) {

    private val textArea = JBTextArea(initialText).apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 2
        font = JBUI.Fonts.label()
        emptyText.text = placeholder
        background = UIUtil.getTextFieldBackground()
        border = fieldBorder(focused = false)
    }

    private val submitButton = JButton(submitText).apply {
        isOpaque = false
        putClientProperty("JButton.buttonType", "default")
        isDefaultCapable = false
        addActionListener { submit() }
    }

    init {
        isOpaque = false

        val buttons = JPanel(HorizontalLayout(JBUI.scale(10))).apply {
            isOpaque = false
            add(submitButton)
            add(ActionLink("Cancel") { onCancel() }.apply { isFocusPainted = false })
            add(JLabel(KeymapUtil.getFirstKeyboardShortcutText(CommonShortcuts.getCtrlEnter()) + " to submit").apply {
                foreground = UIUtil.getContextHelpForeground()
                font = JBUI.Fonts.smallFont()
            })
        }
        add(textArea)
        add(buttons)

        textArea.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                updateSubmitEnabled()
                // Поле растёт вместе с текстом
                revalidate()
                repaint()
            }
        })
        textArea.addFocusListener(object : FocusListener {
            override fun focusGained(e: FocusEvent) {
                textArea.border = fieldBorder(focused = true)
            }

            override fun focusLost(e: FocusEvent) {
                textArea.border = fieldBorder(focused = false)
            }
        })
        DumbAwareAction.create { submit() }.registerCustomShortcutSet(CommonShortcuts.getCtrlEnter(), textArea)
        DumbAwareAction.create { onCancel() }.registerCustomShortcutSet(CommonShortcuts.ESCAPE, textArea)
        updateSubmitEnabled()
    }

    var text: String
        get() = textArea.text
        set(value) {
            textArea.text = value
        }

    fun focusInput() {
        if (textArea.isShowing) {
            textArea.requestFocusInWindow()
        } else {
            UiNotifyConnector.doWhenFirstShown(textArea) { textArea.requestFocusInWindow() }
        }
    }

    private fun submit() {
        val value = textArea.text.trim()
        if (value.isNotEmpty()) onSubmit(value)
    }

    private fun updateSubmitEnabled() {
        submitButton.isEnabled = textArea.text.isNotBlank()
    }

    private fun fieldBorder(focused: Boolean) = JBUI.Borders.compound(
        JBUI.Borders.customLine(if (focused) JBUI.CurrentTheme.Focus.focusColor() else JBColor.border(), 1),
        JBUI.Borders.empty(6, 8)
    )
}
