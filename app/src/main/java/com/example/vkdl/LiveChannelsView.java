package com.example.vkdl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Список каналов и плитки опубликованных в них видео.
 * Страницы VK загружаются обычным HTTP-запросом; браузерный движок не используется.
 */
public final class LiveChannelsView extends LinearLayout {
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private static final Channel[] CHANNELS = {
            new Channel("@be_on_edge", "https://vkvideo.ru/@be_on_edge"),
            new Channel("@36nfl", "https://vkvideo.ru/@36nfl"),
            new Channel("@thedoubleplay", "https://vkvideo.ru/@thedoubleplay/lives"),
            new Channel("@sportcast_nhl", "https://vkvideo.ru/@sportcast_nhl"),
            new Channel("@36hockey", "https://vkvideo.ru/@36hockey")
    };

    public interface OnVideoClickListener {
        void onVideoClick(String url, String title);
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final LinearLayout content;
    private final OnVideoClickListener listener;

    public LiveChannelsView(Context context, OnVideoClickListener listener) {
        super(context);
        this.listener = listener;
        setOrientation(VERTICAL);
        setPadding(dp(8), dp(8), dp(8), dp(8));

        TextView heading = new TextView(context);
        heading.setText("LIVE — каналы VK Video");
        heading.setTextSize(20);
        heading.setTextColor(Color.BLACK);
        heading.setPadding(dp(8), dp(8), dp(8), dp(12));
        addView(heading, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(context);
        content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        scroll.addView(content);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        showChannels();
    }

    private void showChannels() {
        content.removeAllViews();
        for (Channel channel : CHANNELS) addChannelRow(channel);
    }

    private void addChannelRow(Channel channel) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setMinimumHeight(dp(76));
        row.setBackground(selectableBackground());
        row.setClickable(true);
        row.setFocusable(true);

        ImageView avatar = new ImageView(getContext());
        GradientDrawable placeholder = new GradientDrawable();
        placeholder.setShape(GradientDrawable.OVAL);
        placeholder.setColor(Color.rgb(225, 230, 238));
        avatar.setBackground(placeholder);
        avatar.setClipToOutline(true);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        row.addView(avatar, new LayoutParams(dp(54), dp(54)));

        LinearLayout labels = new LinearLayout(getContext());
        labels.setOrientation(VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(14), 0, 0, 0);
        TextView title = new TextView(getContext());
        title.setText(channel.title);
        title.setTextSize(16);
        title.setTextColor(Color.BLACK);
        TextView subtitle = new TextView(getContext());
        subtitle.setText("Нажмите, чтобы показать видео");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.DKGRAY);
        labels.addView(title);
        labels.addView(subtitle);
        row.addView(labels, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> loadChannel(channel));
        content.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        executor.execute(() -> {
            try {
                String html = fetch(channel.url);
                String image = findMeta(html, "og:image");
                String name = findMeta(html, "og:title");
                if (name != null) title.postToUi(() -> title.setText(clean(name, channel.title)));
                if (image != null) {
                    Bitmap bitmap = fetchBitmap(image);
                    if (bitmap != null) avatar.postToUi(() -> avatar.setImageBitmap(bitmap));
                }
            } catch (Exception ignored) { }
        });
    }

