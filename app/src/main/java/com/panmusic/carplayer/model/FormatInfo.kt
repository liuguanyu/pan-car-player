package com.panmusic.carplayer.model

/**
 * 音频格式信息 — 用于 PlayerActivity 显示
 */
data class FormatInfo(
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val sampleMimeType: String = "",
    val bitrate: Long = 0,
    val codec: String = ""
) {
    val displayText: String
        get() {
            // 与 Java 版一致：只在 sampleMimeType 有效时显示，不显示 channelCount
            if (sampleMimeType.isEmpty()) return ""

            val codecStr = codec.ifEmpty {
                when {
                    sampleMimeType.contains("mpeg") || sampleMimeType.contains("mp3") -> "MP3"
                    sampleMimeType.contains("flac") -> "FLAC"
                    sampleMimeType.contains("alac") -> "ALAC"
                    sampleMimeType.contains("aac") -> "AAC"
                    sampleMimeType.contains("wav") || sampleMimeType.contains("pcm") -> "WAV"
                    sampleMimeType.contains("ogg") || sampleMimeType.contains("vorbis") || sampleMimeType.contains("opus") -> "OGG"
                    sampleMimeType.contains("wma") || sampleMimeType.contains("asf") -> "WMA"
                    sampleMimeType.contains("m4a") -> "M4A"
                    else -> sampleMimeType.substringAfterLast("/").uppercase()
                }
            }

            val parts = mutableListOf<String>()
            if (sampleRate > 0) parts.add("%.1fkHz".format(sampleRate / 1000.0))
            if (bitrate > 0) parts.add("${bitrate / 1000}kbps")
            parts.add(codecStr)

            return parts.joinToString(" / ")
        }
}
