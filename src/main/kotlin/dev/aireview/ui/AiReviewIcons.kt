package dev.aireview.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object AiReviewIcons {
    @JvmField
    val Ai: Icon = IconLoader.getIcon("/icons/ai.svg", AiReviewIcons::class.java)

    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/toolWindow.svg", AiReviewIcons::class.java)

    @JvmField
    val Send: Icon = IconLoader.getIcon("/icons/send.svg", AiReviewIcons::class.java)
}
