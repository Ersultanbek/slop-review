package dev.aireview.model

/** На какой стороне diff оставлен комментарий: новый код (правая сторона / рабочий файл) или старый (удалённый). */
enum class ReviewSide(val json: String) {
    NEW("new"),
    OLD("old");

    companion object {
        fun fromJson(value: String?): ReviewSide = if (value.equals("old", ignoreCase = true)) OLD else NEW
    }
}

enum class Author(val json: String) {
    USER("user"),
    AI("ai");

    companion object {
        fun fromJson(value: String?): Author = if (value.equals("user", ignoreCase = true)) USER else AI
    }
}

data class ReviewComment(
    val id: String,
    val author: Author,
    /** Необязательное отображаемое имя (например, "Claude"). */
    val name: String?,
    val text: String,
    /** ISO-8601, UTC. */
    val createdAt: String
)

data class ReviewThread(
    val id: String,
    /** Путь относительно корня проекта (или абсолютный, если файл вне проекта). */
    val file: String,
    val side: ReviewSide,
    /** Строки 1-based, включительно — в том содержимом, где оставлен комментарий. */
    val startLine: Int,
    val endLine: Int,
    /** Код, к которому относится комментарий, на момент создания. */
    val snippet: String,
    /** Хэш ревизии содержимого или null для рабочего дерева. */
    val revision: String?,
    val resolved: Boolean,
    val comments: List<ReviewComment>
) {
    val lastAuthor: Author? get() = comments.lastOrNull()?.author

    val state: ThreadState
        get() = when {
            resolved -> ThreadState.RESOLVED
            lastAuthor == Author.AI -> ThreadState.AI_REPLIED
            else -> ThreadState.WAITING_FOR_AI
        }
}

enum class ThreadState { WAITING_FOR_AI, AI_REPLIED, RESOLVED }