    private void loadChannel(Channel channel) {
        content.removeAllViews();
        LinearLayout top = new LinearLayout(getContext());
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(getContext());
        back.setText("‹  Каналы");
        back.setTextSize(16);
        back.setTextColor(Color.rgb(25, 95, 170));
        back.setPadding(dp(8), dp(12), dp(20), dp(12));
        back.setOnClickListener(v -> showChannels());
        TextView title = new TextView(getContext());
        title.setText(channel.title);
        title.setTextSize(18);
        title.setTextColor(Color.BLACK);
        top.addView(back);
        top.addView(title);
        content.addView(top);

        TextView status = new TextView(getContext());
        status.setText("Сканирование канала…");
        status.setTextColor(Color.DKGRAY);
        status.setPadding(dp(8), dp(12), dp(8), dp(12));
        content.addView(status);

        executor.execute(() -> {
            try {
                List<Video> videos = scanVideos(channel.url);
                postToUi(() -> {
                    if (content.getChildCount() < 2 || content.getChildAt(1) != status) return;
                    content.removeView(status);
                    if (videos.isEmpty()) {
                        TextView empty = new TextView(getContext());
                        empty.setText("Не удалось найти публичные видео в HTML страницы. "
                                + "Возможно, VK загружает список динамически или требует авторизацию.");
                        empty.setTextColor(Color.DKGRAY);
                        empty.setPadding(dp(12), dp(16), dp(12), dp(16));
                        content.addView(empty);
                    } else {
                        TextView count = new TextView(getContext());
                        count.setText("Найдено видео: " + videos.size());
                        count.setTextColor(Color.DKGRAY);
                        count.setPadding(dp(8), dp(4), dp(8), dp(10));
                        content.addView(count);
                        addVideoGrid(videos);
                    }
                });
            } catch (Exception e) {
                postToUi(() -> status.setText("Ошибка загрузки канала: " + e.getMessage()));
            }
        });
    }

