package com.jdmusax.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDownloadUtilsTest {
    @Test
    fun preservesSupportedExtensionsAndIgnoresQueryString() {
        assertEquals("m4a", AudioDownloadUtils.extensionFrom("https://cdn.example/audio/chapter.m4a?token=abc"))
        assertEquals("ogg", AudioDownloadUtils.extensionFrom("https://cdn.example/audio/recitation.OGG"))
    }

    @Test
    fun fallsBackForUnknownOrMissingExtensions() {
        assertEquals("mp3", AudioDownloadUtils.extensionFrom("https://cdn.example/audio/track"))
        assertEquals("mp3", AudioDownloadUtils.extensionFrom("https://cdn.example/audio/page.html?format=wav"))
    }
}
