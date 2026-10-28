package com.example.vkdl

import android.Manifest
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

/** isHls = false -> прямой mp4 (DownloadManager); true -> HLS-поток (свой загрузчик сегментов). */
data class VideoOption(
    val label: String,
    val height: Int,
    val url: String,
    val isHls: Boolean,
    /** Отдельная аудио-дорожка HLS (если звук не вшит в видео-поток). */
    val audioUrl: String? = null
)

private class MuxSrc(val ex: MediaExtractor) {
    val tracks = HashMap<Int, Int>()   // индекс дорожки в источнике -> индекс в muxer
    var done = false
    var baseUs = 0L
}

private class HlsKey(val uri: String, val iv: ByteArray?)
private class HlsSeg(val url: String, val seq: Long, val key: HlsKey?)

class MainActivity : AppCompatActivity() {

    private val http = OkHttpClient()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var input: EditText
    private lateinit var button: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    private var videoId = "video"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        input = EditText(this).apply {
            hint = "Ссылка на видео (vk.com/video-123_456)"
            setSingleLine(false)
            minLines = 2
        }
        button = Button(this).apply {
            text = "Найти варианты"
            setOnClickListener { search() }
        }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        status = TextView(this).apply { setTextIsSelectable(true) }

        root.addView(input)
        root.addView(button)
        root.addView(progress)
        root.addView(status)
        setContentView(root)

