package com.mamaika.songcatcher

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object Recognizer {
    data class Result(val artist: String, val title: String)

    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Возвращает null, если песня не найдена. Бросает исключение при ошибке API/сети. */
    fun recognize(token: String, clip: File): Result? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("api_token", token)
            .addFormDataPart("file", clip.name, clip.asRequestBody("audio/mp4".toMediaType()))
            .build()
        val request = Request.Builder().url("https://api.audd.io/").post(body).build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = JSONObject(text)
            json.optJSONObject("error")?.let {
                throw IllegalStateException("AudD: " + it.optString("error_message", "ошибка"))
            }
            val result = json.optJSONObject("result") ?: return null
            val artist = result.optString("artist")
            val title = result.optString("title")
            if (artist.isBlank() && title.isBlank()) return null
            return Result(artist, title)
        }
    }
}
