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
                if (name != null) title.post(() -> title.setText(clean(name, channel.title)));
                if (image != null) {
                    Bitmap bitmap = fetchBitmap(image);
                    if (bitmap != null) avatar.post(() -> avatar.setImageBitmap(bitmap));
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
            if (bitmap != null) cover.post(() -> cover.setImageBitmap(bitmap));
        });
        return tile;
    }

    private static final String VK_CLIENT_ID = "52461373";
    private static final String VK_API_VERSION = "5.282";
    private static final int VK_PAGE_SIZE = 100;
    private static final int VK_MAX_PAGES = 3;
    private static final int VK_TIMEOUT_MS = 10000;
    private static String anonymousToken;
    private static long tokenExpiresAt;

    private List<Video> scanVideos(String channelUrl) throws Exception {
        String screenName = extractScreenName(channelUrl);
        JSONObject owner = callVkApi("utils.resolveScreenName",
                "screen_name=" + android.net.Uri.encode(screenName));
        JSONObject resolved = owner.optJSONObject("response");
        if (resolved == null || resolved.optLong("object_id") == 0) {
            throw new IllegalStateException("VK не смог определить владельца канала @" + screenName);
        }

        long ownerId = resolved.optLong("object_id");
        if ("group".equalsIgnoreCase(resolved.optString("type"))) ownerId = -ownerId;

        List<Video> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int page = 0; page < VK_MAX_PAGES; page++) {
            String params = "owner_id=" + ownerId + "&count=" + VK_PAGE_SIZE
                    + "&offset=" + (page * VK_PAGE_SIZE) + "&extended=1";
            JSONObject root = callVkApi("video.get", params);
            JSONObject response = root.optJSONObject("response");
            org.json.JSONArray items = response == null ? null : response.optJSONArray("items");
            if (items == null || items.length() == 0) break;

            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                long videoOwner = item.optLong("owner_id", ownerId);
                long videoId = item.optLong("id");
                if (videoId == 0) continue;
                String key = videoOwner + "_" + videoId;
                if (!seen.add(key)) continue;

                String accessKey = item.optString("access_key", "");
                String fullId = key + (accessKey.isEmpty() ? "" : "_" + accessKey);
                String title = clean(item.optString("title", ""), "Видео " + key);
                String image = videoImage(item);
                result.add(new Video(fullId, title, image,
                        "https://vkvideo.ru/video" + fullId));
            }

            int total = response.optInt("count", items.length());
            if (items.length() < VK_PAGE_SIZE || (page + 1) * VK_PAGE_SIZE >= total) break;
        }
        return result;
    }

    private static String extractScreenName(String channelUrl) {
        Matcher matcher = Pattern.compile("@([^/?#]+)").matcher(channelUrl);
        if (!matcher.find()) throw new IllegalArgumentException("В ссылке не найдено имя канала");
        return matcher.group(1);
    }

    private static String videoImage(JSONObject video) {
        org.json.JSONArray images = video.optJSONArray("image");
        String best = "";
        int bestWidth = -1;
        if (images != null) {
            for (int i = 0; i < images.length(); i++) {
                JSONObject image = images.optJSONObject(i);
                if (image == null) continue;
                String url = image.optString("url", "");
                int width = image.optInt("width");
                if (!url.isEmpty() && width > bestWidth && width <= 800) {
                    best = url;
                    bestWidth = width;
                }
            }
            if (best.isEmpty()) {
                for (int i = 0; i < images.length(); i++) {
                    JSONObject image = images.optJSONObject(i);
                    if (image != null && !image.optString("url", "").isEmpty()) {
                        best = image.optString("url");
                        break;
                    }
                }
            }
        }
        if (!best.isEmpty()) return best;
        String[] fields = {"first_frame", "photo_800", "photo_640", "photo_320"};
        for (String field : fields) {
            String value = video.optString(field, "");
            if (value.startsWith("http")) return value;
        }
        return "";
    }

    private static JSONObject callVkApi(String method, String params) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = getAnonymousToken();
            String address = "https://api.vk.com/method/" + method + "?v="
                    + VK_API_VERSION + "&" + params + "&access_token="
                    + android.net.Uri.encode(token);
            JSONObject root = vkGetJson(address);
            JSONObject error = root.optJSONObject("error");
            if (error == null) return root;
            int code = error.optInt("error_code");
            if (code == 5 && attempt == 0) {
                clearAnonymousToken();
                continue;
            }
            throw new IllegalStateException("VK API " + method + ": "
                    + error.optString("error_msg", "ошибка запроса") + " (код " + code + ")");
        }
        throw new IllegalStateException("Не удалось получить доступ к VK API");
    }

    private static synchronized String getAnonymousToken() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        if (anonymousToken != null && now + 60 < tokenExpiresAt) return anonymousToken;

        HttpURLConnection connection = (HttpURLConnection) new URL(
                "https://login.vk.com/?act=get_anonym_token").openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(VK_TIMEOUT_MS);
            connection.setReadTimeout(VK_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("User-Agent", UA);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("Origin", "https://vkvideo.ru");
            connection.setRequestProperty("Referer", "https://vkvideo.ru/");
            byte[] body = ("client_id=" + VK_CLIENT_ID).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("VK login HTTP " + code + " (redirects disabled)");
            }
            JSONObject root = new JSONObject(readVkResponse(connection));
            if (!"okay".equals(root.optString("type"))) {
                throw new IllegalStateException("VK не выдал анонимную сессию");
            }
            JSONObject data = root.optJSONObject("data");
            anonymousToken = data == null ? null : data.optString("access_token", null);
            tokenExpiresAt = data == null ? 0 : data.optLong("expired_at");
            if (anonymousToken == null || anonymousToken.isEmpty()) {
                throw new IllegalStateException("VK не вернул анонимный токен");
            }
            return anonymousToken;
        } finally {
            connection.disconnect();
        }
    }

    private static synchronized void clearAnonymousToken() {
        anonymousToken = null;
        tokenExpiresAt = 0;
    }

    private static JSONObject vkGetJson(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(VK_TIMEOUT_MS);
            connection.setReadTimeout(VK_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", UA);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("VK API HTTP " + code + " (redirects disabled)");
            }
            return new JSONObject(readVkResponse(connection));
        } finally {
            connection.disconnect();
        }
    }

    private static String readVkResponse(HttpURLConnection connection) throws Exception {
        try (InputStream in = connection.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > 6 * 1024 * 1024) throw new IllegalStateException("Ответ VK слишком большой");
                out.write(buffer, 0, count);
            }
            return out.toString("UTF-8");
        }
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
