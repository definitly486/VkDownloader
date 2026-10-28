package com.example.vkdl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Фоновая загрузка HLS → mp4.
 * Работает как foreground-service: можно свернуть/закрыть Activity.
 */
class HlsDownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "vk_hls_downloads"
        const val NOTIF_ID = 42

        const val EXTRA_URL = "url"
        const val EXTRA_AUDIO_URL = "audio_url"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_VIDEO_ID = "video_id"

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        fun start(
            ctx: Context,
            url: String,
            audioUrl: String?,
            height: Int,
            videoId: String
        ) {
            val i = Intent(ctx, HlsDownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_AUDIO_URL, audioUrl)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_VIDEO_ID, videoId)
            }
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var nm: NotificationManager

    private class MuxSrc(val ex: MediaExtractor) {
        val tracks = HashMap<Int, Int>()
        var done = false
        var baseUs = 0L
    }

    private class HlsKey(val uri: String, val iv: ByteArray?)
    private class HlsSeg(val url: String, val seq: Long, val key: HlsKey?)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Загрузки HLS",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Прогресс фоновой загрузки HLS → mp4"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 0)
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: "video"
        val base = "vk_${videoId}_${if (height > 0) "${height}p" else "hls"}"

        startForeground(NOTIF_ID, buildNotif("HLS · $base", "Подготовка…", 0, 0, ongoing = true))

        scope.launch {
            val result = runCatching {
                notify("HLS · $base", "Скачивание видео…", 0, 100)
                val video = downloadHlsSegments(url, "${base}_video") { done, total ->
                    notify("HLS · $base", "Видео: $done/$total", done, total)
                }
                val audio = audioUrl?.let { au ->
                    notify("HLS · $base", "Скачивание аудио…", 0, 100)
                    downloadHlsSegments(au, "${base}_audio") { done, total ->
                        notify("HLS · $base", "Аудио: $done/$total", done, total)
                    }
                }

                val mp4 = File(cacheDir, "$base.mp4").also { it.delete() }
                val t0 = SystemClock.elapsedRealtime()
                val mux = runCatching {
                    muxToMp4(listOfNotNull(video, audio), mp4) { p ->
                        val eta = etaText(t0, p)
                        notify("HLS · $base", "Конвертация: $p%$eta", p, 100)
                    }
                }
                if (mux.isSuccess) {
                    notify("HLS · $base", "Сохранение…", 99, 100)
                    val check = checkMp4(mp4)
                    saveToDownloads(mp4, mp4.name, "video/mp4")
                    video.delete()
                    audio?.delete()
                    "Готово: ${mp4.name}\n$check"
                } else {
                    mp4.delete()
                    val names = mutableListOf<String>()
                    val vName = if (audio == null) "$base.${video.extension}"
                    else "${base}_video.${video.extension}"
                    saveToDownloads(video, vName, mimeFor(video))
                    names += vName
                    audio?.let { a ->
                        val aName = "${base}_audio.${a.extension}"
                        saveToDownloads(a, aName, mimeFor(a))
                        names += aName
                    }
                    "Конвертация не удалась, сохранены: ${names.joinToString()}"
                }
            }

            val title = "HLS · $base"
            val text = result.fold(
                onSuccess = { it },
                onFailure = { "Ошибка: ${it.message}" }
            )
            // Финальное уведомление (не ongoing)
            nm.notify(
                NOTIF_ID + startId,
                buildNotif(title, text.take(200), 0, 0, ongoing = false, done = true)
            )
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------- notifications ----------

    private fun notify(title: String, text: String, progress: Int, max: Int) {
        nm.notify(NOTIF_ID, buildNotif(title, text, progress, max, ongoing = true))
    }

    private fun buildNotif(
        title: String,
        text: String,
        progress: Int,
        max: Int,
        ongoing: Boolean,
        done: Boolean = false
    ): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            else Notification.Builder(this)
        val b = builder
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_download_done.takeIf { done }
                ?: android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        if (ongoing && max > 0) {
            b.setProgress(max, progress.coerceIn(0, max), max <= 0)
        }
        return b.build()
    }

    // ---------- network ----------

    private fun openHttp(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://vk.com/")
            setRequestProperty("Origin", "https://vk.com")
            setRequestProperty("Accept-Language", "ru,en;q=0.8")
        }

    private fun httpGet(url: String): String = openHttp(url).run {
        try {
            val code = responseCode
            if (code !in 200..299) error("HTTP $code (${URI(url).host})")
            inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { disconnect() }
    }

    private fun fetchBytes(url: String): ByteArray {
        var last: Exception? = null
        repeat(3) {
            try {
                return openHttp(url).run {
                    try {
                        val code = responseCode
                        if (code !in 200..299) error("HTTP $code (${URI(url).host})")
                        inputStream.use { it.readBytes() }
                    } finally { disconnect() }
                }
            } catch (e: Exception) { last = e }
        }
        throw last!!
    }

    private fun resolve(base: String, ref: String): String = URI(base).resolve(ref).toString()

    private fun attr(line: String, name: String): String? =
        Regex("""(?:^|[:,])$name=("([^"]*)"|[^,]*)""").find(line)
            ?.let { it.groups[2]?.value ?: it.groupValues[1] }

    private fun hexToBytes(s: String): ByteArray {
        val hex = s.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    // ---------- HLS segments ----------

    private suspend fun downloadHlsSegments(
        playlistUrl: String,
        base: String,
        onProgress: (Int, Int) -> Unit
    ): File {
        val text = httpGet(playlistUrl)

        var seq = 0L
        var key: HlsKey? = null
        var mapUrl: String? = null
        val segs = mutableListOf<HlsSeg>()

        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    seq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = attr(line, "METHOD")
                    key = when (method) {
                        null, "NONE" -> null
                        "AES-128" -> HlsKey(
                            resolve(playlistUrl, attr(line, "URI") ?: error("нет URI ключа")),
                            attr(line, "IV")?.let { hexToBytes(it) }
                        )
                        else -> error("Шифрование $method не поддерживается")
                    }
                }
                line.startsWith("#EXT-X-MAP:") ->
                    mapUrl = attr(line, "URI")?.let { resolve(playlistUrl, it) }
                line.startsWith("#EXT-X-BYTERANGE") -> error("BYTERANGE не поддерживается")
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    segs += HlsSeg(resolve(playlistUrl, line), seq, key)
                    seq++
                }
            }
        }
        if (segs.isEmpty()) error("В плейлисте нет сегментов")

        val keys = ConcurrentHashMap<String, ByteArray>()
        val out = File(cacheDir, "$base.${if (mapUrl != null) "mp4" else "ts"}").also { it.delete() }

        out.outputStream().buffered().use { os ->
            mapUrl?.let { os.write(fetchBytes(it)) }
            var done = 0
            for (batch in segs.chunked(4)) {
                val parts = coroutineScope {
                    batch.map { s -> async(Dispatchers.IO) { fetchSegment(s, keys) } }.awaitAll()
                }
                parts.forEach { os.write(it) }
                done += batch.size
                onProgress(done, segs.size)
            }
        }
        return out
    }

    private fun fetchSegment(s: HlsSeg, keys: ConcurrentHashMap<String, ByteArray>): ByteArray {
        val data = fetchBytes(s.url)
        val k = s.key ?: return data
        val keyBytes = keys.getOrPut(k.uri) { fetchBytes(k.uri) }
        val iv = k.iv ?: ByteBuffer.allocate(16).putLong(8, s.seq).array()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    // ---------- mux ----------

    private fun muxToMp4(files: List<File>, dst: File, onProgress: (Int) -> Unit) {
        val srcs = mutableListOf<MuxSrc>()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var durationUs = 0L
            var haveVideo = false
            var haveAudio = false
            for (f in files) {
                val src = MuxSrc(MediaExtractor())
                srcs += src
                src.ex.setDataSource(f.absolutePath)
                for (i in 0 until src.ex.trackCount) {
                    val format = src.ex.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    val isVideo = mime.startsWith("video/")
                    val isAudio = mime.startsWith("audio/")
                    if (!isVideo && !isAudio) continue
                    if ((isVideo && haveVideo) || (isAudio && haveAudio)) continue
                    if (isVideo) haveVideo = true else haveAudio = true
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                    }
                    src.ex.selectTrack(i)
                    src.tracks[i] = muxer.addTrack(format)
                }
            }
            if (!haveVideo && !haveAudio) error("в потоке нет аудио/видео дорожек")

            muxer.start()
            started = true

            val active = srcs.filter { it.tracks.isNotEmpty() }
            active.forEach { it.baseUs = it.ex.sampleTime.coerceAtLeast(0L) }
            val totalBytes = files.sumOf { it.length() }.coerceAtLeast(1L)
            var bytesDone = 0L
            var lastPercent = -1
            val buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()

            while (true) {
                var best: MuxSrc? = null
                var bestT = Long.MAX_VALUE
                for (s in active) {
                    if (s.done) continue
                    val raw = s.ex.sampleTime
                    if (raw < 0) { s.done = true; continue }
                    val t = raw - s.baseUs
                    if (t < bestT) { best = s; bestT = t }
                }
                val s = best ?: break

                buffer.clear()
                val size = s.ex.readSampleData(buffer, 0)
                if (size < 0) { s.done = true; continue }
                val track = s.tracks[s.ex.sampleTrackIndex]
                if (track != null) {
                    val flags =
                        if (s.ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                            MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, size, bestT.coerceAtLeast(0L), flags)
                    muxer.writeSampleData(track, buffer, info)
                }
                s.ex.advance()

                bytesDone += size
                val percent = (if (durationUs > 0) bestT * 100 / durationUs
                else bytesDone * 100 / totalBytes).toInt().coerceIn(0, 99)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                }
            }
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            srcs.forEach { runCatching { it.ex.release() } }
        }
    }

    private fun etaText(startMs: Long, percent: Int): String {
        if (percent < 3) return ""
        val sec = (SystemClock.elapsedRealtime() - startMs) * (100 - percent) / percent / 1000
        return " (~${sec / 60}м ${sec % 60}с)"
    }

    private fun checkMp4(f: File): String {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val hasV = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasA = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val sec = ms / 1000
            "видео " + (if (hasV) "есть" else "НЕТ") +
                ", звук " + (if (hasA) "есть" else "НЕТ") +
                ", ${sec / 60}:${"%02d".format(sec % 60)}"
        } catch (e: Exception) {
            "не удалось проверить (${e.message})"
        } finally {
            runCatching { r.release() }
        }
    }

    private fun mimeFor(f: File) = if (f.extension == "mp4") "video/mp4" else "video/mp2t"

    private fun saveToDownloads(src: File, name: String, mime: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("не удалось создать файл в «Загрузках»")
            contentResolver.openOutputStream(uri)!!.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            src.copyTo(File(dir, name), overwrite = true)
        }
        src.delete()
    }
}
