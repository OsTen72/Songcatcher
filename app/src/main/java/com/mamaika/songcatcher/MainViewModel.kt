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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Найденные видео, ожидающие подтверждения пользователя. */
class Pending(
    val candidates: List<Candidate>,
    val coverUrl: String?,
    val namesFromVideo: Boolean
)

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
    var confirmDownload by mutableStateOf(prefs.getBoolean("confirm_download", true))
    var query by mutableStateOf("")
    var manualQuery by mutableStateOf("")
    var currentId by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)
    var shuffle by mutableStateOf(false)
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_OFF)
    var position by mutableStateOf(0L)
    var duration by mutableStateOf(0L)
    var report by mutableStateOf<String?>(null)

    // подтверждение скачивания
    var pending by mutableStateOf<Pending?>(null)
    var pendingIndex by mutableStateOf(0)
    var editArtist by mutableStateOf("")
    var editTitle by mutableStateOf("")

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

        override fun onRepeatModeChanged(mode: Int) {
            repeatMode = mode
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
            player.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
            player.shuffleModeEnabled = prefs.getBoolean("shuffle", false)
        }
        repeatMode = player.repeatMode
        currentId = player.currentMediaItem?.mediaId
        isPlaying = player.isPlaying
        shuffle = player.shuffleModeEnabled

        // позиция воспроизведения для ползунка
        viewModelScope.launch {
            while (true) {
                position = player.currentPosition
                duration = player.duration.coerceAtLeast(0L)
                delay(500)
            }
        }

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
        val v = !player.shuffleModeEnabled
        player.shuffleModeEnabled = v
        prefs.edit().putBoolean("shuffle", v).apply()
    }

    fun cycleRepeat() {
        val next = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        player.repeatMode = next
        prefs.edit().putInt("repeat", next).apply()
    }

    fun seekTo(ms: Long) {
        player.seekTo(ms)
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

    fun rename(track: Track, artist: String, title: String) {
        val t = title.trim()
        if (t.isBlank()) return
        val updated = track.copy(artist = artist.trim(), title = t)
        val ti = tracks.indexOfFirst { it.id == track.id }
        if (ti >= 0) tracks[ti] = updated
        // играющий трек не трогаем, чтобы не прерывать воспроизведение
        val pi = indexInPlayer(track.id)
        if (pi >= 0 && pi != player.currentMediaItemIndex) {
            player.replaceMediaItem(pi, toItem(updated))
        }
        store.save(tracks.toList())
    }

    fun export(track: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Exporter.export(app, track)
                status = "Сохранено в Музыка/SongCatcher: ${track.title}"
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

    fun updateConfirmDownload(value: Boolean) {
        confirmDownload = value
        prefs.edit().putBoolean("confirm_download", value).apply()
    }

    // ---------- распознавание ----------
    private fun todayUtc(): String {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }

    /**
     * Сначала бесплатный анонимный запрос (его лимит обновляется каждый день).
     * Если он исчерпан, используем токен, чтобы не тратить разовые бесплатные запросы зря.
     */
    private fun recognizeSmart(clip: File): Recognizer.Result? {
        val tok = token.trim()
        val today = todayUtc()
        val anonBlocked = prefs.getString("anon_blocked_day", "") == today
        if (!anonBlocked || tok.isBlank()) {
            try {
                return Recognizer.recognize("", clip)
            } catch (e: AuddException) {
                if (tok.isBlank() || !e.isLimit()) throw e
                prefs.edit().putString("anon_blocked_day", today).apply()
                mark("анонимный лимит исчерпан")
            }
        }
        return Recognizer.recognize(tok, clip)
    }

    fun catchSong(projection: Pair<Int, Intent>? = null) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            progress = null
            try {
                if (player.isPlaying) player.pause()

                mark("запись")
                val phoneMode = usePhoneAudio && projection != null
                status = if (phoneMode) "Слушаю звук телефона 12 секунд…" else "Слушаю микрофон 10 секунд…"
                val clip = if (phoneMode) {
                    CaptureService.capture(app, projection!!.first, projection.second)
                } else {
                    recordMic()
                }

                mark("распознавание")
                status = "Распознаю…"
                val found = withContext(Dispatchers.IO) { recognizeSmart(clip) }
                if (found == null) {
                    status = "Не удалось распознать. Попробуй другой фрагмент песни"
                    return@launch
                }
                prepareDownload(
                    artist = found.artist,
                    title = found.title,
                    query = "${found.artist} ${found.title} audio",
                    coverUrl = found.coverUrl,
                    namesFromVideo = false
                )
            } catch (e: Throwable) {
                mark("ошибка: ${e.javaClass.simpleName}")
                val extra = if (usePhoneAudio && CaptureBridge.lastStats.isNotEmpty() &&
                    (e.message ?: "").contains("fingerprint")
                ) "\n[${CaptureBridge.lastStats}]" else ""
                status = "Ошибка: ${e.message ?: e.javaClass.simpleName}$extra"
            } finally {
                busy = false
                progress = null
                mark("idle")
            }
        }
    }

    // ---------- ручной режим: название или ссылка ----------
    fun downloadManual() {
        if (busy) return
        val text = manualQuery.trim()
        if (text.isBlank()) return
        viewModelScope.launch {
            busy = true
            progress = null
            try {
                val started = prepareDownload(
                    artist = "",
                    title = "",
                    query = text,
                    coverUrl = null,
                    namesFromVideo = true
                )
                if (started) manualQuery = ""
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

    /** Ищет видео. Показывает подтверждение или сразу качает первое. */
    private suspend fun prepareDownload(
        artist: String,
        title: String,
        query: String,
        coverUrl: String?,
        namesFromVideo: Boolean
    ): Boolean {
        val isLink = query.startsWith("http://") || query.startsWith("https://")
        status = if (isLink) "Читаю ссылку…" else "Ищу на YouTube…"
        mark("ожидание yt-dlp")
        ytReady.await()
        mark("поиск")
        val list = withContext(Dispatchers.IO) {
            if (isLink) Downloader.info(query) else Downloader.search(query, 5)
        }
        if (list.isEmpty()) {
            status = "Ничего не найдено"
            return false
        }

        if (confirmDownload) {
            pending = Pending(list, coverUrl, namesFromVideo)
            pendingIndex = 0
            if (namesFromVideo) {
                applyNames(list[0])
            } else {
                editArtist = artist
                editTitle = title
            }
            status = "Выбери видео для скачивания"
        } else {
            val c = list[0]
            val names = if (namesFromVideo) TitleCleaner.split(c.title, c.channel) else artist to title
            downloadCandidate(c, names.first, names.second, coverUrl)
        }
        return true
    }

    private fun applyNames(c: Candidate) {
        val (a, t) = TitleCleaner.split(c.title, c.channel)
        editArtist = a
        editTitle = t
    }

    fun nextCandidate() {
        val p = pending ?: return
        pendingIndex = (pendingIndex + 1) % p.candidates.size
        if (p.namesFromVideo) applyNames(p.candidates[pendingIndex])
    }

    fun cancelPending() {
        pending = null
        status = "Отменено"
    }

    fun confirmPending() {
        val p = pending ?: return
        val c = p.candidates[pendingIndex]
        val a = editArtist.trim()
        val t = editTitle.trim().ifBlank { c.title }
        pending = null
        viewModelScope.launch {
            busy = true
            progress = null
            try {
                downloadCandidate(c, a, t, p.coverUrl)
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

    /** Качает выбранное видео, подтягивает обложку и добавляет в список. */
    private suspend fun downloadCandidate(c: Candidate, artist: String, title: String, coverUrl: String?): Boolean {
        val label = if (artist.isBlank()) title else "$artist — $title"
        val duplicate = tracks.any {
            it.artist.equals(artist, true) && it.title.equals(title, true)
        }
        if (duplicate) {
            status = "$label уже есть в списке"
            return false
        }

        status = "Качаю: $label…"
        mark("скачивание")
        val file = withContext(Dispatchers.IO) {
            Downloader.download(app, c.url) { p ->
                progress = if (p >= 0f) p / 100f else null
            }
        }

        val id = file.nameWithoutExtension
        val coverPath = withContext(Dispatchers.IO) {
            val dir = File(app.filesDir, "covers").apply { mkdirs() }
            val dest = File(dir, "$id.jpg")
            val ok = (coverUrl != null && Recognizer.downloadCover(coverUrl, dest)) ||
                Recognizer.downloadCover(c.thumbUrl, dest)
            if (ok) dest.absolutePath else ""
        }

        val track = Track(id, artist, title, file.absolutePath, coverPath)
        tracks.add(0, track)
        player.addMediaItem(0, toItem(track))
        store.save(tracks.toList())
        status = "Добавлено: $label"
        return true
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
