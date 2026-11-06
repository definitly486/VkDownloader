package com.example.vkdl;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

public class PlaybackService extends MediaSessionService {
    public static final String ACTION_PLAY = "com.example.vkdl.action.PLAY";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_IS_HLS = "is_hls";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private ExoPlayer player;
    private MediaSession mediaSession;

    public static void startPlayback(android.content.Context context, String url,
                                     String title, boolean isHls) {
        Intent intent = new Intent(context, PlaybackService.class)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_IS_HLS, isHls);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
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

        Intent openPlayer = new Intent(this, PlayerActivity.class);
        PendingIntent sessionActivity = PendingIntent.getActivity(this, 0, openPlayer,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        mediaSession = new MediaSession.Builder(this, player)
                .setSessionActivity(sessionActivity)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent != null && ACTION_PLAY.equals(intent.getAction())) {
            String url = intent.getStringExtra(EXTRA_URL);
            String title = intent.getStringExtra(EXTRA_TITLE);
            boolean isHls = intent.getBooleanExtra(EXTRA_IS_HLS,
                    url != null && url.contains(".m3u8"));
            if (url != null && !url.isEmpty()) {
                MediaItem.Builder item = new MediaItem.Builder()
                        .setUri(url).setMediaId(title == null ? "VK Video" : title);
                boolean hls = isHls || url.contains(".m3u8");
                if (hls) item.setMimeType(MimeTypes.APPLICATION_M3U8);
                MediaItem mediaItem = item.build();
                MediaSource source = hls
                        ? new HlsMediaSource.Factory(new DefaultHttpDataSource.Factory()
                            .setUserAgent(UA).setAllowCrossProtocolRedirects(true)
                            .setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000)
                            .setDefaultRequestProperties(new java.util.HashMap<String, String>() {{
                                put("Referer", "https://vk.com/");
                                put("Origin", "https://vk.com");
                                put("Accept", "*/*");
                                put("Accept-Language", "ru,en;q=0.8");
                            }})).createMediaSource(mediaItem)
                        : new ProgressiveMediaSource.Factory(new DefaultHttpDataSource.Factory()
                            .setUserAgent(UA).setAllowCrossProtocolRedirects(true)
                            .setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000)
                            .setDefaultRequestProperties(new java.util.HashMap<String, String>() {{
                                put("Referer", "https://vk.com/");
                                put("Origin", "https://vk.com");
                                put("Accept", "*/*");
                                put("Accept-Language", "ru,en;q=0.8");
                            }})).createMediaSource(mediaItem);
                player.setMediaSource(source);
                player.prepare();
                player.play();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (player == null || !player.isPlaying()) stopSelf();
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