    private void addVideoGrid(List<Video> videos) {
        LinearLayout grid = new LinearLayout(getContext());
        grid.setOrientation(VERTICAL);
        content.addView(grid, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < videos.size(); i += 2) {
            LinearLayout line = new LinearLayout(getContext());
            line.setOrientation(HORIZONTAL);
            Video left = videos.get(i);
            line.addView(createTile(left), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            if (i + 1 < videos.size()) {
                View gap = new View(getContext());
                line.addView(gap, new LayoutParams(dp(8), 1));
                line.addView(createTile(videos.get(i + 1)),
                        new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            } else {
                line.addView(new View(getContext()), new LayoutParams(0, 1, 1f));
            }
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            grid.addView(line, lp);
        }
    }

    private View createTile(Video video) {
        LinearLayout tile = new LinearLayout(getContext());
        tile.setOrientation(VERTICAL);
        tile.setPadding(dp(6), dp(6), dp(6), dp(8));
        tile.setBackground(selectableBackground());
        tile.setClickable(true);
        tile.setFocusable(true);

        ImageView cover = new ImageView(getContext());
        cover.setBackgroundColor(Color.rgb(225, 230, 238));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.addView(cover, new LayoutParams(LayoutParams.MATCH_PARENT, dp(112)));

        TextView title = new TextView(getContext());
        title.setText(video.title);
        title.setTextSize(14);
        title.setTextColor(Color.BLACK);
        title.setMaxLines(3);
        title.setPadding(dp(2), dp(6), dp(2), 0);
        tile.addView(title);
        tile.setOnClickListener(v -> listener.onVideoClick(video.url, video.title));

        if (video.image != null) executor.execute(() -> {
            Bitmap bitmap = fetchBitmap(video.image);
            if (bitmap != null) cover.postToUi(() -> cover.setImageBitmap(bitmap));
        });
        return tile;
    }

    private List<Video> scanVideos(String channelUrl) throws Exception {
        String html = fetch(channelUrl);
        String normalized = html.replace("\\/", "/").replace("\\u002F", "/")
                .replace("&amp;", "&").replace("\\u0026", "&");
        List<Video> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Сначала берём ссылки именно на видео, чтобы не захватывать упоминания из текста.
        Pattern anchors = Pattern.compile("<a\\b[^>]*href\\s*=\\s*[\"']([^\"']*(?:video|clip)-?\\d+_\\d+[^\"']*)[\"'][^>]*>(.*?)</a>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher a = anchors.matcher(normalized);
        while (a.find()) {
            String href = decode(a.group(1));
            Matcher id = Pattern.compile("(?:video|clip)(-?\\d+_\\d+)").matcher(href);
            if (!id.find()) continue;
            addVideo(result, seen, normalized, id.group(1), a.group(2));
        }

        // В некоторых ответах VK ссылки находятся в JSON, а не в HTML-якорях.
        if (result.isEmpty()) {
            Matcher ids = Pattern.compile("(?:video|clip)(-?\\d+_\\d+)").matcher(normalized);
            while (ids.find()) addVideo(result, seen, normalized, ids.group(1), "");
        }
        return result;
    }

    private void addVideo(List<Video> out, Set<String> seen, String html, String id, String anchor) {
        if (!seen.add(id)) return;
        int at = html.indexOf(id);
        int from = Math.max(0, at - 1600);
        int to = Math.min(html.length(), at + 1800);
        String around = html.substring(from, to) + " " + anchor;
        String title = firstValue(around, new String[]{"title", "aria-label", "alt", "name"});
        if (title == null || title.length() < 2) {
            Matcher text = Pattern.compile(">([^<>]{4,180})<").matcher(anchor);
            if (text.find()) title = decode(text.group(1));
        }
        if (title == null || title.trim().isEmpty()) title = "Видео " + id;
        String image = firstImage(around);
        out.add(new Video(id, clean(title, "Видео " + id), image,
                "https://vkvideo.ru/video" + id));
    }

    private static String firstValue(String text, String[] keys) {
        for (String key : keys) {
            Matcher m = Pattern.compile("[\\\"']" + key
                    + "[\\\"']\\s*:\\s*[\\\"']([^\\\"']{2,240})[\\\"']",
                    Pattern.CASE_INSENSITIVE).matcher(text);
            if (m.find()) return decode(m.group(1));
            m = Pattern.compile(key + "\\s*=\\s*[\"']([^\"']{2,240})[\"']",
                    Pattern.CASE_INSENSITIVE).matcher(text);
            if (m.find()) return decode(m.group(1));
        }
        return null;
    }

    private static String firstImage(String text) {
        Matcher m = Pattern.compile("https?://[^\\s\\\"'<>]+?\\.(?:jpg|jpeg|png|webp)(?:\\?[^\\s\\\"'<>]*)?",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) return m.group();
        return null;
    }

    private static String findMeta(String html, String property) {
        Matcher m = Pattern.compile("<meta[^>]+(?:property|name)=[\\\"']"
                + Pattern.quote(property) + "[\\\"'][^>]+content=[\\\"']([^\\\"']+)",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) return decode(m.group(1));
        return null;
    }

    private static String fetch(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "ru,en;q=0.8");
            c.setRequestProperty("Referer", "https://vkvideo.ru/");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally { c.disconnect(); }
    }

    private static Bitmap fetchBitmap(String address) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
            try {
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", UA);
                try (InputStream in = c.getInputStream()) { return BitmapFactory.decodeStream(in); }
            } finally { c.disconnect(); }
        } catch (Exception ignored) { return null; }
    }

    private void postToUi(Runnable action) { if (getHandler() != null) getHandler().post(action); }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String s = decode(value).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        s = s.replaceAll("\\s*[|—-]\\s*VK Видео.*$", "").trim();
        return s.isEmpty() ? fallback : s;
    }

    private static String decode(String value) {
        return value.replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("\\u0026", "&").replace("\\/", "/");
    }

    private android.graphics.drawable.Drawable selectableBackground() {
        android.util.TypedValue value = new android.util.TypedValue();
        if (getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true))
            return getContext().getDrawable(value.resourceId);
        return null;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override protected void onDetachedFromWindow() {
        executor.shutdownNow();
        super.onDetachedFromWindow();
    }

    private static final class Channel {
        final String title, url;
        Channel(String title, String url) { this.title = title; this.url = url; }
    }

    private static final class Video {
        final String id, title, image, url;
        Video(String id, String title, String image, String url) {
            this.id = id; this.title = title; this.image = image; this.url = url;
        }
    }
}
