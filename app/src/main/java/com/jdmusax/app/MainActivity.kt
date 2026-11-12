package com.jdmusax.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private val repo = AudioLibraryRepository()
    private val listeningHistory by lazy { ListeningHistoryStore(applicationContext) }
    private var playing by mutableStateOf(false)
    private var sessionTracks by mutableStateOf(emptyList<Track>())
    private var sessionIndex by mutableIntStateOf(-1)
    private var sessionShuffleEnabled by mutableStateOf(false)
    private var sessionRepeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    private val audioEffects = AudioEffectsController()
    private var audioBands by mutableStateOf(emptyList<AudioBand>())
    private var audioPresets by mutableStateOf(emptyList<String>())
    private var visualizerLevels by mutableStateOf(emptyList<Float>())
    private var visualizerEnabled by mutableStateOf(false)

    private fun syncPlaybackSnapshot(player: Player) {
        sessionTracks = (0 until player.mediaItemCount).map { index ->
            val item = player.getMediaItemAt(index)
            val uri = item.localConfiguration?.uri
            val isLocal = uri?.scheme == "content" || uri?.scheme == "file"
            Track(
                id = item.mediaId,
                title = item.mediaMetadata.title?.toString() ?: "Unknown track",
                artist = item.mediaMetadata.artist?.toString() ?: "Unknown artist",
                artwork = item.mediaMetadata.artworkUri?.toString(),
                duration = if (index == player.currentMediaItemIndex) player.duration.takeIf { it > 0L } ?: 0L else 0L,
                localUri = if (isLocal) uri.toString() else null,
                streamUrl = if (isLocal) null else uri?.toString()
            )
        }
        sessionIndex = player.currentMediaItemIndex.takeIf { it in sessionTracks.indices } ?: -1
        sessionShuffleEnabled = player.shuffleModeEnabled
        sessionRepeatMode = player.repeatMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val visualizerPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                visualizerEnabled = audioEffects.setVisualizerEnabled(true) { levels -> visualizerLevels = levels }
            } else {
                visualizerEnabled = false
            }
        }

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.getOrNull()?.let { c ->
                c.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                        mediaItem?.let { item ->
                            val uri = item.localConfiguration?.uri
                            val isLocal = uri?.scheme == "content" || uri?.scheme == "file"
                            listeningHistory.record(
                                Track(
                                    id = item.mediaId,
                                    title = item.mediaMetadata.title?.toString() ?: "Unknown track",
                                    artist = item.mediaMetadata.artist?.toString() ?: "Unknown artist",
                                    artwork = item.mediaMetadata.artworkUri?.toString(),
                                    localUri = if (isLocal) uri.toString() else null,
                                    streamUrl = if (isLocal) null else uri?.toString(),
                                    isDownloadable = !isLocal
                                )
                            )
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playing = isPlaying
                    }

                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        audioBands = audioEffects.attach(audioSessionId)
                        audioPresets = audioEffects.presetNames()
                        if (visualizerEnabled) {
                            visualizerEnabled = audioEffects.setVisualizerEnabled(true) { levels -> visualizerLevels = levels }
                        }
                    }

                    override fun onEvents(player: Player, events: Player.Events) {
                        if (
                            events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                            events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                            events.contains(Player.EVENT_MEDIA_METADATA_CHANGED) ||
                            events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
                            events.contains(Player.EVENT_REPEAT_MODE_CHANGED)
                        ) {
                            syncPlaybackSnapshot(player)
                        }
                    }
                })
                controller = c
                playing = c.isPlaying
                audioBands = audioEffects.attach(c.audioSessionId)
                audioPresets = audioEffects.presetNames()
                syncPlaybackSnapshot(c)
            }
        }, MoreExecutors.directExecutor())

        val library = LibraryStore(applicationContext)
        val settings = SettingsStore(applicationContext)

        setContent {
            JDMusaxApp(
                repo = repo,
                library = library,
                settings = settings,
                isPlaying = playing,
                sessionTracks = sessionTracks,
                sessionIndex = sessionIndex,
                sessionShuffleEnabled = sessionShuffleEnabled,
                sessionRepeatMode = sessionRepeatMode,
                audioBands = audioBands,
                audioPresets = audioPresets,
                visualizerLevels = visualizerLevels,
                visualizerEnabled = visualizerEnabled,
                onSpeedChange = { speed -> controller?.setPlaybackSpeed(speed) },
                onCurrentPosition = { controller?.currentPosition ?: 0L },
                onCurrentDuration = { controller?.duration?.takeIf { it > 0L } ?: 0L },
                onSeekTo = { position -> controller?.seekTo(position) },
                onShuffleChange = { enabled -> controller?.shuffleModeEnabled = enabled },
                onRepeatModeChange = { mode -> controller?.repeatMode = mode },
                onBandLevelChange = { index, level -> audioBands = audioEffects.setBandLevel(index, level) },
                onPresetChange = { index -> audioBands = audioEffects.usePreset(index) },
                onVisualizerToggle = { enabled ->
                    if (!enabled) {
                        visualizerEnabled = audioEffects.setVisualizerEnabled(false) { levels -> visualizerLevels = levels }
                    } else if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        visualizerEnabled = audioEffects.setVisualizerEnabled(true) { levels -> visualizerLevels = levels }
                    } else {
                        visualizerPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                onToggle = {
                    controller?.let { c -> if (c.isPlaying) c.pause() else c.play() }
                },
                onPrevious = { controller?.seekToPreviousMediaItem() },
                onNext = { controller?.seekToNextMediaItem() },
                onPlayQueue = { tracks, startIndex ->
                    lifecycleScope.launch {
                        val playable = tracks.filter { track ->
                            track.localUri != null || track.streamUrl?.startsWith("https://") == true
                        }
                        if (playable.isNotEmpty()) {
                            val safeIndex = startIndex.coerceIn(0, playable.lastIndex)
                            controller?.apply {
                                setMediaItems(playable.map { it.mediaItem() }, safeIndex, 0L)
                                prepare()
                                play()
                            }
                        }
                    }
                },
                onEditQueue = { tracks, startIndex, position ->
                    controller?.let { c ->
                        val resume = c.isPlaying
                        if (tracks.isEmpty()) {
                            c.clearMediaItems()
                        } else {
                            c.setMediaItems(tracks.map { it.mediaItem() }, startIndex.coerceIn(0, tracks.lastIndex), position.coerceAtLeast(0L))
                            c.prepare()
                            if (resume) c.play()
                        }
                    }
                },
                onDownload = { track ->
                    val url = track.streamUrl
                    if (track.isDownloadable && !url.isNullOrBlank() && url.startsWith("https://")) {
                        val safeName = track.title.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "SHIAudio-audio" }
                        val pathExtension = Uri.parse(url).lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
                        val extension = pathExtension.takeIf { it in setOf("mp3", "m4a", "ogg", "wav", "aac") } ?: "mp3"
                        val request = DownloadManager.Request(Uri.parse(url))
                            .setTitle(track.title)
                            .setDescription("Downloading audio for offline listening")
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "SHIAudio/$safeName.$extension")
                        val downloadId = (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                        OfflineAudioStore(applicationContext).remember(track, downloadId)
                    }
                },
                onShareVerse = { track ->
                    lifecycleScope.launch {
                        val imageUri = QuranVerseShare.createShareImageUri(applicationContext, track)
                        if (imageUri != null) {
                            val shareIntent = QuranVerseShare.createShareIntent(this@MainActivity, imageUri)
                            runCatching { startActivity(Intent.createChooser(shareIntent, "Share Quran verse")) }
                        } else {
                            Toast.makeText(this@MainActivity, "Could not prepare this verse image. Check your internet connection and try again.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }
    }

    override fun onDestroy() {
        audioEffects.release()
        controller?.release()
        super.onDestroy()
    }
}

/* ---------- Library storage (SharedPreferences + JSON) ---------- */

class LyricsStore(context: Context) {
    private val prefs = context.getSharedPreferences("track_lyrics", Context.MODE_PRIVATE)
    fun load(trackId: String): String = prefs.getString(trackId, "") ?: ""
    fun save(trackId: String, lyrics: String) { prefs.edit().putString(trackId, lyrics).apply() }
}

class ListeningHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("listening_history", Context.MODE_PRIVATE)
    private fun read(): MutableList<JSONObject> {
        val raw = prefs.getString("entries", null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { index -> array.getJSONObject(index) }
        }.getOrDefault(mutableListOf())
    }
    fun record(track: Track) {
        val entries = read()
        val existing = entries.firstOrNull { it.optString("id") == track.id }
        val count = (existing?.optInt("plays", 0) ?: 0) + 1
        entries.removeAll { it.optString("id") == track.id }
        entries.add(0, JSONObject().apply {
            put("id", track.id); put("title", track.title); put("artist", track.artist)
            put("artwork", track.artwork ?: ""); put("duration", track.duration)
            put("localUri", track.localUri ?: ""); put("streamUrl", track.streamUrl ?: "")
            put("downloadable", track.isDownloadable); put("language", track.language)
            put("contentType", track.contentType); put("tradition", track.tradition); put("externalUrl", track.externalUrl ?: "")
            put("plays", count); put("playedAt", System.currentTimeMillis())
        })
        val array = JSONArray()
        entries.take(100).forEach { array.put(it) }
        prefs.edit().putString("entries", array.toString()).apply()
    }
    private fun toTrack(item: JSONObject) = Track(
        id = item.optString("id"), title = item.optString("title", "Unknown track"),
        artist = item.optString("artist", "Unknown artist"), artwork = item.optString("artwork").ifBlank { null },
        duration = item.optLong("duration", 0L), localUri = item.optString("localUri").ifBlank { null },
        streamUrl = item.optString("streamUrl").ifBlank { null }, isDownloadable = item.optBoolean("downloadable", false),
        language = item.optString("language"), contentType = item.optString("contentType", "Audiobook"),
        tradition = item.optString("tradition", "General"), externalUrl = item.optString("externalUrl").ifBlank { null }
    )
    fun recent(): List<Track> = read().sortedByDescending { it.optLong("playedAt") }.map(::toTrack)
    fun mostPlayed(): List<Track> = read().sortedWith(compareByDescending<JSONObject> { it.optInt("plays") }.thenByDescending { it.optLong("playedAt") }).map(::toTrack)
}

class LibraryStore(context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    fun load(): List<Track> {
        val raw = prefs.getString("tracks", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    Track(
                        id = o.getString("id"),
                        title = o.optString("title", "Unknown track"),
                        artist = o.optString("artist", "Unknown artist"),
                        artwork = o.optString("artwork").ifBlank { null },
                        duration = o.optLong("duration", 0L),
                        localUri = o.optString("localUri").ifBlank { null },
                        streamUrl = o.optString("streamUrl").ifBlank { null },
                        isDownloadable = o.optBoolean("isDownloadable", false),
                        language = o.optString("language"), contentType = o.optString("contentType", "Audiobook"),
                        tradition = o.optString("tradition", "General"),
                        externalUrl = o.optString("externalUrl").ifBlank { null }
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(tracks: List<Track>) {
        val arr = JSONArray()
        tracks.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("title", t.title)
                put("artist", t.artist)
                put("artwork", t.artwork ?: "")
                put("duration", t.duration)
                put("localUri", t.localUri ?: "")
                put("streamUrl", t.streamUrl ?: "")
                put("isDownloadable", t.isDownloadable)
                put("language", t.language)
                put("contentType", t.contentType)
                put("tradition", t.tradition)
                put("externalUrl", t.externalUrl ?: "")
            })
        }
        prefs.edit().putString("tracks", arr.toString()).apply()
    }
}

private suspend fun extractArtworkAccent(url: String?): Color? = withContext(Dispatchers.IO) {
    if (url.isNullOrBlank()) return@withContext null
    runCatching {
        val request = Request.Builder().url(url).build()
        artworkClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val stream = response.body?.byteStream() ?: return@use null
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bitmap = stream.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return@use null
            try {
                val palette = Palette.from(bitmap).clearFilters().generate()
                val color = palette.vibrantSwatch?.rgb
                    ?: palette.lightVibrantSwatch?.rgb
                    ?: palette.mutedSwatch?.rgb
                    ?: palette.dominantSwatch?.rgb
                color?.let { Color(it) }
            } finally {
                bitmap.recycle()
            }
        }
    }.getOrNull()
}

/* ---------- Artwork color extraction ---------- */

private val artworkClient = OkHttpClient()


/* ---------- UI ---------- */

internal data class AppPalette(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val text: Color,
    val muted: Color,
    val border: Color
)
internal val LocalAppPalette = compositionLocalOf {
    AppPalette(Color.Black, Color(0xFF101010), Color(0xFF1A1A1A), Color.White, Color.Gray, Color.DarkGray)
}

private enum class AppTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    EXPLORE("Discover", Icons.Default.Explore),
    LIBRARY("Library", Icons.Default.MenuBook),
    DOWNLOADS("Downloads", Icons.Default.Download),
    SETTINGS("Settings", Icons.Default.Settings),
    INSIGHTS("Insights", Icons.Default.Insights),
    PLAYER("Player", Icons.Default.GraphicEq)
}

@Composable
private fun JDMusaxApp(
    repo: AudioLibraryRepository,
    library: LibraryStore,
    settings: SettingsStore,
    isPlaying: Boolean,
    sessionTracks: List<Track>,
    sessionIndex: Int,
    sessionShuffleEnabled: Boolean,
    sessionRepeatMode: Int,
    audioBands: List<AudioBand>,
    audioPresets: List<String>,
    visualizerLevels: List<Float>,
    visualizerEnabled: Boolean,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPlayQueue: (List<Track>, Int) -> Unit,
    onEditQueue: (List<Track>, Int, Long) -> Unit,
    onDownload: (Track) -> Unit,
    onShareVerse: (Track) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onCurrentPosition: () -> Long,
    onCurrentDuration: () -> Long,
    onSeekTo: (Long) -> Unit,
    onShuffleChange: (Boolean) -> Unit,
    onRepeatModeChange: (Int) -> Unit,
    onBandLevelChange: (Int, Int) -> Unit,
    onPresetChange: (Int) -> Unit,
    onVisualizerToggle: (Boolean) -> Unit
) {
    var accentName by remember { mutableStateOf(settings.getAccent()) }
    var dynamicTheme by remember { mutableStateOf(settings.isDynamicThemeEnabled()) }
    var audioTheme by remember { mutableStateOf(settings.getAudioTheme()) }
    var appearanceMode by remember { mutableStateOf(settings.getAppearanceMode()) }
    var artworkAccent by remember { mutableStateOf<Color?>(null) }
    val fixedAccent = when (accentName) {
        "gold" -> ShiaudioDesign.gold
        "blue" -> Color(0xFF80BFFF)
        "cyan" -> Color(0xFF62D9E8)
        "teal" -> Color(0xFF55C7B3)
        "mint" -> Color(0xFF80E0C0)
        "green" -> Color(0xFF8BCB88)
        "lime" -> Color(0xFFC5D96B)
        "yellow" -> Color(0xFFF2D16B)
        "orange" -> Color(0xFFFFA45B)
        "red" -> Color(0xFFFF7777)
        "rose" -> Color(0xFFFF9EB5)
        "pink" -> Color(0xFFE99AD6)
        "indigo" -> Color(0xFF929BFF)
        else -> Color(0xFFB7A4FF)
    }
    val accent = if (dynamicTheme) artworkAccent ?: fixedAccent else fixedAccent
    val palette = when (appearanceMode) {
        "light" -> AppPalette(ShiaudioDesign.ivory, ShiaudioDesign.paper, ShiaudioDesign.ivoryVariant, ShiaudioDesign.ink, ShiaudioDesign.mutedInk, ShiaudioDesign.ivoryBorder)
        "amoled" -> AppPalette(Color.Black, Color(0xFF050B08), Color(0xFF101A14), Color(0xFFF5F4EC), Color(0xFFA5B2A8), Color(0xFF26352C))
        else -> AppPalette(ShiaudioDesign.emeraldNight, ShiaudioDesign.emeraldSurface, ShiaudioDesign.emeraldRaised, ShiaudioDesign.warmWhite, ShiaudioDesign.sageMuted, ShiaudioDesign.emeraldBorder)
    }
    val scope = rememberCoroutineScope()
    val appContext = androidx.compose.ui.platform.LocalContext.current

    var tab by remember { mutableStateOf(AppTab.HOME) }
    var nowPlaying by remember { mutableStateOf<Track?>(null) }
    var playbackQueue by remember { mutableStateOf(emptyList<Track>()) }
    var playbackIndex by remember { mutableIntStateOf(-1) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var sleepMinutes by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var shuffleEnabled by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(sessionRepeatMode) }
    val lyricsStore = remember { LyricsStore(appContext) }
    var lyricsText by remember(nowPlaying?.id) { mutableStateOf(nowPlaying?.let { lyricsStore.load(it.id) } ?: "") }
    val saved = remember { mutableStateListOf<Track>().apply { addAll(library.load()) } }
    val savedIds = saved.map { it.id }.toSet()
    val historyStore = remember { ListeningHistoryStore(appContext) }
    val bookmarkStore = remember { BookmarkStore(appContext) }
    var bookmarkTracks by remember { mutableStateOf(bookmarkStore.load()) }
    var recentTracks by remember { mutableStateOf(historyStore.recent()) }
    var mostPlayedTracks by remember { mutableStateOf(historyStore.mostPlayed()) }
    val playlistStore = remember { PlaylistStore(appContext) }
    var playlists by remember { mutableStateOf(playlistStore.load()) }
    val progressStore = remember { ListeningProgressStore(appContext) }
    val analyticsStore = remember { ListeningAnalyticsStore(appContext) }
    val challengeStore = remember { ListeningChallengeStore(appContext) }
    val offlineStore = remember { OfflineAudioStore(appContext) }
    var listeningSnapshot by remember { mutableStateOf(analyticsStore.snapshot()) }
    var challenge by remember { mutableStateOf(challengeStore.load()) }
    var offlineTracks by remember { mutableStateOf(offlineStore.completed().map { item -> Track(id = "download:${item.downloadId}", title = item.title, artist = "Downloaded audio", localUri = item.uri.toString()) }) }
    var savedProgressMs by remember(nowPlaying?.id) { mutableLongStateOf(nowPlaying?.let { progressStore.load(it.id) } ?: 0L) }

    LaunchedEffect(sessionTracks, sessionIndex) {
        if (sessionTracks.isNotEmpty() && sessionIndex in sessionTracks.indices) {
            playbackQueue = sessionTracks
            playbackIndex = sessionIndex
            nowPlaying = sessionTracks[sessionIndex]
            recentTracks = historyStore.recent()
            mostPlayedTracks = historyStore.mostPlayed()
        } else if (sessionTracks.isEmpty() && sessionIndex == -1) {
            playbackQueue = emptyList()
            playbackIndex = -1
            nowPlaying = null
        }
    }

    LaunchedEffect(sessionShuffleEnabled) {
        shuffleEnabled = sessionShuffleEnabled
    }

    LaunchedEffect(sessionRepeatMode) {
        repeatMode = sessionRepeatMode
    }

    LaunchedEffect(nowPlaying?.artwork, dynamicTheme) {
        if (dynamicTheme) {
            artworkAccent = extractArtworkAccent(nowPlaying?.artwork)
        } else {
            artworkAccent = null
        }
    }

    LaunchedEffect(isPlaying, nowPlaying?.id) {
        if (nowPlaying == null) {
            positionMs = 0L
            durationMs = 0L
            return@LaunchedEffect
        }

        fun refreshPlaybackPosition() {
            positionMs = onCurrentPosition().coerceAtLeast(0L)
            durationMs = onCurrentDuration().coerceAtLeast(0L)
        }

        // A paused player does not need a polling loop. Refresh once and let
        // explicit seek/player events update the position until playback resumes.
        refreshPlaybackPosition()
        if (!isPlaying) return@LaunchedEffect

        while (isPlaying && nowPlaying != null) {
            delay(500L)
            refreshPlaybackPosition()
            nowPlaying?.let { track ->
                progressStore.save(track.id, positionMs)
                analyticsStore.record(track, 500L)
                listeningSnapshot = analyticsStore.snapshot()
            }
        }
    }

    LaunchedEffect(sleepMinutes) {
        if (sleepMinutes > 0) {
            delay(sleepMinutes * 60_000L)
            if (isPlaying) onToggle()
            sleepMinutes = 0
        }
    }

    // Home / search state (kept here so it survives tab switches)
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("Shia Audiobooks") }
    var results by remember { mutableStateOf(emptyList<Track>()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchGeneration by remember { mutableIntStateOf(0) }

    // Explore / featured state
    var trending by remember { mutableStateOf(emptyList<Track>()) }
    var trendingLoading by remember { mutableStateOf(false) }
    var trendingError by remember { mutableStateOf<String?>(null) }
    var trendingLoaded by remember { mutableStateOf(false) }
    var featuredGeneration by remember { mutableIntStateOf(0) }

    val context = androidx.compose.ui.platform.LocalContext.current

    fun play(track: Track, queue: List<Track>) {
        if (!track.externalUrl.isNullOrBlank()) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(track.externalUrl))) }
            return
        }
        val safeQueue = (queue.ifEmpty { listOf(track) }).distinctBy { it.id }
        playbackQueue = safeQueue
        playbackIndex = safeQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        nowPlaying = safeQueue[playbackIndex]
        onPlayQueue(safeQueue, playbackIndex)
    }

    fun skipTo(index: Int) {
        if (index !in playbackQueue.indices) return
        playbackIndex = index
        nowPlaying = playbackQueue[index]
        onPlayQueue(playbackQueue, index)
    }

    fun toggleSave(track: Track) {
        val index = saved.indexOfFirst { it.id == track.id }
        if (index >= 0) saved.removeAt(index) else saved.add(0, track)
        library.save(saved.toList())
    }

    fun toggleBookmark(track: Track) {
        bookmarkTracks = if (bookmarkTracks.any { it.id == track.id }) {
            bookmarkStore.remove(track.id)
            bookmarkTracks.filterNot { it.id == track.id }
        } else {
            bookmarkStore.add(track)
            listOf(track) + bookmarkTracks
        }
    }

    fun runSearch(category: String = selectedCategory) {
        val requestId = ++searchGeneration
        scope.launch {
            searching = true
            searchError = null
            val response = runCatching {
                if (query.isBlank()) repo.featured(category) else repo.search(query, category)
            }
            if (requestId == searchGeneration) {
                response.onSuccess { results = it }
                    .onFailure { searchError = it.message ?: "Search failed" }
                searching = false
            }
        }
    }

    fun loadTrending(category: String = selectedCategory) {
        selectedCategory = category
        val requestId = ++featuredGeneration
        scope.launch {
            trendingLoading = true
            trendingError = null
            val response = runCatching { repo.featured(category) }
            if (requestId == featuredGeneration) {
                response.onSuccess { trending = it; trendingLoaded = true }
                    .onFailure { trendingError = it.message ?: "Could not load featured audio" }
                trendingLoading = false
            }
        }
    }

    LaunchedEffect(tab) {
        if (tab == AppTab.EXPLORE && !trendingLoaded && !trendingLoading) loadTrending()
        if (tab == AppTab.INSIGHTS) {
            listeningSnapshot = analyticsStore.snapshot()
            offlineTracks = offlineStore.completed().map { item ->
                Track(id = "download:${item.downloadId}", title = item.title, artist = "Downloaded audio", localUri = item.uri.toString())
            }
            challenge = challengeStore.load()
        }
    }

    BackHandler(enabled = tab == AppTab.PLAYER) { tab = AppTab.HOME }

    MaterialTheme(
        colorScheme = if (appearanceMode == "light") {
            lightColorScheme(primary = accent, onPrimary = ShiaudioDesign.emeraldNight, background = palette.background, surface = palette.surface, onBackground = palette.text, onSurface = palette.text)
        } else {
            darkColorScheme(primary = accent, onPrimary = ShiaudioDesign.emeraldNight, background = palette.background, surface = palette.surface, onBackground = palette.text, onSurface = palette.text)
        }
    ) {
      CompositionLocalProvider(LocalAppPalette provides palette) {
        Scaffold(
            containerColor = palette.background,
            bottomBar = {
                Column {
                    nowPlaying?.takeIf { tab != AppTab.PLAYER }?.let { miniTrack ->
                        val miniProgress = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = palette.surfaceVariant,
                            tonalElevation = 2.dp
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { tab = AppTab.PLAYER }.padding(start = 9.dp, end = 4.dp, top = 7.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)).background(palette.surface), contentAlignment = Alignment.Center) {
                                        if (!miniTrack.artwork.isNullOrBlank()) {
                                            AsyncImage(model = miniTrack.artwork, contentDescription = "Now playing artwork", modifier = Modifier.fillMaxSize())
                                        } else {
                                            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = accent)
                                        }
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(miniTrack.title, color = palette.text, maxLines = 1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        Text(miniTrack.artist, color = palette.muted, maxLines = 1, fontSize = 11.sp)
                                    }
                                    IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = palette.text) }
                                    IconButton(onClick = onToggle) {
                                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (isPlaying) "Pause" else "Play", tint = accent)
                                    }
                                    IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = palette.text) }
                                }
                                Box(Modifier.fillMaxWidth().height(3.dp).background(palette.border)) {
                                    Box(Modifier.fillMaxWidth(miniProgress).fillMaxHeight().background(accent))
                                }
                            }
                        }
                    }
                    NavigationBar(containerColor = palette.surface, tonalElevation = 0.dp) {
                        AppTab.values().filter { it != AppTab.PLAYER && it != AppTab.INSIGHTS }.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color(0xFF18372D),
                                    selectedTextColor = accent,
                                    indicatorColor = accent,
                                    unselectedIconColor = palette.muted,
                                    unselectedTextColor = palette.muted
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (tab == AppTab.PLAYER && nowPlaying != null) {
                    NowPlayingScreen(
                        track = nowPlaying!!, queue = playbackQueue, currentIndex = playbackIndex,
                        isPlaying = isPlaying, palette = palette, accent = accent, audioTheme = audioTheme, speed = playbackSpeed,
                        sleepMinutes = sleepMinutes, onClose = { tab = AppTab.HOME }, onToggle = onToggle,
                        onPrevious = onPrevious,
                        onNext = onNext,
                        onSelectTrack = { skipTo(it) },
                        onQueueEdit = onEditQueue,
                        onShareVerse = onShareVerse,
                        availableTracks = (results + trending + saved.toList()).distinctBy { it.id },
                        onSpeedChange = { speed -> playbackSpeed = speed; onSpeedChange(speed) },
                        onSleepTimerChange = { sleepMinutes = it },
                        lyrics = lyricsText,
                        isBookmarked = bookmarkTracks.any { it.id == nowPlaying!!.id },
                        onBookmarkToggle = { toggleBookmark(nowPlaying!!) },
                        visualizerLevels = visualizerLevels,
                        visualizerEnabled = visualizerEnabled,
                        positionMs = positionMs,
                        durationMs = durationMs.takeIf { it > 0L } ?: (nowPlaying?.duration ?: 0L),
                        onSeekTo = { position -> positionMs = position; onSeekTo(position) },
                        shuffleEnabled = shuffleEnabled,
                        repeatMode = repeatMode,
                        onShuffleToggle = { shuffleEnabled = !shuffleEnabled; onShuffleChange(shuffleEnabled) },
                        onRepeatCycle = { repeatMode = when (repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }; onRepeatModeChange(repeatMode) },
                        onLyricsChange = { text -> lyricsText = text; nowPlaying?.let { lyricsStore.save(it.id, text) } },
                        savedProgressMs = savedProgressMs,
                        onSaveProgress = { nowPlaying?.let { progressStore.save(it.id, positionMs); savedProgressMs = positionMs } },
                        onResumeProgress = { nowPlaying?.let { onSeekTo(progressStore.load(it.id)); savedProgressMs = progressStore.load(it.id) } }
                    )
                } else when (tab) {
                    AppTab.HOME -> HomeScreen(
                        query = query,
                        onQueryChange = { query = it },
                        category = selectedCategory,
                        onCategoryChange = { selectedCategory = it; runSearch(it) },
                        onSearch = { runSearch() },
                        loading = searching,
                        error = searchError,
                        tracks = results,
                        accent = accent,
                        savedIds = savedIds,
                        onPlay = { play(it, results) },
                        onToggleSave = { toggleSave(it) },
                        onDownload = onDownload,
                        recentTracks = recentTracks,
                        mostPlayedTracks = mostPlayedTracks,
                        onPlayMix = { mix -> if (mix.isNotEmpty()) play(mix.first(), mix) },
                        onContinue = { track -> play(track, recentTracks) }
                    )
                    AppTab.EXPLORE -> ExploreScreen(
                        loading = trendingLoading,
                        error = trendingError,
                        tracks = trending,
                        accent = accent,
                        savedIds = savedIds,
                        category = selectedCategory,
                        onCategoryChange = { loadTrending(it) },
                        onRefresh = { loadTrending() },
                        onPlay = { play(it, trending) },
                        onToggleSave = { toggleSave(it) },
                        onDownload = onDownload
                    )
                    AppTab.INSIGHTS -> ListeningInsightsScreen(
                        snapshot = listeningSnapshot,
                        challenge = challenge,
                        todayMs = listeningSnapshot.dailyTotals[ java.time.LocalDate.now() ] ?: 0L,
                        offlineTracks = offlineTracks,
                        accent = accent,
                        onGoalChange = { minutes -> challenge = challenge.copy(goalMinutes = minutes); challengeStore.save(challenge) },
                        onChallengeToggle = { enabled -> challenge = challenge.copy(enabled = enabled); challengeStore.save(challenge) },
                        onPlayOffline = { track -> play(track, listOf(track)) },
                        onRemoveOffline = { id -> if (id > 0L) { offlineStore.remove(id); offlineTracks = offlineStore.completed().map { item -> Track(id = "download:${item.downloadId}", title = item.title, artist = "Downloaded audio", localUri = item.uri.toString()) } } }
                    )
                    AppTab.LIBRARY -> LibraryScreen(
                        tracks = saved.toList(),
                        bookmarkTracks = bookmarkTracks,
                        onOpenInsights = { tab = AppTab.INSIGHTS },
                        recentTracks = recentTracks,
                        mostPlayedTracks = mostPlayedTracks,
                        playlists = playlists,
                        onCreatePlaylist = { name -> playlists = playlistStore.create(name, saved.toList()) },
                        onDeletePlaylist = { id -> playlists = playlistStore.delete(id) },
                        onRenamePlaylist = { id, name -> playlists = playlistStore.rename(id, name) },
                        onReorderPlaylist = { from, to -> playlists = playlistStore.reorder(from, to) },
                        onAddFavoritesToPlaylist = { id -> playlists = playlistStore.addTracks(id, saved.toList()) },
                        accent = accent,
                        savedIds = savedIds,
                        onPlay = { track, queue -> play(track, queue) },
                        onToggleSave = { toggleSave(it) },
                        onImport = { imported ->
                            imported.forEach { item -> if (saved.none { it.id == item.id }) saved.add(0, item) }
                            library.save(saved.toList())
                        },
                        onDownload = onDownload
                    )
                    AppTab.DOWNLOADS -> DownloadsScreen(
                        store = offlineStore,
                        accent = accent,
                        onPlayOffline = { track -> play(track, listOf(track)) }
                    )
                    AppTab.SETTINGS -> SettingsScreen(
                        accentName = accentName,
                        dynamicTheme = dynamicTheme,
                        audioTheme = audioTheme,
                        onAudioThemeChange = { theme -> audioTheme = theme; settings.setAudioTheme(theme) },
                        onAccentChange = { name ->
                            accentName = name
                            settings.setAccent(name)
                        },
                        onDynamicThemeChange = { enabled ->
                            dynamicTheme = enabled
                            settings.setDynamicThemeEnabled(enabled)
                        },
                        appearanceMode = appearanceMode,
                        onAppearanceModeChange = { mode ->
                            appearanceMode = mode
                            settings.setAppearanceMode(mode)
                        },
                        audioBands = audioBands,
                        audioPresets = audioPresets,
                        onBandLevelChange = onBandLevelChange,
                        onPresetChange = onPresetChange,
                        visualizerEnabled = visualizerEnabled,
                        visualizerLevels = visualizerLevels,
                        onVisualizerToggle = onVisualizerToggle
                    )
                    AppTab.PLAYER -> Text(
                        "Play a track to open the full player here.",
                        color = palette.muted,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
        }
      }
    }
}

