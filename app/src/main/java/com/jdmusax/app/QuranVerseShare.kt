package com.jdmusax.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.LinearGradient
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Builds a shareable verse card from the currently playing Al-Furqan ayah.
 * Verse text and translations are fetched from AlQuran Cloud at share time;
 * the audio provider URL is never treated as a source of Quran text.
 */
internal object QuranVerseShare {
    private val client = OkHttpClient()

    suspend fun createShareImageUri(context: Context, track: Track): Uri? = withContext(Dispatchers.IO) {
        val parts = track.id.split(':')
        if (parts.size < 4 || parts[0] != "alfurqan") return@withContext null
        val surahNumber = parts[2].toIntOrNull()?.takeIf { it in 1..114 } ?: return@withContext null
        val ayahNumber = parts[3].toIntOrNull()?.takeIf { it > 0 } ?: return@withContext null

        runCatching {
            val url = "https://api.alquran.cloud/v1/ayah/$surahNumber:$ayahNumber/editions/quran-uthmani,en.sahih,ur.jalandhry"
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            val root = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Quran text service returned HTTP ${response.code}")
                JSONObject(response.body?.string().orEmpty())
            }
            val data = root.optJSONArray("data") ?: error("Quran text was not returned")
            if (data.length() == 0) error("Arabic verse text is unavailable")
            val arabic = data.optJSONObject(0)?.optString("text").orEmpty()
            val english = data.optJSONObject(1)?.optString("text").orEmpty()
            val urdu = data.optJSONObject(2)?.optString("text").orEmpty()
            val surah = data.optJSONObject(0)?.optJSONObject("surah")
            val surahName = surah?.optString("englishName").orEmpty().ifBlank { track.title.substringBefore(" · Ayah") }
            val arabicSurahName = surah?.optString("name").orEmpty()
            if (arabic.isBlank()) error("Arabic verse text is empty")

            val bitmap = renderCard(
                arabic = arabic,
                english = english,
                urdu = urdu,
                surahName = surahName,
                arabicSurahName = arabicSurahName,
                surahNumber = surahNumber,
                ayahNumber = ayahNumber
            )
            val directory = File(context.cacheDir, "shared_verses").apply { mkdirs() }
            val imageFile = File(directory, "quran-${surahNumber}-${ayahNumber}.png")
            FileOutputStream(imageFile).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) error("Could not encode verse image")
            }
            bitmap.recycle()
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imageFile)
        }.getOrNull()
    }

    fun createShareIntent(context: Context, imageUri: Uri): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            clipData = ClipData.newUri(context.contentResolver, "Quran verse", imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun renderCard(
        arabic: String,
        english: String,
        urdu: String,
        surahName: String,
        arabicSurahName: String,
        surahNumber: Int,
        ayahNumber: Int
    ): Bitmap {
        val width = 1080
        val height = 1350
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(0xFF101B2D.toInt(), 0xFF172D3B.toInt(), 0xFF101722.toInt()),
                null, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)

        val ornament = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x22D6B878
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(RectF(42f, 42f, 1038f, 1308f), 34f, 34f, ornament)
        canvas.drawCircle(540f, 178f, 46f, ornament)
        canvas.drawCircle(540f, 178f, 30f, ornament)

        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE2C98E.toInt()
            textSize = 35f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.12f
        }
        canvas.drawText("SHIAUDIO", 540f, 275f, brandPaint)

        val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB7C5D3.toInt()
            textSize = 23f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("QURAN  •  VERSE", 540f, 326f, sectionPaint)

        val arabicPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFF8E9.toInt()
            textSize = if (arabic.length > 230) 43f else if (arabic.length > 130) 50f else 59f
            typeface = Typeface.create("serif", Typeface.NORMAL)
        }
        val arabicLayout = StaticLayout.Builder.obtain(arabic, 0, arabic.length, arabicPaint, 900)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setLineSpacing(12f, 1.12f)
            .setIncludePad(true)
            .build()
        val arabicTop = 390f
        canvas.save()
        canvas.translate(90f, arabicTop)
        arabicLayout.draw(canvas)
        canvas.restore()

        var y = arabicTop + arabicLayout.height + 62f
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66D6B878
            strokeWidth = 2f
        }
        canvas.drawLine(250f, y - 22f, 830f, y - 22f, dividerPaint)

        if (english.isNotBlank()) {
            val englishPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFE2E7EC.toInt()
                textSize = 29f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            val layout = StaticLayout.Builder.obtain(english, 0, english.length, englishPaint, 850)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(TextDirectionHeuristics.LTR)
                .setLineSpacing(7f, 1.08f)
                .setIncludePad(true)
                .build()
            canvas.save()
            canvas.translate(115f, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + 25f
        }

        if (urdu.isNotBlank() && y < 1050f) {
            val urduPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFD7CBB4.toInt()
                textSize = 28f
                typeface = Typeface.create("serif", Typeface.NORMAL)
            }
            val layout = StaticLayout.Builder.obtain(urdu, 0, urdu.length, urduPaint, 850)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setLineSpacing(6f, 1.05f)
                .setIncludePad(true)
                .build()
            canvas.save()
            canvas.translate(115f, y)
            layout.draw(canvas)
            canvas.restore()
        }

        val referencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE2C98E.toInt()
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.MEDIUM)
            textAlign = Paint.Align.CENTER
        }
        val reference = "$surahName  •  $surahNumber:$ayahNumber"
        canvas.drawText(reference, 540f, 1190f, referencePaint)

        if (arabicSurahName.isNotBlank()) {
            val arabicNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFB7C5D3.toInt()
                textSize = 27f
                typeface = Typeface.create("serif", Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(arabicSurahName, 540f, 1238f, arabicNamePaint)
        }
        return bitmap
    }
}
