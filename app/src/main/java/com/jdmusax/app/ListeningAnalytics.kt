package com.jdmusax.app

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
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

/** Tracks DownloadManager IDs created by JD musax and exposes only completed files. */
data class OfflineAudio(val trackId: String, val title: String, val downloadId: Long, val uri: Uri)

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
            put("downloadId", downloadId)
        })
        prefs.edit().putString("items", updated.toString()).apply()
    }

    fun completed(): List<OfflineAudio> {
        val array = runCatching { JSONArray(prefs.getString("items", "[]") ?: "[]") }.getOrDefault(JSONArray())
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optLong("downloadId")
                val query = DownloadManager.Query().setFilterById(id)
                val cursor: Cursor = manager.query(query) ?: continue
                cursor.use {
                    if (!it.moveToFirst()) return@use
                    val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status != DownloadManager.STATUS_SUCCESSFUL) return@use
                    val uri = runCatching { manager.getUriForDownloadedFile(id) }.getOrNull() ?: return@use
                    add(OfflineAudio(item.optString("trackId"), item.optString("title"), id, uri))
                }
            }
        }
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
