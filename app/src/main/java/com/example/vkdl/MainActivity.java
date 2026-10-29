package com.example.vkdl;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** isHls=false → прямой mp4; true → HLS. */
class VideoOption {
    final String label;
    final int height;
    final String url;
    final boolean isHls;
    final String audioUrl;

    VideoOption(String label, int height, String url, boolean isHls) {
        this(label, height, url, isHls, null);
    }

    VideoOption(String label, int height, String url, boolean isHls, String audioUrl) {
        this.label = label;
        this.height = height;
        this.url = url;
        this.isHls = isHls;
        this.audioUrl = audioUrl;
    }
}

public class MainActivity extends Activity {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final String CRASH_PREF = "vkdl_crash";

    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText input;
    private Button button;
    private Button cancelDownloadButton;
    private ProgressBar progress;
    private ProgressBar downloadProgress;
    private TextView status;

    private String videoId = "video";
    private String lastHlsMaster;
    private long activeDownloadId = -1L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashLogger();
        showPreviousCrash();

        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        input = new EditText(this);
        input.setHint("Ссылка на видео (vk.com/video-123_456)");
        input.setSingleLine(false);
        input.setMinLines(2);

        button = new Button(this);
        button.setText("НАЙТИ MP4 / ВАРИАНТЫ");
        button.setOnClickListener(v -> search());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);

        downloadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        downloadProgress.setMax(100);
        downloadProgress.setVisibility(View.GONE);

        cancelDownloadButton = new Button(this);
        cancelDownloadButton.setText("Отменить загрузку");
        cancelDownloadButton.setVisibility(View.GONE);
        cancelDownloadButton.setOnClickListener(v -> {
            Intent cancel = new Intent(this, Mp4DownloadService.class)
                    .setAction(Mp4DownloadService.ACTION_CANCEL);
            startService(cancel);
            cancelDownloadButton.setVisibility(View.GONE);
            downloadProgress.setVisibility(View.GONE);
            status.setText("Загрузка отменена");
        });

        status = new TextView(this);
        status.setText("Вставьте ссылку VK / VK Video / live");

        root.addView(input);
        root.addView(button);
        root.addView(progress);
        root.addView(downloadProgress);
        root.addView(cancelDownloadButton);
        root.addView(status);
        setContentView(root);

        askPermissions();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void handleIntent(Intent i) {
        if (i != null && Intent.ACTION_SEND.equals(i.getAction())) {
            String t = i.getStringExtra(Intent.EXTRA_TEXT);
            if (t != null) {
                input.setText(t);
                search();
            }
        }
    }

    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
    }

    private void installCrashLogger() {
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                getSharedPreferences(CRASH_PREF, MODE_PRIVATE).edit()
                        .putString("last", describe(e)).apply();
            } catch (Exception ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    private void showPreviousCrash() {
        String last = getSharedPreferences(CRASH_PREF, MODE_PRIVATE).getString("last", null);
        if (last != null) {
            status.setText("Прошлый запуск завершился падением:\n" + last);
            getSharedPreferences(CRASH_PREF, MODE_PRIVATE).edit().remove("last").apply();
        }
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        button.setEnabled(!busy);
        if (busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable c = e;
        while (c != null) {
            if (sb.length() > 0) sb.append("\n← ");
            sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
            c = c.getCause();
        }
        return sb.toString();
    }

    private void search() {
        final String text = input.getText().toString();
        setBusy(true);
        status.setText("Загрузка информации…");
        io.execute(() -> {
            try {
                String[] ids = parseIds(text);
                if (ids == null) {
                    Matcher m = Pattern.compile("https?://\\S+").matcher(text);
                    if (m.find()) {
                        String resolved = resolveRedirect(m.group());
                        ids = parseIds(resolved);
                    }
                }
                if (ids == null) throw new IllegalStateException("Не удалось найти ID видео в ссылке");
                final String oid = ids[0], id = ids[1], hash = ids[2];
                videoId = oid + "_" + id;
                final List<VideoOption> options = fetchOptions(oid, id, hash);
                ui.post(() -> {
                    setBusy(false);
                    int direct = 0;
                    for (VideoOption o : options) if (!o.isHls) direct++;
                    status.setText("Найдено: " + direct + " прямых mp4 + "
                            + (options.size() - direct) + " HLS");
                    if (options.isEmpty()) status.setText("Варианты не найдены");
                    else showOptions(options);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    setBusy(false);
                    status.setText(e instanceof IllegalStateException
                            ? "Ошибка: " + e.getMessage()
                            : "Ошибка: " + describe(e));
                });
            }
        });
    }

    /** [oid, id, hash] */
    private static String[] parseIds(String text) {
        Matcher m = Pattern.compile("oid=(-?\\d+)&(?:amp;)?id=(\\d+)(?:&(?:amp;)?hash=([0-9a-fA-F]+))?")
                .matcher(text);
        if (m.find()) return new String[]{m.group(1), m.group(2), m.group(3) != null ? m.group(3) : ""};
        m = Pattern.compile("(?:video|clip|live)(-?\\d+)_(\\d+)(?:_([0-9a-fA-F]{6,}))?").matcher(text);
        if (m.find()) return new String[]{m.group(1), m.group(2), m.group(3) != null ? m.group(3) : ""};
        return null;
    }

    private HttpURLConnection openHttp(String url, String method, Map<String, String> headers, byte[] body)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20_000);
        c.setReadTimeout(45_000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "https://vk.com/");
        c.setRequestProperty("Origin", "https://vk.com");
        c.setRequestProperty("Accept-Language", "ru,en;q=0.8");
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet())
            c.setRequestProperty(e.getKey(), e.getValue());
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
        }
        return c;
    }

    private String resolveRedirect(String url) throws Exception {
        HttpURLConnection c = openHttp(url, "GET", null, null);
        try {
            c.getResponseCode();
            return c.getURL().toString();
        } finally {
            c.disconnect();
        }
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection c = openHttp(url, "GET", null, null);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300)
                throw new IllegalStateException("HTTP " + code + " (" + URI.create(url).getHost() + ")");
            return readStream(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private Object[] httpFetch(String url) throws Exception {
        HttpURLConnection c = openHttp(url, "GET", null, null);
        try {
            int code = c.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String body = stream != null ? readStream(stream) : "";
            return new Object[]{code, body};
        } finally {
            c.disconnect();
        }
    }

    private static String readStream(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) >= 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private static String hintFrom(String body) {
        Matcher m = Pattern.compile(
                "[^<>\"{}]{0,60}(?:не найден|недоступн|удал[её]н|приватн|ограничен)[^<>\"{}]{0,60}",
                Pattern.CASE_INSENSITIVE).matcher(body);
        return m.find() ? " — «" + m.group().trim() + "»" : "";
    }

    private List<VideoOption> fetchOptions(String oid, String id, String hash) throws Exception {
        StringBuilder log = new StringBuilder();
        String vid = oid + "_" + id;

        try {
            List<VideoOption> opts = fetchViaAlVideo(oid, id, hash);
            log.append("• al_video.php: вариантов ").append(opts.size()).append('\n');
            if (!opts.isEmpty()) return opts;
        } catch (Exception e) {
            log.append("• al_video.php: ").append(e.getMessage()).append('\n');
        }

        String q = "oid=" + oid + "&id=" + id + (hash.isEmpty() ? "" : "&hash=" + hash);
        String[] pages = {
                "https://vk.com/video_ext.php?" + q,
                "https://vkvideo.ru/video_ext.php?" + q,
                "https://vkvideo.ru/video" + vid,
                "https://vkvideo.ru/live" + vid,
                "https://vksport.vkvideo.ru/video" + vid,
                "https://vksport.vkvideo.ru/live" + vid,
                "https://vksport.m.vkvideo.ru/video" + vid,
                "https://vksport.m.vkvideo.ru/live" + vid,
                "https://m.vk.com/video" + vid,
                "https://m.vk.com/live" + vid,
                "https://vk.com/video" + vid,
                "https://vk.com/live" + vid
        };
        for (String u : pages) {
            String label;
            try {
                URI uri = URI.create(u);
                label = uri.getHost() + uri.getPath();
            } catch (Exception e) {
                label = u;
            }
            try {
                Object[] r = httpFetch(u);
                int code = (Integer) r[0];
                String body = (String) r[1];
                List<VideoOption> opts = (code >= 200 && code < 300)
                        ? extractOptions(body) : Collections.emptyList();
                log.append("• ").append(label).append(": HTTP ").append(code)
                        .append(", ").append(body.length()).append(" байт, вариантов: ")
                        .append(opts.size());
                if (opts.isEmpty()) log.append(hintFrom(body));
                log.append('\n');
                if (!opts.isEmpty()) return opts;
            } catch (Exception e) {
                log.append("• ").append(label).append(": ").append(e.getMessage()).append('\n');
            }
        }
        throw new IllegalStateException(
                "Ссылки не найдены. Видео может быть приватным, удалённым, доступным "
                        + "только по hash/после входа, либо внешним (YouTube и т.п.).\n\nПопытки:\n"
                        + log);
    }

    private List<VideoOption> fetchViaAlVideo(String oid, String id, String hash) throws Exception {
        String video = oid + "_" + id;
        StringBuilder form = new StringBuilder("act=show&al=1&video=")
                .append(URLEncoder.encode(video, "UTF-8"));
        if (!hash.isEmpty()) form.append("&hash=").append(URLEncoder.encode(hash, "UTF-8"));

        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://vk.com/video" + video);
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("Accept", "*/*");

        HttpURLConnection c = openHttp("https://vk.com/al_video.php", "POST", headers,
                form.toString().getBytes("UTF-8"));
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            String json = readStream(c.getInputStream());
            if (json.isEmpty()) throw new IllegalStateException("пустой ответ");
            return extractOptions(json);
        } finally {
            c.disconnect();
        }
    }

    private List<VideoOption> extractOptions(String raw) {
        String clean = raw
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .replace("\\u003d", "=")
                .replace("\\u003A", ":")
                .replace("\\u003a", ":")
                .replace("\\\"", "\"")
                .replace("\\x2F", "/")
                .replace("\\x2f", "/");

        List<int[]> heights = new ArrayList<>();
        List<String> urls = new ArrayList<>();

        Pattern p1 = Pattern.compile("\"(?:url|cache)_?(\\d{3,4})\"\\s*:\\s*\"(https?:[^\"]+)\"");
        Matcher m = p1.matcher(clean);
        while (m.find()) {
            heights.add(new int[]{Integer.parseInt(m.group(1))});
            urls.add(m.group(2));
        }
        Pattern p2 = Pattern.compile("(?:^|[,{\\s])(?:url|cache)(\\d{3,4})\\s*:\\s*\"(https?:[^\"]+)\"");
        m = p2.matcher(clean);
        while (m.find()) {
            heights.add(new int[]{Integer.parseInt(m.group(1))});
            urls.add(m.group(2));
        }
        Pattern p3 = Pattern.compile(
                "https?://[^\\s\"'<>\\\\]+?(?:[./_](\\d{3,4})\\.mp4|\\.(\\d{3,4})\\.mp4)[^\\s\"'<>\\\\]*");
        m = p3.matcher(clean);
        while (m.find()) {
            String h = m.group(1) != null && !m.group(1).isEmpty() ? m.group(1) : m.group(2);
            if (h == null) continue;
            heights.add(new int[]{Integer.parseInt(h)});
            urls.add(m.group());
        }
        Pattern p4 = Pattern.compile(
                "\"(?:postlive_mp4|live_mp4|extra_data|url_live)\"\\s*:\\s*\"(https?:[^\"]+)\"");
        m = p4.matcher(clean);
        while (m.find()) {
            String u = m.group(1);
            int h = 720;
            Matcher hm = Pattern.compile("[./_](\\d{3,4})\\.mp4").matcher(u);
            if (hm.find()) h = Integer.parseInt(hm.group(1));
            heights.add(new int[]{h});
            urls.add(u);
        }

        Map<Integer, String> byH = new LinkedHashMap<>();
        for (int i = 0; i < heights.size(); i++) {
            int h = heights.get(i)[0];
            String u = urls.get(i)
                    .replace("\\/", "/")
                    .replace("\\u002F", "/")
                    .replace("\\u0026", "&")
                    .replace("&amp;", "&");
            if (h < 144 || h > 4320) continue;
            if (!(u.startsWith("http://") || u.startsWith("https://") || u.startsWith("//"))) continue;
            String low = u.toLowerCase();
            if (low.contains(".m3u8") || low.contains("manifest") || low.contains(".mpd")
                    || low.contains("mediascope") || low.contains("getvideopreview")) continue;
            if (u.startsWith("//")) u = "https:" + u;
            if (!byH.containsKey(h)) byH.put(h, u);
        }

        List<VideoOption> mp4 = new ArrayList<>();
        List<Integer> sorted = new ArrayList<>(byH.keySet());
        Collections.sort(sorted, Comparator.reverseOrder());
        for (int h : sorted) {
            mp4.add(new VideoOption(h + "p — mp4 (прямая ссылка) ★", h, byH.get(h), false));
        }

        List<String> hlsCandidates = new ArrayList<>();
        Matcher hm = Pattern.compile(
                "\"(?:hls(?:_ondemand|_live|_src)?|live_hls|manifest)\"\\s*:\\s*\"(https?://[^\"]+\\.m3u8[^\"]*)\"")
                .matcher(clean);
        while (hm.find()) hlsCandidates.add(hm.group(1));
        String hlsUrl = hlsCandidates.isEmpty() ? null : hlsCandidates.get(0);
        if (hlsUrl == null) {
            Matcher hm2 = Pattern.compile(
                    "\"(?:hls(?:_ondemand|_live)?|live_hls)\"\\s*:\\s*\"(https?://[^\"]+)\"")
                    .matcher(clean);
            if (hm2.find()) hlsUrl = hm2.group(1);
        }

        List<VideoOption> hls = new ArrayList<>();
        if (hlsUrl != null) {
            lastHlsMaster = hlsUrl;
            try {
                hls = parseHls(hlsUrl);
            } catch (Exception e) {
                hls = Collections.singletonList(new VideoOption("HLS live → mp4", 0, hlsUrl, true));
            }
            if (hls.isEmpty()) {
                hls = Collections.singletonList(new VideoOption("HLS live → mp4", 0, hlsUrl, true));
            }
        } else {
            lastHlsMaster = null;
        }

        List<VideoOption> all = new ArrayList<>(mp4);
        all.addAll(hls);
        return all;
    }

    private List<VideoOption> parseHls(String masterUrl) throws Exception {
        String text = httpGet(masterUrl);
        if (!text.contains("#EXT-X-STREAM-INF")) {
            return Collections.singletonList(new VideoOption("HLS-поток → mp4", 0, masterUrl, true));
        }
        String[] lines = text.split("\n");
        Map<String, String> audioGroups = new HashMap<>();
        for (String l0 : lines) {
            String l = l0.trim();
            if (!l.startsWith("#EXT-X-MEDIA:") || !"AUDIO".equals(attr(l, "TYPE"))) continue;
            String uri = attr(l, "URI");
            String gid = attr(l, "GROUP-ID");
            if (uri == null || gid == null) continue;
            String resolved = resolve(masterUrl, uri);
            if ("YES".equals(attr(l, "DEFAULT")) || !audioGroups.containsKey(gid)) {
                audioGroups.put(gid, resolved);
            }
        }
        String fallbackAudio = audioGroups.isEmpty() ? null : audioGroups.values().iterator().next();

        List<VideoOption> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue;
            String uri = null;
            if (i + 1 < lines.length) {
                String n = lines[i + 1].trim();
                if (!n.startsWith("#") && !n.isEmpty()) uri = n;
            }
            if (uri == null) continue;
            int h = 0;
            Matcher rm = Pattern.compile("RESOLUTION=\\d+x(\\d+)").matcher(l);
            if (rm.find()) h = Integer.parseInt(rm.group(1));
            String audio = attr(l, "AUDIO");
            String audioUrl = audio != null ? audioGroups.get(audio) : null;
            if (audioUrl == null) audioUrl = fallbackAudio;
            String q = h > 0 ? h + "p" : "HLS";
            String label = audioUrl != null ? q + " — HLS видео + аудио → mp4" : q + " — HLS → mp4";
            if (seen.add(h)) {
                out.add(new VideoOption(label, h, resolve(masterUrl, uri), true, audioUrl));
            }
        }
        if (out.isEmpty()) {
            out.add(new VideoOption("HLS-поток → mp4", 0, masterUrl, true, fallbackAudio));
        }
        out.sort((a, b) -> Integer.compare(b.height, a.height));
        return out;
    }

    private static String resolve(String base, String ref) {
        return URI.create(base).resolve(ref).toString();
    }

    private static String attr(String line, String name) {
        Matcher m = Pattern.compile("(?:^|[:,])" + name + "=(\"([^\"]*)\"|[^,]*)").matcher(line);
        if (!m.find()) return null;
        return m.group(2) != null ? m.group(2) : m.group(1);
    }

    private void showOptions(List<VideoOption> options) {
        List<VideoOption> direct = new ArrayList<>();
        List<VideoOption> hls = new ArrayList<>();
        for (VideoOption o : options) {
            if (o.isHls) hls.add(o);
            else direct.add(o);
        }
        String playUrl = lastHlsMaster;
        if (playUrl == null && !hls.isEmpty()) playUrl = bestPlayUrl(hls.get(0));
        if (playUrl == null && !direct.isEmpty()) playUrl = direct.get(0).url;

        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        if (playUrl != null && !hls.isEmpty()) {
            final String pu = playUrl;
            labels.add("▶ Смотреть live (встроенный плеер)");
            actions.add(() -> playInApp(pu, true));
            labels.add("▶ Смотреть live — другое приложение");
            actions.add(() -> playStream(pu, true, true));
        }
        if (!direct.isEmpty() && hls.isEmpty()) {
            final String u = direct.get(0).url;
            labels.add("▶ Смотреть (встроенный плеер)");
            actions.add(() -> playInApp(u, false));
            labels.add("▶ Смотреть — другое приложение");
            actions.add(() -> playStream(u, false, true));
        }
        for (VideoOption opt : direct) {
            final VideoOption o = opt;
            labels.add(o.label + "  — скачать");
            actions.add(() -> downloadMp4(o));
            labels.add("▶ Смотреть " + o.height + "p (встроенный плеер)");
            actions.add(() -> playInApp(o.url, false));
        }
        for (VideoOption opt : hls) {
            final VideoOption o = opt;
            labels.add(o.label + "  — скачать (фон)");
            actions.add(() -> downloadHls(o));
            String q = o.height > 0 ? o.height + "p" : "HLS";
            labels.add("▶ Смотреть " + q + " (встроенный плеер)");
            actions.add(() -> playInApp(bestPlayUrl(o), true));
        }

        String title;
        if (!hls.isEmpty() && !direct.isEmpty()) title = "Live / видео — смотреть или скачать";
        else if (!hls.isEmpty()) title = "Live HLS — смотреть или скачать";
        else title = "Выберите вариант (★ = прямая mp4)";

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private String bestPlayUrl(VideoOption opt) {
        return (lastHlsMaster != null && !lastHlsMaster.isEmpty()) ? lastHlsMaster : opt.url;
    }

    private void playInApp(String url, boolean isHls) {
        PlayerActivity.start(this, url, "VK " + videoId, isHls);
        status.setText("Встроенный плеер\n" + url);
    }

    private void playStream(String url, boolean isHls, boolean forceChooser) {
        Uri uri = Uri.parse(url);
        boolean isM3u8 = isHls || url.contains(".m3u8");
        String[] mimes = isM3u8
                ? new String[]{"application/x-mpegURL", "application/vnd.apple.mpegurl", "video/*"}
                : new String[]{"video/mp4", "video/*"};
        if (!forceChooser) {
            for (String mime : mimes) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, mime);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (intent.resolveActivity(getPackageManager()) != null) {
                    try {
                        startActivity(intent);
                        status.setText("Открыто во внешнем плеере\n" + url);
                        return;
                    } catch (Exception ignored) {}
                }
            }
        }
        try {
            Intent primary = new Intent(Intent.ACTION_VIEW);
            primary.setDataAndType(uri, mimes[0]);
            primary.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(primary, "Открыть поток в…"));
            status.setText("Выберите приложение\n" + url);
        } catch (Exception e) {
            status.setText("Не удалось открыть: " + e.getMessage());
            Toast.makeText(this, "Нужен плеер с HLS (VLC)", Toast.LENGTH_LONG).show();
        }
    }

    private void downloadMp4(VideoOption opt) {
        String fileName = "vk_" + videoId + "_" + opt.height + "p.mp4";
        downloadProgress.setVisibility(View.VISIBLE);
        downloadProgress.setIndeterminate(true);
        status.setText("Запуск фоновой загрузки…");
        Mp4DownloadService.start(this, opt.url, fileName);
        cancelDownloadButton.setVisibility(View.VISIBLE);
        activeDownloadId = -3L;
        downloadProgress.setIndeterminate(false);
        status.setText("Загрузка в фоне. Смотрите уведомление.");
    }

    private void downloadHls(VideoOption opt) {
        String label = opt.height > 0 ? opt.height + "p" : "hls";
        HlsDownloadService.start(this, opt.url, opt.audioUrl, opt.height, videoId);
        status.setText("Фоновая HLS-загрузка (" + label + ")\nПрогресс — в уведомлении.");
        Toast.makeText(this, "HLS в фоне (" + label + ")", Toast.LENGTH_LONG).show();
    }
}
