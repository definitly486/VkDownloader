package com.example.vkdl

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlin.math.abs

/**
 * Встроенный плеер (ExoPlayer / Media3).
 *
 * Громкость: вертикальный свайп по **правой** половине экрана.
 * Яркость: вертикальный свайп по **левой** половине экрана.
 */
class PlayerActivity : Activity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_IS_HLS = "is_hls"

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        fun start(ctx: Context, url: String, title: String, isHls: Boolean) {
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java).apply {
                    putExtra(EXTRA_URL, url)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_IS_HLS, isHls)
                }
            )
        }
    }

    private var player: ExoPlayer? = null
    private lateinit var audioManager: AudioManager
    private lateinit var overlayText: TextView
    private lateinit var overlayBar: ProgressBar
    private lateinit var overlayBox: FrameLayout

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var gestureActive = false
    private var gestureIsVolume = true
    private var startVolume = 0
    private var maxVolume = 1
    private var startBrightness = 0.5f
    private val touchSlopPx by lazy { 16f * resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "VK Video"
        val isHls = intent.getBooleanExtra(EXTRA_IS_HLS, url.contains(".m3u8", true))

        if (url.isBlank()) {
            Toast.makeText(this, "Нет URL потока", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        val density = resources.displayMetrics.density
        val playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(0xFF000000.toInt())
        }

        overlayText = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(
                (20 * density).toInt(),
                (10 * density).toInt(),
                (20 * density).toInt(),
                (10 * density).toInt()
            )
        }
        overlayBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 50
            layoutParams = LinearLayout.LayoutParams(
                (180 * density).toInt(),
                (10 * density).toInt()
            )
        }
        overlayBox = FrameLayout(this).apply {
            visibility = android.view.View.GONE
            setBackgroundColor(0xCC222222.toInt())
            val pad = (14 * density).toInt()
            setPadding(pad, pad, pad, pad)
            val inner = LinearLayout(this@PlayerActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(overlayText)
                addView(overlayBar)
            }
            addView(inner)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(playerView)
            addView(overlayBox)
        }
        setContentView(root)
        this.title = title

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://vk.com/",
                    "Origin" to "https://vk.com",
                    "Accept" to "*/*",
                    "Accept-Language" to "ru,en;q=0.8"
                )
            )

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
            .also { player = it }

        playerView.player = exo

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaId(title)
            .apply {
                if (isHls || url.contains(".m3u8", true)) {
                    setMimeType(MimeTypes.APPLICATION_M3U8)
                }
            }
            .build()

        val source = if (isHls || url.contains(".m3u8", true)) {
            HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
        }

        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    "Ошибка воспроизведения: ${error.errorCodeName}\n${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    /**
     * Перехват на уровне Activity — PlayerView не «съедает» вертикальный свайп.
     * Тапы и горизонтальные жесты уходят в контроллер ExoPlayer как обычно.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val w = resources.displayMetrics.widthPixels
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = ev.x
                touchStartY = ev.y
                gestureActive = false
                gestureIsVolume = touchStartX >= w / 2f
                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val lp = window.attributes
                startBrightness = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - touchStartX
                val dy = ev.y - touchStartY
                if (!gestureActive) {
                    if (abs(dy) > touchSlopPx && abs(dy) > abs(dx) * 1.5f) {
                        gestureActive = true
                        overlayBox.visibility = android.view.View.VISIBLE
                    }
                }
                if (gestureActive) {
                    applyGesture(dy)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gestureActive) {
                    overlayBox.visibility = android.view.View.GONE
                    gestureActive = false
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun applyGesture(dy: Float) {
        val h = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val delta = -dy / (h * 0.6f) // ~60% высоты экрана = полный диапазон
        if (gestureIsVolume) {
            val newVol = (startVolume + delta * maxVolume).toInt().coerceIn(0, maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
            val pct = newVol * 100 / maxVolume
            overlayText.text = "🔊  $pct%"
            overlayBar.progress = pct
        } else {
            val newBr = (startBrightness + delta).coerceIn(0.01f, 1f)
            val lp = window.attributes
            lp.screenBrightness = newBr
            window.attributes = lp
            val pct = (newBr * 100).toInt()
            overlayText.text = "☀  $pct%"
            overlayBar.progress = pct
        }
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onStop() {
        player?.playWhenReady = false
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        player?.release()
        player = null
        super.onBackPressed()
    }
}
