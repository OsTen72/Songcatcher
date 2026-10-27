package com.mamaika.songcatcher

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    private val store = TrackStore(app)
    private val ytReady = CompletableDeferred<Unit>()
    private val player = PlayerHolder.get(app)

    val tracks = mutableStateListOf<Track>()
    var status by mutableStateOf("Готов")
    var busy by mutableStateOf(false)
    var progress by mutableStateOf<Float?>(null)
    var token by mutableStateOf(prefs.getString("audd_token", "") ?: "")
    var usePhoneAudio by mutableStateOf(prefs.getBoolean("phone_audio", false))
    var query by mutableStateOf("")
    var currentId by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)
    var shuffle by mutableStateOf(false)
    var report by mutableStateOf<String?>(null)

    val visibleTracks: List<Track>
        get() = if (query.isBlank()) tracks.toList() else tracks.filter {
            it.title.contains(query, true) || it.artist.contains(query, true)
        }

    val currentTrack: Track?
        get() = tracks.firstOrNull { it.id == currentId }

    // ---------- диагностика ----------
    private val traceFile = File(app.filesDir, "trace.txt")
    private val crashFile = File(app.filesDir, "crash.txt")

    private fun mark(stage: String) {
        try {
            traceFile.appendText(stage + "\n")
        } catch (_: Throwable) {
        }
    }

    fun dismissReport() {
        report = null
    }

    private fun loadReport() {
        val sb = StringBuilder()
        try {
            if (crashFile.exists()) {
                sb.append("Приложение вылетело:\n").append(crashFile.readText().take(2500))
                crashFile.delete()
            }
            if (traceFile.exists()) {
                val lines = traceFile.readLines().filter { it.isNotBlank() }
                if (lines.isNotEmpty() && lines.last() != "idle") {
                    sb.append("\nПоследние этапы прошлого запуска: ")
                        .append(lines.takeLast(6).joinToString(" > "))
                }
                traceFile.delete()
            }
        } catch (_: Throwable) {
        }
        mark("start")
        if (sb.isNotEmpty()) report = sb.toString()
    }

    // ---------- плеер ----------
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentId = mediaItem?.mediaId
        }

        override fun onShuffleModeEnabledChanged(enabled: Boolean) {
            shuffle = enabled
        }
    }

    private fun toItem(t: Track): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(t.artist)
        if (t.cover.isNotEmpty() && File(t.cover).exists()) {
            meta.setArtworkUri(Uri.fromFile(File(t.cover)))
        }
        return MediaItem.Builder()
            .setMediaId(t.id)
            .setUri(Uri.fromFile(File(t.path)))
            .setMediaMetadata(meta.build())
            .build()
    }

    private fun indexInPlayer(id: String): Int {
        for (i in 0 until player.mediaItemCount) {
            if (player.getMediaItemAt(i).mediaId == id) return i
        }
        return -1
    }

    init {
        loadReport()
        tracks.addAll(store.load())

        player.addListener(listener)
        if (player.mediaItemCount == 0) {
            player.setMediaItems(tracks.map { toItem(it) })
        }
        player.repeatMode = Player.REPEAT_MODE_ALL
        currentId = player.currentMediaItem?.mediaId
        isPlaying = player.isPlaying
        shuffle = player.shuffleModeEnabled

        viewModelScope.launch(Dispatchers.IO) {
            try {
                mark("init yt-dlp")
                YoutubeDL.getInstance().init(app)
                mark("init ok")
                ytReady.complete(Unit)
            } catch (e: Throwable) {
                ytReady.completeExceptionally(e)
                return@launch
            }
            // Обновляем yt-dlp: YouTube часто ломает старые версии.
            try {
                mark("update yt-dlp")
                YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
            } catch (_: Throwable) {
            }
            mark("idle")
        }
    }

    fun play(track: Track) {
        val i = indexInPlayer(track.id)
        if (i < 0) return
        player.seekToDefaultPosition(i)
        player.prepare()
        player.play()
        try {
            app.startService(Intent(app, PlaybackService::class.java))
        } catch (_: Throwable) {
        }
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else if (currentId != null) {
            player.prepare()
            player.play()
        } else if (tracks.isNotEmpty()) {
            play(tracks[0])
        }
    }

    fun next() = player.seekToNextMediaItem()
    fun previous() = player.seekToPreviousMediaItem()

    fun toggleShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    fun delete(track: Track) {
        val i = indexInPlayer(track.id)
        if (i >= 0) player.removeMediaItem(i)
        if (player.mediaItemCount == 0) currentId = null
        File(track.path).delete()
        if (track.cover.isNotEmpty()) File(track.cover).delete()
        tracks.remove(track)
        store.save(tracks.toList())
    }

    fun export(track: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Exporter.export(app, track)
                status = "Сохранено в Музыка/SongCatcher: ${track.artist} — ${track.title}"
            } catch (e: Throwable) {
                status = "Ошибка экспорта: ${e.message}"
            }
        }
    }

    // ---------- настройки ----------
    fun updateToken(value: String) {
        token = value
        prefs.edit().putString("audd_token", value.trim()).apply()
    }

    fun updateUsePhoneAudio(value: Boolean) {
        usePhoneAudio = value
        prefs.edit().putBoolean("phone_audio", value).apply()
    }

    // ---------- главная кнопка ----------
    fun catchSong(projection: Pair<Int, Intent>? = null) {
        if (busy) return
        if (token.isBlank()) {
            status = "Сначала вставь токен AudD"
            return
        }
        viewModelScope.launch {
            busy = true
            progress = null
            try {
                if (player.isPlaying) player.pause()

                mark("запись")
                val phoneMode = usePhoneAudio && projection != null
                status = if (phoneMode) "Слушаю звук телефона 10 секунд…" else "Слушаю микрофон 10 секунд…"
                val clip = if (phoneMode) {
                    CaptureService.capture(app, projection!!.first, projection.second)
                } else {
                    recordMic()
                }

                mark("распознавание")
                status = "Распознаю…"
                val found = withContext(Dispatchers.IO) { Recognizer.recognize(token.trim(), clip) }
                if (found == null) {
                    status = "Не удалось распознать. Попробуй другой фрагмент песни"
                    return@launch
                }

                val duplicate = tracks.any {
                    it.artist.equals(found.artist, true) && it.title.equals(found.title, true)
                }
                if (duplicate) {
                    status = "${found.artist} — ${found.title} уже есть в списке"
                    return@launch
                }

                status = "Нашёл: ${found.artist} — ${found.title}. Ищу и качаю…"
                mark("ожидание yt-dlp")
                ytReady.await()
                mark("поиск и скачивание")
                val file = withContext(Dispatchers.IO) {
                    Downloader.download(app, "${found.artist} ${found.title} audio") { p ->
                        progress = if (p >= 0f) p / 100f else null
                    }
                }

                val id = file.nameWithoutExtension
                val coverPath = withContext(Dispatchers.IO) {
                    val url = found.coverUrl
                    if (url == null) {
                        ""
                    } else {
                        val dir = File(app.filesDir, "covers").apply { mkdirs() }
                        val dest = File(dir, "$id.jpg")
                        if (Recognizer.downloadCover(url, dest)) dest.absolutePath else ""
                    }
                }

                val track = Track(id, found.artist, found.title, file.absolutePath, coverPath)
                tracks.add(0, track)
                player.addMediaItem(0, toItem(track))
                store.save(tracks.toList())
                status = "Добавлено: ${found.artist} — ${found.title}"
            } catch (e: Throwable) {
                mark("ошибка: ${e.javaClass.simpleName}")
                status = "Ошибка: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
                progress = null
                mark("idle")
            }
        }
    }

    private suspend fun recordMic(): File = withContext(Dispatchers.IO) {
        val out = File(app.cacheDir, "clip.m4a")
        if (out.exists()) out.delete()
        val rec = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(app)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(44100)
            rec.setAudioEncodingBitRate(128000)
            rec.setOutputFile(out.absolutePath)
            rec.prepare()
            rec.start()
            delay(10_000)
            rec.stop()
        } finally {
            rec.release()
        }
        out
    }

    override fun onCleared() {
        // плеер общий с фоновым сервисом, его не освобождаем
        player.removeListener(listener)
    }
}
