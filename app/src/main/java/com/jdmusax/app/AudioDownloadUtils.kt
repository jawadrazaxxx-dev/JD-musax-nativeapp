package com.jdmusax.app

import java.net.URI

/** Shared, allow-listed extension selection for new and retried audio downloads. */
internal object AudioDownloadUtils {
    private val supportedExtensions = setOf("mp3", "m4a", "ogg", "wav", "aac")

    fun extensionFrom(url: String): String {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return extension.takeIf { it in supportedExtensions } ?: "mp3"
    }
}
