package com.jdmusax.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
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

class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private val repo = AudiusRepository()
    private var playing by mutableStateOf(false)
    private var sessionTracks by mutableStateOf(emptyList<Track>())
    private var sessionIndex by mutableIntStateOf(-1)
    private var sessionShuffleEnabled by mutableStateOf(false)
    private var sessionRepeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)

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

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.getOrNull()?.let { c ->
                c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playing = isPlaying
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
                onSpeedChange = { speed -> controller?.setPlaybackSpeed(speed) },
                onCurrentPosition = { controller?.currentPosition ?: 0L },
                onCurrentDuration = { controller?.duration?.takeIf { it > 0L } ?: 0L },
                onSeekTo = { position -> controller?.seekTo(position) },
                onShuffleChange = { enabled -> controller?.shuffleModeEnabled = enabled },
                onRepeatModeChange = { mode -> controller?.repeatMode = mode },
                onToggle = {
                    controller?.let { c -> if (c.isPlaying) c.pause() else c.play() }
                },
                onPrevious = { controller?.seekToPreviousMediaItem() },
                onNext = { controller?.seekToNextMediaItem() },
                onPlayQueue = { tracks, startIndex ->
                    lifecycleScope.launch {
                        val playable = tracks.mapNotNull { track ->
                            if (track.localUri != null) track
                            else runCatching { track.copy(streamUrl = repo.resolveStream(track.id)) }.getOrNull()
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
                onDownload = { track ->
                    if (track.isDownloadable) {
                        val safeName = track.title.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)
                        val request = DownloadManager.Request(Uri.parse("https://api.audius.co/v1/tracks/${track.id}/download?app_name=JDmusax"))
                            .setTitle(track.title)
                            .setDescription("Downloading an Audius track marked downloadable")
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "JD musax/$safeName.mp3")
                        (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                    }
                }
            )
        }
    }

    override fun onDestroy() {
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
            put("localUri", track.localUri ?: ""); put("downloadable", track.isDownloadable)
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
        isDownloadable = item.optBoolean("downloadable", false)
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
                        isDownloadable = o.optBoolean("isDownloadable", false)
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
                put("isDownloadable", t.isDownloadable)
            })
        }
        prefs.edit().putString("tracks", arr.toString()).apply()
    }
}

