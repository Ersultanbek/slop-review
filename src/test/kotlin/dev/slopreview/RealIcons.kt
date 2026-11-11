package dev.slopreview

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.IconManager
import com.intellij.ui.icons.CoreIconManager

/**
 * В тестах платформа подменяет стандартные иконки пустышками. Для снимков включаем настоящий загрузчик —
 * до старта приложения, пока иконки ещё не созданы. Поэтому каждый класс тестов идёт в своей JVM (forkEvery).
 */
object RealIcons {
    fun activate() {
        try {
            IconManager.activate(CoreIconManager())
        } catch (e: Throwable) {
            println("Real icons are not available: $e")
        }
        IconLoader.activate()
    }
}
