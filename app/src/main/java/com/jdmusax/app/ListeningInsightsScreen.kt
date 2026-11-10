package com.jdmusax.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import java.time.LocalDate

@Composable
internal fun ListeningInsightsScreen(
    snapshot: ListeningSnapshot,
    challenge: ListeningChallenge,
    todayMs: Long,
    offlineTracks: List<Track>,
    accent: Color,
    onGoalChange: (Int) -> Unit,
    onChallengeToggle: (Boolean) -> Unit,
    onPlayOffline: (Track) -> Unit,
    onRemoveOffline: (Long) -> Unit
) {
    val palette = LocalAppPalette.current
    val goalMs = challenge.goalMinutes * 60_000L
    val progress = if (goalMs > 0L) (todayMs.toFloat() / goalMs).coerceIn(0f, 1f) else 0f
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text("Listening insights", color = palette.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("YOUR ACTIVITY • GOALS • OFFLINE AUDIO", color = palette.muted, fontSize = 10.sp, letterSpacing = 1.3.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InsightMetric("Total listening", formatListeningDuration(snapshot.totalMs), Modifier.weight(1f), accent)
                InsightMetric("Today", formatListeningDuration(todayMs), Modifier.weight(1f), accent)
            }
        }
        item {
            Surface(color = palette.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Daily listening challenge", color = palette.text, fontWeight = FontWeight.Bold)
                            Text("Build a consistent listening habit", color = palette.muted, fontSize = 12.sp)
                        }
                        Switch(checked = challenge.enabled, onCheckedChange = onChallengeToggle)
                    }
                    if (challenge.enabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${(todayMs / 60_000L)} / ${challenge.goalMinutes} min", color = palette.text, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text(if (progress >= 1f) "Goal reached ✓" else "${(progress * 100).toInt()}%", color = accent, fontSize = 12.sp)
                        }
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = accent)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 30, 45, 60).forEach { minutes ->
                                FilterChip(
                                    selected = challenge.goalMinutes == minutes,
                                    onClick = { onGoalChange(minutes) },
                                    label = { Text("${minutes}m") }
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Surface(color = palette.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Last 7 days", color = palette.text, fontWeight = FontWeight.Bold)
                    val maxMs = (snapshot.dailyTotals.values.maxOrNull() ?: 1L).coerceAtLeast(1L)
                    Row(Modifier.fillMaxWidth().height(100.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        snapshot.dailyTotals.forEach { (date, value) ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                                Box(
                                    Modifier.fillMaxWidth().height((72f * value.toFloat() / maxMs).coerceAtLeast(4f).dp)
                                        .background(accent.copy(alpha = 0.85f), RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(date.dayOfWeek.name.take(1), color = palette.muted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
        item { InsightRanking("Top authors", snapshot.authorTotals, accent) }
        item { InsightRanking("Top categories", snapshot.categoryTotals, accent) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Offline library", color = palette.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("${offlineTracks.size} downloaded files available", color = palette.muted, fontSize = 11.sp)
                }
                Icon(Icons.Default.OfflinePin, contentDescription = null, tint = accent)
            }
        }
        if (offlineTracks.isEmpty()) {
            item {
                Text("No completed downloads yet. Use the download icon on supported audio to save it for offline listening.", color = palette.muted, fontSize = 12.sp)
            }
        } else {
            items(offlineTracks, key = { it.id }) { track ->
                Surface(color = palette.surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DownloadDone, contentDescription = null, tint = accent)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, color = palette.text, maxLines = 1, fontWeight = FontWeight.SemiBold)
                            Text(track.artist, color = palette.muted, maxLines = 1, fontSize = 11.sp)
                        }
                        IconButton(onClick = { onPlayOffline(track) }) { Icon(Icons.Default.PlayArrow, contentDescription = "Play offline", tint = accent) }
                        IconButton(onClick = { onRemoveOffline(track.id.removePrefix("download:").toLongOrNull() ?: -1L) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Remove download", tint = palette.muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightMetric(title: String, value: String, modifier: Modifier, accent: Color) {
    val palette = LocalAppPalette.current
    Surface(color = palette.surface, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = palette.muted, fontSize = 11.sp)
            Text(value, color = accent, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun InsightRanking(title: String, values: Map<String, Long>, accent: Color) {
    val palette = LocalAppPalette.current
    Surface(color = palette.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, color = palette.text, fontWeight = FontWeight.Bold)
            val top = values.entries.sortedByDescending { it.value }.take(5)
            if (top.isEmpty()) Text("Your listening activity will appear here.", color = palette.muted, fontSize = 12.sp)
            top.forEachIndexed { index, entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}.", color = accent, modifier = Modifier.width(24.dp))
                    Text(entry.key, color = palette.text, modifier = Modifier.weight(1f), maxLines = 1, fontSize = 13.sp)
                    Text(formatListeningDuration(entry.value), color = palette.muted, fontSize = 11.sp)
                }
            }
        }
    }
}

private fun formatListeningDuration(milliseconds: Long): String {
    val minutes = (milliseconds.coerceAtLeast(0L) / 60_000L)
    val hours = minutes / 60L
    return if (hours > 0L) "${hours}h ${minutes % 60}m" else "${minutes}m"
}
