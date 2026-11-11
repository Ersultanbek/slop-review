package dev.slopreview.editor

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Настройки показа ревью в обычном редакторе (в diff треды видны всегда). */
@Service(Service.Level.PROJECT)
class ReviewViewSettings(private val project: Project) {

    private val _showInEditor = MutableStateFlow(PropertiesComponent.getInstance(project).getBoolean(SHOW_IN_EDITOR, true))

    /** false — треды скрыты, на строке остаётся значок в гаттере; клик по нему открывает тред этой строки. */
    val showInEditor: StateFlow<Boolean> = _showInEditor

    fun setShowInEditor(show: Boolean) {
        PropertiesComponent.getInstance(project).setValue(SHOW_IN_EDITOR, show, true)
        _showInEditor.value = show
    }

    companion object {
        private const val SHOW_IN_EDITOR = "slopreview.showCommentsInEditor"

        fun getInstance(project: Project): ReviewViewSettings = project.service()
    }
}
