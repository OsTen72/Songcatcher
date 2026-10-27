package com.mamaika.songcatcher

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

object Downloader {
    /** Ищет первый результат на YouTube по запросу и скачивает звук. Возвращает файл. */
    fun download(ctx: Context, query: String, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.filesDir, "music").apply { mkdirs() }
        val base = "t_" + System.currentTimeMillis()

        val request = YoutubeDLRequest("ytsearch1:$query")
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
