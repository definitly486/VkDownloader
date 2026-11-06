package com.example.vkdl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.HashMap;
import java.util.Map;

public class PlaybackService extends Service {
    public static final String ACTION_PLAY = "com.example.vkdl.action.PLAY";
    private static final String ACTION_TOGGLE = "com.example.vkdl.action.TOGGLE";
    private static final String ACTION_STOP = "com.example.vkdl.action.STOP";
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_IS_HLS = "is_hls";
    private static final String CHANNEL_ID = "vk_video_playback";
    private static final int NOTIFICATION_ID = 4102;
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private final IBinder binder = new LocalBinder();
    private ExoPlayer player;
    private String currentTitle = "VK Video";

    public class LocalBinder extends Binder {
        public PlaybackService getService() { return PlaybackService.this; }
    }

    public ExoPlayer getPlayer() { return player; }

    public static void startPlayback(Context context, String url, String title, boolean isHls) {
        Intent intent = new Intent(context, PlaybackService.class)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_IS_HLS, isHls);
        startCompat(context, intent);
    }

    public static void startService(Context context) {
        startCompat(context, new Intent(context, PlaybackService.class));
    }

    private static void startCompat(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://vk.com/");
        headers.put("Origin", "https://vk.com");
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "ru,en;q=0.8");
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
                .setDefaultRequestProperties(headers);

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                updateNotification();
            }
            @Override public void onMediaItemTransition(MediaItem item, int reason) {
                if (item != null && item.mediaMetadata.title != null) {
                    currentTitle = item.mediaMetadata.title.toString();
                }
                updateNotification();
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            if (player != null) player.stop();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Promote immediately after startForegroundService().
        startForeground(NOTIFICATION_ID, buildNotification());
        if (intent != null && ACTION_TOGGLE.equals(intent.getAction())) {
            if (player != null) {
                if (player.isPlaying()) player.pause();
                else if (player.getMediaItemCount() > 0) player.play();
            }
        } else if (intent != null && ACTION_PLAY.equals(intent.getAction())) {
            String url = intent.getStringExtra(EXTRA_URL);
            String title = intent.getStringExtra(EXTRA_TITLE);
            boolean isHls = intent.getBooleanExtra(EXTRA_IS_HLS,
                    url != null && url.contains(".m3u8"));
            if (url != null && !url.isEmpty()) {
                currentTitle = title == null || title.isEmpty() ? "VK Video" : title;
                MediaMetadata metadata = new MediaMetadata.Builder()
                        .setTitle(currentTitle).build();
                MediaItem.Builder item = new MediaItem.Builder()
                        .setUri(url).setMediaMetadata(metadata);
                if (isHls || url.contains(".m3u8")) {
                    item.setMimeType(MimeTypes.APPLICATION_M3U8);
                }
                player.setMediaItem(item.build());
                player.prepare();
                player.play();
            }
        }
        updateNotification();
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Воспроизведение VK", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Управление фоновым воспроизведением видео");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        boolean playing = player != null && player.isPlaying();
        Intent open = new Intent(this, PlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());

        Intent toggle = new Intent(this, PlaybackService.class).setAction(ACTION_TOGGLE);
        PendingIntent togglePending = PendingIntent.getService(this, 2, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
        Intent stop = new Intent(this, PlaybackService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 3, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(playing
                        ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play)
                .setContentTitle(currentTitle)
                .setContentText(playing ? "Воспроизведение" : "Пауза")
                .setContentIntent(content)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .addAction(playing ? android.R.drawable.ic_media_pause
                                : android.R.drawable.ic_media_play,
                        playing ? "Пауза" : "Продолжить", togglePending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Закрыть", stopPending);
        return builder.build();
    }

    private int immutableFlag() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification());
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (player == null || !player.isPlaying()) {
            stopForeground(true);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        stopForeground(true);
        super.onDestroy();
    }
}
