package dev.aireview.model

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Чтение и запись review.json. Файл правит и плагин, и ИИ-агент, поэтому разбор терпим
 * к отсутствующим полям, а формат записи стабилен и читаем.
 */
object ReviewJson {
    const val VERSION = 1

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()

    /** @throws RuntimeException если текст не является корректным JSON нужной формы */
    fun parse(text: String): List<ReviewThread> {
        val root = JsonParser.parseString(text).asJsonObject
        val threads = root.get("threads")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return threads.mapNotNull { parseThread(it) }
    }

    private fun parseThread(element: JsonElement): ReviewThread? {
        if (!element.isJsonObject) return null
        val o = element.asJsonObject
        val id = o.string("id") ?: return null
        val file = o.string("file") ?: return null
        val start = o.int("startLine") ?: 1
        val comments = o.get("comments")?.takeIf { it.isJsonArray }?.asJsonArray.orEmpty()
            .mapIndexedNotNull { index, c -> parseComment(c, "$id-c$index") }
        return ReviewThread(
            id = id,
            file = file,
            side = ReviewSide.fromJson(o.string("side")),
            startLine = start,
            endLine = (o.int("endLine") ?: start).coerceAtLeast(start),
            snippet = o.string("snippet").orEmpty(),
            revision = o.string("revision")?.takeIf { it.isNotBlank() },
            resolved = o.string("status").equals("resolved", ignoreCase = true),
            comments = comments
        )
    }

    private fun parseComment(element: JsonElement, fallbackId: String): ReviewComment? {
        if (!element.isJsonObject) return null
        val o = element.asJsonObject
        val text = o.string("text") ?: return null
        return ReviewComment(
            id = o.string("id") ?: fallbackId,
            author = Author.fromJson(o.string("author")),
            name = o.string("name")?.takeIf { it.isNotBlank() },
            text = text,
            createdAt = o.string("createdAt").orEmpty()
        )
    }

    fun serialize(threads: List<ReviewThread>): String {
        val root = JsonObject()
        root.addProperty("version", VERSION)
        val array = JsonArray()
        for (t in threads) {
            val o = JsonObject()
            o.addProperty("id", t.id)
            o.addProperty("status", if (t.resolved) "resolved" else "open")
            o.addProperty("file", t.file)
            o.addProperty("side", t.side.json)
            o.addProperty("startLine", t.startLine)
            o.addProperty("endLine", t.endLine)
            o.addProperty("revision", t.revision)
            o.addProperty("snippet", t.snippet)
            val comments = JsonArray()
            for (c in t.comments) {
                val co = JsonObject()
                co.addProperty("id", c.id)
                co.addProperty("author", c.author.json)
                if (c.name != null) co.addProperty("name", c.name)
                co.addProperty("createdAt", c.createdAt)
                co.addProperty("text", c.text)
                comments.add(co)
            }
            o.add("comments", comments)
            array.add(o)
        }
        root.add("threads", array)
        return gson.toJson(root) + "\n"
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull()

    private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
}
