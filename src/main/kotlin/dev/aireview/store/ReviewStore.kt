package dev.aireview.store

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.aireview.model.Author
import dev.aireview.model.ReviewComment
import dev.aireview.model.ReviewJson
import dev.aireview.model.ReviewSide
import dev.aireview.model.ReviewThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * Единственный источник правды о ревью: файл `.ai-review/review.json` в корне проекта.
 * Файл правит и плагин, и ИИ-агент, поэтому каждая мутация перечитывает диск,
 * а внешние изменения подхватываются опросом.
 */
@Service(Service.Level.PROJECT)
class ReviewStore(private val project: Project, cs: CoroutineScope) {

    private val baseDir: Path? = project.basePath?.let { Path.of(it) }
    val reviewFile: Path? = baseDir?.resolve(REVIEW_DIR)?.resolve(REVIEW_FILE)

    private val _threads = MutableStateFlow<List<ReviewThread>>(emptyList())
    val threads: StateFlow<List<ReviewThread>> = _threads.asStateFlow()

    /** Обновляет строки тредов по живым маркерам перед записью. Устанавливается AnchorTracker. */
    @Volatile
    var anchorSync: ((ReviewThread) -> ReviewThread)? = null

    private val lock = Any()
    private var stamp: Stamp? = null
    private var diskBroken = false

    init {
        synchronized(lock) { reloadIfChanged() }
        cs.launch(Dispatchers.IO) {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                synchronized(lock) { reloadIfChanged() }
            }
        }
    }

    // ---------- пути ----------

    fun relativePath(absolutePath: String): String {
        val base = baseDir ?: return absolutePath
        val path = Path.of(absolutePath)
        return if (path.startsWith(base)) base.relativize(path).joinToString("/") else absolutePath
    }

    fun absolutePath(thread: ReviewThread): String {
        val path = Path.of(thread.file)
        val base = baseDir
        return if (path.isAbsolute || base == null) path.toString() else base.resolve(path).normalize().toString()
    }

    // ---------- мутации ----------

    fun addThread(
        absolutePath: String,
        side: ReviewSide,
        startLine: Int,
        endLine: Int,
        snippet: String,
        revision: String?,
        text: String
    ) {
        val thread = ReviewThread(
            id = newId("t"),
            file = relativePath(absolutePath),
            side = side,
            startLine = startLine + 1,
            endLine = endLine + 1,
            snippet = snippet,
            revision = revision,
            resolved = false,
            comments = listOf(userComment(text))
        )
        update { it + thread }
    }

    /** Ответ пользователя заново открывает решённый тред: вопрос снова адресован ИИ. */
    fun reply(threadId: String, text: String) = updateThread(threadId) {
        it.copy(resolved = false, comments = it.comments + userComment(text))
    }

    fun editComment(threadId: String, commentId: String, text: String) = updateThread(threadId) { thread ->
        thread.copy(comments = thread.comments.map { if (it.id == commentId) it.copy(text = text) else it })
    }

    fun deleteComment(threadId: String, commentId: String) = update { threads ->
        threads.mapNotNull { thread ->
            if (thread.id != threadId) return@mapNotNull thread
            val remaining = thread.comments.filterNot { it.id == commentId }
            if (remaining.isEmpty()) null else thread.copy(comments = remaining)
        }
    }

    fun setResolved(threadId: String, resolved: Boolean) = updateThread(threadId) { it.copy(resolved = resolved) }

    fun deleteThread(threadId: String) = update { threads -> threads.filterNot { it.id == threadId } }

    fun deleteResolved() = update { threads -> threads.filterNot { it.resolved } }

    fun deleteAll() = update { emptyList() }

    /** Записывает актуальные позиции тредов (после правок кода) без других изменений. */
    fun flushAnchors() = update { it }

    private fun updateThread(threadId: String, transform: (ReviewThread) -> ReviewThread) = update { threads ->
        threads.map { if (it.id == threadId) transform(it) else it }
    }

    private fun update(transform: (List<ReviewThread>) -> List<ReviewThread>) {
        synchronized(lock) {
            reloadIfChanged()
            val sync = anchorSync
            val current = _threads.value
            val synced = if (sync != null) current.map(sync) else current
            val next = transform(synced)
            if (next == current) return
            write(next)
            _threads.value = next
        }
    }

    // ---------- диск ----------

    private fun reloadIfChanged() {
        val file = reviewFile ?: return
        val newStamp = Stamp.of(file)
        if (newStamp == stamp) return
        if (newStamp == null) {
            stamp = null
            diskBroken = false
            _threads.value = emptyList()
            return
        }
        try {
            _threads.value = ReviewJson.parse(Files.readString(file))
            stamp = newStamp
            diskBroken = false
        } catch (e: Exception) {
            // Агент мог записать файл не до конца или испортить JSON — оставляем прежнее состояние и пробуем снова
            diskBroken = true
            LOG.debug("Cannot parse $file", e)
        }
    }

    private fun write(threads: List<ReviewThread>) {
        val file = reviewFile ?: return
        try {
            Files.createDirectories(file.parent)
            if (diskBroken && Files.exists(file)) {
                Files.copy(file, file.resolveSibling("$REVIEW_FILE.broken"), StandardCopyOption.REPLACE_EXISTING)
            }
            val tmp = file.resolveSibling("$REVIEW_FILE.tmp")
            Files.writeString(tmp, ReviewJson.serialize(threads))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            stamp = Stamp.of(file)
            diskBroken = false
            excludeFromGit()
        } catch (e: Exception) {
            LOG.warn("Cannot write $file", e)
        }
    }

    /** Ревью — локальный артефакт: прячем его от git через .git/info/exclude, не трогая .gitignore проекта. */
    private fun excludeFromGit() {
        val info = baseDir?.resolve(".git")?.resolve("info") ?: return
        try {
            if (!Files.isDirectory(info.parent)) return
            val exclude = info.resolve("exclude")
            val existing = if (Files.exists(exclude)) Files.readString(exclude) else ""
            if (existing.lineSequence().any { it.trim() == "$REVIEW_DIR/" }) return
            Files.createDirectories(info)
            val separator = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"
            Files.writeString(exclude, "$existing$separator$REVIEW_DIR/\n")
        } catch (e: Exception) {
            LOG.debug("Cannot update git exclude", e)
        }
    }

    private fun userComment(text: String) = ReviewComment(
        id = newId("c"),
        author = Author.USER,
        name = null,
        text = text.trim(),
        createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
    )

    private fun newId(prefix: String): String =
        prefix + Random.nextLong(0, 36L * 36 * 36 * 36 * 36 * 36).toString(36).padStart(6, '0')

    private data class Stamp(val modified: Long, val size: Long) {
        companion object {
            fun of(file: Path): Stamp? = try {
                if (Files.isRegularFile(file)) Stamp(Files.getLastModifiedTime(file).toMillis(), Files.size(file)) else null
            } catch (e: Exception) {
                null
            }
        }
    }

    companion object {
        const val REVIEW_DIR = ".ai-review"
        const val REVIEW_FILE = "review.json"
        private const val POLL_INTERVAL_MS = 1000L
        private val LOG = logger<ReviewStore>()

        fun getInstance(project: Project): ReviewStore = project.service()
    }
}
