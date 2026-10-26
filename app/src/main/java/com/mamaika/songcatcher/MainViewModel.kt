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

    private val player: ExoPlayer = ExoPlayer.Builder(app).build()

    init {
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
                YoutubeDL.getInstance().init(app)
                ytReady.complete(Unit)
            } catch (e: Exception) {
                ytReady.completeExceptionally(e)
                return@launch
            }
            // Обновляем yt-dlp: YouTube часто ломает старые версии.
            try {
                YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
            } catch (_: Exception) {
            }
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
                status = "Слушаю 10 секунд…"
                val clip = recordClip()

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
                ytReady.await()
                val file = withContext(Dispatchers.IO) {
                    Downloader.download(app, "${found.artist} ${found.title} audio") { p ->
                        progress = if (p >= 0f) p / 100f else null
                    }
                }

                val track = Track(file.nameWithoutExtension, found.artist, found.title, file.absolutePath)
                tracks.add(0, track)
                store.save(tracks.toList())
                status = "Добавлено: ${found.artist} — ${found.title}"
            } catch (e: Exception) {
                status = "Ошибка: ${e.message}"
            } finally {
                busy = false
                progress = null
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
