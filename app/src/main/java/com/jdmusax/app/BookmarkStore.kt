package com.jdmusax.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent saved-book collection, separate from the user's favorites/library. */
class BookmarkStore(context: Context) {
    private val prefs = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<Track> {
        val raw = prefs.getString("entries", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::toTrack)
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(track: Track) {
        val entries = load().filterNot { it.id == track.id }.toMutableList()
        entries.add(0, track)
        save(entries)
    }

    @Synchronized
    fun remove(trackId: String) {
        save(load().filterNot { it.id == trackId })
    }

    private fun save(tracks: List<Track>) {
        val array = JSONArray()
        tracks.forEach { track ->
            array.put(JSONObject().apply {
                put("id", track.id)
                put("title", track.title)
                put("artist", track.artist)
                put("artwork", track.artwork ?: "")
                put("duration", track.duration)
                put("streamUrl", track.streamUrl ?: "")
                put("localUri", track.localUri ?: "")
                put("downloadable", track.isDownloadable)
                put("language", track.language)
                put("contentType", track.contentType)
                put("tradition", track.tradition)
            })
        }
        prefs.edit().putString("entries", array.toString()).apply()
    }

    private fun toTrack(item: JSONObject) = Track(
        id = item.optString("id"),
        title = item.optString("title", "Unknown book"),
        artist = item.optString("artist", "Unknown author"),
        artwork = item.optString("artwork").ifBlank { null },
        duration = item.optLong("duration"),
        streamUrl = item.optString("streamUrl").ifBlank { null },
        localUri = item.optString("localUri").ifBlank { null },
        isDownloadable = item.optBoolean("downloadable"),
        language = item.optString("language"),
        contentType = item.optString("contentType", "Audiobook"),
        tradition = item.optString("tradition", "General")
    )
}
