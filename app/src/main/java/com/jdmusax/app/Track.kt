package com.jdmusax.app

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val artwork: String? = null,
    val duration: Long = 0L,
    val streamUrl: String? = null,
    val localUri: String? = null,
    val isDownloadable: Boolean = false,
    val language: String = "",
    val contentType: String = "Audiobook",
    val tradition: String = "General"
) {
    fun mediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(localUri ?: streamUrl ?: "")
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setArtist(artist)
                .setArtworkUri(artwork?.let(Uri::parse)).build()
        ).build()
}
