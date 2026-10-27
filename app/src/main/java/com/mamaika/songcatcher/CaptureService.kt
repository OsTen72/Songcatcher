package com.mamaika.songcatcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

object CaptureBridge {
    @Volatile
    var pending: CompletableDeferred<File>? = null

    @Volatile
    var lastStats: String = ""
}

class CaptureService : Service() {

    companion object {
        suspend fun capture(ctx: Context, code: Int, data: Intent): File {
            val deferred = CompletableDeferred<File>()
            CaptureBridge.pending = deferred
            CaptureBridge.lastStats = ""
            val i = Intent(ctx, CaptureService::class.java)
                .putExtra("code", code)
                .putExtra("data", data)
            ContextCompat.startForegroundService(ctx, i)
            return withTimeout(40_000) { deferred.await() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")

        startAsForeground()

        if (data == null) {
            CaptureBridge.pending?.completeExceptionally(IllegalStateException("Нет разрешения на захват звука"))
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        Thread {
            try {
                val file = record(code, data)
                CaptureBridge.pending?.complete(file)
            } catch (e: Throwable) {
                CaptureBridge.pending?.completeExceptionally(e)
            } finally {
                stopForeground(true)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val channelId = "capture"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Запись звука", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("SongCatcher")
            .setContentText("Слушаю звук телефона…")
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun record(code: Int, data: Intent): File {
        if (Build.VERSION.SDK_INT < 29) {
            throw IllegalStateException("Захват звука телефона работает на Android 10 и новее")
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(code, data)
            ?: throw IllegalStateException("Не удалось получить доступ к захвату")
        projection.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val sampleRate = 44100
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, 16384) * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            val total = sampleRate * 2 * 12 // 12 секунд, 16 бит, моно
            val pcm = ByteArray(total)
            var read = 0
            rec.startRecording()
            while (read < total) {
                val n = rec.read(pcm, read, minOf(4096, total - read))
                if (n <= 0) break
                read += n
            }
            rec.stop()
            rec.release()

            var peak = 0
            var active = 0
            var i = 0
            while (i + 1 < read) {
                val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
                val a = abs(s)
                peak = maxOf(peak, a)
                if (a > 300) active++
                i += 2
            }
            val samples = read / 2
            val pct = if (samples > 0) active * 100 / samples else 0
            CaptureBridge.lastStats = "записано ${read / 2 / sampleRate} с, пик $peak, звук в $pct% записи"
            if (peak < 100) {
                throw IllegalStateException(
                    "Звук не захвачен: на телефоне тишина или приложение запретило захват"
                )
            }

            // Тихую запись усиливаем: AudD плохо строит отпечаток из слишком тихого звука.
            if (peak < 20000) {
                val gain = minOf(30f, 20000f / peak)
                var j = 0
                while (j + 1 < read) {
                    val v0 = ((pcm[j + 1].toInt() shl 8) or (pcm[j].toInt() and 0xFF)).toShort().toInt()
                    val v = (v0 * gain).toInt().coerceIn(-32768, 32767)
                    pcm[j] = (v and 0xFF).toByte()
                    pcm[j + 1] = ((v shr 8) and 0xFF).toByte()
                    j += 2
                }
            }

            val out = File(cacheDir, "clip.wav")
            writeWav(out, pcm, read, sampleRate)
            return out
        } finally {
            projection.stop()
        }
    }

    private fun writeWav(file: File, pcm: ByteArray, len: Int, sampleRate: Int) {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + len); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(sampleRate); h.putInt(sampleRate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(len)
        file.outputStream().use {
            it.write(h.array())
            it.write(pcm, 0, len)
        }
    }
}
