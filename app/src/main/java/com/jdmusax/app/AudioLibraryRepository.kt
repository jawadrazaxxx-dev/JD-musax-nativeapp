package com.jdmusax.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class AudioLibraryRepository {
    private val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

    suspend fun search(query: String, section: String): List<Track> = withContext(Dispatchers.IO) {
        if (section == "General Audiobooks") searchLibriVox(query.trim())
        else listOf("ur", "en", "ar").map { language ->
            async { searchIslamHouse(query.trim(), language, section) }
        }.awaitAll().flatten().distinctBy { it.id }.take(60)
    }

    suspend fun featured(section: String): List<Track> = search("", section)

    private suspend fun searchIslamHouse(query: String, language: String, section: String): List<Track> {
        val types = listOf("audios", "books")
        val itemLists = coroutineScope {
            types.map { type ->
                async {
                val root = getJson("$ISLAMHOUSE_BASE/main/$type/$language/$language/1/40/json")
                val data = when (root) {
                    is JSONArray -> root
                    is JSONObject -> root.optJSONArray("data") ?: JSONArray()
                    else -> JSONArray()
                }
                (0 until data.length()).mapNotNull { data.optJSONObject(it) }
                    .filter { item ->
                        val text = (item.optString("title") + " " + item.optString("description")).lowercase()
                        query.isBlank() || query.lowercase() in text
                    }.map { it to type }
                }
            }.awaitAll().flatten()
        }

        return coroutineScope {
            itemLists.take(12).map { (item, type) -> async {
                val id = item.optInt("id")
                val detail = runCatching { getJson("$ISLAMHOUSE_BASE/main/get-item/$id/$language/json") as? JSONObject }.getOrNull()
                val attachments = detail?.optJSONArray("attachments") ?: JSONArray()
                val audio = (0 until attachments.length()).mapNotNull { attachments.optJSONObject(it) }
                    .firstOrNull { attachment ->
                        val ext = attachment.optString("extension_type").lowercase()
                        val href = attachment.optString("url")
                        (ext in setOf("mp3", "m4a", "ogg", "wav", "audio") ||
                            href.substringBefore("?").lowercase().endsWith(".mp3")) && href.startsWith("https://")
                    }
                val publisher = detail?.optJSONArray("prepared_by")?.optJSONObject(0)?.optString("title")
                    .orEmpty().ifBlank { "IslamHouse" }
                val tradition = classifyTradition(item, detail)
                Track(
                    id = "islamhouse:$id", title = item.optString("title", "Islamic audio"),
                    artist = publisher, artwork = item.optString("image").takeIf { it.startsWith("https://") },
                    streamUrl = audio?.optString("url")?.takeIf { it.startsWith("https://") },
                    isDownloadable = audio?.optString("url")?.startsWith("https://") == true,
                    language = language,
                    contentType = if (type == "books") "Islamic audiobook" else "Islamic lecture",
                    tradition = tradition
                )
            } }.awaitAll()
        }.filter { it.streamUrl != null && (section == "All Islamic" || it.tradition == section) }
    }

    private suspend fun searchLibriVox(query: String): List<Track> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://librivox.org/api/feed/audiobooks/?format=json&extended=1&coverart=1&limit=30" +
            if (query.isBlank()) "" else "&title=$encoded"
        val root = getJson(url) as? JSONObject ?: return emptyList()
        val books = root.optJSONArray("books") ?: root.optJSONArray("audiobooks") ?: JSONArray()
        return buildList {
            for (i in 0 until books.length()) {
                val book = books.optJSONObject(i) ?: continue
                val sections = book.optJSONArray("sections") ?: JSONArray()
                for (chapter in 0 until sections.length()) {
                    val section = sections.optJSONObject(chapter) ?: continue
                    val audioUrl = section.optString("listen_url").takeIf { it.startsWith("https://") } ?: continue
                    add(Track(
                        id = "librivox:${book.optString("id")}:$chapter",
                        title = book.optString("title", "Audiobook") + " — " + section.optString("title", "Chapter ${chapter + 1}"),
                        artist = "LibriVox", artwork = book.optString("coverart_thumbnail").takeIf { it.startsWith("https://") },
                        streamUrl = audioUrl, isDownloadable = true,
                        language = book.optString("language", "English"), contentType = "Audiobook", tradition = "General"
                    ))
                }
            }
        }
    }

    private fun classifyTradition(item: JSONObject, detail: JSONObject?): String {
        // Sect placement is based only on explicit category/publisher metadata.
        // Titles and descriptive prose are deliberately excluded to avoid guessing.
        val metadata = buildString {
            for (array in listOf(item.optJSONArray("categories"), detail?.optJSONArray("categories"),
                item.optJSONArray("prepared_by"), detail?.optJSONArray("prepared_by"))) {
                if (array != null) for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    append(entry.optString("title")).append(' ')
                    append(entry.optString("name")).append(' ')
                    append(entry.optString("type")).append(' ')
                }
            }
        }.lowercase()
        return when {
            listOf("shia", "shi'a", "ja'fari", "jafari", "ithna ashari").any(metadata::contains) -> "Shia Library"
            listOf("sunni", "ahl al-sunnah", "ahlus sunnah").any(metadata::contains) -> "Sunni Library"
            listOf("comparative islam", "inter-sect", "sectarian comparison").any(metadata::contains) -> "Comparative Islamic Studies"
            listOf("ibadi", "ismaili", "sufi").any(metadata::contains) -> "Other Islamic Perspectives"
            else -> "Unclassified / General Islamic"
        }
    }

    private fun getJson(url: String): Any {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Audio provider returned HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            return when (body.trimStart().firstOrNull()) {
                '[' -> JSONArray(body)
                '{' -> JSONObject(body)
                else -> error("Audio provider returned an unexpected response.")
            }
        }
    }

    private companion object {
        const val ISLAMHOUSE_BASE = "https://api3.islamhouse.com/v3/paV29H2gm56kvLPy"
    }
}