        installCrashLogger()
        showPreviousCrash()
        askPermissions()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let {
                input.setText(it)
                search()
            }
        }
    }

    private fun askPermissions() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
    }

    /** Сохраняет стектрейс падения в файл, чтобы показать его при следующем запуске. */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { File(filesDir, "last_crash.txt").writeText(Log.getStackTraceString(e)) }
            prev?.uncaughtException(t, e)
        }
    }

    private fun showPreviousCrash() {
        val f = File(filesDir, "last_crash.txt")
        if (f.exists()) {
            status.text = "Прошлый запуск завершился падением:\n" + f.readText().take(3000)
            f.delete()
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        button.isEnabled = !busy
        if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Показывает всю цепочку причин ошибки, а не только общее сообщение. */
    private fun describe(e: Throwable): String =
        generateSequence(e) { it.cause }
            .joinToString("\n← ") { "${it.javaClass.simpleName}: ${it.message}" }

    // ---------- поиск вариантов ----------

    private fun search() {
        val text = input.text.toString()
        setBusy(true)
        status.text = "Загрузка информации…"

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    var ids = parseIds(text)
                    if (ids == null) {
                        // короткие ссылки (vk.cc и т.п.) — идём по редиректу
                        val link = Regex("""https?://\S+""").find(text)?.value
                        if (link != null) {
                            ids = parseIds(runCatching { resolveRedirect(link) }.getOrDefault(link))
                        }
                    }
                    val (oid, id, hash) = ids ?: error("Не удалось найти ID видео в ссылке")
                    videoId = "${oid}_$id"
                    fetchOptions(oid, id, hash)
                }
            }
            setBusy(false)
            result.onSuccess { options ->
                status.text = "Найдено вариантов: ${options.size}"
                showOptions(options)
            }.onFailure {
                status.text = if (it is IllegalStateException) "Ошибка: ${it.message}"
                else "Ошибка: ${describe(it)}"
            }
        }
    }

    /** Понимает video-1_2, clip-1_2, ?z=video-1_2, video_ext.php?oid=..&id=..&hash=.. */
    private fun parseIds(text: String): Triple<String, String, String>? {
        Regex("""oid=(-?\d+)&(?:amp;)?id=(\d+)(?:&(?:amp;)?hash=([0-9a-fA-F]+))?""")
            .find(text)?.let { return Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        Regex("""(?:video|clip)(-?\d+)_(\d+)(?:_([0-9a-fA-F]{6,}))?""")
            .find(text)?.let { return Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        return null
    }

    private fun resolveRedirect(url: String): String =
        http.newCall(buildReq(url)).execute().use { it.request.url.toString() }

    private fun buildReq(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", UA)
        .header("Referer", "https://vk.com/")
        .header("Origin", "https://vk.com")
        .header("Accept-Language", "ru,en;q=0.8")
        .build()

    private fun httpGet(url: String): String =
        http.newCall(buildReq(url)).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} (${URI(url).host})")
            resp.body?.string().orEmpty()
        }

    private fun fetchBytes(url: String): ByteArray {
        var last: Exception? = null
        repeat(3) {
            try {
                return http.newCall(buildReq(url)).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code} (${URI(url).host})")
                    r.body!!.bytes()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last!!
    }

    private fun httpFetch(url: String): Pair<Int, String> =
        http.newCall(buildReq(url)).execute().use { it.code to it.body?.string().orEmpty() }

    private fun hintFrom(body: String): String =
        Regex("""[^<>"{}]{0,60}(?:не найден|недоступн|удал[её]н|приватн|ограничен)[^<>"{}]{0,60}""",
            RegexOption.IGNORE_CASE)
            .find(body)?.value?.trim()?.let { " — «$it»" }.orEmpty()

    /** Пробует несколько страниц VK по очереди, пока не найдёт хотя бы один вариант. */
    private fun fetchOptions(oid: String, id: String, hash: String): List<VideoOption> {
        val q = "oid=$oid&id=$id" + if (hash.isNotEmpty()) "&hash=$hash" else ""
        val pages = listOf(
            "https://vk.com/video_ext.php?$q",
            "https://vkvideo.ru/video_ext.php?$q",
            "https://vkvideo.ru/video${oid}_$id",
            "https://m.vk.com/video${oid}_$id"
        )
        val log = StringBuilder()
        for (u in pages) {
            val label = URI(u).host + URI(u).path
            try {
                val (code, body) = httpFetch(u)
                val opts = if (code in 200..299) extractOptions(body) else emptyList()
                log.appendLine("• $label: HTTP $code, ${body.length} байт, вариантов: ${opts.size}" +
                    if (opts.isEmpty()) hintFrom(body) else "")
                if (opts.isNotEmpty()) return opts
            } catch (e: Exception) {
                log.appendLine("• $label: ${e.message}")
            }
        }
        error("Ссылки не найдены. Видео может быть приватным, удалённым, доступным " +
            "только по hash/после входа, либо внешним (YouTube и т.п.).\n\nПопытки:\n$log")
    }

    /** Достаёт mp4 и HLS из страницы плеера (JSON-параметры или теги <source>). */
    private fun extractOptions(raw: String): List<VideoOption> {
        val clean = raw.replace("\\/", "/").replace("\\u002F", "/")
            .replace("\\u0026", "&").replace("&amp;", "&")

        val byKey = Regex(""""url(\d{3,4})"\s*:\s*"(https?://[^"]+)"""").findAll(clean)
            .map { it.groupValues[1].toInt() to it.groupValues[2] }
        val byName = Regex("""https?://[^"'\s<>\\]+?\.(\d{3,4})\.mp4[^"'\s<>\\]*""").findAll(clean)
            .map { it.groupValues[1].toInt() to it.value }

        val mp4 = (byKey + byName)
            .distinctBy { it.first }
            .sortedByDescending { it.first }
            .map { (h, u) -> VideoOption("${h}p — mp4 (прямая ссылка)", h, u, false) }
            .toList()

        val hlsUrl = Regex(""""hls(?:_ondemand)?"\s*:\s*"(https?://[^"]+)"""")
            .find(clean)?.groupValues?.get(1)
        val hls = if (hlsUrl != null) runCatching { parseHls(hlsUrl) }.getOrDefault(emptyList())
        else emptyList()

        return mp4 + hls
    }

    /** Разбирает master-плейлист: качества + отдельные аудио-дорожки (если есть). */
    private fun parseHls(masterUrl: String): List<VideoOption> {
        val text = httpGet(masterUrl)
        if (!text.contains("#EXT-X-STREAM-INF")) {
            return listOf(VideoOption("HLS-поток → mp4", 0, masterUrl, true))
        }
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // GROUP-ID -> URI отдельной аудио-дорожки
        val audioGroups = HashMap<String, String>()
        for (l in lines) {
            if (!l.startsWith("#EXT-X-MEDIA:") || attr(l, "TYPE") != "AUDIO") continue
            val uri = attr(l, "URI") ?: continue
            val gid = attr(l, "GROUP-ID") ?: continue
            if (attr(l, "DEFAULT") == "YES" || gid !in audioGroups) {
                audioGroups[gid] = resolve(masterUrl, uri)
            }
        }

        val out = mutableListOf<VideoOption>()
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
            val uri = lines.getOrNull(i + 1)?.takeIf { !it.startsWith("#") } ?: continue
            val h = Regex("""RESOLUTION=\d+x(\d+)""").find(lines[i])
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val audio = attr(lines[i], "AUDIO")?.let { audioGroups[it] }
            val q = if (h > 0) "${h}p" else "HLS"
            val label = if (audio != null) "$q — HLS видео + аудио → mp4" else "$q — HLS → mp4"
            out += VideoOption(label, h, resolve(masterUrl, uri), true, audio)
        }
        return out.distinctBy { it.height }.sortedByDescending { it.height }
    }

    private fun resolve(base: String, ref: String): String = URI(base).resolve(ref).toString()

    private fun showOptions(options: List<VideoOption>) {
        AlertDialog.Builder(this)
            .setTitle("Выберите вариант")
            .setItems(options.map { it.label }.toTypedArray()) { _, which ->
                val opt = options[which]
                if (opt.isHls) downloadHls(opt) else downloadMp4(opt)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------- mp4: системный DownloadManager ----------

    private fun downloadMp4(opt: VideoOption) {
        val fileName = "vk_${videoId}_${opt.height}p.mp4"
        val req = DownloadManager.Request(Uri.parse(opt.url))
            .setTitle(fileName)
            .addRequestHeader("User-Agent", UA)
            .addRequestHeader("Referer", "https://vk.com/")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        Toast.makeText(this, "Скачивание началось: $fileName", Toast.LENGTH_LONG).show()
    }

    // ---------- HLS: свой загрузчик сегментов + (по возможности) ремукс в mp4 ----------

    private fun progressCb(title: String): (Int, Int) -> Unit = { done, total ->
        ui.post {
            status.text = "Скачивание ($title): $done/$total (${done * 100 / total}%)\n" +
                "Не закрывайте приложение до конца."
        }
    }

    private fun etaText(startMs: Long, percent: Int): String {
        if (percent < 3) return ""
        val sec = (SystemClock.elapsedRealtime() - startMs) * (100 - percent) / percent / 1000
        return " (осталось ~${sec / 60} мин ${sec % 60} с)"
    }

/** Проверяет готовый mp4 штатными средствами Android и возвращает короткий отчёт. */
    private fun checkMp4(f: File): String {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val hasV = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasA = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val frame = runCatching {
                r.getFrameAtTime(ms * 1000 / 2, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }.getOrNull()
            val sec = ms / 1000
            "Проверка файла: видео " + (if (hasV) "есть" else "НЕТ") +
                ", звук " + (if (hasA) "есть" else "НЕТ") +
                ", длительность ${sec / 60}:${"%02d".format(sec % 60)}" +
                ", кадр из середины " + (if (frame != null) "декодируется" else "НЕ декодируется")
        } catch (e: Exception) {
            "Проверка файла: Android не смог прочитать mp4 (${e.message})"
        } finally {
            runCatching { r.release() }
        }
    }

    private fun mimeFor(f: File) = if (f.extension == "mp4") "video/mp4" else "video/mp2t"

    private fun downloadHls(opt: VideoOption) {
        val base = "vk_${videoId}_${if (opt.height > 0) "${opt.height}p" else "hls"}"
        setBusy(true)
        status.text = "Скачивание (видео): 0%\nНе закрывайте приложение до конца."

        lifecycleScope.launch {
            val result = runCatching {
                val video = withContext(Dispatchers.IO) {
                    downloadHlsSegments(opt.url, "${base}_video", progressCb("видео"))
                }
                val audio = opt.audioUrl?.let { au ->
                    withContext(Dispatchers.IO) {
                        downloadHlsSegments(au, "${base}_audio", progressCb("аудио"))
                    }
                }

                run {
                    status.text = "Конвертация в mp4: 0%"
                    val mp4 = File(cacheDir, "$base.mp4").also { it.delete() }
                    val t0 = SystemClock.elapsedRealtime()
                    val mux = runCatching {
                        withContext(Dispatchers.IO) {
                            muxToMp4(listOfNotNull(video, audio), mp4) { p ->
                                ui.post { status.text = "Конвертация в mp4: $p%${etaText(t0, p)}" }
                            }
                        }
                    }
                    if (mux.isSuccess) {
                        status.text = "Проверка и сохранение…"
                        val check = withContext(Dispatchers.IO) {
                            val c = checkMp4(mp4)
                            saveToDownloads(mp4, mp4.name, "video/mp4")
                            video.delete()
                            audio?.delete()
                            c
                        }
                        "Готово: ${mp4.name} сохранён в «Загрузки»\n$check"
                    } else {
                        mp4.delete()
                        status.text = "Сохранение в «Загрузки»…"
                        val saved = withContext(Dispatchers.IO) {
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
                            names
                        }
                        "Конвертация в mp4 не удалась, исходные файлы сохранены в «Загрузки»: " +
                            saved.joinToString() + "\n" +
                            (if (audio != null) "Видео и звук лежат отдельно.\n" else "") +
                            "Причина:\n${describe(mux.exceptionOrNull()!!)}"
                    }
                }
            }
            setBusy(false)
            status.text = result.fold(
                onSuccess = { it },
                onFailure = { "Ошибка: ${describe(it)}" }
            )
        }
    }

    private fun attr(line: String, name: String): String? =
        Regex("""(?:^|[:,])$name=("([^"]*)"|[^,]*)""").find(line)
            ?.let { it.groups[2]?.value ?: it.groupValues[1] }

    private fun hexToBytes(s: String): ByteArray {
        val hex = s.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Скачивает все сегменты media-плейлиста в один файл (.ts или .mp4 для fMP4). */
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

    /**
     * Собирает mp4 из одного или нескольких файлов (видео и отдельное аудио), без перекодирования.
     * Сэмплы берутся в порядке времени, чтобы звук и картинка чередовались в файле.
     */
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
                    // по одной дорожке каждого типа
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
            // у каждого источника своё начало отсчёта: видео и отдельное аудио совпадают по старту
            active.forEach { it.baseUs = it.ex.sampleTime.coerceAtLeast(0L) }
            val totalBytes = files.sumOf { it.length() }.coerceAtLeast(1L)
            var bytesDone = 0L
            var lastPercent = -1
            val buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()

            while (true) {
                // берём источник с самым ранним следующим сэмплом
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
