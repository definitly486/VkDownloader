package com.example.vkdl

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.app.DownloadManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

/** Foreground MP4 downloader. Uses small dynamic ranges so fast workers take more work. */
class Mp4DownloadService : Service() {
    companion object {
        private const val CHANNEL = "vk_mp4_foreground"
        private const val NOTIFICATION_ID = 4103
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val ACTION_CANCEL = "com.example.vkdl.action.CANCEL_MP4"
        private const val ACTION_CANCEL_NOTIFICATION = 4104
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        fun start(ctx: Context, url: String, name: String) {
            val intent = Intent(ctx, Mp4DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url); putExtra(EXTRA_NAME, name)
            }
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
        }
    }
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var nm: NotificationManager
    private var downloadJob: Job? = null
    private var downloadManagerId: Long = -1L
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Загрузка MP4", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification("Подготовка загрузки", 0, 0, 0, true))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            downloadJob?.cancel(CancellationException("Загрузка отменена пользователем"))
            if (downloadManagerId < 0L) downloadManagerId = getSharedPreferences("mp4_download", MODE_PRIVATE).getLong("download_manager_id", -1L)
            if (downloadManagerId >= 0L) {
                runCatching { (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).remove(downloadManagerId) }
                downloadManagerId = -1L
                getSharedPreferences("mp4_download", MODE_PRIVATE).edit().remove("download_manager_id").apply()
            }
            nm.notify(NOTIFICATION_ID, notification("Загрузка прервана", 0, 0, 0, false, "По запросу пользователя"))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra(EXTRA_URL)
        val name = intent?.getStringExtra(EXTRA_NAME) ?: "video.mp4"
        if (url.isNullOrBlank()) { stopSelf(startId); return START_NOT_STICKY }
        downloadJob = scope.launch {
            try {
                val total = probe(url)
                if (total == null || total < 2L * 1024 * 1024) {
                    val req = DownloadManager.Request(Uri.parse(url)).setTitle(name)
                        .setDescription("VK Downloader · фоновая загрузка")
                        .addRequestHeader("User-Agent", UA).addRequestHeader("Referer", "https://vk.com/")
                        .addRequestHeader("Origin", "https://vk.com/")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setAllowedOverMetered(true).setAllowedOverRoaming(true)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    downloadManagerId = (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                    getSharedPreferences("mp4_download", MODE_PRIVATE).edit().putLong("download_manager_id", downloadManagerId).apply()
                    nm.notify(NOTIFICATION_ID, notification("Передано системному загрузчику", 0, 0, 0, false, "Android продолжит загрузку в фоне"))
                } else {
                    download(url, name, total)
                    nm.notify(NOTIFICATION_ID, notification("Загрузка завершена", 100, total, 0, false))
                }
            } catch (e: CancellationException) {
                Log.i("VkDownloader", "MP4 download cancelled")
                nm.notify(NOTIFICATION_ID, notification("Загрузка прервана", 0, 0, 0, false, "Загрузка остановлена; временные файлы удалены"))
            } catch (e: Exception) {
                Log.e("VkDownloader", "MP4 background download failed", e)
                nm.notify(NOTIFICATION_ID, notification("Ошибка загрузки", 0, 0, 0, false, e.message ?: "Повторите попытку"))
            } finally {
                // Do not detach the foreground notification: that leaves the stale
                // "download still running in background" notification visible.
                downloadJob = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                nm.cancel(NOTIFICATION_ID)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(title: String, percent: Int, bytes: Long, speed: Long, ongoing: Boolean, extra: String? = null): Notification {
        val text = extra ?: if (ongoing) "$percent% · ${fmt(bytes)} /суммарно · ${fmt(speed)}/с" else "${fmt(bytes)}"
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val cancelIntent = Intent(this, Mp4DownloadService::class.java).setAction(ACTION_CANCEL)
        val cancelPending = PendingIntent.getService(this, ACTION_CANCEL_NOTIFICATION, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return b.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText(text)
            .apply { if (ongoing) addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Прервать", cancelPending).build()) }
            .setStyle(Notification.BigTextStyle().bigText(text)).setOnlyAlertOnce(true).setOngoing(ongoing)
            .setProgress(100, percent.coerceIn(0,100), false).build()
    }
    private fun fmt(n: Long): String = if (n >= 1024L*1024) String.format("%.1f МБ", n / 1048576.0) else String.format("%.0f КБ", n / 1024.0)

    private fun probe(url: String): Long? {
        val c = connection(url, "bytes=0-0")
        return try {
            val code = c.responseCode; val h = c.getHeaderField("Content-Range")
            val m = Regex("bytes\\s+0-0/(\\d+)", RegexOption.IGNORE_CASE).matchEntire(h?.trim().orEmpty())
            if (code == 206 && m != null) { c.inputStream.use { it.read() }; m.groupValues[1].toLongOrNull() } else null
        } finally { c.disconnect() }
    }
    private fun connection(url: String, range: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true; connectTimeout = 20000; readTimeout = 30000
        setRequestProperty("User-Agent", UA); setRequestProperty("Referer", "https://vk.com/"); setRequestProperty("Origin", "https://vk.com")
        setRequestProperty("Accept-Encoding", "identity"); setRequestProperty("Range", range)
    }

    private suspend fun download(url: String, name: String, total: Long) = coroutineScope {
        val dir = File(cacheDir, "mp4_${System.currentTimeMillis()}").apply { if (!mkdirs()) throw IOException("Не удалось создать временную папку") }
        val done = AtomicLong(0); val speed = AtomicLong(0); val lastBytes = AtomicLong(0); val lastAt = AtomicLong(SystemClock.elapsedRealtime())
        val chunk = 2L * 1024 * 1024
        val count = ceil(total.toDouble() / chunk).toInt()
        val next = AtomicLong(0)
        val parts = arrayOfNulls<File>(count)
        try {
            // Начинаем с двух соединений; при большом остатке добавляем третье и четвёртое.
            val workers = minOf(4, maxOf(2, ceil(total.toDouble() / (16L*1024*1024)).toInt()))
            (0 until workers).map { worker -> async(Dispatchers.IO) {
                while (true) {
                    val idx = next.getAndIncrement().toInt(); if (idx >= count) break
                    val from = idx * chunk; val to = minOf(total - 1, from + chunk - 1)
                    val part = File(dir, "part_$idx"); parts[idx] = part
                    var failure: Exception? = null
                    for (attempt in 1..3) {
                        try {
                            part.delete()
                            val c = connection(url, "bytes=$from-$to")
                            try {
                                val expected = "bytes $from-$to/$total"
                                if (c.responseCode != 206 || c.getHeaderField("Content-Range")?.trim() != expected) throw IOException("Range не принят сервером")
                                var received = 0L
                                c.inputStream.use { input -> FileOutputStream(part).use { out ->
                                    val buf = ByteArray(256*1024)
                                    while (true) { ensureActive(); val n = input.read(buf); if (n < 0) break; out.write(buf,0,n); received += n
                                        val all = done.addAndGet(n.toLong()); val now = SystemClock.elapsedRealtime(); val elapsed = now - lastAt.get()
                                        if (elapsed >= 700 && lastAt.compareAndSet(lastAt.get(), now)) {
                                            val previous = lastBytes.getAndSet(all); val instant = ((all-previous).coerceAtLeast(0)*1000/elapsed.coerceAtLeast(1))
                                            // EMA smoothing suppresses notification speed spikes.
                                            speed.set(if (speed.get() == 0L) instant else (speed.get()*0.65 + instant*0.35).toLong())
                                            val pct = (all*100/total).toInt().coerceIn(0,99)
                                            nm.notify(NOTIFICATION_ID, notification("Скачивание: $name", pct, all, speed.get(), true))
                                        }
                                    }
                                } }
                                if (received != to-from+1) throw IOException("Неполный диапазон: $received байт")
                            } finally { c.disconnect() }
                            failure = null; break
                        } catch (e: Exception) { failure = e; if (attempt < 3) delay(attempt * 800L) }
                    }
                    if (failure != null) throw IOException("Не удалось скачать часть ${idx+1} после 3 попыток", failure)
                }
            } }.awaitAll()
            if (parts.any { it == null || !it.exists() } || parts.sumOf { it!!.length() } != total) throw IOException("Проверка размера файла не пройдена")
            save(parts.map { it!! }, name, total)
        } finally { dir.deleteRecursively() }
    }

    private fun save(parts: List<File>, name: String, total: Long) {
        val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME,name); put(MediaStore.Downloads.MIME_TYPE,"video/mp4"); put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS); if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Downloads.IS_PENDING,1) }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values) ?: throw IOException("Не удалось создать файл в Загрузках")
        try {
            contentResolver.openOutputStream(uri,"w")?.use { out -> parts.forEach { f -> FileInputStream(f).use { it.copyTo(out,128*1024) } } } ?: throw IOException("Не удалось записать файл")
            if (Build.VERSION.SDK_INT >= 29) contentResolver.update(uri,ContentValues().apply { put(MediaStore.Downloads.IS_PENDING,0) },null,null)
        } catch (e: Exception) { contentResolver.delete(uri,null,null); throw e }
    }
    override fun onDestroy() { job.cancel(); super.onDestroy() }
}
