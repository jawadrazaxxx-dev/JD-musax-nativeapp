package com.jdmusax.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

class AudiusRepository {
    private val client = OkHttpClient()
    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url("https://api.audius.co/v1/tracks/search?query=$encoded&limit=25&app_name=JDmusax")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Audius request failed: ${response.code}")
            val body = response.body?.string() ?: return@withContext emptyList()
            val data = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
            (0 until data.length()).mapNotNull { i ->
                data.optJSONObject(i)?.let(Track::fromAudius)
            }
        }
    }

    suspend fun resolveStream(trackId: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.audius.co/v1/tracks/$trackId/stream?app_name=JDmusax")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Could not resolve stream: ${response.code}")
            response.request.url.toString()
        }
    }
}
