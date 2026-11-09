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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun getAccent(): String = prefs.getString("accent", "violet") ?: "violet"

    fun setAccent(value: String) {
        if (value in setOf("violet", "blue", "mint", "rose")) {
            prefs.edit().putString("accent", value).apply()
        }
    }
}

@Composable
fun SettingsScreen(
    accentName: String,
    onAccentChange: (String) -> Unit
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
                Text("Accent color", color = Color.LightGray, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "violet" to "Violet",
                        "blue" to "Blue",
                        "mint" to "Mint",
                        "rose" to "Rose"
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = accentName == key,
                            onClick = { onAccentChange(key) },
                            label = { Text(label) }
                        )
                    }
                }
                Text(
                    "Your choice is saved on this device and restored when JD musax opens again.",
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
