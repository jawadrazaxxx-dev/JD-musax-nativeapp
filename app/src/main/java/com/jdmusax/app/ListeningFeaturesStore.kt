package com.jdmusax.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AudioPlaylist(val id: String, val name: String, val tracks: List<Track>)

class PlaylistStore(context: Context) {
    private val prefs = context.getSharedPreferences("audio_playlists", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<AudioPlaylist> {
        val raw = prefs.getString("items", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val tracks = item.optJSONArray("tracks") ?: JSONArray()
                AudioPlaylist(
                    item.optString("id"), item.optString("name"),
                    (0 until tracks.length()).mapNotNull { j ->
                        tracks.optJSONObject(j)?.let { o ->
                            Track(
                                o.optString("id"), o.optString("title"), o.optString("artist"),
                                o.optString("artwork").ifBlank { null }, o.optLong("duration"),
                                o.optString("streamUrl").ifBlank { null }, o.optString("localUri").ifBlank { null },
                                o.optBoolean("downloadable"), o.optString("language"),
                                o.optString("contentType", "Audiobook"), o.optString("tradition", "General"),
                                o.optString("externalUrl").ifBlank { null }
                            )
                        }
                    }
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun create(name: String, tracks: List<Track>): List<AudioPlaylist> {
        val safeName = name.trim().take(48)
        if (safeName.isBlank()) return load()
        val updated = listOf(AudioPlaylist("playlist:${System.currentTimeMillis()}", safeName, tracks.distinctBy { it.id })) + load()
        save(updated)
        return updated
    }

    @Synchronized
    fun rename(id: String, name: String): List<AudioPlaylist> {
        val safeName = name.trim().take(48)
        if (safeName.isBlank()) return load()
        val updated = load().map { if (it.id == id) it.copy(name = safeName) else it }
        save(updated)
        return updated
    }

    @Synchronized
    fun reorder(from: Int, to: Int): List<AudioPlaylist> {
        val current = load()
        if (from !in current.indices || to !in current.indices || from == to) return current
        val updated = current.toMutableList().apply { add(to, removeAt(from)) }
        save(updated)
        return updated
    }

    @Synchronized
    fun addTrack(id: String, track: Track): List<AudioPlaylist> {
        val updated = load().map { playlist ->
            if (playlist.id == id && playlist.tracks.none { it.id == track.id }) playlist.copy(tracks = playlist.tracks + track)
            else playlist
        }
        save(updated)
        return updated
    }

    @Synchronized
    fun addTracks(id: String, tracks: List<Track>): List<AudioPlaylist> {
        val updated = load().map { playlist ->
            if (playlist.id == id) playlist.copy(tracks = (playlist.tracks + tracks).distinctBy { it.id })
            else playlist
        }
        save(updated)
        return updated
    }

    @Synchronized
    fun removeTrack(id: String, trackId: String): List<AudioPlaylist> {
        val updated = load().map { playlist ->
            if (playlist.id == id) playlist.copy(tracks = playlist.tracks.filterNot { it.id == trackId }) else playlist
        }
        save(updated)
        return updated
    }

    @Synchronized
    fun delete(id: String): List<AudioPlaylist> {
        val updated = load().filterNot { it.id == id }
        save(updated)
        return updated
    }

    private fun save(playlists: List<AudioPlaylist>) {
        val array = JSONArray()
        playlists.forEach { playlist ->
            val tracks = JSONArray()
            playlist.tracks.forEach { t ->
                tracks.put(JSONObject().apply {
                    put("id", t.id); put("title", t.title); put("artist", t.artist)
                    put("artwork", t.artwork ?: ""); put("duration", t.duration)
                    put("streamUrl", t.streamUrl ?: ""); put("localUri", t.localUri ?: "")
                    put("downloadable", t.isDownloadable); put("language", t.language)
                    put("contentType", t.contentType); put("tradition", t.tradition); put("externalUrl", t.externalUrl ?: "")
                })
            }
            array.put(JSONObject().apply {
                put("id", playlist.id); put("name", playlist.name); put("tracks", tracks)
            })
        }
        prefs.edit().putString("items", array.toString()).apply()
    }
}

class ListeningProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences("listening_progress", Context.MODE_PRIVATE)
    fun save(trackId: String, positionMs: Long) {
        if (trackId.isNotBlank()) prefs.edit().putLong(trackId, positionMs.coerceAtLeast(0L)).apply()
    }
    fun load(trackId: String): Long = prefs.getLong(trackId, 0L).coerceAtLeast(0L)
    fun clear(trackId: String) { prefs.edit().remove(trackId).apply() }
}
