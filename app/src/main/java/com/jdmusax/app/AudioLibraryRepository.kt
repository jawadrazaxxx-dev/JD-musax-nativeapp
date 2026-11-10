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
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

// Remote catalogue adapters for Islamic audio and public-domain audiobooks.
class AudioLibraryRepository {
    private val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

    suspend fun search(query: String, section: String): List<Track> = withContext(Dispatchers.IO) {
        if (section in AUDIO_SOURCE_CATEGORIES) return@withContext sourceDirectory(query, section)
        if (section == "General Audiobooks") {
            val providers = coroutineScope {
                listOf(
                    async { searchLibriVox(query.trim()) },
                    async { searchInternetArchive(query.trim()) },
                    async { searchPodcastAudiobooks(query.trim(), loyalBooksOnly = true) },
                    async { searchPodcastAudiobooks(query.trim(), loyalBooksOnly = false) }
                ).awaitAll()
            }
            return@withContext providers.flatten().distinctBy { it.id }.take(100)
        }
        val languageOnly = mapOf("Urdu Audio" to "ur", "English Audio" to "en", "Arabic Audio" to "ar")[section]
        val tradition = if (languageOnly != null) "All Islamic" else section
        val languages = languageOnly?.let(::listOf) ?: listOf("ur", "en", "ar")
        languages.map { language ->
            async { searchIslamHouse(query.trim(), language, tradition) }
        }.awaitAll().flatten().distinctBy { it.id }.take(60)
    }

    suspend fun featured(section: String): List<Track> = search("", section)

    private data class AudioSource(val name: String, val description: String, val url: String, val categories: Set<String>, val language: String = "Urdu / English")

    private fun sourceDirectory(query: String, section: String): List<Track> {
        val sources = listOf(
            AudioSource("Shia Audios", "Urdu and English audiobooks, Quran translation and Tafseer", "https://www.shiaaudios.com/audios", setOf("Shia Sources", "Audiobooks", "Quran", "Tafseer")),
            AudioSource("Wilayat Waves", "Urdu Shia audiobooks with book and episode listings", "https://wilayatwaves.com/audiobook/", setOf("Shia Sources", "Audiobooks", "Tafseer")),
            AudioSource("Shia Readers · SoundCloud", "Community audiobooks, lectures and religious audio", "https://soundcloud.com/shiareaders", setOf("Shia Sources", "Audiobooks", "Majalis", "Islamic Podcasts")),
            AudioSource("Shia Readers · YouTube", "Audiobooks, lectures and religious audio series", "https://www.youtube.com/@ShiaReaders", setOf("Shia Sources", "Audiobooks", "Majalis")),
            AudioSource("Internet Archive · Urdu Shia audio", "Searchable archive; item rights and playback vary", "https://archive.org/search?query=urdu+shia+audio", setOf("Shia Sources", "Audiobooks", "Marsiya")),
            AudioSource("Kashana-e-Baseerat", "Urdu Islamic audio literature and educational resources", "https://www.kashanaebaseerat.org/", setOf("Shia Sources", "Audiobooks", "Quran", "Tafseer", "Duas & Ziyarat")),
            AudioSource("Rawda", "Shia latmiyat, nohay, marsiya, Quran, duas and lectures", "https://rawda.app/", setOf("Shia Sources", "Nohay", "Marsiya", "Quran", "Duas & Ziyarat", "Majalis", "Manqabat")),
            AudioSource("Azadari Media", "Nohay, manqabat, marsiya and majlis audio", "https://azadari.media/", setOf("Shia Sources", "Nohay", "Marsiya", "Majalis", "Manqabat")),
            AudioSource("Nauha Diary · Azadari Media", "Nauha and marsiya lyrics and reciter discovery", "https://nauhadiary.com/azadari-media", setOf("Shia Sources", "Nohay", "Marsiya")),
            AudioSource("ShiaViews", "Shia Quran, majalis, lectures and Islamic lessons", "https://www.shiaviews.com/", setOf("Shia Sources", "Quran", "Tafseer", "Majalis")),
            AudioSource("ShiaRise Media", "Podcasts, Quran recitations, duas and latmiyat", "https://shiarise.org/media-explore/", setOf("Shia Sources", "Nohay", "Quran", "Duas & Ziyarat", "Islamic Podcasts")),
            AudioSource("Alwasi TV", "Shia lectures, Quranic commentary and podcasts", "https://alwasi.tv/", setOf("Shia Sources", "Quran", "Tafseer", "Majalis", "Islamic Podcasts")),
            AudioSource("Al Furqan Quran Audio", "Quran recitations and reciter catalogue; API terms apply", "https://alfurqan.online/docs", setOf("Quran"), "Arabic"),
            AudioSource("Quran Foundation Developer API", "Official Quran audio and recitation metadata API; credentials and terms apply", "https://api-docs.quran.foundation/docs/api-reference/", setOf("Quran"), "Arabic"),
            AudioSource("MP3 Quran", "Quran reciters and surah audio catalogue", "https://mp3quran.net/", setOf("Quran"), "Arabic"),
            AudioSource("Shia Multimedia", "Quran audio, translations and Tafseer resources", "https://shiamultimedia.com/tafseer.html", setOf("Quran", "Tafseer"), "Arabic / Urdu"),
            AudioSource("Al Basheer", "Shia community khutbah, lectures, Quran and podcast media", "https://albasheeraz.org/", setOf("Majalis", "Islamic Podcasts", "Quran"))
        )
        return sources.filter { source ->
            (section == "All Audio Sources" || section in source.categories) &&
                (query.isBlank() || (source.name + " " + source.description).contains(query, ignoreCase = true))
        }.distinctBy { it.url }.map { source ->
            Track(id = "source:" + source.name.lowercase().replace(Regex("[^a-z0-9]+"), "-"), title = source.name, artist = source.description, contentType = "External audio source", language = source.language, tradition = if ("Shia Sources" in source.categories) "Shia Library" else "Audio Library", externalUrl = source.url, isDownloadable = false)
        }
    }

