package dev.slopreview.anchor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import dev.slopreview.model.ReviewSide
import dev.slopreview.model.ReviewThread
import dev.slopreview.store.ReviewStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Следит за тем, где сейчас находится код каждого треда.
 * В рабочих файлах позиция держится на RangeMarker — он переживает и ручные правки,
 * и перезагрузку файла после правок агента. В неизменяемом содержимом ревизий код ищется по фрагменту.
 */
@Service(Service.Level.PROJECT)
class AnchorTracker(private val project: Project, cs: CoroutineScope) {

    private val store = ReviewStore.getInstance(project)

    // Доступ только с EDT
    private val markers = HashMap<String, RangeMarker>()

    init {
        store.anchorSync = ::syncAnchor
        cs.launch(Dispatchers.EDT) {
            store.threads.collect { trackWorkingTreeThreads(it) }
        }
    }

    /**
     * @param workingTree документ — живой файл рабочего дерева
     * @param viewRevision ревизия показываемого содержимого (null для рабочего дерева)
     */
    fun place(thread: ReviewThread, document: Document, workingTree: Boolean, viewRevision: String?): Placement? {
        if (document.lineCount == 0) return null
        if (!workingTree) {
            return locate(thread, document, allowFallback = thread.revision != null && thread.revision == viewRevision)
        }

        val marker = markers[thread.id]
        if (marker != null && marker.isValid && marker.document === document) {
            val start = document.getLineNumber(marker.startOffset)
            val end = document.getLineNumber(marker.endOffset).coerceAtLeast(start)
            val current = document.getText(TextRange(document.getLineStartOffset(start), document.getLineEndOffset(end)))
            return Placement(start, end, outdated = normalize(current) != normalize(thread.snippet))
        }

        val placement = locate(thread, document, allowFallback = thread.revision == null) ?: return null
        marker?.dispose()
        markers[thread.id] = document.createRangeMarker(
            document.getLineStartOffset(placement.startLine),
            document.getLineEndOffset(placement.endLine),
            true
        )
        return placement
    }

    /** Текущая строка треда в рабочем файле (0-based) — для навигации. */
    fun currentLine(thread: ReviewThread): Int {
        val marker = markers[thread.id]
        if (marker != null && marker.isValid) return marker.document.getLineNumber(marker.startOffset)
        return (thread.startLine - 1).coerceAtLeast(0)
    }

    private fun locate(thread: ReviewThread, document: Document, allowFallback: Boolean): Placement? =
        SnippetLocator.locate(
            lines = document.immutableCharSequence.toString().split('\n'),
            snippet = thread.snippet,
            hintStartLine = thread.startLine - 1,
            hintEndLine = thread.endLine - 1,
            allowFallback = allowFallback
        )

    /** Берём под наблюдение треды рабочего дерева сразу, не дожидаясь открытия файла в редакторе. */
    private fun trackWorkingTreeThreads(threads: List<ReviewThread>) {
        val ids = threads.mapTo(HashSet()) { it.id }
        markers.entries.removeIf { (id, marker) ->
            (id !in ids).also { if (it) marker.dispose() }
        }
        for (thread in threads) {
            if (thread.side != ReviewSide.NEW || thread.revision != null) continue
            if (markers[thread.id]?.isValid == true) continue
            val file = LocalFileSystem.getInstance().findFileByPath(store.absolutePath(thread)) ?: continue
            if (file.isDirectory || file.fileType.isBinary) continue
            val document = FileDocumentManager.getInstance().getDocument(file) ?: continue
            place(thread, document, workingTree = true, viewRevision = null)
        }
    }

    private fun syncAnchor(thread: ReviewThread): ReviewThread {
        if (!ApplicationManager.getApplication().isDispatchThread) return thread
        // Строки треда на ревизии относятся к содержимому коммита, а не рабочего файла
        if (thread.revision != null) return thread
        val marker = markers[thread.id] ?: return thread
        if (!marker.isValid) return thread
        val document = marker.document
        val start = document.getLineNumber(marker.startOffset) + 1
        val end = (document.getLineNumber(marker.endOffset) + 1).coerceAtLeast(start)
        return if (start == thread.startLine && end == thread.endLine) thread else thread.copy(startLine = start, endLine = end)
    }

    private fun normalize(text: String): String = text.lineSequence().joinToString("\n") { it.trim() }

    companion object {
        fun getInstance(project: Project): AnchorTracker = project.service()
    }
}
