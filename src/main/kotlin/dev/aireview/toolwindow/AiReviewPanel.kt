package dev.aireview.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import dev.aireview.anchor.AnchorTracker
import dev.aireview.model.ReviewThread
import dev.aireview.model.ThreadState
import dev.aireview.actions.AttachSessionAction
import dev.aireview.send.AgentSessions
import dev.aireview.send.ReviewSender
import dev.aireview.store.ReviewStore
import dev.aireview.ui.AiReviewIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JPanel
import javax.swing.Timer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.panels.HorizontalLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** Панель со всеми тредами ревью: по файлам, со статусами и отправкой агенту. */
class AiReviewPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {

    private val store = ReviewStore.getInstance(project)
    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel)
    private val summary = JBLabel().apply {
        border = JBUI.Borders.empty(4, 10)
        foreground = UIUtil.getContextHelpForeground()
        font = JBUI.Fonts.smallFont()
    }
    private var showResolved = true

    // --- строка сессии: к какой вкладке терминала с Claude Code привязано ревью ---
    private val sessions = AgentSessions.getInstance(project)
    private val sessionLabel = JBLabel()
    private val attachLink = ActionLink("Attach Session") { e ->
        AttachSessionAction.showChooser(project, e.source as Component) { refreshSession() }
    }.apply { isFocusPainted = false }
    private val detachLink = ActionLink("Detach") {
        sessions.detach()
        refreshSession()
    }.apply { isFocusPainted = false }
    private val sessionBar = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(6, 10))
        add(sessionLabel, BorderLayout.CENTER)
        add(JPanel(HorizontalLayout(JBUI.scale(12))).apply {
            isOpaque = false
            add(attachLink)
            add(detachLink)
        }, BorderLayout.EAST)
    }

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = Renderer()
        tree.emptyText.text = "No review comments"
        tree.emptyText.appendLine("Open a diff and click + in the gutter to comment on the code")
        TreeUtil.installActions(tree)

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val thread = selectedThread() ?: return false
                navigate(thread)
                return true
            }
        }.installOn(tree)
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) selectedThread()?.let { navigate(it); e.consume() }
            }
        })
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(ToggleResolvedAction(), DeleteThreadAction()), "AiReviewTreePopup")

        val content = JBUI.Panels.simplePanel(ScrollPaneFactory.createScrollPane(tree, true)).addToTop(sessionBar).addToBottom(summary)
        setContent(content)

        val toolbar = ActionManager.getInstance().createActionToolbar("AiReviewToolbar", toolbarGroup(), true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        refreshSession()
        // Вкладки терминала открывают и закрывают мимо плагина, поэтому строку сессии сверяем по таймеру
        val sessionTimer = Timer(1000) { if (isShowing) refreshSession() }.apply { start() }
        Disposer.register(parent) { sessionTimer.stop() }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        Disposer.register(parent) { scope.cancel() }
        scope.launch {
            store.threads.collect {
                rebuild()
                // Счётчик на «Send to AI» должен обновиться сразу, а не при следующем движении мыши
                toolbar.updateActionsAsync()
            }
        }
    }

    private fun refreshSession() {
        val session = sessions.attached()
        when {
            session == null -> {
                sessionLabel.icon = AllIcons.Debugger.ThreadSuspended
                sessionLabel.text = "No Claude Code session"
                sessionLabel.foreground = UIUtil.getContextHelpForeground()
                sessionLabel.toolTipText = "Attach the terminal tab where Claude Code is running — reviews will be sent there"
            }
            session.busy -> {
                sessionLabel.icon = AllIcons.Debugger.ThreadRunning
                sessionLabel.text = session.tabName
                sessionLabel.foreground = UIUtil.getLabelForeground()
                sessionLabel.toolTipText = "Reviews are sent to the terminal tab \"${session.label}\""
            }
            else -> {
                sessionLabel.icon = AllIcons.General.Warning
                sessionLabel.text = "${session.tabName} — nothing is running"
                sessionLabel.foreground = UIUtil.getLabelForeground()
                sessionLabel.toolTipText = "Start Claude Code in this terminal tab, or attach another one"
            }
        }
        attachLink.text = if (session == null) "Attach Session" else "Change"
        detachLink.isVisible = session != null
    }

    private fun toolbarGroup(): DefaultActionGroup {
        val group = DefaultActionGroup()
        ActionManager.getInstance().getAction("AiReview.Send")?.let { group.add(it) }
        group.add(Separator.getInstance())
        ActionManager.getInstance().getAction("AiReview.ToggleEditorComments")?.let { group.add(it) }
        group.add(object : ToggleAction("Show Resolved", "Show resolved threads in the list", AllIcons.Actions.ToggleVisibility) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = showResolved
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                showResolved = state
                rebuild()
            }
        })
        group.add(object : DumbAwareAction("Delete Resolved", "Delete all resolved threads", AllIcons.Actions.GC) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = store.threads.value.any { it.resolved }
            }

            override fun actionPerformed(e: AnActionEvent) = store.deleteResolved()
        })
        group.add(object : DumbAwareAction("Delete All", "Delete every thread and start a new review", AllIcons.Actions.Cancel) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = store.threads.value.isNotEmpty()
            }

            override fun actionPerformed(e: AnActionEvent) {
                val count = store.threads.value.size
                if (MessageDialogBuilder.yesNo("Delete All Threads", "Delete all $count review threads? This cannot be undone.").ask(project)) {
                    store.deleteAll()
                }
            }
        })
        group.add(Separator.getInstance())
        group.add(object : DumbAwareAction("Copy Review Prompt", "Copy a self-contained prompt for any coding agent", AllIcons.Actions.Copy) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) = ReviewSender.copyPrompt(project)
        })
        return group
    }

    // ---------- дерево ----------

    private fun rebuild() {
        val selectedId = selectedThread()?.id
        val threads = store.threads.value.filter { showResolved || !it.resolved }

        root.removeAllChildren()
        var toSelect: DefaultMutableTreeNode? = null
        for ((file, fileThreads) in threads.groupBy { it.file }) {
            val fileNode = DefaultMutableTreeNode(FileItem(file, fileThreads.size))
            for (thread in fileThreads.sortedBy { it.startLine }) {
                val node = DefaultMutableTreeNode(thread)
                if (thread.id == selectedId) toSelect = node
                fileNode.add(node)
            }
            root.add(fileNode)
        }
        treeModel.reload()
        TreeUtil.expandAll(tree)
        toSelect?.let { tree.selectionPath = TreePath(it.path) }

        val all = store.threads.value
        val waiting = all.count { it.state == ThreadState.WAITING_FOR_AI }
        val replied = all.count { it.state == ThreadState.AI_REPLIED }
        val resolved = all.count { it.state == ThreadState.RESOLVED }
        summary.text = if (all.isEmpty()) " " else listOfNotNull(
            "$waiting waiting for AI".takeIf { waiting > 0 },
            "$replied AI replied".takeIf { replied > 0 },
            "$resolved resolved".takeIf { resolved > 0 }
        ).joinToString("  ·  ")
    }

    private fun selectedThread(): ReviewThread? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ReviewThread

    private fun navigate(thread: ReviewThread) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(store.absolutePath(thread))
        if (file == null) {
            NotificationGroupManager.getInstance().getNotificationGroup("AI Review")
                .createNotification("File not found", thread.file, NotificationType.WARNING)
                .notify(project)
            return
        }
        val line = AnchorTracker.getInstance(project).currentLine(thread)
        OpenFileDescriptor(project, file, line, 0).navigate(true)
    }

    private class FileItem(val path: String, val count: Int)

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
        ) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is FileItem -> {
                    val name = item.path.substringAfterLast('/')
                    val dir = item.path.substringBeforeLast('/', "")
                    icon = FileTypeManager.getInstance().getFileTypeByFileName(name).icon
                    append(name)
                    if (dir.isNotEmpty()) append("  $dir", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("  ${item.count}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                is ReviewThread -> {
                    icon = when (item.state) {
                        ThreadState.WAITING_FOR_AI -> AllIcons.General.User
                        ThreadState.AI_REPLIED -> AiReviewIcons.Ai
                        ThreadState.RESOLVED -> AllIcons.RunConfigurations.TestPassed
                    }
                    val title = item.comments.firstOrNull()?.text?.lineSequence()?.firstOrNull().orEmpty()
                    append(title, if (item.resolved) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  :${item.startLine}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (item.state == ThreadState.AI_REPLIED) {
                        append("  AI replied", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, AI_REPLIED_COLOR))
                    }
                }
            }
        }
    }

    // ---------- контекстное меню ----------

    private inner class ToggleResolvedAction : DumbAwareAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            val thread = selectedThread()
            e.presentation.isEnabledAndVisible = thread != null
            e.presentation.text = if (thread?.resolved == true) "Unresolve" else "Resolve"
        }

        override fun actionPerformed(e: AnActionEvent) {
            val thread = selectedThread() ?: return
            store.setResolved(thread.id, !thread.resolved)
        }
    }

    private inner class DeleteThreadAction : DumbAwareAction("Delete Thread", null, AllIcons.General.Remove) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = selectedThread() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            selectedThread()?.let { store.deleteThread(it.id) }
        }
    }

    companion object {
        private val AI_REPLIED_COLOR = JBColor(0x208A3C, 0x5FAD65)
    }
}
