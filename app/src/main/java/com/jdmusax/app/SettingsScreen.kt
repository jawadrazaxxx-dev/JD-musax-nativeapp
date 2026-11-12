package com.jdmusax.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun getAccent(): String = prefs.getString("accent", "gold") ?: "gold"
    fun setAccent(value: String) {
        if (value in ACCENT_OPTIONS) prefs.edit().putString("accent", value).apply()
    }
    fun isDynamicThemeEnabled(): Boolean = prefs.getBoolean("dynamic_theme", false)
    fun getAudioTheme(): String = prefs.getString("audio_theme", "artwork") ?: "artwork"
    fun setAudioTheme(value: String) { if (value in AUDIO_THEMES) prefs.edit().putString("audio_theme", value).apply() }
    fun setDynamicThemeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("dynamic_theme", enabled).apply()
    }
    fun getAppearanceMode(): String = prefs.getString("appearance_mode", "dark") ?: "dark"
    fun setAppearanceMode(mode: String) {
        if (mode in APPEARANCE_MODES) prefs.edit().putString("appearance_mode", mode).apply()
    }

    companion object {
        val ACCENT_OPTIONS = setOf(
            "gold", "violet", "indigo", "blue", "cyan", "teal", "mint",
            "green", "lime", "yellow", "orange", "red", "rose", "pink"
        )
        val APPEARANCE_MODES = setOf("light", "dark", "amoled")
        val AUDIO_THEMES = setOf("artwork", "aurora", "neon", "ocean", "sunset", "forest", "minimal")
    }
}

private val accentOptions = listOf(
    "gold" to "Gold", "violet" to "Violet", "indigo" to "Indigo", "blue" to "Blue",
    "cyan" to "Cyan", "teal" to "Teal", "mint" to "Mint",
    "green" to "Green", "lime" to "Lime", "yellow" to "Yellow",
    "orange" to "Orange", "red" to "Red", "rose" to "Rose",
    "pink" to "Pink"
)

private val appearanceOptions = listOf(
    "light" to "Light",
    "dark" to "Dark",
    "amoled" to "AMOLED"
)