private suspend fun extractArtworkAccent(url: String?): Color? = withContext(Dispatchers.IO) {
    if (url.isNullOrBlank()) return@withContext null
    runCatching {
        val request = Request.Builder().url(url).build()
        trendingClient.newCall(request).execute().use { response ->
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

/* ---------- Trending request ---------- */

private val trendingClient = OkHttpClient()

private suspend fun fetchTrending(): List<Track> = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("https://api.audius.co/v1/tracks/trending?limit=25&app_name=JDmusax")
        .build()
    trendingClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("Trending failed: ${response.code}")
        val body = response.body?.string() ?: return@withContext emptyList()
        val data = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
        (0 until data.length()).mapNotNull { i ->
            data.optJSONObject(i)?.let(Track::fromAudius)
        }
    }
}

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
    EXPLORE("Explore", Icons.Default.Explore),
    LIBRARY("Library", Icons.Default.LibraryMusic),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
private fun JDMusaxApp(
    repo: AudiusRepository,
    library: LibraryStore,
    settings: SettingsStore,
    isPlaying: Boolean,
    sessionTracks: List<Track>,
    sessionIndex: Int,
    sessionShuffleEnabled: Boolean,
    sessionRepeatMode: Int,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPlayQueue: (List<Track>, Int) -> Unit,
    onDownload: (Track) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onCurrentPosition: () -> Long,
    onCurrentDuration: () -> Long,
    onSeekTo: (Long) -> Unit,
    onShuffleChange: (Boolean) -> Unit,
    onRepeatModeChange: (Int) -> Unit
) {
    var accentName by remember { mutableStateOf(settings.getAccent()) }
    var dynamicTheme by remember { mutableStateOf(settings.isDynamicThemeEnabled()) }
    var appearanceMode by remember { mutableStateOf(settings.getAppearanceMode()) }
    var artworkAccent by remember { mutableStateOf<Color?>(null) }
    val fixedAccent = when (accentName) {
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
        "light" -> AppPalette(Color(0xFFF5F5F8), Color.White, Color(0xFFECECF2), Color(0xFF17171D), Color(0xFF686875), Color(0xFFD8D8E0))
        "amoled" -> AppPalette(Color.Black, Color.Black, Color(0xFF101014), Color.White, Color(0xFFAAAAAF), Color(0xFF29292F))
        else -> AppPalette(Color(0xFF0D0D10), Color(0xFF151519), Color(0xFF202026), Color.White, Color(0xFFAAAAAF), Color(0xFF303038))
    }
    val scope = rememberCoroutineScope()
    val appContext = androidx.compose.ui.platform.LocalContext.current

    var tab by remember { mutableStateOf(AppTab.HOME) }
    var nowPlaying by remember { mutableStateOf<Track?>(null) }
    var playbackQueue by remember { mutableStateOf(emptyList<Track>()) }
    var playbackIndex by remember { mutableIntStateOf(-1) }
    var showNowPlaying by remember { mutableStateOf(false) }
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
    var recentTracks by remember { mutableStateOf(historyStore.recent()) }
    var mostPlayedTracks by remember { mutableStateOf(historyStore.mostPlayed()) }

    LaunchedEffect(sessionTracks, sessionIndex) {
        if (sessionTracks.isNotEmpty() && sessionIndex in sessionTracks.indices) {
            playbackQueue = sessionTracks
            playbackIndex = sessionIndex
            nowPlaying = sessionTracks[sessionIndex]
        } else if (sessionTracks.isEmpty() && sessionIndex == -1) {
            playbackQueue = emptyList()
            playbackIndex = -1
            nowPlaying = null
            showNowPlaying = false
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
        while (nowPlaying != null) {
            positionMs = onCurrentPosition().coerceAtLeast(0L)
            durationMs = onCurrentDuration().coerceAtLeast(0L)
            delay(250L)
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
    var selectedCategory by remember { mutableStateOf("Pakistani") }
    var results by remember { mutableStateOf(emptyList<Track>()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    // Explore / trending state
    var trending by remember { mutableStateOf(emptyList<Track>()) }
    var trendingLoading by remember { mutableStateOf(false) }
    var trendingError by remember { mutableStateOf<String?>(null) }
    var trendingLoaded by remember { mutableStateOf(false) }

    fun play(track: Track, queue: List<Track>) {
        val safeQueue = (queue.ifEmpty { listOf(track) }).distinctBy { it.id }
        playbackQueue = safeQueue
        playbackIndex = safeQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        nowPlaying = safeQueue[playbackIndex]
        historyStore.record(safeQueue[playbackIndex])
        recentTracks = historyStore.recent()
        mostPlayedTracks = historyStore.mostPlayed()
        onPlayQueue(safeQueue, playbackIndex)
    }

    fun skipTo(index: Int) {
        if (index !in playbackQueue.indices) return
        playbackIndex = index
        nowPlaying = playbackQueue[index]
        historyStore.record(playbackQueue[index])
        recentTracks = historyStore.recent()
        mostPlayedTracks = historyStore.mostPlayed()
        onPlayQueue(playbackQueue, index)
    }

    fun toggleSave(track: Track) {
        val index = saved.indexOfFirst { it.id == track.id }
        if (index >= 0) saved.removeAt(index) else saved.add(0, track)
        library.save(saved.toList())
    }

    fun runSearch() {
        if (query.isBlank()) return
        scope.launch {
            searching = true
            searchError = null
            runCatching { repo.search("${selectedCategory} $query") }
                .onSuccess { results = it }
                .onFailure { searchError = it.message ?: "Search failed" }
            searching = false
        }
    }

    fun loadTrending(category: String = selectedCategory) {
        selectedCategory = category
        scope.launch {
            trendingLoading = true
            trendingError = null
            runCatching { repo.search("${categoryQuery(selectedCategory)} music") }
                .onSuccess { trending = it; trendingLoaded = true }
                .onFailure { trendingError = it.message ?: "Could not load trending" }
            trendingLoading = false
        }
    }

    LaunchedEffect(tab) {
        if (tab == AppTab.EXPLORE && !trendingLoaded && !trendingLoading) loadTrending()
    }

    MaterialTheme(
        colorScheme = if (appearanceMode == "light") {
            lightColorScheme(primary = accent, onPrimary = Color.White, background = palette.background, surface = palette.surface, onBackground = palette.text, onSurface = palette.text)
        } else {
            darkColorScheme(primary = accent, onPrimary = Color.Black, background = palette.background, surface = palette.surface, onBackground = palette.text, onSurface = palette.text)
        }
    ) {
      CompositionLocalProvider(LocalAppPalette provides palette) {
        Scaffold(
            containerColor = palette.background,
            bottomBar = {
                Column {
                    MiniPlayer(
                        nowPlaying, isPlaying, accent,
                        onPrevious = onPrevious,
                        onToggle = onToggle,
                        onNext = onNext,
                        onOpen = { showNowPlaying = true }
                    )
                    NavigationBar(containerColor = palette.surface) {
                        AppTab.values().forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = if (appearanceMode == "light") Color.White else Color.Black,
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
                if (showNowPlaying && nowPlaying != null) {
                    NowPlayingScreen(
                        track = nowPlaying!!, queue = playbackQueue, currentIndex = playbackIndex,
                        isPlaying = isPlaying, palette = palette, accent = accent, speed = playbackSpeed,
                        sleepMinutes = sleepMinutes, onClose = { showNowPlaying = false }, onToggle = onToggle,
                        onPrevious = onPrevious,
                        onNext = onNext,
                        onSelectTrack = { skipTo(it) },
                        onSpeedChange = { speed -> playbackSpeed = speed; onSpeedChange(speed) },
                        onSleepTimerChange = { sleepMinutes = it },
                        lyrics = lyricsText,
                        positionMs = positionMs,
                        durationMs = durationMs.takeIf { it > 0L } ?: (nowPlaying?.duration ?: 0L),
                        onSeekTo = { position -> positionMs = position; onSeekTo(position) },
                        shuffleEnabled = shuffleEnabled,
                        repeatMode = repeatMode,
                        onShuffleToggle = { shuffleEnabled = !shuffleEnabled; onShuffleChange(shuffleEnabled) },
                        onRepeatCycle = { repeatMode = when (repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }; onRepeatModeChange(repeatMode) },
                        onLyricsChange = { text -> lyricsText = text; nowPlaying?.let { lyricsStore.save(it.id, text) } }
                    )
                } else when (tab) {
                    AppTab.HOME -> HomeScreen(
                        query = query,
                        onQueryChange = { query = it },
                        category = selectedCategory,
                        onCategoryChange = { selectedCategory = it; runSearch() },
                        onSearch = { runSearch() },
                        loading = searching,
                        error = searchError,
                        tracks = results,
                        accent = accent,
                        savedIds = savedIds,
                        onPlay = { play(it, results) },
                        onToggleSave = { toggleSave(it) },
                        onDownload = onDownload
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
                    AppTab.LIBRARY -> LibraryScreen(
                        tracks = saved.toList(),
                        recentTracks = recentTracks,
                        mostPlayedTracks = mostPlayedTracks,
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
                    AppTab.SETTINGS -> SettingsScreen(
                        accentName = accentName,
                        dynamicTheme = dynamicTheme,
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
                        }
                    )
                }
            }
        }
      }
    }
}

private val musicCategories = listOf("Pakistani", "Indian", "Hollywood", "Lollywood", "Hindi")

private fun categoryQuery(category: String): String = when (category) {
    "Pakistani" -> "Pakistani"
    "Indian" -> "Indian"
    "Hollywood" -> "Hollywood"
    "Lollywood" -> "Lollywood"
    "Hindi" -> "Hindi"
    else -> "Pakistani"
}

@Composable
private fun CategoryPicker(selected: String, onSelected: (String) -> Unit, accent: Color) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 6.dp)
    ) {
        items(musicCategories) { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelected(category) },
                label = { Text(category) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = accent,
                    selectedLabelColor = Color.Black
                )
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
    onDownload: (Track) -> Unit
 ) {
    val palette = LocalAppPalette.current
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader("JD musax", "MUSIC, WITHOUT THE NOISE.")
        CategoryPicker(category, onCategoryChange, accent)
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search tracks on Audius") },
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
                emptyTitle = "Find your next repeat.",
                emptySubtitle = "Search Audius to discover independent music.",
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
            Column(Modifier.weight(1f)) { ScreenHeader("Explore", "TRENDING ON AUDIUS") }
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
                emptyTitle = "Nothing trending yet",
                emptySubtitle = "Tap refresh to try again.",
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
    recentTracks: List<Track>,
    mostPlayedTracks: List<Track>,
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
    val visibleTracks = when (selectedCollection) {
        "Recently played" -> recentTracks
        "Most played" -> mostPlayedTracks
        else -> tracks
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { ScreenHeader("Library", "YOUR FAVORITES & MUSIC") }
            FilledTonalButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Import")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("Favorites", "Recently played", "Most played").forEach { collection ->
                FilterChip(selected = selectedCollection == collection, onClick = { selectedCollection = collection }, label = { Text(collection, fontSize = 11.sp) })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            TrackContent(
                loading = false,
                error = null,
                tracks = visibleTracks,
                emptyTitle = when (selectedCollection) { "Recently played" -> "Nothing played yet"; "Most played" -> "No listening stats yet"; else -> "Nothing saved yet" },
                emptySubtitle = when (selectedCollection) { "Recently played" -> "Tracks you play will appear here."; "Most played" -> "Your most-played tracks will build up here."; else -> "Tap the heart on any track to keep it here." },
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
    Column {
        Spacer(Modifier.height(22.dp))
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White)
        Text(subtitle, color = Color.Gray, fontSize = 10.sp, letterSpacing = 2.sp)
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
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = Color.Gray)
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
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        color = palette.surface
    ) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = track.artwork,
            contentDescription = null,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.surfaceVariant)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = palette.text, maxLines = 1, fontWeight = FontWeight.SemiBold)
            Text(track.artist, color = palette.muted, maxLines = 1, fontSize = 12.sp)
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
                Icon(Icons.Default.Download, contentDescription = "Download", tint = accent)
            }
        }
        Icon(Icons.Default.PlayCircle, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
    }
    }
}

@Composable
private fun MiniPlayer(
    track: Track?,
    isPlaying: Boolean,
    accent: Color,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit
 ) {
    val palette = LocalAppPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable(onClick = onOpen),
        shape = RoundedCornerShape(22.dp),
        color = palette.surfaceVariant
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = onPrevious, enabled = track != null) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous track", tint = if (track != null) palette.text else palette.muted, modifier = Modifier.size(30.dp))
            }
            Box(
                Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(palette.surface),
                contentAlignment = Alignment.Center
            ) {
                if (track?.artwork != null) {
                    AsyncImage(model = track.artwork, contentDescription = "Album artwork", modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(track?.title ?: "Nothing playing", color = palette.text, maxLines = 1, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(track?.artist ?: "Choose a track to start", color = palette.muted, maxLines = 1, fontSize = 11.sp)
            }
            IconButton(onClick = onToggle, enabled = track != null) {
                Icon(
                    if (isPlaying) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = if (track != null) accent else Color.DarkGray,
                    modifier = Modifier.size(38.dp)
                )
            }
            IconButton(onClick = onNext, enabled = track != null) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next track", tint = if (track != null) palette.text else palette.muted, modifier = Modifier.size(30.dp))
            }
        }
    }
}

