package com.example.vkdl;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

/**
 * Встроенный плеер без media3-ui: TextureView + свои кнопки.
 * Громкость — свайп справа, яркость — слева.
 * Кнопка fullscreen появляется только по тапу (3 сек).
 */
public class PlayerActivity extends Activity implements TextureView.SurfaceTextureListener {

    private static final String EXTRA_URL = "url";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_IS_HLS = "is_hls";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    public static void start(Context ctx, String url, String title, boolean isHls) {
        Intent i = new Intent(ctx, PlayerActivity.class);
        i.putExtra(EXTRA_URL, url);
        i.putExtra(EXTRA_TITLE, title);
        i.putExtra(EXTRA_IS_HLS, isHls);
        ctx.startActivity(i);
    }

    private ExoPlayer player;
    private TextureView textureView;
    private Surface surface;
    private AudioManager audioManager;

    private FrameLayout root;
    private LinearLayout controls;
    private ImageButton btnPlay;
    private ImageButton btnFs;
    private SeekBar seekBar;
    private TextView timeText;
    private ProgressBar buffering;
    private TextView overlayText;
    private ProgressBar overlayBar;
    private FrameLayout overlayBox;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean isFullscreen = true;
    private boolean userSeeking = false;

    private float touchStartX, touchStartY;
    private boolean gestureActive;
    private boolean gestureIsVolume = true;
    private int startVolume, maxVolume = 1;
    private float startBrightness = 0.5f;
    private long lastTapMs;
    private float touchSlopPx;

    private final Runnable hideChrome = this::hideChrome;
    private final Runnable updateProgress = new Runnable() {
        @Override
        public void run() {
            if (player == null) return;
            if (!userSeeking && player.getDuration() > 0) {
                long pos = player.getCurrentPosition();
                long dur = player.getDuration();
                seekBar.setMax(1000);
                seekBar.setProgress((int) (pos * 1000 / dur));
                timeText.setText(formatTime(pos) + " / " + formatTime(dur));
            }
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null) title = "VK Video";
        boolean isHls = getIntent().getBooleanExtra(EXTRA_IS_HLS,
                url != null && url.contains(".m3u8"));

        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "Нет URL потока", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        maxVolume = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        touchSlopPx = 16f * getResources().getDisplayMetrics().density;
        float d = getResources().getDisplayMetrics().density;

        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(this);
        textureView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buffering = new ProgressBar(this);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                (int) (48 * d), (int) (48 * d), Gravity.CENTER);
        buffering.setLayoutParams(bp);

        // overlay volume/brightness
        overlayText = new TextView(this);
        overlayText.setTextColor(Color.WHITE);
        overlayText.setTextSize(18);
        overlayText.setGravity(Gravity.CENTER);
        int pad = (int) (12 * d);
        overlayBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        overlayBar.setMax(100);
        overlayBar.setLayoutParams(new LinearLayout.LayoutParams((int) (180 * d), (int) (10 * d)));
        LinearLayout overlayInner = new LinearLayout(this);
        overlayInner.setOrientation(LinearLayout.VERTICAL);
        overlayInner.setGravity(Gravity.CENTER_HORIZONTAL);
        overlayInner.addView(overlayText);
        overlayInner.addView(overlayBar);
        overlayBox = new FrameLayout(this);
        overlayBox.setBackgroundColor(0xCC222222);
        overlayBox.setPadding(pad, pad, pad, pad);
        overlayBox.addView(overlayInner);
        overlayBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        overlayBox.setLayoutParams(op);