@Composable
fun SettingsScreen(
    accentName: String,
    dynamicTheme: Boolean,
    audioTheme: String,
    onAudioThemeChange: (String) -> Unit,
    onAccentChange: (String) -> Unit,
    onDynamicThemeChange: (Boolean) -> Unit,
    appearanceMode: String,
    onAppearanceModeChange: (String) -> Unit,
    audioBands: List<AudioBand>,
    audioPresets: List<String>,
    onBandLevelChange: (Int, Int) -> Unit,
    onPresetChange: (Int) -> Unit,
    visualizerEnabled: Boolean,
    visualizerLevels: List<Float>,
    onVisualizerToggle: (Boolean) -> Unit
) {
    val palette = LocalAppPalette.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.Black, color = palette.text)
        Text("MAKE SHIAUDIO YOURS", color = palette.muted, fontSize = 10.sp, letterSpacing = 2.sp)
        Text("Emerald edition · Modern Islamic design", color = palette.muted, fontSize = 12.sp)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = palette.surface)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Appearance", color = palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Display mode", color = palette.muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    appearanceOptions.forEach { (key, label) ->
                        FilterChip(
                            selected = appearanceMode == key,
                            onClick = { onAppearanceModeChange(key) },
                            label = { Text(label) }
                        )
                    }
                }
                Text(
                    when (appearanceMode) {
                        "light" -> "Bright surfaces for daytime listening."
                        "amoled" -> "Pure black backgrounds for OLED displays and lower pixel power use."
                        else -> "Comfortable dark surfaces for low-light listening."
                    },
                    color = palette.muted,
                    fontSize = 12.sp
                )
                Text("Accent palette", color = palette.muted, fontSize = 13.sp)
                accentOptions.chunked(3).forEach { group ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        group.forEach { (key, label) ->
                            FilterChip(
                                selected = accentName == key && !dynamicTheme,
                                onClick = { onAccentChange(key) },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Match album artwork", color = palette.text, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Automatically sample the current track thumbnail and use its colors for the app accent.",
                            color = palette.muted,
                            fontSize = 12.sp
                        )
                    }
                    Switch(checked = dynamicTheme, onCheckedChange = onDynamicThemeChange)
                }
                Text(
                    "Your display mode and accent are saved on this device.",
                    color = palette.muted,
                    fontSize = 12.sp
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = palette.surface)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Audio Themes", color = palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Choose the animated atmosphere shown behind the Now Playing screen.", color = palette.muted, fontSize = 12.sp)
                val themes = listOf("artwork" to "Artwork", "aurora" to "Aurora", "neon" to "Neon", "ocean" to "Ocean", "sunset" to "Sunset", "forest" to "Forest", "minimal" to "Minimal")
                themes.chunked(2).forEach { group ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.forEach { (key, label) -> FilterChip(selected = audioTheme == key, onClick = { onAudioThemeChange(key) }, label = { Text(label) }) }
                    }
                }
                Text("Selection is saved on this device. Artwork mode follows the current album cover accent.", color = palette.muted, fontSize = 11.sp)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = palette.surface)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Playback", color = palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Background playback and Android media controls are provided by Media3.", color = palette.muted, fontSize = 13.sp)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = palette.surface)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Audio tools", color = palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Equalizer", color = palette.text, fontWeight = FontWeight.SemiBold)
                if (audioBands.isEmpty()) {
                    Text("Start a track to connect the equalizer to its audio session.", color = palette.muted, fontSize = 12.sp)
                } else {
                    Text("Presets", color = palette.muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        audioPresets.forEachIndexed { index, label ->
                            FilterChip(
                                selected = false,
                                onClick = { onPresetChange(index) },
                                label = { Text(label, fontSize = 11.sp) },
                                enabled = index < audioPresets.size
                            )
                        }
                    }
                    audioBands.forEach { band ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(band.label, color = palette.text, fontSize = 12.sp)
                                Spacer(Modifier.weight(1f))
                                Text("%+.1f dB".format(band.levelMb / 100f), color = palette.muted, fontSize = 11.sp)
                            }
                            androidx.compose.material3.Slider(
                                value = band.levelMb / 100f,
                                onValueChange = { onBandLevelChange(band.index, (it * 100).toInt()) },
                                valueRange = (band.minLevelMb / 100f)..(band.maxLevelMb / 100f)
                            )
                        }
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = palette.border)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Visualizer", color = palette.text, fontWeight = FontWeight.SemiBold)
                        Text("Live frequency response from the playing track", color = palette.muted, fontSize = 12.sp)
                    }
                    Switch(checked = visualizerEnabled, onCheckedChange = onVisualizerToggle)
                }
                if (visualizerEnabled && visualizerLevels.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(92.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        visualizerLevels.take(48).forEach { level ->
                            androidx.compose.foundation.layout.Box(
                                Modifier.width(4.dp)
                                    .height((8f + level.coerceIn(0f, 1f) * 76f).dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(androidx.compose.material3.MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                } else if (visualizerEnabled) {
                    Text("Waiting for audio data…", color = palette.muted, fontSize = 12.sp)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = palette.surface)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Privacy & sources", color = palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "SHIAudio has no account requirement. Your saved library, playlists, bookmarks, listening history and playback progress are stored locally on this device.",
                    color = palette.muted, fontSize = 12.sp
                )
                Text(
                    "When you open an external provider, that website or app applies its own privacy policy and content rules. SHIAudio does not bypass download restrictions or claim external-only sources as in-app playback.",
                    color = palette.muted, fontSize = 12.sp
                )
                Text(
                    "Audio visualizer access is optional and is requested only when you enable the visualizer.",
                    color = palette.muted, fontSize = 12.sp
                )
            }
        }

        Text("SHIAudio • Native Android edition", color = palette.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