private val musicCategories = listOf(
    "Shia Audiobooks", "Nohay", "Marsiya", "Quran", "Tafseer",
    "Majalis", "Duas & Ziyarat", "Manqabat", "Shia Podcasts"
)

@Composable
private fun CategoryPicker(selected: String, onSelected: (String) -> Unit, accent: Color) {
    val selectedIndex = musicCategories.indexOf(selected).coerceAtLeast(0)
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        edgePadding = 0.dp,
        containerColor = Color.Transparent,
        contentColor = accent,
        divider = {}
    ) {
        musicCategories.forEach { category ->
            Tab(
                selected = selected == category,
                onClick = { onSelected(category) },
                text = { Text(category, maxLines = 1, fontSize = 12.sp) }
            )
        }
    }
}

@Composable
private fun HomeScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    category: String,
    onCategoryChange: (String) -> Unit,
    onSearch: () -> Unit,
    loading: Boolean,
    error: String?,
    tracks: List<Track>,
    accent: Color,
    savedIds: Set<String>,
    onPlay: (Track) -> Unit,
    onToggleSave: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    recentTracks: List<Track>,
    mostPlayedTracks: List<Track>,
    onPlayMix: (List<Track>) -> Unit,
    onContinue: (Track) -> Unit
 ) {
    val palette = LocalAppPalette.current
    val dailyMix = (mostPlayedTracks.take(8) + recentTracks).distinctBy { it.id }.take(12)
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader("SHIAudio", "LISTEN • REFLECT • REMEMBER")
        Box(
            Modifier.fillMaxWidth().height(148.dp).clip(RoundedCornerShape(26.dp))
                .background(Brush.linearGradient(listOf(ShiaudioDesign.emeraldCardStart, ShiaudioDesign.emeraldCardEnd)))
                .padding(18.dp)
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = ShiaudioDesign.gold)
                    Spacer(Modifier.width(7.dp))
                    Text("A MOMENT OF REFLECTION", color = ShiaudioDesign.gold, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Keep the remembrance close.", color = Color(0xFFF7F2E6), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("Explore Quran, supplications and the words of Ahlulbayt (AS).", color = Color(0xFFD4E0D7), fontSize = 12.sp, maxLines = 2)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onCategoryChange("Quran") }) {
                        Text("Explore Quran", color = ShiaudioDesign.gold, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null, tint = ShiaudioDesign.gold, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(musicCategories, key = { it }) { item ->
                FilterChip(selected = category == item, onClick = { onCategoryChange(item) }, label = { Text(item, maxLines = 1, fontSize = 11.sp) })
            }
        }
        if (dailyMix.isNotEmpty()) {
            HomeListeningHighlights(
                recentTrack = recentTracks.firstOrNull(),
                mixCount = dailyMix.size,
                listenedCount = recentTracks.size,
                accent = accent,
                onPlayMix = { onPlayMix(dailyMix) },
                onContinue = { recentTracks.firstOrNull()?.let(onContinue) }
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search Quran, Nohay, Majalis, books…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "Search", tint = accent)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = palette.border,
                focusedTextColor = palette.text,
                unfocusedTextColor = palette.text
            )
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            TrackContent(
                loading = loading,
                error = error,
                tracks = tracks,
                emptyTitle = "Find something meaningful to hear.",
                emptySubtitle = "Search recitations, Nohay, Marsiya, Majalis and audiobooks.",
                accent = accent,
                savedIds = savedIds,
                onPlay = onPlay,
                onToggleSave = onToggleSave,
                onDownload = onDownload
            )
        }
    }
}