        // controls bar
        btnPlay = new ImageButton(this);
        btnPlay.setImageResource(android.R.drawable.ic_media_pause);
        btnPlay.setBackgroundColor(Color.TRANSPARENT);
        btnPlay.setColorFilter(Color.WHITE);
        btnPlay.setOnClickListener(v -> togglePlay());

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        seekBar.setLayoutParams(sp);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && player != null && player.getDuration() > 0) {
                    timeText.setText(formatTime(p * player.getDuration() / 1000) + " / "
                            + formatTime(player.getDuration()));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                if (player != null && player.getDuration() > 0) {
                    player.seekTo(s.getProgress() * player.getDuration() / 1000);
                }
            }
        });

        timeText = new TextView(this);
        timeText.setTextColor(Color.WHITE);
        timeText.setTextSize(12);
        timeText.setText("0:00 / 0:00");

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setBackgroundColor(0x99000000);
        controls.setPadding(pad, pad, pad, pad);
        controls.setVisibility(View.GONE);
        controls.addView(btnPlay);
        controls.addView(seekBar);
        controls.addView(timeText);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        controls.setLayoutParams(cp);

        btnFs = new ImageButton(this);
        btnFs.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        btnFs.setBackgroundColor(0x66000000);
        btnFs.setColorFilter(Color.WHITE);
        btnFs.setVisibility(View.GONE);
        int btnSize = (int) (48 * d);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(btnSize, btnSize);
        fp.gravity = Gravity.TOP | Gravity.END;
        fp.topMargin = (int) (12 * d);
        fp.setMarginEnd((int) (12 * d));
        btnFs.setLayoutParams(fp);
        btnFs.setOnClickListener(v -> toggleFullscreen());

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(textureView);
        root.addView(buffering);
        root.addView(overlayBox);
        root.addView(controls);
        root.addView(btnFs);
        setContentView(root);
        setTitle(title);

        root.post(this::enterFullscreen);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
                .setDefaultRequestProperties(new java.util.HashMap<String, String>() {{
                    put("Referer", "https://vk.com/");
                    put("Origin", "https://vk.com");
                    put("Accept", "*/*");
                    put("Accept-Language", "ru,en;q=0.8");
                }});

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .build();

        MediaItem.Builder mib = new MediaItem.Builder().setUri(url).setMediaId(title);
        boolean hls = isHls || url.contains(".m3u8");
        if (hls) mib.setMimeType(MimeTypes.APPLICATION_M3U8);
        MediaItem mediaItem = mib.build();

        MediaSource source;
        if (hls) {
            source = new HlsMediaSource.Factory(http).createMediaSource(mediaItem);
        } else {
            source = new ProgressiveMediaSource.Factory(http).createMediaSource(mediaItem);
        }
        player.setMediaSource(source);
        player.prepare();
        player.setPlayWhenReady(true);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                buffering.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_READY) {
                    btnPlay.setImageResource(player.isPlaying()
                            ? android.R.drawable.ic_media_pause
                            : android.R.drawable.ic_media_play);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                btnPlay.setImageResource(playing
                        ? android.R.drawable.ic_media_pause
                        : android.R.drawable.ic_media_play);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Toast.makeText(PlayerActivity.this,
                        "Ошибка: " + error.getErrorCodeName() + "\n" + error.getMessage(),
                        Toast.LENGTH_LONG).show();
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                // TextureView letterbox handled by transform if needed
            }
        });

        ui.post(updateProgress);
    }

    private void togglePlay() {
        if (player == null) return;
        player.setPlayWhenReady(!player.getPlayWhenReady());
    }

    private void showChrome() {
        controls.setVisibility(View.VISIBLE);
        btnFs.setVisibility(View.VISIBLE);
        btnFs.removeCallbacks(hideChrome);
        btnFs.postDelayed(hideChrome, 3000);
    }

    private void hideChrome() {
        controls.setVisibility(View.GONE);
        btnFs.setVisibility(View.GONE);
    }

    private void toggleFullscreen() {
        if (isFullscreen) exitFullscreen();
        else enterFullscreen();
    }

    private void enterFullscreen() {
        isFullscreen = true;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                getWindow().setDecorFitsSystemWindows(false);
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                //noinspection deprecation
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } catch (Exception ignored) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        try {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } catch (Exception ignored) { }
        btnFs.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
    }

    private void exitFullscreen() {
        isFullscreen = false;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                getWindow().setDecorFitsSystemWindows(true);
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                }
            } else {
                //noinspection deprecation
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } catch (Exception ignored) { }
        try {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        } catch (Exception ignored) { }
        btnFs.setImageResource(android.R.drawable.ic_menu_crop);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && isFullscreen) {
            getWindow().getDecorView().post(this::enterFullscreen);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int w = getResources().getDisplayMetrics().widthPixels;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = ev.getX();
                touchStartY = ev.getY();
                gestureActive = false;
                gestureIsVolume = touchStartX >= w / 2f;
                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                float br = getWindow().getAttributes().screenBrightness;
                startBrightness = br < 0f ? 0.5f : br;
                long now = System.currentTimeMillis();
                float cx = w / 2f;
                float cy = getResources().getDisplayMetrics().heightPixels / 2f;
                if (Math.abs(ev.getX() - cx) < w * 0.25f
                        && Math.abs(ev.getY() - cy) < cy * 0.35f) {
                    if (now - lastTapMs < 300) {
                        toggleFullscreen();
                        lastTapMs = 0;
                        return true;
                    }
                    lastTapMs = now;
                } else {
                    lastTapMs = 0;
                }
                break;
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - touchStartX;
                float dy = ev.getY() - touchStartY;
                if (!gestureActive) {
                    if (Math.abs(dy) > touchSlopPx && Math.abs(dy) > Math.abs(dx) * 1.5f) {
                        gestureActive = true;
                        overlayBox.setVisibility(View.VISIBLE);
                    }
                }
                if (gestureActive) {
                    applyGesture(dy);
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (gestureActive) {
                    overlayBox.setVisibility(View.GONE);
                    gestureActive = false;
                    return true;
                }
                if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                    float dx = Math.abs(ev.getX() - touchStartX);
                    float dy = Math.abs(ev.getY() - touchStartY);
                    if (dx < touchSlopPx && dy < touchSlopPx) {
                        showChrome();
                    }
                }
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    private void applyGesture(float dy) {
        int h = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        float delta = -dy / (h * 0.6f);
        if (gestureIsVolume) {
            int newVol = Math.max(0, Math.min(maxVolume,
                    (int) (startVolume + delta * maxVolume)));
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0);
            int pct = newVol * 100 / maxVolume;
            overlayText.setText("\uD83D\uDD0A  " + pct + "%");
            overlayBar.setProgress(pct);
        } else {
            float newBr = Math.max(0.01f, Math.min(1f, startBrightness + delta));
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = newBr;
            getWindow().setAttributes(lp);
            int pct = (int) (newBr * 100);
            overlayText.setText("\u2600  " + pct + "%");
            overlayBar.setProgress(pct);
        }
    }

    private static String formatTime(long ms) {
        long s = ms / 1000;
        long m = s / 60;
        s = s % 60;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }

    // --- TextureView.SurfaceTextureListener ---
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        surface = new Surface(st);
        if (player != null) player.setVideoSurface(surface);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) { }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        if (player != null) player.setVideoSurface(null);
        if (surface != null) {
            surface.release();
            surface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null) player.setPlayWhenReady(true);
    }

    @Override
    protected void onStop() {
        if (player != null) player.setPlayWhenReady(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(updateProgress);
        btnFs.removeCallbacks(hideChrome);
        if (player != null) {
            player.release();
            player = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (isFullscreen) {
            exitFullscreen();
        } else {
            if (player != null) {
                player.release();
                player = null;
            }
            super.onBackPressed();
        }
    }
}
