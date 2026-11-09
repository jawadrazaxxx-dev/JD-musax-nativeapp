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
import androidx.compose.foundation.animateContentSize
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

private data class AppPalette(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val text: Color,
    val muted: Color,
    val border: Color
)
private val LocalAppPalette = compositionLocalOf {
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
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPlayQueue: (List<Track>, Int) -> Unit,
    onDownload: (Track) -> Unit
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

    var tab by remember { mutableStateOf(AppTab.HOME) }
    var nowPlaying by remember { mutableStateOf<Track?>(null) }
    var playbackQueue by remember { mutableStateOf(emptyList<Track>()) }
    var playbackIndex by remember { mutableIntStateOf(-1) }
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
                        onPrevious = { if (playbackIndex > 0) skipTo(playbackIndex - 1) else onPrevious() },
                        onToggle = onToggle,
                        onNext = { if (playbackIndex >= 0 && playbackIndex < playbackQueue.lastIndex) skipTo(playbackIndex + 1) else onNext() }
                    )
                    NavigationBar(containerColor = palette.surface) {
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
                        accent = accent,
                        savedIds = savedIds,
                        onPlay = { play(it, saved.toList()) },
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
    accent: Color,
    savedIds: Set<String>,
    onPlay: (Track) -> Unit,
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
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { ScreenHeader("Library", "YOUR FAVORITES & MUSIC") }
            FilledTonalButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Import")
            }
        }
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
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(tracks) { track ->
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
    onNext: () -> Unit
 ) {
    val palette = LocalAppPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
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