@Composable
private fun HomeListeningHighlights(
    recentTrack: Track?,
    mixCount: Int,
    listenedCount: Int,
    accent: Color,
    onPlayMix: () -> Unit,
    onContinue: () -> Unit
) {
    val palette = LocalAppPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = palette.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("YOUR PERSONAL MIX", color = accent, fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Text("A mix built from your listening", color = palette.text, fontWeight = FontWeight.SemiBold)
                    Text("$mixCount tracks · $listenedCount recently played", color = palette.muted, fontSize = 11.sp)
                }
                FilledTonalButton(onClick = onPlayMix) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("Play mix")
                }
            }
        }
        if (recentTrack != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = palette.surface,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onContinue)
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.History, contentDescription = null, tint = accent)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("PICK UP WHERE YOU LEFT OFF", color = palette.muted, fontSize = 9.sp, letterSpacing = 1.sp)
                        Text(recentTrack.title, color = palette.text, maxLines = 1, fontWeight = FontWeight.Medium)
                        Text(recentTrack.artist, color = palette.muted, maxLines = 1, fontSize = 11.sp)
                    }
                    Icon(Icons.Default.PlayCircle, contentDescription = "Continue listening", tint = accent, modifier = Modifier.size(27.dp))
                }
            }
        }
    }
}