@Composable
private fun NowPlayingScreen(
    track: Track, queue: List<Track>, currentIndex: Int, isPlaying: Boolean,
    palette: AppPalette, accent: Color, speed: Float, sleepMinutes: Int,
    onClose: () -> Unit, onToggle: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onSelectTrack: (Int) -> Unit, onSpeedChange: (Float) -> Unit, onSleepTimerChange: (Int) -> Unit,
    lyrics: String, positionMs: Long, durationMs: Long, onSeekTo: (Long) -> Unit,
    shuffleEnabled: Boolean, repeatMode: Int, onShuffleToggle: () -> Unit, onRepeatCycle: () -> Unit,
    onLyricsChange: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close player", tint = palette.text) }
            Spacer(Modifier.weight(1f))
            Text("NOW PLAYING", color = palette.muted, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.GraphicEq, null, tint = accent)
        }
        Spacer(Modifier.height(16.dp))
        AsyncImage(model = track.artwork, contentDescription = "Album artwork",
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(1f).clip(RoundedCornerShape(26.dp)).background(palette.surfaceVariant))
        Spacer(Modifier.height(24.dp))
        Text(track.title, color = palette.text, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        Text(track.artist, color = palette.muted, fontSize = 15.sp, maxLines = 1)
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Up next", color = palette.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                Text(queue.size.toString() + " tracks", color = palette.muted, fontSize = 12.sp)
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
                    }
                }
            }
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
