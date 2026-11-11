package com.jdmusax.app

import android.app.DownloadManager
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
import kotlinx.coroutines.delay

@Composable
internal fun DownloadsScreen(
    store: OfflineAudioStore,
    accent: Color,
    onPlayOffline: (Track) -> Unit
) {
    val palette = LocalAppPalette.current
    var downloads by remember { mutableStateOf(store.all()) }

    LaunchedEffect(store) {
        while (true) {
            downloads = store.all()
            delay(1_000L)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Downloads", color = palette.text, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("MANAGE OFFLINE AUDIO", color = palette.muted, fontSize = 10.sp, letterSpacing = 1.3.sp)
            }
            IconButton(onClick = { downloads = store.all() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh downloads", tint = accent)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DownloadSummary("All", downloads.size, accent, Modifier.weight(1f))
            DownloadSummary("Active", downloads.count { it.isActive }, accent, Modifier.weight(1f))
            DownloadSummary("Done", downloads.count { it.isComplete }, accent, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        if (downloads.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.DownloadForOffline, contentDescription = null, tint = accent, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Nothing downloading yet", color = palette.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(5.dp))
                    Text("Use the download icon beside supported audio. Your downloads will appear here.", color = palette.muted, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(downloads, key = { it.downloadId }) { item ->
                    Surface(color = palette.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    when {
                                        item.isComplete -> Icons.Default.CheckCircle
                                        item.isFailed -> Icons.Default.ErrorOutline
                                        else -> Icons.Default.Downloading
                                    },
                                    contentDescription = null,
                                    tint = if (item.isFailed) MaterialTheme.colorScheme.error else accent
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.track.title, color = palette.text, maxLines = 2, fontWeight = FontWeight.SemiBold)
                                    Text(item.track.artist, color = palette.muted, maxLines = 1, fontSize = 11.sp)
                                }
                                Text(downloadStatus(item.status), color = if (item.isFailed) MaterialTheme.colorScheme.error else accent, fontSize = 11.sp)
                            }
                            if (item.isActive) {
                                if (item.totalBytes > 0L) {
                                    LinearProgressIndicator(
                                        progress = { item.progress },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = accent
                                    )
                                    Text(
                                        "${(item.progress * 100).toInt()}% · ${formatDownloadBytes(item.downloadedBytes)} / ${formatDownloadBytes(item.totalBytes)}",
                                        color = palette.muted, fontSize = 11.sp
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = accent)
                                    Text("Waiting for file size…", color = palette.muted, fontSize = 11.sp)
                                }
                            } else if (item.isFailed) {
                                Text("Download failed (code ${item.reason}). You can retry it.", color = palette.muted, fontSize = 11.sp)
                            } else if (item.isComplete) {
                                Text("Saved for offline listening · ${formatDownloadBytes(item.totalBytes)}", color = palette.muted, fontSize = 11.sp)
                            } else if (item.status == DownloadManager.STATUS_PAUSED) {
                                Text("Android DownloadManager has paused this transfer (code ${item.reason}).", color = palette.muted, fontSize = 11.sp)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                if (item.isComplete && item.uri != null) {
                                    TextButton(onClick = {
                                        onPlayOffline(item.track.copy(localUri = item.uri.toString(), streamUrl = null))
                                    }) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                                        Text("Play")
                                    }
                                }
                                if (item.isFailed) {
                                    TextButton(onClick = {
                                        store.retry(item.downloadId)
                                        downloads = store.all()
                                    }) {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                        Text("Retry")
                                    }
                                }
                                TextButton(onClick = {
                                    store.remove(item.downloadId)
                                    downloads = store.all()
                                }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null)
                                    Text(if (item.isActive) "Cancel" else "Remove")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadSummary(label: String, count: Int, accent: Color, modifier: Modifier = Modifier) {
    val palette = LocalAppPalette.current
    Surface(color = palette.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(count.toString(), color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(label, color = palette.muted, fontSize = 11.sp)
        }
    }
}

private fun downloadStatus(status: Int): String = when (status) {
    DownloadManager.STATUS_PENDING -> "Queued"
    DownloadManager.STATUS_RUNNING -> "Downloading"
    DownloadManager.STATUS_PAUSED -> "Paused"
    DownloadManager.STATUS_SUCCESSFUL -> "Completed"
    DownloadManager.STATUS_FAILED -> "Failed"
    else -> "Unknown"
}

private fun formatDownloadBytes(bytes: Long): String {
    if (bytes < 1_024L) return "$bytes B"
    val kb = bytes / 1_024.0
    if (kb < 1_024.0) return String.format("%.1f KB", kb)
    val mb = kb / 1_024.0
    if (mb < 1_024.0) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1_024.0)
}
