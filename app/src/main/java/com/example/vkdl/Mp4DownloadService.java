package com.example.vkdl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Foreground MP4 downloader with parallel HTTP Range requests. */
public class Mp4DownloadService extends Service {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_NAME = "name";
    public static final String ACTION_CANCEL = "com.example.vkdl.action.CANCEL_MP4";

    private static final String CHANNEL = "vk_mp4_foreground";
    private static final int NOTIFICATION_ID = 4103;
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    public static void start(Context ctx, String url, String name) {
        Intent intent = new Intent(ctx, Mp4DownloadService.class);
        intent.putExtra(EXTRA_URL, url);
        intent.putExtra(EXTRA_NAME, name);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent);
        else ctx.startService(intent);
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
                    CHANNEL, "Загрузка MP4", NotificationManager.IMPORTANCE_LOW));
        }
        startForeground(NOTIFICATION_ID, notification("Подготовка загрузки", 0, 0, 0, true));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelled.set(true);
            if (work != null) work.cancel(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        final String url = intent.getStringExtra(EXTRA_URL);
        final String name = intent.getStringExtra(EXTRA_NAME);
        if (url == null || name == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        cancelled.set(false);
        work = pool.submit(() -> {
            try {
                download(url, name);
                nm.notify(NOTIFICATION_ID, notification("Готово: " + name, 100, 0, 0, false));
            } catch (Exception e) {
                if (!cancelled.get()) {
                    nm.notify(NOTIFICATION_ID, notification(
                            "Ошибка: " + e.getMessage(), 0, 0, 0, false));
                }
            } finally {
                stopForeground(false);
                stopSelf();
            }
        });
        return START_NOT_STICKY;
    }

    private void download(String url, String name) throws Exception {
        long total = probeSize(url);
        if (total <= 0) throw new IOException("Не удалось определить размер файла");

        int workers = total > 8_000_000 ? 3 : 1;
        long chunk = (total + workers - 1) / workers;
        File dir = new File(getCacheDir(), "mp4_" + System.currentTimeMillis());
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("cache dir");

        AtomicLong downloaded = new AtomicLong(0);
        AtomicLong speed = new AtomicLong(0);
        AtomicLong lastBytes = new AtomicLong(0);
        AtomicLong lastAt = new AtomicLong(SystemClock.elapsedRealtime());
        List<File> parts = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < workers; i++) {
                final int idx = i;
                final long from = i * chunk;
                final long to = Math.min(total - 1, from + chunk - 1);
                if (from >= total) break;
                File part = new File(dir, "part_" + idx);
                parts.add(part);
                futures.add(pool.submit(() -> {
                    Exception last = null;
                    for (int attempt = 1; attempt <= 3; attempt++) {
                        if (cancelled.get()) throw new IOException("Отменено");
                        try {
                            downloadRange(url, from, to, part, n -> {
                                long all = downloaded.addAndGet(n);
                                long now = SystemClock.elapsedRealtime();
                                long prevAt = lastAt.get();
                                if (now - prevAt >= 400 && lastAt.compareAndSet(prevAt, now)) {
                                    long prev = lastBytes.getAndSet(all);
                                    long instant = Math.max(0, all - prev) * 1000 / Math.max(1, now - prevAt);
                                    long ema = speed.get() == 0 ? instant : (long) (speed.get() * 0.65 + instant * 0.35);
                                    speed.set(ema);
                                    int pct = (int) Math.min(99, all * 100 / total);
                                    nm.notify(NOTIFICATION_ID,
                                            notification("Скачивание: " + name, pct, all, speed.get(), true));
                                }
                            });
                            last = null;
                            break;
                        } catch (Exception e) {
                            last = e;
                            try { Thread.sleep(attempt * 800L); } catch (InterruptedException ie) {
                                throw new IOException("Отменено");
                            }
                        }
                    }
                    if (last != null) throw new IOException("Часть " + (idx + 1), last);
                    return null;
                }));
            }
            for (Future<?> f : futures) f.get();
            long sum = 0;
            for (File p : parts) sum += p.length();
            if (sum != total) throw new IOException("Размер не совпал: " + sum + " / " + total);
            save(parts, name);
        } finally {
            deleteDir(dir);
        }
    }

    private interface ByteSink {
        void onBytes(int n);
    }

    private void downloadRange(String url, long from, long to, File out, ByteSink sink) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "https://vk.com/");
        c.setRequestProperty("Range", "bytes=" + from + "-" + to);
        try {
            int code = c.getResponseCode();
            if (code != 206 && code != 200) throw new IOException("HTTP " + code);
            long expected = to - from + 1;
            try (InputStream in = c.getInputStream();
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                long received = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (cancelled.get()) throw new IOException("Отменено");
                    fos.write(buf, 0, n);
                    received += n;
                    sink.onBytes(n);
                }
                if (received != expected && code == 206) {
                    throw new IOException("Неполный диапазон: " + received);
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private long probeSize(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("HEAD");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "https://vk.com/");
        try {
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                long len = c.getContentLengthLong();
                if (len > 0) return len;
            }
        } finally {
            c.disconnect();
        }
        // fallback GET Range 0-0
        c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Range", "bytes=0-0");
        try {
            String cr = c.getHeaderField("Content-Range");
            if (cr != null && cr.contains("/")) {
                return Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
            }
            return c.getContentLengthLong();
        } finally {
            c.disconnect();
        }
    }

    private void save(List<File> parts, String name) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.Downloads.IS_PENDING, 1);
        android.net.Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Не удалось создать файл в Загрузках");
        try {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new IOException("openOutputStream");
                byte[] buf = new byte[128 * 1024];
                for (File f : parts) {
                    try (FileInputStream in = new FileInputStream(f)) {
                        int n;
                        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);
            }
        } catch (Exception e) {
            getContentResolver().delete(uri, null, null);
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    private Notification notification(String text, int pct, long bytes, long speed, boolean ongoing) {
        Intent cancel = new Intent(this, Mp4DownloadService.class).setAction(ACTION_CANCEL);
        PendingIntent pi = PendingIntent.getService(this, 4104, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        String detail = text;
        if (speed > 0) detail += "\n" + formatBytes(speed) + "/с";
        b.setContentTitle("VK MP4")
                .setContentText(detail)
                .setStyle(new Notification.BigTextStyle().bigText(detail))
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setProgress(100, pct, ongoing && pct <= 0)
                .addAction(android.R.drawable.ic_delete, "Отмена", pi);
        return b.build();
    }

    private static String formatBytes(long v) {
        if (v < 1024) return v + " Б";
        if (v < 1024 * 1024) return (v / 1024) + " КБ";
        return String.format("%.1f МБ", v / (1024.0 * 1024.0));
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }

    @Override
    public void onDestroy() {
        cancelled.set(true);
        pool.shutdownNow();
        super.onDestroy();
    }
}
