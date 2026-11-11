package dev.aireview.ui

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/**
 * Вертикальная раскладка, которая считает высоту детей исходя из доступной ширины.
 * Нужна для текста с переносами: его высота зависит от ширины, а стандартные раскладки
 * спрашивают предпочтительный размер до того, как ширина известна.
 */
class ColumnLayout(private val gap: Int = 0) : LayoutManager {

    private var measuredWidth = -1

    override fun addLayoutComponent(name: String?, comp: Component?) = Unit
    override fun removeLayoutComponent(comp: Component?) = Unit

    /**
     * Минимальная ширина намеренно мала: иначе в узкой панели diff тред не сжимается до видимой области,
     * редактор получает горизонтальную прокрутку и тред обрезается по краю.
     */
    override fun minimumLayoutSize(parent: Container): Dimension =
        Dimension(JBUI.scale(MIN_WIDTH), preferredLayoutSize(parent).height)

    override fun preferredLayoutSize(parent: Container): Dimension {
        val width = if (parent.width > 0) parent.width else JBUI.scale(DEFAULT_WIDTH)
        val insets = parent.insets
        val inner = (width - insets.left - insets.right).coerceAtLeast(1)
        measuredWidth = inner
        var height = insets.top + insets.bottom
        var visible = 0
        for (child in parent.components) {
            if (!child.isVisible) continue
            if (visible++ > 0) height += gap
            height += heightFor(child, inner)
        }
        return Dimension(width, height)
    }

    override fun layoutContainer(parent: Container) {
        val insets = parent.insets
        val inner = (parent.width - insets.left - insets.right).coerceAtLeast(1)
        var y = insets.top
        for (child in parent.components) {
            if (!child.isVisible) continue
            val height = heightFor(child, inner)
            child.setBounds(insets.left, y, inner, height)
            y += height + gap
        }
        // Высота была посчитана для другой ширины — просим родителя пересчитать размер
        if (inner != measuredWidth) {
            measuredWidth = inner
            SwingUtilities.invokeLater {
                parent.revalidate()
                parent.repaint()
            }
        }
    }

    private fun heightFor(child: Component, width: Int): Int {
        if (child is JTextComponent) {
            // Текстовый компонент считает переносы по текущему размеру, поэтому сначала задаём ширину
            child.setSize(width, Short.MAX_VALUE.toInt())
        } else if (child.width != width) {
            child.setSize(width, child.height.coerceAtLeast(1))
        }
        return child.preferredSize.height
    }

    companion object {
        /** Совпадает с максимальной шириной инлая ревью в платформе. */
        private const val DEFAULT_WIDTH = 718
        private const val MIN_WIDTH = 160
    }
}
