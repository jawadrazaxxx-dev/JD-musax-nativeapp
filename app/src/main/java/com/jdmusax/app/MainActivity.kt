package com.jdmusax.app

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private val repo = AudiusRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture.addListener({
            controller = runCatching { controllerFuture.get() }.getOrNull()
        }, MoreExecutors.directExecutor())

        setContent { JDMusaxApp(repo, onPlay = { track ->
            lifecycleScope.launch {
                val url = runCatching { repo.resolveStream(track.id) }.getOrNull() ?: return@launch
                val playable = track.copy(streamUrl = url)
                controller?.apply {
                    setMediaItem(playable.mediaItem())
                    prepare()
                    play()
                }
            }
        }) }
    }

    override fun onDestroy() {
        controller?.release()
        super.onDestroy()
    }
}

@Composable
private fun JDMusaxApp(repo: AudiusRepository, onPlay: (Track) -> Unit) {
    var query by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf(emptyList<Track>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var accentMode by remember { mutableStateOf("Artwork") }
    var accent by remember { mutableStateOf(Color(0xFFB7A4FF)) }
    val animatedAccent by animateColorAsState(accent, label = "accent")
    val scope = rememberCoroutineScope()

    MaterialTheme(colorScheme = darkColorScheme(
        background = Color.Black, surface = Color(0xFF101010),
        primary = animatedAccent, onPrimary = Color.Black,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Scaffold(containerColor = Color.Black, bottomBar = {
            NavigationBar(containerColor = Color(0xFF080808)) {
                NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Default.Explore, null) }, label = { Text("Explore") })
                NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Library") })
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(22.dp))
                Text("JD musax", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White)
                Text("MUSIC, WITHOUT THE NOISE.", color = Color.Gray, fontSize = 10.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(22.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search tracks on Audius") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = {
                            if (query.isNotBlank()) scope.launch {
                                loading = true; error = null
                                runCatching { repo.search(query) }
                                    .onSuccess { tracks = it }
                                    .onFailure { error = it.message ?: "Search failed" }
                                loading = false
                            }
                        }) { Icon(Icons.Default.ArrowForward, "Search", tint = animatedAccent) }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = animatedAccent, unfocusedBorderColor = Color.DarkGray,
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White
                    )
                )
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("YOUR SOUND", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.weight(1f))
                    Text("ACCENT", color = Color.Gray, fontSize = 10.sp)
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = accentMode == "Artwork", onClick = {
                        accentMode = "Artwork"; accent = Color(0xFFB7A4FF)
                    }, label = { Text("Artwork") })
                    FilterChip(selected = accentMode == "Custom", onClick = {
                        accentMode = "Custom"; accent = Color(0xFF63C7B2)
                    }, label = { Text("Custom") })
                }
                Spacer(Modifier.height(12.dp))
                MiniPlayer(accent = animatedAccent)
                Spacer(Modifier.height(18.dp))
                when {
                    loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = animatedAccent) }
                    error != null -> Text(error!!, color = Color(0xFFFF7777))
                    tracks.isNotEmpty() -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(tracks) { track -> TrackRow(track, animatedAccent) { onPlay(track) } }
                    }
                    else -> Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center) {
                        Text("Find your next repeat.", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("Search Audius to discover independent music.", color = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(accent: Color) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(112.dp)) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF1A1A1A)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(72.dp).background(Color(0xFF080808), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.GraphicEq, null, tint = accent, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Nothing playing", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Choose a track to begin", color = Color.Gray, fontSize = 12.sp)
                    }
                    IconButton(onClick = {}) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White) }
                    IconButton(onClick = {}) { Icon(Icons.Default.PlayArrow, null, tint = accent) }
                    IconButton(onClick = {}) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
                }
            }
        }
        // Rounded drip-like tabs echo the supplied mini-player reference.
        Row(Modifier.padding(start = 18.dp, end = 18.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            repeat(3) { Box(Modifier.width(28.dp).height(9.dp).background(Color(0xFF1A1A1A), RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))) }
        }
    }
}

@Composable
private fun TrackRow(track: Track, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(model = track.artwork, contentDescription = null,
            modifier = Modifier.size(52.dp).background(Color.DarkGray, RoundedCornerShape(10.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = Color.White, maxLines = 1, fontWeight = FontWeight.Medium)
            Text(track.artist, color = Color.Gray, maxLines = 1, fontSize = 12.sp)
        }
        Icon(Icons.Default.PlayCircle, null, tint = accent, modifier = Modifier.size(28.dp))
    }
}
