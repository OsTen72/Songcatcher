package com.mamaika.songcatcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Track(
    val id: String,
    val artist: String,
    val title: String,
    val path: String,
    val cover: String = ""
)

class TrackStore(ctx: Context) {
    private val file = File(ctx.filesDir, "tracks.json")

    fun load(): List<Track> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Track(
                    o.getString("id"),
                    o.getString("artist"),
                    o.getString("title"),
                    o.getString("path"),
                    o.optString("cover", "")
                )
            }.filter { File(it.path).exists() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(list: List<Track>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("artist", it.artist)
                    .put("title", it.title)
                    .put("path", it.path)
                    .put("cover", it.cover)
            )
        }
        file.writeText(arr.toString())
    }
}
