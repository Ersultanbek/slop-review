package dev.slopreview

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.application.ApplicationManager
import dev.slopreview.anchor.AnchorTracker
import dev.slopreview.send.ReviewSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.slopreview.selfcheck.DemoRecorder
import dev.slopreview.selfcheck.SelfCheck

/** Поднимает хранилище и слежение за кодом сразу при открытии проекта, чтобы не пропустить правки агента. */
class SlopReviewStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        AnchorTracker.getInstance(project)
        // Команда должна появиться в Claude Code заранее, а не в момент первой отправки
        if (!ApplicationManager.getApplication().isUnitTestMode) withContext(Dispatchers.IO) { ReviewSender.installClaudeCommand() }
        if (SelfCheck.isEnabled()) SelfCheck.run(project)
        if (DemoRecorder.isEnabled()) DemoRecorder.run(project)
    }
}
