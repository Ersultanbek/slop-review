package dev.slopreview.anchor

import kotlin.math.abs

/** Положение треда в конкретном тексте. Строки 0-based, включительно. */
data class Placement(val startLine: Int, val endLine: Int, val outdated: Boolean)

/**
 * Находит в тексте код, к которому был оставлен комментарий.
 * Сначала проверяет сохранённые строки, затем ищет ближайшее вхождение фрагмента;
 * если код изменился до неузнаваемости — по желанию возвращает исходные строки с пометкой outdated.
 */
object SnippetLocator {

    fun locate(
        lines: List<String>,
        snippet: String,
        hintStartLine: Int,
        hintEndLine: Int,
        allowFallback: Boolean
    ): Placement? {
        if (lines.isEmpty()) return null
        val needle = snippet.split('\n').map { it.trim() }
        val size = needle.size
        val hay = lines.map { it.trim() }

        if (matchesAt(hay, needle, hintStartLine)) {
            return Placement(hintStartLine, hintStartLine + size - 1, outdated = false)
        }

        // Фрагмент из одних пустых строк нельзя найти по содержимому — только по позиции
        if (needle.any { it.isNotEmpty() }) {
            var best = -1
            for (i in 0..hay.size - size) {
                if (matchesAt(hay, needle, i) && (best < 0 || abs(i - hintStartLine) < abs(best - hintStartLine))) {
                    best = i
                }
            }
            if (best >= 0) return Placement(best, best + size - 1, outdated = false)
        }

        if (!allowFallback) return null
        val last = lines.size - 1
        val start = hintStartLine.coerceIn(0, last)
        val end = hintEndLine.coerceIn(start, last)
        return Placement(start, end, outdated = true)
    }

    private fun matchesAt(hay: List<String>, needle: List<String>, index: Int): Boolean {
        if (index < 0 || index + needle.size > hay.size) return false
        for (i in needle.indices) {
            if (hay[index + i] != needle[i]) return false
        }
        return true
    }
}
