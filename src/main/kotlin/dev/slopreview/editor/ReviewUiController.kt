package dev.slopreview.editor

import com.intellij.collaboration.ui.codereview.diff.viewer.showCodeReview
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorGutterControlsRenderer
import com.intellij.collaboration.ui.codereview.editor.renderInlays
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.simple.SimpleOnesideDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.Side
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.cancelOnDispose
import com.intellij.util.containers.HashingStrategy
import dev.slopreview.model.ReviewSide
import dev.slopreview.ui.ReviewComponents.createRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Подключает ревью к редакторам: к сторонам diff и к обычным редакторам файлов. */
@Service(Service.Level.PROJECT)
class ReviewUiController(private val project: Project, private val cs: CoroutineScope) {

    /** Diff: «+» в гаттере при наведении, треды под кодом на обеих сторонах. */
    fun installInDiff(viewer: DiffViewerBase, sides: Map<ReviewSide, SideInfo>) {
        cs.launch(Dispatchers.EDT) {
            viewer.showCodeReview<ReviewEditorModel, ReviewInlayModel>(
                { locationToLine, lineToLocation ->
                    // В двустороннем diff модель создаётся отдельно для каждого редактора — определяем его по стороне
                    val ownSide = lineToLocation(0)?.first
                    val editor = when (viewer) {
                        is TwosideTextDiffViewer -> viewer.getEditor(ownSide ?: Side.RIGHT)
                        is UnifiedDiffViewer -> viewer.editor
                        is SimpleOnesideDiffViewer -> viewer.editor
                        else -> error("Unsupported diff viewer: ${viewer.javaClass.name}")
                    }
                    val editorSides = if (viewer is TwosideTextDiffViewer && ownSide != null) {
                        sides.filterKeys { it == ownSide.toReviewSide() }
                    } else {
                        sides
                    }
                    ReviewEditorModel(
                        cs = this,
                        project = project,
                        editor = editor,
                        sides = editorSides,
                        locationToLine = { side, line -> locationToLine(side.toDiffSide() to line) },
                        lineToLocation = { line -> lineToLocation(line)?.let { it.first.toReviewSide() to it.second } },
                        showAddOnHover = true
                    )
                },
                MODEL_KEY
            ) { model -> createRenderer(project, model) }
        }.cancelOnDispose(viewer)
    }

    /** Обычный редактор: показываем треды рабочего файла; новый комментарий — через действие, без «+» на каждой строке. */
    fun installInEditor(editor: Editor) {
        if (editor !is EditorEx || editor.editorKind != EditorKind.MAIN_EDITOR) return
        val file = editor.virtualFile ?: return
        if (!file.isInLocalFileSystem) return
        attachToEditor(editor, file)
    }

    internal fun attachToEditor(editor: EditorEx, file: VirtualFile) {
        val disposable = Disposer.newDisposable("Slop Review in editor")
        EditorUtil.disposeWithEditor(editor, disposable)

        cs.launch(Dispatchers.EDT) {
            coroutineScope {
                val model = ReviewEditorModel(
                    cs = this,
                    project = project,
                    editor = editor,
                    sides = mapOf(ReviewSide.NEW to SideInfo(file.path, null, editor.document, workingTree = true)),
                    locationToLine = { side, line -> line.takeIf { side == ReviewSide.NEW } },
                    lineToLocation = { line -> ReviewSide.NEW to line },
                    showAddOnHover = false,
                    threadsVisible = ReviewViewSettings.getInstance(project).showInEditor
                )
                launch(start = CoroutineStart.UNDISPATCHED) {
                    CodeReviewEditorGutterControlsRenderer.render(model, editor)
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    editor.renderInlays(model.inlays, INLAY_KEY_STRATEGY) { createRenderer(project, it) }
                }
                editor.putUserData(MODEL_KEY, model)
                try {
                    awaitCancellation()
                } finally {
                    editor.putUserData(MODEL_KEY, null)
                }
            }
        }.cancelOnDispose(disposable)
    }

    companion object {
        /** Модель ревью редактора — по ней действие «Add Slop Review Comment» находит, куда добавить комментарий. */
        val MODEL_KEY: Key<ReviewEditorModel> = Key.create("SlopReview.EditorModel")

        private val INLAY_KEY_STRATEGY = object : HashingStrategy<ReviewInlayModel> {
            override fun hashCode(model: ReviewInlayModel?): Int = model?.key.hashCode()
            override fun equals(a: ReviewInlayModel?, b: ReviewInlayModel?): Boolean = a?.key == b?.key
        }

        fun getInstance(project: Project): ReviewUiController = project.service()

        private fun Side.toReviewSide(): ReviewSide = if (this == Side.LEFT) ReviewSide.OLD else ReviewSide.NEW
        private fun ReviewSide.toDiffSide(): Side = if (this == ReviewSide.OLD) Side.LEFT else Side.RIGHT
    }
}
