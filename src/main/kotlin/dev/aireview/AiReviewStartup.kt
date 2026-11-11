package dev.aireview

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.aireview.anchor.AnchorTracker
import dev.aireview.selfcheck.SelfCheck

/** Поднимает хранилище и слежение за кодом сразу при открытии проекта, чтобы не пропустить правки агента. */
class AiReviewStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        AnchorTracker.getInstance(project)
        if (SelfCheck.isEnabled()) SelfCheck.run(project)
    }
}
