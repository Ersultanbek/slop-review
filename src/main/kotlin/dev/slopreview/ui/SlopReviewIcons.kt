package dev.slopreview.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object SlopReviewIcons {
    @JvmField
    val Ai: Icon = IconLoader.getIcon("/icons/ai.svg", SlopReviewIcons::class.java)

    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/toolWindow.svg", SlopReviewIcons::class.java)

    @JvmField
    val Send: Icon = IconLoader.getIcon("/icons/send.svg", SlopReviewIcons::class.java)
}
