package dev.aireview.editor

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.DiffVcsDataKeys
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.contents.FileContent
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.simple.SimpleOnesideDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.Side
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import dev.aireview.model.ReviewSide

/** Встраивает ревью в стандартный diff IDEA: локальные изменения, коммиты из Git Log, сравнение веток. */
class AiReviewDiffExtension : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val project = context.project ?: return
        if (viewer !is DiffViewerBase) return

        val contents: Map<Side, DiffContent> = when (viewer) {
            is TwosideTextDiffViewer -> mapOf(Side.LEFT to viewer.getContent(Side.LEFT), Side.RIGHT to viewer.getContent(Side.RIGHT))
            is UnifiedDiffViewer -> mapOf(Side.LEFT to viewer.getContent(Side.LEFT), Side.RIGHT to viewer.getContent(Side.RIGHT))
            is SimpleOnesideDiffViewer -> mapOf(viewer.side to viewer.content)
            else -> return
        }

        val sides = HashMap<ReviewSide, SideInfo>()
        for ((side, content) in contents) {
            val info = describe(content, side, request) ?: continue
            sides[if (side == Side.LEFT) ReviewSide.OLD else ReviewSide.NEW] = info
        }
        if (sides.isEmpty()) return

        ReviewUiController.getInstance(project).installInDiff(viewer, sides)
    }

    /** Определяет, какой файл и какая его версия показаны на стороне diff. */
    private fun describe(content: DiffContent, side: Side, request: DiffRequest): SideInfo? {
        val document = (content as? DocumentContent)?.document ?: return null

        val localFile = (content as? FileContent)?.file?.takeIf { it.isInLocalFileSystem }
        if (localFile != null) {
            return SideInfo(localFile.path, revision = null, document = document, workingTree = true)
        }

        content.getUserData(DiffVcsDataKeys.REVISION_INFO)?.let { info ->
            return SideInfo(info.first.path, info.second.asString(), document, workingTree = false)
        }

        val change = request.getUserData(ChangeDiffRequestProducer.CHANGE_KEY) ?: return null
        val revision = (if (side == Side.LEFT) change.beforeRevision else change.afterRevision) ?: return null
        val revisionId = if (revision is CurrentContentRevision) null else revision.revisionNumber.asString()
        return SideInfo(revision.file.path, revisionId, document, workingTree = false)
    }
}
