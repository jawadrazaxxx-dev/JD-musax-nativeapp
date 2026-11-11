package com.jdmusax.app

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Local-first listening analytics. Playback time is recorded in small deltas so
 * closing the app never loses an entire listening session.
 */
data class ListeningSnapshot(
    val totalMs: Long,
    val authorTotals: Map<String, Long>,
    val categoryTotals: Map<String, Long>,
    val dailyTotals: Map<LocalDate, Long>,
    val trackTotals: Map<String, Long>
)

class ListeningAnalyticsStore(context: Context) {
    private val prefs = context.getSharedPreferences("listening_analytics_v1", Context.MODE_PRIVATE)

    @Synchronized
    fun record(track: Track, listenedMs: Long, at: Long = System.currentTimeMillis()) {
        if (listenedMs <= 0L || track.id.isBlank()) return
        val day = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val author = track.artist.ifBlank { "Unknown author" }
        val category = track.contentType.ifBlank { "Uncategorized" }
        val trackKey = "track:" + track.id
        val authorKey = "author:" + author
        val categoryKey = "category:" + category
        val dayKey = "day:" + day
        synchronized(prefs) {
            val editor = prefs.edit()
            listOf(trackKey, authorKey, categoryKey, dayKey, "total").forEach { key ->
                editor.putLong(key, (prefs.getLong(key, 0L) + listenedMs).coerceAtLeast(0L))
            }
            editor.apply()
        }
    }

    fun snapshot(now: LocalDate = LocalDate.now()): ListeningSnapshot {
        val all = prefs.all
        val authors = all.filterKeys { it.startsWith("author:") }
            .mapKeys { it.key.removePrefix("author:") }
            .mapValues { (it.value as? Long) ?: 0L }
        val categories = all.filterKeys { it.startsWith("category:") }
            .mapKeys { it.key.removePrefix("category:") }
            .mapValues { (it.value as? Long) ?: 0L }
        val tracks = all.filterKeys { it.startsWith("track:") }
            .mapKeys { it.key.removePrefix("track:") }
            .mapValues { (it.value as? Long) ?: 0L }
        val days = (0..6).associate { offset ->
            val date = now.minusDays((6 - offset).toLong())
            date to (prefs.getLong("day:$date", 0L))
        }
        return ListeningSnapshot(prefs.getLong("total", 0L), authors, categories, days, tracks)
    }
}

/** A daily listening goal. Progress is derived from the analytics day bucket. */
data class ListeningChallenge(val goalMinutes: Int = 30, val enabled: Boolean = true)

class ListeningChallengeStore(context: Context) {
    private val prefs = context.getSharedPreferences("listening_challenge_v1", Context.MODE_PRIVATE)
    fun load() = ListeningChallenge(
        goalMinutes = prefs.getInt("goal_minutes", 30).coerceIn(5, 600),
        enabled = prefs.getBoolean("enabled", true)
    )
    fun save(challenge: ListeningChallenge) {
        prefs.edit()
            .putInt("goal_minutes", challenge.goalMinutes.coerceIn(5, 600))
            .putBoolean("enabled", challenge.enabled)
            .apply()
    }
}

/** DownloadManager entries owned by SHIAudio, including their current transfer state. */
data class OfflineAudio(
    val trackId: String,
    val title: String,
    val downloadId: Long,
    val uri: Uri
)

data class OfflineDownload(
    val track: Track,
    val downloadId: Long,
    val status: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val reason: Int,
    val uri: Uri?
) {
    val isComplete: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL
    val isFailed: Boolean get() = status == DownloadManager.STATUS_FAILED
    val isActive: Boolean get() = status == DownloadManager.STATUS_PENDING ||
        status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PAUSED
    val progress: Float get() = if (totalBytes > 0L) {
        (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f
}

class OfflineAudioStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("offline_audio_v1", Context.MODE_PRIVATE)
    private val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    @Synchronized
    fun remember(track: Track, downloadId: Long) {
        val raw = prefs.getString("items", null)
        val array = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
        val updated = JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optLong("downloadId") != downloadId && item.optString("trackId") != track.id) updated.put(item)
        }
        updated.put(JSONObject().apply {
            put("trackId", track.id)
            put("title", track.title)
            put("artist", track.artist)
            put("artwork", track.artwork ?: "")
            put("streamUrl", track.streamUrl ?: "")
            put("language", track.language)
            put("contentType", track.contentType)
            put("tradition", track.tradition)
            put("downloadId", downloadId)
        })
        prefs.edit().putString("items", updated.toString()).apply()
    }

    fun all(): List<OfflineDownload> {
        val array = runCatching { JSONArray(prefs.getString("items", "[]") ?: "[]") }
            .getOrDefault(JSONArray())
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optLong("downloadId")
                if (id <= 0L) continue
                val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: continue
                cursor.use {
                    if (!it.moveToFirst()) return@use
                    val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    val track = Track(
                        id = item.optString("trackId"),
                        title = item.optString("title", "Audio download"),
                        artist = item.optString("artist", "Unknown artist"),
                        artwork = item.optString("artwork").ifBlank { null },
                        streamUrl = item.optString("streamUrl").ifBlank { null },
                        isDownloadable = true,
                        language = item.optString("language"),
                        contentType = item.optString("contentType", "Audiobook"),
                        tradition = item.optString("tradition", "General")
                    )
                    val uri = if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        runCatching { manager.getUriForDownloadedFile(id) }.getOrNull()
                    } else null
                    add(OfflineDownload(track, id, status, downloaded, total, reason, uri))
                }
            }
        }.sortedByDescending { it.downloadId }
    }

    fun completed(): List<OfflineAudio> = all().filter { it.isComplete }
        .mapNotNull { item -> item.uri?.let { OfflineAudio(item.track.id, item.track.title, item.downloadId, it) } }

    fun retry(downloadId: Long): Long? {
        val old = all().firstOrNull { it.downloadId == downloadId && it.isFailed } ?: return null
        val url = old.track.streamUrl?.takeIf { runCatching { Uri.parse(it).scheme.equals("https", true) }.getOrDefault(false) }
            ?: return null
        manager.remove(downloadId)
        val safeName = old.track.title.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "SHIAudio-audio" }
        val extension = url.substringBefore("?").substringAfterLast('.', "mp3").take(5)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(old.track.title)
            .setDescription("Retrying audio download")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "SHIAudio/$safeName.$extension")
        val newId = manager.enqueue(request)
        remember(old.track, newId)
        return newId
    }

    fun remove(downloadId: Long) {
        manager.remove(downloadId)
        val array = runCatching { JSONArray(prefs.getString("items", "[]") ?: "[]") }.getOrDefault(JSONArray())
        val updated = JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optLong("downloadId") != downloadId) updated.put(item)
        }
        prefs.edit().putString("items", updated.toString()).apply()
    }
}

/** Immutable queue operations shared by UI and Media3 controller integration. */
object QueueEditor {
    fun move(items: List<Track>, from: Int, to: Int): List<Track> {
        if (from !in items.indices || to !in items.indices || from == to) return items
        return items.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun remove(items: List<Track>, index: Int): List<Track> =
        if (index in items.indices) items.toMutableList().apply { removeAt(index) } else items

    fun append(items: List<Track>, additions: List<Track>): List<Track> =
        (items + additions).distinctBy { it.id }
}
