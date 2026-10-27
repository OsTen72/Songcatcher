package com.mamaika.songcatcher

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File

/** Видео, которое можно скачать (результат поиска или ссылка). */
data class Candidate(
    val id: String,
    val title: String,
    val channel: String,
    val durationSec: Long,
    val url: String,
    val thumbUrl: String
)

object Downloader {

    /** Ищет на YouTube и возвращает до [count] вариантов (без скачивания). */
    fun search(query: String, count: Int = 5): List<Candidate> {
        val request = YoutubeDLRequest("ytsearch$count:$query")
        request.addOption("--flat-playlist")
        request.addOption("--dump-json")
        request.addOption("--no-warnings")
        val resp = YoutubeDL.getInstance().execute(request, null) { _, _, _ -> }
        return parse(resp.out, null)
    }

    /** Читает информацию о видео по ссылке (без скачивания). */
    fun info(url: String): List<Candidate> {
        val request = YoutubeDLRequest(url)
        request.addOption("--dump-json")
        request.addOption("--no-playlist")
        request.addOption("--no-warnings")
        val resp = YoutubeDL.getInstance().execute(request, null) { _, _, _ -> }
        return parse(resp.out, url)
    }

    private fun parse(out: String, fallbackUrl: String?): List<Candidate> {
        return out.lines()
            .map { it.trim() }
            .filter { it.startsWith("{") }
            .mapNotNull { line ->
                try {
                    val o = JSONObject(line)
                    val id = o.optString("id")
                    if (id.isBlank()) return@mapNotNull null
                    val webUrl = o.optString("webpage_url").ifBlank {
                        fallbackUrl ?: "https://www.youtube.com/watch?v=$id"
                    }
                    val thumb = o.optString("thumbnail").ifBlank {
                        "https://i.ytimg.com/vi/$id/mqdefault.jpg"
                    }
                    Candidate(
                        id = id,
                        title = o.optString("title").ifBlank { id },
                        channel = o.optString("channel").ifBlank { o.optString("uploader") },
                        durationSec = o.optLong("duration", 0L),
                        url = if (fallbackUrl == null) "https://www.youtube.com/watch?v=$id" else webUrl,
                        thumbUrl = if (fallbackUrl == null) "https://i.ytimg.com/vi/$id/mqdefault.jpg" else thumb
                    )
                } catch (e: Exception) {
                    null
                }
            }
    }

    /** Скачивает звук видео по ссылке. Возвращает файл. */
    fun download(ctx: Context, url: String, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.filesDir, "music").apply { mkdirs() }
        val base = "t_" + System.currentTimeMillis()

        val request = YoutubeDLRequest(url)
        request.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
        request.addOption("--no-playlist")
        request.addOption("-o", File(dir, "$base.%(ext)s").absolutePath)

        YoutubeDL.getInstance().execute(request, null) { progress, _, _ ->
            onProgress(progress)
        }

        return dir.listFiles()
            ?.firstOrNull { it.name.startsWith(base) && !it.name.endsWith(".part") }
            ?: throw IllegalStateException("Файл не найден после скачивания")
    }
}
