package com.example.vkdl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Фоновая загрузка HLS → файл в «Загрузки». */
public class HlsDownloadService extends Service {

    public static final String CHANNEL_ID = "vk_hls_downloads";
    public static final int NOTIF_ID = 42;
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_AUDIO_URL = "audio_url";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_VIDEO_ID = "video_id";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    public static void start(Context ctx, String url, String audioUrl, int height, String videoId) {
        Intent i = new Intent(ctx, HlsDownloadService.class);
        i.putExtra(EXTRA_URL, url);
        i.putExtra(EXTRA_AUDIO_URL, audioUrl);
        i.putExtra(EXTRA_HEIGHT, height);
        i.putExtra(EXTRA_VIDEO_ID, videoId);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    private NotificationManager nm;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private Future<?> work;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "HLS загрузка", NotificationManager.IMPORTANCE_LOW));
        }
        startForeground(NOTIF_ID, notif("Подготовка HLS…", 0, true));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        final String url = intent.getStringExtra(EXTRA_URL);
        final String audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL);
        final int height = intent.getIntExtra(EXTRA_HEIGHT, 0);
        final String videoId = intent.getStringExtra(EXTRA_VIDEO_ID);
        if (url == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        cancelled.set(false);
        work = pool.submit(() -> {
            File tmp = null;
            try {
                String label = height > 0 ? height + "p" : "hls";
                nm.notify(NOTIF_ID, notif("HLS " + label + "…", 0, true));
                File videoFile = downloadPlaylist(url, pct ->
                        nm.notify(NOTIF_ID, notif("Видео " + label + ": " + pct + "%", pct, true)));
                File audioFile = null;
                if (audioUrl != null && !audioUrl.isEmpty()) {
                    audioFile = downloadPlaylist(audioUrl, pct ->
                            nm.notify(NOTIF_ID, notif("Аудио: " + pct + "%", pct, true)));
                }
                String outName = "vk_" + (videoId != null ? videoId : "video") + "_" + label + ".mp4";
                File out = new File(getCacheDir(), outName);
                if (audioFile != null) {
                    nm.notify(NOTIF_ID, notif("Сборка mp4…", 95, true));
                    muxToMp4(java.util.Arrays.asList(videoFile, audioFile), out);
                    videoFile.delete();
                    audioFile.delete();
                } else {
                    // ts/fmp4 as-is or try remux single
                    if (videoFile.getName().endsWith(".mp4")) {
                        out = videoFile;
                    } else {
                        try {
                            muxToMp4(java.util.Collections.singletonList(videoFile), out);
                            videoFile.delete();
                        } catch (Exception e) {
                            // leave as .ts
                            out = videoFile;
                            outName = outName.replace(".mp4", ".ts");
                        }
                    }
                }
                String mime = out.getName().endsWith(".mp4") ? "video/mp4" : "video/mp2t";
                saveToDownloads(out, outName, mime);
                String check = checkMp4(new File(getCacheDir(), "x")); // noop path
                nm.notify(NOTIF_ID, notif("Готово: " + outName, 100, false));
            } catch (Exception e) {
                if (!cancelled.get()) {
                    nm.notify(NOTIF_ID, notif("Ошибка: " + e.getMessage(), 0, false));
                }
            } finally {
                stopForeground(false);
                stopSelf();
            }
        });
        return START_NOT_STICKY;
    }

    private interface Progress {
        void on(int pct);
    }

    private static class HlsKey {
        final String uri;
        final byte[] iv;
        HlsKey(String uri, byte[] iv) { this.uri = uri; this.iv = iv; }
    }

    private static class HlsSeg {
        final String url;
        final long seq;
        final HlsKey key;
        HlsSeg(String url, long seq, HlsKey key) {
            this.url = url; this.seq = seq; this.key = key;
        }
    }

    private File downloadPlaylist(String playlistUrl, Progress progress) throws Exception {
        String text = httpGet(playlistUrl);
        // if master, pick first media playlist
        if (text.contains("#EXT-X-STREAM-INF")) {
            String[] lines = text.split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                    for (int j = i + 1; j < lines.length; j++) {
                        String u = lines[j].trim();
                        if (!u.isEmpty() && !u.startsWith("#")) {
                            playlistUrl = resolve(playlistUrl, u);
                            text = httpGet(playlistUrl);
                            break;
                        }
                    }
                    break;
                }
            }
        }

        long seq = 0;
        HlsKey key = null;
        String mapUrl = null;
        List<HlsSeg> segs = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try { seq = Long.parseLong(line.substring(line.indexOf(':') + 1).trim()); } catch (Exception ignored) {}
            } else if (line.startsWith("#EXT-X-KEY:")) {
                String method = attr(line, "METHOD");
                if (method == null || "NONE".equals(method)) key = null;
                else if ("AES-128".equals(method)) {
                    String ku = attr(line, "URI");
                    if (ku == null) throw new IllegalStateException("нет URI ключа");
                    String ivs = attr(line, "IV");
                    key = new HlsKey(resolve(playlistUrl, ku), ivs != null ? hexToBytes(ivs) : null);
                } else throw new IllegalStateException("Шифрование " + method);
            } else if (line.startsWith("#EXT-X-MAP:")) {
                String u = attr(line, "URI");
                if (u != null) mapUrl = resolve(playlistUrl, u);
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                segs.add(new HlsSeg(resolve(playlistUrl, line), seq++, key));
            }
        }
        if (segs.isEmpty()) throw new IllegalStateException("Нет сегментов HLS");

        boolean fmp4 = mapUrl != null;
        File out = new File(getCacheDir(), "hls_" + System.currentTimeMillis() + (fmp4 ? ".mp4" : ".ts"));
        ConcurrentHashMap<String, byte[]> keys = new ConcurrentHashMap<>();
        try (FileOutputStream fos = new FileOutputStream(out)) {
            if (mapUrl != null) {
                byte[] init = httpBytes(mapUrl);
                fos.write(init);
            }
            int total = segs.size();
            for (int i = 0; i < total; i++) {
                if (cancelled.get()) throw new IllegalStateException("Отменено");
                byte[] data = fetchSegment(segs.get(i), keys);
                fos.write(data);
                progress.on((i + 1) * 100 / total);
            }
        }
        return out;
    }

    private byte[] fetchSegment(HlsSeg s, ConcurrentHashMap<String, byte[]> keys) throws Exception {
        byte[] data = httpBytes(s.url);
        if (s.key == null) return data;
        byte[] keyBytes = keys.computeIfAbsent(s.key.uri, u -> {
            try { return httpBytes(u); } catch (Exception e) { throw new RuntimeException(e); }
        });
        byte[] iv = s.key.iv;
        if (iv == null) {
            iv = new byte[16];
            long seq = s.seq;
            for (int i = 15; i >= 8; i--) {
                iv[i] = (byte) (seq & 0xFF);
                seq >>= 8;
            }
        }
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(data);
    }

    private void muxToMp4(List<File> files, File dst) throws Exception {
        class MuxSrc {
            final MediaExtractor ex = new MediaExtractor();
            final java.util.HashMap<Integer, Integer> tracks = new java.util.HashMap<>();
            boolean done;
            long baseUs;
        }
        List<MuxSrc> srcs = new ArrayList<>();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            for (File f : files) {
                MuxSrc s = new MuxSrc();
                s.ex.setDataSource(f.getAbsolutePath());
                srcs.add(s);
            }
            muxer = new MediaMuxer(dst.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            for (MuxSrc s : srcs) {
                int count = s.ex.getTrackCount();
                for (int t = 0; t < count; t++) {
                    MediaFormat fmt = s.ex.getTrackFormat(t);
                    String mime = fmt.getString(MediaFormat.KEY_MIME);
                    if (mime == null) continue;
                    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                        s.ex.selectTrack(t);
                        int idx = muxer.addTrack(fmt);
                        s.tracks.put(t, idx);
                    }
                }
            }
            muxer.start();
            started = true;
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            while (true) {
                MuxSrc best = null;
                long bestT = Long.MAX_VALUE;
                boolean any = false;
                for (MuxSrc s : srcs) {
                    if (s.done) continue;
                    any = true;
                    long raw = s.ex.getSampleTime();
                    if (raw < 0) { s.done = true; continue; }
                    long t = raw - s.baseUs;
                    if (t < bestT) { best = s; bestT = t; }
                }
                if (!any || best == null) break;
                buffer.clear();
                int size = best.ex.readSampleData(buffer, 0);
                if (size < 0) { best.done = true; continue; }
                Integer track = best.tracks.get(best.ex.getSampleTrackIndex());
                if (track != null) {
                    int flags = (best.ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    info.set(0, size, Math.max(0, bestT), flags);
                    muxer.writeSampleData(track, buffer, info);
                }
                best.ex.advance();
            }
        } finally {
            if (muxer != null) {
                try { if (started) muxer.stop(); } catch (Exception ignored) {}
                try { muxer.release(); } catch (Exception ignored) {}
            }
            for (MuxSrc s : srcs) try { s.ex.release(); } catch (Exception ignored) {}
        }
    }

    private void saveToDownloads(File src, String name, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            android.net.Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("не удалось создать файл");
            try (OutputStream out = getContentResolver().openOutputStream(uri);
                 InputStream in = new FileInputStream(src)) {
                if (out == null) throw new IllegalStateException("out");
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            }
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(new File(dir, name))) {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        src.delete();
    }

    private String checkMp4(File f) { return ""; }

    private Notification notif(String text, int pct, boolean ongoing) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("VK HLS")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setProgress(100, pct, ongoing && pct <= 0)
                .build();
    }

    private static String httpGet(String url) throws Exception {
        return new String(httpBytes(url), "UTF-8");
    }

    private static byte[] httpBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20_000);
        c.setReadTimeout(45_000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "https://vk.com/");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            try (InputStream in = c.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static String resolve(String base, String ref) {
        return URI.create(base).resolve(ref).toString();
    }

    private static String attr(String line, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:^|[:,])" + name + "=(\"([^\"]*)\"|[^,]*)")
                .matcher(line);
        if (!m.find()) return null;
        return m.group(2) != null ? m.group(2) : m.group(1);
    }

    private static byte[] hexToBytes(String s) {
        String hex = s;
        if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
        while (hex.length() < 32) hex = "0" + hex;
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Override
    public void onDestroy() {
        cancelled.set(true);
        pool.shutdownNow();
        super.onDestroy();
    }
}
