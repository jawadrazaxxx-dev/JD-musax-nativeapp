package com.jdmusax.app

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONObject

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val artwork: String? = null,
    val duration: Long = 0L,
    val streamUrl: String? = null
) {
    fun mediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(streamUrl ?: "")
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setArtist(artist)
                .setArtworkUri(artwork?.let(Uri::parse)).build()
        ).build()

    companion object {
        fun fromAudius(json: JSONObject) = Track(
            id = json.optString("id"),
            title = json.optString("title", "Unknown track"),
            artist = json.optJSONObject("user")?.optString("name") ?: "Unknown artist",
            artwork = json.optJSONObject("artwork")?.optString("150x150"),
            duration = json.optLong("duration") * 1000L
        )
    }
}
