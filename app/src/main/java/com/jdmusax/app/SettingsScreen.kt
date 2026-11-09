package com.jdmusax.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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

    fun getAccent(): String = prefs.getString("accent", "violet") ?: "violet"

    fun setAccent(value: String) {
        if (value in ACCENT_OPTIONS) prefs.edit().putString("accent", value).apply()
    }

    fun isDynamicThemeEnabled(): Boolean = prefs.getBoolean("dynamic_theme", false)

    fun setDynamicThemeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("dynamic_theme", enabled).apply()
    }

    companion object {
        val ACCENT_OPTIONS = setOf(
            "violet", "indigo", "blue", "cyan", "teal", "mint",
            "green", "lime", "yellow", "orange", "red", "rose", "pink"
        )
    }
}

private val accentOptions = listOf(
    "violet" to "Violet", "indigo" to "Indigo", "blue" to "Blue",
    "cyan" to "Cyan", "teal" to "Teal", "mint" to "Mint",
    "green" to "Green", "lime" to "Lime", "yellow" to "Yellow",
    "orange" to "Orange", "red" to "Red", "rose" to "Rose",
    "pink" to "Pink"
)

@Composable
fun SettingsScreen(
    accentName: String,
    dynamicTheme: Boolean,
    onAccentChange: (String) -> Unit,
    onDynamicThemeChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White)
        Text("MAKE JD MUSAX YOURS", color = Color.Gray, fontSize = 10.sp, letterSpacing = 2.sp)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF151515))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Appearance", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Accent palette", color = Color.LightGray, fontSize = 13.sp)
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Match album artwork", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Automatically sample the current track thumbnail and use its colors for the app accent.",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                    Switch(checked = dynamicTheme, onCheckedChange = onDynamicThemeChange)
                }
                Text(
                    "When artwork matching is off, your selected accent is saved and restored on this device.",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF151515))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Playback", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Background playback and Android media controls are provided by Media3.", color = Color.LightGray, fontSize = 13.sp)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF151515))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Audio tools", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Equalizer", color = Color.LightGray, fontWeight = FontWeight.SemiBold)
                Text("Not connected yet — the native Android audio-session integration is still required.", color = Color.Gray, fontSize = 12.sp)
                Text("Visualizer", color = Color.LightGray, fontWeight = FontWeight.SemiBold)
                Text("Not connected yet — no inactive or simulated audio controls are shown.", color = Color.Gray, fontSize = 12.sp)
            }
        }

        Text(
            "JD musax • Native Android edition",
            color = Color.DarkGray,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