    companion object {
        private val AUDIO_SOURCE_CATEGORIES = setOf("Shia Sources", "Audiobooks", "Nohay", "Marsiya", "Quran", "Tafseer", "Majalis", "Duas & Ziyarat", "Manqabat", "Islamic Podcasts")
    }

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
            itemLists.take(8).map { (item, type) -> async {
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
                    id = "islamhouse:$type:$id", title = item.optString("title", "Islamic audio"),
                    artist = "$publisher · IslamHouse", artwork = item.optString("image").takeIf { it.startsWith("https://") },
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
                if (size >= 100) break
                val book = books.optJSONObject(i) ?: continue
                val sections = book.optJSONArray("sections") ?: JSONArray()
                for (chapter in 0 until sections.length()) {
                    if (size >= 100) break
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

    /**
     * Searches Internet Archive audio. Only items with an explicit
     * public-domain or Creative Commons reuse licence are surfaced.
     */
    private suspend fun searchInternetArchive(query: String): List<Track> {
        val baseQuery = "mediatype:audio AND (collection:audio_bookspoetry OR collection:opensource_audio)"
        val fullQuery = if (query.isBlank()) baseQuery else baseQuery + " AND (title:\"" + query + "\" OR description:\"" + query + "\" OR creator:\"" + query + "\" OR subject:\"" + query + "\")"
        val encoded = URLEncoder.encode(fullQuery, "UTF-8")
        val searchUrl = "https://archive.org/advancedsearch.php?q=" + encoded + "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=description&fl[]=language&fl[]=subject&fl[]=licenseurl&fl[]=rights&rows=30&page=1&output=json"
        val root = getJson(searchUrl) as? JSONObject ?: return emptyList()
        val docs = root.optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
        return coroutineScope {
            (0 until docs.length()).mapNotNull { docs.optJSONObject(it) }.take(20).map { item ->
                async {
                    val identifier = item.optString("identifier")
                    if (identifier.isBlank()) return@async null
                    val metadataRoot = runCatching { getJson("https://archive.org/metadata/" + identifier) as? JSONObject }.getOrNull() ?: return@async null
                    val metadata = metadataRoot.optJSONObject("metadata") ?: JSONObject()
                    val license = (item.optString("licenseurl") + " " + item.optString("rights") + " " + metadata.optString("licenseurl") + " " + metadata.optString("rights")).lowercase()
                    val publicDomain = license.contains("publicdomain") || license.contains("public domain") || license.contains("creativecommons.org/publicdomain/zero") || license.contains("creativecommons.org/licenses/by/") || license.contains("creativecommons.org/licenses/by-sa/")
                    if (!publicDomain) return@async null
                    val files = metadataRoot.optJSONArray("files") ?: JSONArray()
                    val audioFile = (0 until files.length()).mapNotNull { files.optJSONObject(it) }.firstOrNull { file ->
                        val name = file.optString("name").lowercase()
                        val format = file.optString("format").lowercase()
                        name.endsWith(".mp3") && !name.contains("sample") && (format.contains("mp3") || format.contains("mpeg"))
                    } ?: return@async null
                    val fileName = audioFile.optString("name")
                    val creator = item.optString("creator").ifBlank { "Unknown creator" }
                    val language = item.optString("language").ifBlank { "Multilingual / unspecified" }
                    val subject = item.optString("subject").take(100)
                    val licenseLabel = when { license.contains("publicdomain") || license.contains("public domain") -> "Public Domain"; license.contains("by-sa") -> "CC BY-SA"; else -> "CC BY" }
                    val encodedFile = fileName.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                    Track(id = "archive:" + identifier + ":" + fileName, title = item.optString("title", "Open audiobook"), artist = creator + " · Internet Archive · " + licenseLabel, artwork = "https://archive.org/services/img/" + identifier, streamUrl = "https://archive.org/download/" + identifier + "/" + encodedFile, isDownloadable = true, language = language, contentType = if (subject.isBlank()) "Open audiobook" else "Audiobook · " + subject, tradition = "General")
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * Loyal Books publishes one RSS podcast feed per audiobook. Apple's public
     * podcast directory is used only for discovery; playback stays on the
     * publisher's own RSS enclosure URL.
     */
    private suspend fun searchPodcastAudiobooks(query: String, loyalBooksOnly: Boolean): List<Track> {
        val terms = if (query.isNotBlank()) listOf(query + " audiobook", query + " audio book")
            else if (loyalBooksOnly) listOf("Loyal Books audiobook", "loyalbooks classic audiobook")
            else listOf("public domain audiobook", "children stories audio", "audio drama stories")
        val found = coroutineScope {
            terms.map { term -> async {
                val encoded = URLEncoder.encode(term, "UTF-8")
                val url = "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=25&term=" + encoded
                val root = runCatching { getJson(url) as? JSONObject }.getOrNull() ?: JSONObject()
                val results = root.optJSONArray("results") ?: JSONArray()
                (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            } }.awaitAll().flatten()
        }.distinctBy { it.optString("feedUrl") }.filter { item ->
            val feed = item.optString("feedUrl").lowercase()
            val title = item.optString("collectionName").lowercase()
            val description = item.optString("artistName").lowercase() + " " + item.optString("primaryGenreName").lowercase()
            if (loyalBooksOnly) feed.contains("loyalbooks.com")
            else !feed.contains("loyalbooks.com") && (title.contains("audiobook") || title.contains("audio book") || title.contains("story") || title.contains("audio drama") || title.contains("stories") || title.contains("bedtime") || title.contains("children") || title.contains("fiction") || title.contains("novel") || description.contains("audiobook"))
        }.take(10)
        return coroutineScope {
            found.map { feed -> async {
                val feedUrl = feed.optString("feedUrl")
                val xml = runCatching { getText(feedUrl) }.getOrNull() ?: return@async emptyList<Track>()
                runCatching { parsePodcastFeed(xml, feed).take(30) }.getOrDefault(emptyList())
            } }.awaitAll().flatten()
        }
    }

    private fun parsePodcastFeed(xml: String, feed: JSONObject): List<Track> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())
        val items = mutableListOf<Track>()
        var event = parser.eventType
        var inItem = false
        var title = ""
        var author = feed.optString("artistName", "Podcast publisher")
        var enclosure = ""
        var enclosureType = ""
        var artwork = feed.optString("artworkUrl600").ifBlank { feed.optString("artworkUrl100") }
        var description = ""
        var language = ""
        while (event != XmlPullParser.END_DOCUMENT && items.size < 30) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "item" -> { inItem = true; title = ""; enclosure = ""; enclosureType = ""; description = "" }
                    "title" -> if (inItem) title = parser.nextText().trim()
                    "author", "itunes:author" -> if (inItem) author = parser.nextText().trim()
                    "description", "summary", "itunes:summary" -> if (inItem) description = parser.nextText().take(300).trim()
                    "enclosure" -> if (inItem) { enclosure = parser.getAttributeValue(null, "url").orEmpty(); enclosureType = parser.getAttributeValue(null, "type").orEmpty().lowercase() }
                    "image" -> parser.getAttributeValue(null, "href")?.let { artwork = it }
                    "language" -> if (!inItem) language = parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> if (parser.name.equals("item", true)) {
                    if (inItem && title.isNotBlank() && enclosure.startsWith("https://") &&
                        ((enclosure.contains(".mp3", true) || enclosure.contains(".m4a", true) || enclosure.contains(".m4b", true) || enclosure.contains(".aac", true)) || enclosureType.startsWith("audio/"))) {
                        val source = if (feed.optString("feedUrl").contains("loyalbooks.com", true)) "Loyal Books" else "Podcast audiobook"
                        items.add(Track(id = "rss:" + feed.optString("feedUrl") + ":" + enclosure, title = title, artist = author + " · " + source, artwork = artwork.takeIf { it.startsWith("https://") }, streamUrl = enclosure, isDownloadable = false, language = language.ifBlank { "Unspecified" }, contentType = if (source == "Loyal Books") "Audiobook" else "Audiobook / serialized audio", tradition = "General"))
                    }
                    inItem = false
                }
            }
            event = parser.next()
        }
        return items
    }

    private fun getText(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/rss+xml, application/xml, text/xml").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Audio feed returned HTTP " + response.code)
            return response.body?.string().orEmpty()
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
            listOf("ibadi", "ismaili", "zaydi", "zaidi").any(metadata::contains) -> "Other Islamic Perspectives"
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