@Composable
private fun ExploreScreen(
    loading: Boolean,
    error: String?,
    tracks: List<Track>,
    accent: Color,
    savedIds: Set<String>,
    category: String,
    onCategoryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onPlay: (Track) -> Unit,
    onToggleSave: (Track) -> Unit,
    onDownload: (Track) -> Unit
 ) {
    val palette = LocalAppPalette.current
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { ScreenHeader("Discover", "EXPLORE SHIA AUDIO COLLECTIONS") }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = accent)
            }
        }
        CategoryPicker(category, onCategoryChange, accent)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            TrackContent(
                loading = loading,
                error = error,
                tracks = tracks,
                emptyTitle = "No featured audio yet",
                emptySubtitle = "Refresh to check the selected library again.",
                accent = accent,
                savedIds = savedIds,
                onPlay = onPlay,
                onToggleSave = onToggleSave,
                onDownload = onDownload
            )
        }
    }
}

@Composable
private fun LibraryScreen(
    tracks: List<Track>,
    bookmarkTracks: List<Track>,
    onOpenInsights: () -> Unit,
    recentTracks: List<Track>,
    mostPlayedTracks: List<Track>,
    playlists: List<AudioPlaylist>,
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onRenamePlaylist: (String, String) -> Unit,
    onReorderPlaylist: (Int, Int) -> Unit,
    onAddFavoritesToPlaylist: (String) -> Unit,
    accent: Color,
    savedIds: Set<String>,
    onPlay: (Track, List<Track>) -> Unit,
    onToggleSave: (Track) -> Unit,
    onImport: (List<Track>) -> Unit,
    onDownload: (Track) -> Unit
 ) {
    val palette = LocalAppPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val imported = uris.map { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment ?: "Local audio"
            Track(id = "local:$uri", title = name.substringBeforeLast('.'), artist = "On this device", localUri = uri.toString())
        }
        if (imported.isNotEmpty()) onImport(imported)
    }
    var selectedCollection by remember { mutableStateOf("Favorites") }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var showRenamePlaylist by remember { mutableStateOf(false) }
    var renamedPlaylist by remember { mutableStateOf("") }
    val activePlaylist = playlists.firstOrNull { selectedCollection == it.id }
    val activePlaylistIndex = playlists.indexOfFirst { it.id == selectedCollection }
    val visibleTracks = when (selectedCollection) {
        "Bookmarks" -> bookmarkTracks
        "Recently played" -> recentTracks
        "Most played" -> mostPlayedTracks
        else -> activePlaylist?.tracks ?: tracks
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { ScreenHeader("Library", "YOUR SAVED AUDIO & LISTENING HISTORY") }
            IconButton(onClick = onOpenInsights) { Icon(Icons.Default.Insights, contentDescription = "Listening insights", tint = accent) }
            FilledTonalButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Import")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LISTENING SNAPSHOT", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text("${recentTracks.size} recent · ${mostPlayedTracks.size} most played · ${tracks.size} favorites", color = palette.muted, fontSize = 11.sp)
            }
            FilledTonalButton(onClick = { showCreatePlaylist = true }, enabled = tracks.isNotEmpty()) {
                Icon(Icons.Default.PlaylistAdd, contentDescription = null)
                Text("New playlist")
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(listOf("Favorites", "Bookmarks", "Recently played", "Most played")) { collection ->
                FilterChip(selected = selectedCollection == collection, onClick = { selectedCollection = collection }, label = { Text(collection, fontSize = 11.sp) })
            }
            items(playlists, key = { it.id }) { playlist ->
                FilterChip(selected = selectedCollection == playlist.id, onClick = { selectedCollection = playlist.id }, label = { Text(playlist.name, fontSize = 11.sp) })
            }
        }
        if (activePlaylist != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${activePlaylist.tracks.size} tracks · ${activePlaylist.name}", color = palette.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { onAddFavoritesToPlaylist(activePlaylist.id) }) { Text("Add favorites", color = accent) }
                IconButton(onClick = { renamedPlaylist = activePlaylist.name; showRenamePlaylist = true }) { Icon(Icons.Default.Edit, "Rename playlist", tint = accent) }
                IconButton(onClick = { onReorderPlaylist(activePlaylistIndex, activePlaylistIndex - 1) }, enabled = activePlaylistIndex > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move playlist up") }
                IconButton(onClick = { onReorderPlaylist(activePlaylistIndex, activePlaylistIndex + 1) }, enabled = activePlaylistIndex in 0 until playlists.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Move playlist down") }
                TextButton(onClick = { onDeletePlaylist(activePlaylist.id); selectedCollection = "Favorites" }) { Text("Delete", color = accent) }
            }
        }
        if (showRenamePlaylist && activePlaylist != null) {
            AlertDialog(
                onDismissRequest = { showRenamePlaylist = false },
                title = { Text("Rename playlist") },
                text = { OutlinedTextField(value = renamedPlaylist, onValueChange = { renamedPlaylist = it.take(48) }, singleLine = true, label = { Text("Playlist name") }) },
                confirmButton = { TextButton(onClick = { onRenamePlaylist(activePlaylist.id, renamedPlaylist); showRenamePlaylist = false }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { showRenamePlaylist = false }) { Text("Cancel") } }
            )
        }
        if (showCreatePlaylist) {
            AlertDialog(
                onDismissRequest = { showCreatePlaylist = false },
                title = { Text("Create playlist") },
                text = {
                    Column {
                        Text("Create a playlist from your current Favorites collection.", color = palette.muted, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(value = playlistName, onValueChange = { playlistName = it.take(48) }, label = { Text("Playlist name") }, singleLine = true)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onCreatePlaylist(playlistName); playlistName = ""; showCreatePlaylist = false }) { Text("Create") }
                },
                dismissButton = { TextButton(onClick = { showCreatePlaylist = false }) { Text("Cancel") }
                }
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            TrackContent(
                loading = false,
                error = null,
                tracks = visibleTracks,
                emptyTitle = when (selectedCollection) { "Bookmarks" -> "No bookmarks yet"; "Recently played" -> "Nothing played yet"; "Most played" -> "No listening stats yet"; else -> if (activePlaylist != null) "This playlist is empty" else "Nothing saved yet" },
                emptySubtitle = when (selectedCollection) { "Bookmarks" -> "Bookmark a book from the player to keep it here."; "Recently played" -> "Tracks you play will appear here."; "Most played" -> "Your most-played tracks will build up here."; else -> if (activePlaylist != null) "Add favorites to your library, then create a new playlist." else "Tap the heart on any track to keep it here." },
                accent = accent,
                savedIds = savedIds,
                onPlay = { track -> onPlay(track, visibleTracks) },
                onToggleSave = onToggleSave,
                onDownload = onDownload
            )
        }
    }
}

