package com.jdmusax.app

import android.media.audiofx.Equalizer
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt

data class AudioBand(
    val index: Int,
    val centerFrequencyHz: Int,
    val levelMb: Int,
    val minLevelMb: Int,
    val maxLevelMb: Int
) {
    val label: String
        get() = if (centerFrequencyHz >= 1000) "${centerFrequencyHz / 1000} kHz" else "${centerFrequencyHz} Hz"
}

class AudioEffectsController {
    private var equalizer: Equalizer? = null
    private var visualizer: Visualizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Synchronized
    fun attach(audioSessionId: Int): List<AudioBand> {
        release()
        if (audioSessionId <= 0) return emptyList()
        return runCatching {
            Equalizer(0, audioSessionId).also {
                it.enabled = true
                equalizer = it
            }
            bands()
        }.getOrElse {
            release()
            emptyList()
        }
    }

    @Synchronized
    fun bands(): List<AudioBand> {
        val eq = equalizer ?: return emptyList()
        return runCatching {
            val range = eq.bandLevelRange
            (0 until eq.numberOfBands).map { index ->
                val band = index.toShort()
                AudioBand(
                    index = index,
                    centerFrequencyHz = eq.getCenterFreq(band) / 1000,
                    levelMb = eq.getBandLevel(band).toInt(),
                    minLevelMb = range[0].toInt(),
                    maxLevelMb = range[1].toInt()
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun setBandLevel(index: Int, levelMb: Int): List<AudioBand> {
        val eq = equalizer ?: return emptyList()
        return runCatching {
            val range = eq.bandLevelRange
            eq.setBandLevel(index.toShort(), levelMb.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
            bands()
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun presetNames(): List<String> {
        val eq = equalizer ?: return emptyList()
        return runCatching { (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) } }
            .getOrDefault(emptyList())
    }

    @Synchronized
    fun usePreset(index: Int): List<AudioBand> {
        val eq = equalizer ?: return emptyList()
        return runCatching {
            if (index in 0 until eq.numberOfPresets) eq.usePreset(index.toShort())
            bands()
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun setVisualizerEnabled(enabled: Boolean, onLevels: (List<Float>) -> Unit): Boolean {
        if (!enabled) {
            visualizer?.runCatching {
                setDataCaptureListener(null, 0, false, false)
                enabled = false
                release()
            }
            visualizer = null
            onLevels(emptyList())
            return true
        }
        val sessionId = runCatching { equalizer?.getId() }.getOrNull() ?: return false
        return runCatching {
            val current = visualizer ?: VisualizerAudioSession.currentSessionId?.let(::Visualizer)
                ?: return false
            visualizer = current
            val range = current.captureSizeRange
            val desired = 1024.coerceIn(range[0], range[1])
            val size = Integer.highestOneBit(desired).coerceAtLeast(range[0])
            current.captureSize = size
            current.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit

                override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft == null || fft.size < 4) return
                    val bins = (1 until minOf(fft.size / 2, 65)).map { bin ->
                        val real = fft[bin * 2].toInt()
                        val imaginary = fft[bin * 2 + 1].toInt()
                        (sqrt((real * real + imaginary * imaginary).toDouble()) / 128.0)
                            .toFloat().coerceIn(0f, 1f)
                    }
                    mainHandler.post { onLevels(bins) }
                }
            }, (Visualizer.getMaxCaptureRate() / 2).coerceAtLeast(1), false, true)
            current.enabled = true
            true
        }.getOrElse {
            visualizer?.release()
            visualizer = null
            false
        }
    }

    @Synchronized
    fun release() {
        visualizer?.runCatching {
            setDataCaptureListener(null, 0, false, false)
            enabled = false
            release()
        }
        visualizer = null
        equalizer?.runCatching {
            enabled = false
            release()
        }
        equalizer = null
    }
}

/** Holds the active ExoPlayer session ID for the visualizer factory. */
object VisualizerAudioSession {
    @Volatile var currentSessionId: Int? = null
}
