package com.mamaika.songcatcher

import android.app.Application
import android.content.Context
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
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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

    val tracks = mutableStateListOf<Track>()
    var status by mutableStateOf("Готов")
    var busy by mutableStateOf(false)
    var progress by mutableStateOf<Float?>(null)
    var token by mutableStateOf(prefs.getString("audd_token", "") ?: "")
    var currentId by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)
    var report by mutableStateOf<String?>(null)

    private val player: ExoPlayer = ExoPlayer.Builder(app).build()

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

    init {
        loadReport()
        tracks.addAll(store.load())

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) playNext()
            }
        })

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

    fun updateToken(value: String) {
        token = value
        prefs.edit().putString("audd_token", value.trim()).apply()
    }

    fun catchSong() {
        if (busy) return
        if (token.isBlank()) {
            status = "Сначала вставь токен AudD"
            return
        }
        viewModelScope.launch {
            busy = true
            progress = null
            try {
                mark("запись")
                status = "Слушаю 10 секунд…"
                val clip = recordClip()

                mark("распознавание")
                status = "Распознаю…"
                val found = withContext(Dispatchers.IO) { Recognizer.recognize(token.trim(), clip) }
                if (found == null) {
                    status = "Не удалось распознать. Поднеси телефон ближе к источнику звука"
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

                val track = Track(file.nameWithoutExtension, found.artist, found.title, file.absolutePath)
                tracks.add(0, track)
                store.save(tracks.toList())
                status = "Добавлено: ${found.artist} — ${found.title}"
            } catch (e: Throwable) {
                mark("ошибка: ${e.javaClass.simpleName}")
                status = "Ошибка: ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                busy = false
                progress = null
                mark("idle")
            }
        }
    }

    private suspend fun recordClip(): File = withContext(Dispatchers.IO) {
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

    fun play(track: Track) {
        currentId = track.id
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(track.path))))
        player.prepare()
        player.play()
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else if (currentId != null) player.play()
    }

    private fun playNext() {
        val i = tracks.indexOfFirst { it.id == currentId }
        // новые треки добавляются в начало, поэтому "следующий" — это элемент ниже по списку
        if (i >= 0 && i + 1 < tracks.size) play(tracks[i + 1])
    }

    fun delete(track: Track) {
        if (track.id == currentId) {
            player.stop()
            currentId = null
        }
        File(track.path).delete()
        tracks.remove(track)
        store.save(tracks.toList())
    }

    val currentTrack: Track?
        get() = tracks.firstOrNull { it.id == currentId }

    override fun onCleared() {
        player.release()
    }
}