@Composable
private fun ScreenHeader(title: String, subtitle: String) {
    val palette = LocalAppPalette.current
    Column {
        Spacer(Modifier.height(18.dp))
        Text(title, fontSize = 29.sp, fontWeight = FontWeight.Bold, color = palette.text)
        Text(subtitle, color = palette.muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun TrackContent(
    loading: Boolean,
    error: String?,
    tracks: List<Track>,
    emptyTitle: String,
    emptySubtitle: String,
    accent: Color,
    savedIds: Set<String>,
    onPlay: (Track) -> Unit,
    onToggleSave: (Track) -> Unit,
    onDownload: (Track) -> Unit
) {
    val palette = LocalAppPalette.current
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = accent)
        }
        error != null -> EmptyState("Something went wrong", error)
        tracks.isEmpty() -> EmptyState(emptyTitle, emptySubtitle)
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(tracks, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    accent = accent,
                    saved = track.id in savedIds,
                    onClick = { onPlay(track) },
                    onToggleSave = { onToggleSave(track) },
                    onDownload = { onDownload(track) }
                )
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    val palette = LocalAppPalette.current
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Headphones, contentDescription = null, tint = palette.muted, modifier = Modifier.size(38.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = palette.muted)
    }
}

@Composable
private fun TrackRow(
    track: Track,
    accent: Color,
    saved: Boolean,
    onClick: () -> Unit,
    onToggleSave: () -> Unit,
    onDownload: () -> Unit
) {
    val palette = LocalAppPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val externalDownloadUrl = track.streamUrl?.takeIf { url ->
        runCatching { Uri.parse(url).scheme.equals("https", ignoreCase = true) }.getOrDefault(false)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        color = palette.surface
    ) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(palette.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (!track.artwork.isNullOrBlank()) {
                AsyncImage(
                    model = track.artwork,
                    contentDescription = "${track.contentType} cover",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    if (track.contentType.contains("lecture", true)) Icons.Default.Headphones else Icons.Default.MenuBook,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = palette.text, maxLines = 1, fontWeight = FontWeight.SemiBold)
            Text(
                listOf(
                    track.contentType,
                    when (track.language.lowercase()) {
                        "ur" -> "Urdu"
                        "en" -> "English"
                        "ar" -> "Arabic"
                        else -> track.language.takeIf { it.isNotBlank() }
                    },
                    track.artist
                ).filterNotNull().joinToString(" • "),
                color = palette.muted, maxLines = 1, fontSize = 11.sp
            )
            if (track.tradition != "General" && track.tradition != "Unclassified / General Islamic") {
                Text(track.tradition, color = accent, maxLines = 1, fontSize = 10.sp)
            }
        }
        IconButton(onClick = onToggleSave) {
            Icon(
                if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "Save to library",
                tint = if (saved) accent else Color.Gray
            )
        }
        if (track.isDownloadable) {
            IconButton(onClick = onDownload) {
                Icon(Icons.Default.Download, contentDescription = "Download for offline listening", tint = accent)
            }
            if (externalDownloadUrl != null) {
                IconButton(onClick = {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, track.title)
                        putExtra(Intent.EXTRA_TEXT, externalDownloadUrl)
                    }
                    val chooser = Intent.createChooser(sendIntent, "Send audio link to downloader")
                    runCatching { context.startActivity(chooser) }
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Send audio link to an external downloader", tint = accent)
                }
            }
        }
        Icon(if (track.externalUrl != null) Icons.Default.OpenInNew else Icons.Default.PlayCircle, contentDescription = if (track.externalUrl != null) "Open source" else "Play", tint = accent, modifier = Modifier.size(28.dp))
    }
    }
}

