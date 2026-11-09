package com.jdmusax.app

import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
                })
                controller = c
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
                onToggle = {
                    controller?.let { c -> if (c.isPlaying) c.pause() else c.play() }
                },
                onPlay = { track ->
                    lifecycleScope.launch {
                        val url = runCatching { repo.resolveStream(track.id) }.getOrNull()
                            ?: return@launch
                        val playable = track.copy(streamUrl = url)
                        controller?.apply {
                            setMediaItem(playable.mediaItem())
                            prepare()
                            play()
                        }
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
                        duration = o.optLong("duration", 0L)
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
    onToggle: () -> Unit,
    onPlay: (Track) -> Unit
) {
    var accentName by remember { mutableStateOf(settings.getAccent()) }
    var dynamicTheme by remember { mutableStateOf(settings.isDynamicThemeEnabled()) }
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
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(AppTab.HOME) }
    var nowPlaying by remember { mutableStateOf<Track?>(null) }
    val saved = remember { mutableStateListOf<Track>().apply { addAll(library.load()) } }
    val savedIds = saved.map { it.id }.toSet()

    LaunchedEffect(nowPlaying?.artwork, dynamicTheme) {
        if (dynamicTheme) {
            artworkAccent = extractArtworkAccent(nowPlaying?.artwork)
        } else {
            artworkAccent = null
        }
    }

    // Home / search state (kept here so it survives tab switches)
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<Track>()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    // Explore / trending state
    var trending by remember { mutableStateOf(emptyList<Track>()) }
    var trendingLoading by remember { mutableStateOf(false) }
    var trendingError by remember { mutableStateOf<String?>(null) }
    var trendingLoaded by remember { mutableStateOf(false) }

    fun play(track: Track) {
        nowPlaying = track
        onPlay(track)
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
            runCatching { repo.search(query) }
                .onSuccess { results = it }
                .onFailure { searchError = it.message ?: "Search failed" }
            searching = false
        }
    }

    fun loadTrending() {
        scope.launch {
            trendingLoading = true
            trendingError = null
            runCatching { fetchTrending() }
                .onSuccess { trending = it; trendingLoaded = true }
                .onFailure { trendingError = it.message ?: "Could not load trending" }
            trendingLoading = false
        }
    }

    LaunchedEffect(tab) {
        if (tab == AppTab.EXPLORE && !trendingLoaded && !trendingLoading) loadTrending()
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color.Black,
            surface = Color(0xFF101010),
            primary = accent,
            onPrimary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White
        )
    ) {
        Scaffold(
            containerColor = Color.Black,
            bottomBar = {
                Column {
                    MiniPlayer(nowPlaying, isPlaying, accent, onToggle)
                    NavigationBar(containerColor = Color(0xFF101010)) {
                        AppTab.values().forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color.Black,
                                    selectedTextColor = accent,
                                    indicatorColor = accent,
                                    unselectedIconColor = Color.Gray,
                                    unselectedTextColor = Color.Gray
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    AppTab.HOME -> HomeScreen(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = { runSearch() },
                        loading = searching,
                        error = searchError,
                        tracks = results,
                        accent = accent,
                        savedIds = savedIds,
                        onPlay = { play(it) },
                        onToggleSave = { toggleSave(it) }
                    )
                    AppTab.EXPLORE -> ExploreScreen(
                        loading = trendingLoading,
                        error = trendingError,
                        tracks = trending,
                        accent = accent,
                        savedIds = savedIds,
                        onRefresh = { loadTrending() },
                        onPlay = { play(it) },
                        onToggleSave = { toggleSave(it) }
                    )
                    AppTab.LIBRARY -> LibraryScreen(
                        tracks = saved.toList(),
                        accent = accent,
                        savedIds = savedIds,
                        onPlay = { play(it) },
                        onToggleSave = { toggleSave(it) }
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
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    loading: Boolean,
    error: String?,
    tracks: List<Track>,
    accent: Color,
    savedIds: Set<String>,
    onPlay: (Track) -> Unit,
    onToggleSave: (Track) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader("JD musax", "MUSIC, WITHOUT THE NOISE.")
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
                unfocusedBorderColor = Color.DarkGray,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
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
                onToggleSave = onToggleSave
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
    onRefresh: () -> Unit,
    onPlay: (Track) -> Unit,
    onToggleSave: (Track) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { ScreenHeader("Explore", "TRENDING ON AUDIUS") }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = accent)
            }
        }
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
                onToggleSave = onToggleSave
            )
        }
    }
}

@Composable
private fun LibraryScreen(
    tracks: List<Track>,
    accent: Color,
    savedIds: Set<String>,
    onPlay: (Track) -> Unit,
    onToggleSave: (Track) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader("Library", "YOUR SAVED TRACKS")
        Box(Modifier.weight(1f).fillMaxWidth()) {
            TrackContent(
                loading = false,
                error = null,
                tracks = tracks,
                emptyTitle = "Nothing saved yet",
                emptySubtitle = "Tap the heart on any track to keep it here.",
                accent = accent,
                savedIds = savedIds,
                onPlay = onPlay,
                onToggleSave = onToggleSave
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
    onToggleSave: (Track) -> Unit
) {
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = accent)
        }
        error != null -> EmptyState("Something went wrong", error)
        tracks.isEmpty() -> EmptyState(emptyTitle, emptySubtitle)
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(tracks) { track ->
                TrackRow(
                    track = track,
                    accent = accent,
                    saved = track.id in savedIds,
                    onClick = { onPlay(track) },
                    onToggleSave = { onToggleSave(track) }
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
    onToggleSave: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = track.artwork,
            contentDescription = null,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.DarkGray)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = Color.White, maxLines = 1, fontWeight = FontWeight.Medium)
            Text(track.artist, color = Color.Gray, maxLines = 1, fontSize = 12.sp)
        }
        IconButton(onClick = onToggleSave) {
            Icon(
                if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "Save to library",
                tint = if (saved) accent else Color.Gray
            )
        }
        Icon(
            Icons.Default.PlayCircle,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun MiniPlayer(track: Track?, isPlaying: Boolean, accent: Color, onToggle: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF1A1A1A)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val art = track?.artwork
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF080808)),
                contentAlignment = Alignment.Center
            ) {
                if (art != null) {
                    AsyncImage(
                        model = art,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track?.title ?: "Nothing playing",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    track?.artist ?: "Choose a track to begin",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
            IconButton(onClick = onToggle, enabled = track != null) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play or pause",
                    tint = if (track != null) accent else Color.DarkGray
                )
            }
        }
    }
}
