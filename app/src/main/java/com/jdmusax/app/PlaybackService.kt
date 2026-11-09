package com.jdmusax.app

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.json.JSONArray
import org.json.JSONObject

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionPrefs by lazy {
        getSharedPreferences("playback_session", MODE_PRIVATE)
    }

    private val persistSession = object : Runnable {
        override fun run() {
            mediaSession?.player?.let(::savePlaybackSession)
            mainHandler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
        }

        restorePlaybackSession(player)
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (
                    events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                    events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                    events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                    events.contains(Player.EVENT_REPEAT_MODE_CHANGED) ||
                    events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
                    events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)
                ) {
                    savePlaybackSession(player)
                }
            }
        })

        mediaSession = MediaSession.Builder(this, player).build()
        mainHandler.postDelayed(persistSession, SAVE_INTERVAL_MS)
    }

    private fun restorePlaybackSession(player: ExoPlayer) {
        val raw = sessionPrefs.getString(KEY_ITEMS, null) ?: return
        runCatching {
            val array = JSONArray(raw)
            val items = (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val uri = item.optString("uri").takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val metadata = MediaMetadata.Builder()
                    .setTitle(item.optString("title", "Unknown track"))
                    .setArtist(item.optString("artist", "Unknown artist"))
                    .setArtworkUri(item.optString("artwork").takeIf(String::isNotBlank)?.let(Uri::parse))
                    .build()
                MediaItem.Builder()
                    .setMediaId(item.optString("id", uri))
                    .setUri(uri)
                    .setMediaMetadata(metadata)
                    .build()
            }
            if (items.isNotEmpty()) {
                val index = sessionPrefs.getInt(KEY_INDEX, 0).coerceIn(items.indices)
                val position = sessionPrefs.getLong(KEY_POSITION, 0L).coerceAtLeast(0L)
                player.setMediaItems(items, index, position)
                player.shuffleModeEnabled = sessionPrefs.getBoolean(KEY_SHUFFLE, false)
                player.repeatMode = sessionPrefs.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF)
                    .coerceIn(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL)
                player.prepare()
                player.pause()
            }
        }.onFailure {
            sessionPrefs.edit().remove(KEY_ITEMS).apply()
        }
    }

    private fun savePlaybackSession(player: Player) {
        if (player.mediaItemCount == 0) return
        runCatching {
            val items = JSONArray()
            for (index in 0 until player.mediaItemCount) {
                val mediaItem = player.getMediaItemAt(index)
                val metadata = mediaItem.mediaMetadata
                val uri = mediaItem.localConfiguration?.uri?.toString().orEmpty()
                if (uri.isBlank()) continue
                items.put(JSONObject().apply {
                    put("id", mediaItem.mediaId)
                    put("uri", uri)
                    put("title", metadata.title?.toString().orEmpty())
                    put("artist", metadata.artist?.toString().orEmpty())
                    put("artwork", metadata.artworkUri?.toString().orEmpty())
                })
            }
            if (items.length() == 0) return
            sessionPrefs.edit()
                .putString(KEY_ITEMS, items.toString())
                .putInt(KEY_INDEX, player.currentMediaItemIndex.coerceAtLeast(0))
                .putLong(KEY_POSITION, player.currentPosition.coerceAtLeast(0L))
                .putBoolean(KEY_SHUFFLE, player.shuffleModeEnabled)
                .putInt(KEY_REPEAT, player.repeatMode)
                .apply()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mainHandler.removeCallbacks(persistSession)
        mediaSession?.run {
            savePlaybackSession(player)
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
        const val KEY_ITEMS = "queue_items"
        const val KEY_INDEX = "queue_index"
        const val KEY_POSITION = "queue_position"
        const val KEY_SHUFFLE = "shuffle_enabled"
        const val KEY_REPEAT = "repeat_mode"
    }
}
