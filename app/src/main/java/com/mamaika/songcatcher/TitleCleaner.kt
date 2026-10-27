package com.mamaika.songcatcher

/** Убирает мусор из названий роликов: (Official Video), [4K], #shorts и т. п. */
object TitleCleaner {
    private const val B = "(?<![\\p{L}\\p{N}])"
    private const val A = "(?![\\p{L}\\p{N}])"
    private const val WORDS =
        "official|video|audio|lyrics?|lyric|visuali[sz]er|hd|hq|4k|8k|mv|m/v|clip|клип|премьера|текст|remaster(?:ed)?|full album"

    private val brackets = Regex(
        "(?iu)\\s*[(\\[{][^)\\]}]*?" + B + "(?:" + WORDS + ")" + A + "[^)\\]}]*[)\\]}]"
    )
    private val hashtags = Regex("\\s*#\\S+")
    private val afterPipe = Regex("\\s*\\|.*$")
    private val spaces = Regex("\\s+")
    private val edges = Regex("^[\\s\\-–—_]+|[\\s\\-–—_]+$")
    private val dash = Regex("\\s+[-–—]\\s+")
    private val topic = Regex("(?iu)\\s*-\\s*topic$")
    private val vevo = Regex("(?iu)\\s*vevo$")

    fun clean(raw: String): String {
        var s = brackets.replace(raw, "")
        s = hashtags.replace(s, "")
        s = afterPipe.replace(s, "")
        s = spaces.replace(s, " ")
        s = edges.replace(s, "")
        return s.ifBlank { raw.trim() }
    }

    fun cleanChannel(channel: String): String {
        var s = topic.replace(channel, "")
        s = vevo.replace(s, "")
        return s.trim()
    }

    /** Возвращает пару (исполнитель, название). */
    fun split(videoTitle: String, channel: String): Pair<String, String> {
        val t = clean(videoTitle)
        val parts = t.split(dash, limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            return parts[0].trim() to parts[1].trim()
        }
        return cleanChannel(channel) to t
    }
}