@Composable
private fun NowPlayingScreen(
    track: Track, queue: List<Track>, currentIndex: Int, isPlaying: Boolean,
    palette: AppPalette, accent: Color, audioTheme: String, speed: Float, sleepMinutes: Int,
    onClose: () -> Unit, onToggle: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onSelectTrack: (Int) -> Unit, onQueueEdit: (List<Track>, Int, Long) -> Unit,
    availableTracks: List<Track>, onSpeedChange: (Float) -> Unit, onSleepTimerChange: (Int) -> Unit,
    lyrics: String, isBookmarked: Boolean, onBookmarkToggle: () -> Unit,
    visualizerLevels: List<Float>, visualizerEnabled: Boolean,
    positionMs: Long, durationMs: Long, onSeekTo: (Long) -> Unit,
    shuffleEnabled: Boolean, repeatMode: Int, onShuffleToggle: () -> Unit, onRepeatCycle: () -> Unit,
    onLyricsChange: (String) -> Unit,
    onShareVerse: (Track) -> Unit,
    savedProgressMs: Long,
    onSaveProgress: () -> Unit,
    onResumeProgress: () -> Unit
) {
    val themeMotion by animateFloatAsState(targetValue = if (isPlaying) 1f else 0.25f, label = "audioThemeMotion")
    val themeColors = when (audioTheme) {
        "aurora" -> listOf(Color(0xFF164E63), Color(0xFF312E81), palette.background)
        "neon" -> listOf(Color(0xFF3B0764), Color(0xFF0E7490), palette.background)
        "ocean" -> listOf(Color(0xFF082F49), Color(0xFF155E75), palette.background)
        "sunset" -> listOf(Color(0xFF7C2D12), Color(0xFF9D174D), palette.background)
        "forest" -> listOf(Color(0xFF14532D), Color(0xFF365314), palette.background)
        "artwork" -> listOf(accent.copy(alpha = 0.30f), palette.background, accent.copy(alpha = 0.10f))
        else -> listOf(palette.background, palette.background, palette.background)
    }
    val themeBrush = Brush.linearGradient(themeColors.mapIndexed { index, color -> color.copy(alpha = if (index == 2) 1f else color.alpha * themeMotion) })
    Column(Modifier.fillMaxSize().background(themeBrush).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close player", tint = palette.text) }
            Spacer(Modifier.weight(1f))
            Text("NOW PLAYING", color = palette.muted, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.GraphicEq, null, tint = accent)
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(1f)
                .clip(RoundedCornerShape(26.dp)).background(palette.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (!track.artwork.isNullOrBlank()) {
                AsyncImage(model = track.artwork, contentDescription = "${track.contentType} cover", modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    if (track.contentType.contains("lecture", true)) Icons.Default.Headphones else Icons.Default.MenuBook,
                    contentDescription = null, tint = accent, modifier = Modifier.size(72.dp)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(track.title, color = palette.text, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        Text(track.artist, color = palette.muted, fontSize = 15.sp, maxLines = 1)
        if (track.id.startsWith("alfurqan:")) {
            FilledTonalButton(onClick = { onShareVerse(track) }) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Share verse as image")
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            FilledTonalButton(onClick = onBookmarkToggle) {
                Icon(if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (isBookmarked) "Bookmarked" else "Bookmark book")
            }
            FilledTonalButton(onClick = onSaveProgress) {
                Icon(Icons.Default.Flag, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text("Save position")
            }
        }
        if (savedProgressMs > 10_000L && savedProgressMs < durationMs - 10_000L) {
            TextButton(onClick = onResumeProgress) {
                Icon(Icons.Default.RestartAlt, contentDescription = null)
                Text("Resume saved position · ${formatPlaybackTime(savedProgressMs)}", color = accent)
            }
        }
        if (visualizerEnabled) {
            AudioVisualizerStrip(levels = visualizerLevels, accent = accent, modifier = Modifier.fillMaxWidth().height(42.dp).padding(vertical = 4.dp))
        }
        Text(
            listOf(track.contentType, when (track.language.lowercase()) { "ur" -> "Urdu"; "en" -> "English"; "ar" -> "Arabic"; else -> track.language })
                .filter { it.isNotBlank() }.joinToString(" • "),
            color = accent, fontSize = 12.sp
        )
        val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
        Slider(value = progress, onValueChange = { fraction -> if (durationMs > 0L) onSeekTo((fraction * durationMs).toLong()) },
            enabled = durationMs > 0L, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatPlaybackTime(positionMs), color = palette.muted, fontSize = 11.sp)
            Text(formatPlaybackTime(durationMs), color = palette.muted, fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onShuffleToggle) { Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffleEnabled) accent else palette.muted) }
            IconButton(onClick = onRepeatCycle) { Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat", tint = if (repeatMode != Player.REPEAT_MODE_OFF) accent else palette.muted) }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, enabled = queue.size > 1) { Icon(Icons.Default.SkipPrevious, "Previous", tint = palette.text, modifier = Modifier.size(34.dp)) }
            FilledIconButton(onClick = onToggle, modifier = Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent)) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pause" else "Play", tint = Color.Black, modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = onNext, enabled = queue.size > 1) { Icon(Icons.Default.SkipNext, "Next", tint = palette.text, modifier = Modifier.size(34.dp)) }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Speed", color = palette.muted, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                FilterChip(selected = speed == value, onClick = { onSpeedChange(value) }, label = { Text(value.toString() + "x") })
                Spacer(Modifier.width(4.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Bedtime, null, tint = palette.muted)
            Spacer(Modifier.width(8.dp))
            Text("Sleep timer", color = palette.text)
            Spacer(Modifier.weight(1f))
            listOf(0, 15, 30, 60).forEach { minutes ->
                FilterChip(selected = sleepMinutes == minutes, onClick = { onSleepTimerChange(minutes) }, label = { Text(if (minutes == 0) "Off" else minutes.toString() + "m") })
                Spacer(Modifier.width(3.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        var showLyrics by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (showLyrics) "Lyrics" else "Up next", color = palette.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showLyrics = !showLyrics }) { Text(if (showLyrics) "Show queue" else "Lyrics", color = accent) }
        }
        if (showLyrics) {
            val timedLines = remember(lyrics) { parseLrcLyrics(lyrics) }
            if (timedLines.isNotEmpty()) {
                val activeLine = timedLines.indexOfLast { it.first <= positionMs }.coerceAtLeast(0)
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 190.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    items(timedLines.size) { index ->
                        Text(timedLines[index].second, color = if (index == activeLine) accent else palette.muted,
                            fontSize = if (index == activeLine) 18.sp else 14.sp, fontWeight = if (index == activeLine) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(vertical = 7.dp))
                    }
                }
            } else {
                Text("Paste timed .LRC lyrics below to enable line highlighting.", color = palette.muted, fontSize = 12.sp)
            }
            OutlinedTextField(value = lyrics, onValueChange = onLyricsChange, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 140.dp),
                placeholder = { Text("[00:12.00] Lyrics line…") }, label = { Text("Track lyrics (.LRC supported)") })
        } else {
            var showQueueAdd by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Up next", color = palette.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showQueueAdd = true }) { Icon(Icons.Default.Add, null); Text("Add") }
                Text(queue.size.toString() + " tracks", color = palette.muted, fontSize = 12.sp)
            }
            if (showQueueAdd) {
                val candidates = availableTracks.filter { candidate -> queue.none { it.id == candidate.id } }
                AlertDialog(
                    onDismissRequest = { showQueueAdd = false },
                    title = { Text("Add to queue") },
                    text = {
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(candidates, key = { it.id }) { candidate ->
                                TextButton(onClick = {
                                    onQueueEdit(QueueEditor.append(queue, listOf(candidate)), currentIndex, positionMs)
                                    showQueueAdd = false
                                }) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(candidate.title, color = palette.text, maxLines = 1)
                                        Text(candidate.artist, color = palette.muted, fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showQueueAdd = false }) { Text("Close") } }
                )
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 150.dp)) {
                items(queue.size) { index ->
                    val item = queue[index]
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (index == currentIndex) palette.surfaceVariant else Color.Transparent)
                        .clickable { onSelectTrack(index) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text((index + 1).toString(), color = if (index == currentIndex) accent else palette.muted, modifier = Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, color = palette.text, maxLines = 1, fontSize = 13.sp)
                            Text(item.artist, color = palette.muted, maxLines = 1, fontSize = 11.sp)
                        }
                        if (index == currentIndex) Icon(Icons.Default.Equalizer, null, tint = accent)
                        IconButton(onClick = {
                            if (index > 0) {
                                val moved = QueueEditor.move(queue, index, index - 1)
                                val nextIndex = when (currentIndex) { index -> index - 1; index - 1 -> index; else -> currentIndex }
                                onQueueEdit(moved, nextIndex, positionMs)
                            }
                        }, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up", tint = palette.muted) }
                        IconButton(onClick = {
                            val updated = QueueEditor.remove(queue, index)
                            val nextIndex = when {
                                updated.isEmpty() -> 0
                                index < currentIndex -> currentIndex - 1
                                index == currentIndex -> index.coerceAtMost(updated.lastIndex)
                                else -> currentIndex
                            }
                            onQueueEdit(updated, nextIndex, if (index == currentIndex) 0L else positionMs)
                        }) { Icon(Icons.Default.Close, "Remove from queue", tint = palette.muted) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioVisualizerStrip(levels: List<Float>, accent: Color, modifier: Modifier = Modifier) {
    val bars = if (levels.isEmpty()) List(32) { 0.08f } else List(32) { index -> levels[(index * levels.size / 32).coerceIn(0, levels.lastIndex)] }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        bars.forEach { level ->
            Box(Modifier.weight(1f).fillMaxHeight(level.coerceIn(0.08f, 1f)).clip(RoundedCornerShape(4.dp)).background(accent))
        }
    }
}

private fun parseLrcLyrics(raw: String): List<Pair<Long, String>> {
    val pattern = Regex("""\[(\d{2}):(\d{2})(?:\.(\d{1,3}))?\](.*)""")
    return raw.lineSequence().mapNotNull { line ->
        val match = pattern.matchEntire(line.trim()) ?: return@mapNotNull null
        val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val seconds = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        (minutes * 60_000L + seconds * 1_000L + fraction) to match.groupValues[4].trim()
    }.filter { it.second.isNotBlank() }.sortedBy { it.first }.toList()
}


private fun formatPlaybackTime(milliseconds: Long): String {
    if (milliseconds <= 0L) return "0:00"
    val totalSeconds = milliseconds / 1000L
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
