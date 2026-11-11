package dev.aireview.ui

/**
 * Небольшой рендер markdown в HTML для комментариев: блоки кода, `код`, **жирный**, списки.
 * В отличие от полного markdown сохраняет переносы строк — комментарии пишут как обычный текст.
 */
object MiniMarkdown {

    private val inlineCode = Regex("`([^`]+)`")
    private val bold = Regex("\\*\\*(.+?)\\*\\*")
    private val bullet = Regex("^\\s*[-*]\\s+")

    fun toHtml(text: String): String {
        val out = StringBuilder()
        val paragraph = ArrayList<String>()
        val code = ArrayList<String>()
        var inCode = false

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            out.append("<div>").append(paragraph.joinToString("<br>")).append("</div>")
            paragraph.clear()
        }

        for (line in text.trim('\n').split('\n')) {
            if (line.trimStart().startsWith("```")) {
                if (inCode) {
                    out.append(codeBlock(code))
                    code.clear()
                } else {
                    flushParagraph()
                }
                inCode = !inCode
                continue
            }
            if (inCode) code += line else paragraph += inline(line)
        }
        if (inCode && code.isNotEmpty()) out.append(codeBlock(code))
        flushParagraph()
        return out.toString()
    }

    /**
     * Блок кода — не <pre>: Swing не переносит <pre>, и длинная строка обрезалась бы по краю треда.
     * Отступы сохраняем неразрывными пробелами, остальное переносится по ширине.
     */
    private fun codeBlock(lines: List<String>): String =
        lines.joinToString("<br>", "<div class=\"code\">", "</div>") { line ->
            val indent = line.length - line.trimStart().length
            "&nbsp;".repeat(indent) + escape(line.substring(indent))
        }

    private fun inline(line: String): String {
        var html = escape(line)
        html = bullet.replace(html, "&bull;&nbsp;")
        html = inlineCode.replace(html) { "<code>${it.groupValues[1]}</code>" }
        html = bold.replace(html) { "<b>${it.groupValues[1]}</b>" }
        return html
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
