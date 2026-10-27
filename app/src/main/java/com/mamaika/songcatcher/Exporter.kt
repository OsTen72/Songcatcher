package com.mamaika.songcatcher

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

object Exporter {
    /** Копирует трек в общую папку Music/SongCatcher, чтобы его видели другие плееры. */
    fun export(ctx: Context, t: Track) {
        if (Build.VERSION.SDK_INT < 29) {
            throw IllegalStateException("Экспорт работает на Android 10 и новее")
        }
        val src = File(t.path)
        val ext = src.extension.lowercase()
        val mime = when (ext) {
            "m4a", "mp4" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "webm" -> "audio/webm"
            "opus", "ogg" -> "audio/ogg"
            else -> "audio/mpeg"
        }
        val name = "${t.artist} - ${t.title}".replace(Regex("[\\\\/:*?\"<>|]"), "_") + "." + ext

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/SongCatcher")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Не удалось создать файл в Music")
        resolver.openOutputStream(uri)!!.use { out ->
            src.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}
