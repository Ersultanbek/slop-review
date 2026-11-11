package dev.slopreview.editor

import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorGutterControlsModel
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorModel
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import dev.slopreview.anchor.AnchorTracker
import dev.slopreview.model.ReviewSide
import dev.slopreview.store.ReviewStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** Содержимое одной стороны: какой файл и какая его версия показаны в редакторе. */
class SideInfo(
    val absolutePath: String,
    /** Хэш ревизии или null для рабочего дерева. */
    val revision: String?,
    val document: Document,
    /** Документ — живой файл на диске, а не снимок ревизии. */
    val workingTree: Boolean
)

/**
 * Модель ревью для одного редактора — обычного или стороны diff.
 * Знает, какие треды относятся к показанному содержимому и на каких строках редактора они находятся.
 *
 * @param locationToLine строка содержимого стороны → строка редактора (null, если строка не видна)
 * @param lineToLocation строка редактора → сторона и строка её содержимого
 * @param showAddOnHover показывать ли «+» в гаттере при наведении
 * @param threadsVisible если задан — общий переключатель «показывать треды»; при false от треда остаётся значок в гаттере
 */
class ReviewEditorModel(
    cs: CoroutineScope,
    project: Project,
    private val editor: EditorEx,
    private val sides: Map<ReviewSide, SideInfo>,
    private val locationToLine: (ReviewSide, Int) -> Int?,
    private val lineToLocation: (Int) -> Pair<ReviewSide, Int>?,
    private val showAddOnHover: Boolean,
    private val threadsVisible: StateFlow<Boolean>? = null
) : CodeReviewEditorModel<ReviewInlayModel> {

    private val store = ReviewStore.getInstance(project)
    private val tracker = AnchorTracker.getInstance(project)

    private val threadModels = LinkedHashMap<String, ThreadInlayModel>()
    private val newModels = LinkedHashMap<String, NewCommentInlayModel>()

    /** Только что написанный тред остаётся раскрытым, даже когда остальные скрыты. */
    private var revealNextThread = false

    private val _inlays = MutableStateFlow<Collection<ReviewInlayModel>>(emptyList())
    override val inlays: StateFlow<Collection<ReviewInlayModel>> = _inlays

    private val _gutterControlsState = MutableStateFlow<CodeReviewEditorGutterControlsModel.ControlsState?>(null)
    override val gutterControlsState: StateFlow<CodeReviewEditorGutterControlsModel.ControlsState?> = _gutterControlsState

    init {
        val documentChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val disposable: Disposable = Disposer.newDisposable("Slop Review editor model")
        cs.coroutineContext.job.invokeOnCompletion { Disposer.dispose(disposable) }
        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                documentChanges.tryEmit(Unit)
            }
        }
        sides.values.map { it.document }.distinct().forEach { it.addDocumentListener(listener, disposable) }

        cs.launch(start = CoroutineStart.UNDISPATCHED) {
            store.threads.collect { refresh() }
        }
        if (threadsVisible != null) {
            cs.launch(start = CoroutineStart.UNDISPATCHED) {
                threadsVisible.collect { show -> threadModels.values.forEach { it.isVisible.value = show } }
            }
        }
        cs.launch {
            @OptIn(FlowPreview::class)
            documentChanges.debounce(DOCUMENT_DEBOUNCE_MS).collect { refresh() }
        }
    }

    private fun refresh() {
        val visible = HashSet<String>()
        for (thread in store.threads.value) {
            val info = sides[thread.side] ?: continue
            if (store.absolutePath(thread) != info.absolutePath) continue
            val placement = tracker.place(thread, info.document, info.workingTree, info.revision) ?: continue
            val line = locationToLine(thread.side, placement.endLine)
            visible += thread.id

            val model = threadModels[thread.id]
            if (model == null) {
                threadModels[thread.id] = ThreadInlayModel(thread, line, placement.outdated).also {
                    if (threadsVisible?.value == false && !revealNextThread) it.isVisible.value = false
                    revealNextThread = false
                }
            } else {
                if (model.thread.value.resolved != thread.resolved) model.collapsed.value = thread.resolved
                model.thread.value = thread
                model.line.value = line
                model.outdated.value = placement.outdated
            }
        }
        threadModels.keys.retainAll(visible)
        publish()
    }

    private fun publish() {
        _inlays.value = threadModels.values + newModels.values
        _gutterControlsState.value = GutterState(
            linesWithComments = threadModels.values.mapNotNullTo(HashSet()) { it.line.value },
            linesWithNewComments = newModels.values.mapNotNullTo(HashSet()) { it.line.value },
            commentable = { line -> showAddOnHover && canCreateComment(line) }
        )
    }

    // ---------- новый комментарий ----------

    override fun canCreateComment(lineIdx: Int): Boolean {
        val location = lineToLocation(lineIdx) ?: return false
        return sides.containsKey(location.first)
    }

    /** Комментарий к выделению, если строка [lineIdx] входит в него, иначе к одной строке. */
    override fun requestNewComment(lineIdx: Int) {
        val (side, line) = lineToLocation(lineIdx) ?: return
        if (!sides.containsKey(side)) return

        var start = line
        var end = line
        selectedEditorLines()?.takeIf { lineIdx in it }?.let { selection ->
            val lines = selection.mapNotNull(lineToLocation).filter { it.first == side }.map { it.second }
            if (lines.isNotEmpty()) {
                start = lines.min()
                end = lines.max()
            }
        }

        val key = "NEW:$side:$start:$end"
        if (key !in newModels) {
            newModels[key] = NewCommentInlayModel(key, side, start, end, locationToLine(side, end), ::submitNew, ::cancelNew)
            publish()
        }
    }

    override fun cancelNewComment(lineIdx: Int) {
        if (newModels.values.removeIf { it.line.value == lineIdx }) publish()
    }

    private fun selectedEditorLines(): IntRange? {
        val selection = editor.selectionModel
        if (!selection.hasSelection()) return null
        val document = editor.document
        val first = document.getLineNumber(selection.selectionStart)
        var last = document.getLineNumber(selection.selectionEnd)
        // Выделение, закончившееся в начале строки, эту строку не включает
        if (last > first && selection.selectionEnd == document.getLineStartOffset(last)) last--
        return first..last
    }

    private fun submitNew(model: NewCommentInlayModel, text: String) {
        val info = sides[model.side] ?: return
        val document = info.document
        if (document.lineCount == 0) return
        val start = model.startLine.coerceIn(0, document.lineCount - 1)
        val end = model.endLine.coerceIn(start, document.lineCount - 1)
        val snippet = document.getText(TextRange(document.getLineStartOffset(start), document.getLineEndOffset(end)))
        newModels.remove(model.key)
        revealNextThread = true
        store.addThread(info.absolutePath, model.side, start, end, snippet, info.revision, text)
        publish()
    }

    private fun cancelNew(model: NewCommentInlayModel) {
        if (newModels.remove(model.key) != null) publish()
    }

    /** Клик по иконке комментария в гаттере скрывает или показывает треды этой строки. */
    override fun toggleComments(lineIdx: Int) {
        val onLine = threadModels.values.filter { it.line.value == lineIdx }
        if (onLine.isEmpty()) return
        val show = onLine.none { it.isVisible.value }
        onLine.forEach { it.isVisible.value = show }
    }

    private class GutterState(
        override val linesWithComments: Set<Int>,
        override val linesWithNewComments: Set<Int>,
        private val commentable: (Int) -> Boolean
    ) : CodeReviewEditorGutterControlsModel.ControlsState {
        override fun isLineCommentable(lineIdx: Int): Boolean = commentable(lineIdx)

        override fun equals(other: Any?): Boolean =
            other is GutterState && linesWithComments == other.linesWithComments && linesWithNewComments == other.linesWithNewComments

        override fun hashCode(): Int = 31 * linesWithComments.hashCode() + linesWithNewComments.hashCode()
    }

    companion object {
        private const val DOCUMENT_DEBOUNCE_MS = 150L
    }
}
