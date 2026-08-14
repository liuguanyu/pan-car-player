package com.panmusic.carplayer.util

/**
 * LRC 歌词解析器
 * 
 * 支持标准 LRC 格式：
 * [mm:ss.xx]歌词文本
 * 支持多行标签：[ti:],[ar:],[al:],[by:],[offset:]
 */
object LrcParser {

    data class LrcLine(
        val time: Long,          // 时间 ms
        val text: String         // 歌词文本
    )

    data class LrcInfo(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val lines: List<LrcLine> = emptyList()
    )

    private val tagPattern = Regex("""\[(ti|ar|al|by|offset):\s*(.+?)]""", RegexOption.IGNORE_CASE)
    private val timePattern = Regex("""\[(\d{2}):(\d{2})(?:[.:](\d{2,3}))?](.*)""")

    fun parse(lrcText: String): LrcInfo {
        var title = ""
        var artist = ""
        var album = ""
        var offset = 0L
        val lines = mutableListOf<LrcLine>()

        for (line in lrcText.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // 解析标签
            val tagMatch = tagPattern.find(trimmed)
            if (tagMatch != null) {
                val key = tagMatch.groupValues[1].lowercase()
                val value = tagMatch.groupValues[2].trim()
                when (key) {
                    "ti" -> title = value
                    "ar" -> artist = value
                    "al" -> album = value
                    "offset" -> offset = value.toLongOrNull() ?: 0L
                }
                continue
            }

            // 解析时间标签
            val timeMatches = timePattern.findAll(trimmed)
            var lastText = ""
            for (match in timeMatches) {
                val min = match.groupValues[1].toInt()
                val sec = match.groupValues[2].toInt()
                val ms = match.groupValues[3].let {
                    if (it.isNotEmpty()) {
                        if (it.length == 2) it.toInt() * 10L else it.toInt().toLong()
                    } else 0L
                }
                val time = (min * 60L + sec) * 1000L + ms + offset
                lastText = match.groupValues[4].trim()
                lines.add(LrcLine(time, lastText))
            }
        }

        lines.sortBy { it.time }

        return LrcInfo(
            title = title,
            artist = artist,
            album = album,
            lines = lines
        )
    }

    /**
     * 根据当前播放时间查找对应的歌词行索引
     */
    fun findCurrentLine(lines: List<LrcLine>, currentTimeMs: Long): Int {
        if (lines.isEmpty()) return -1
        for (i in lines.indices.reversed()) {
            if (lines[i].time <= currentTimeMs) return i
        }
        return -1
    }
}
