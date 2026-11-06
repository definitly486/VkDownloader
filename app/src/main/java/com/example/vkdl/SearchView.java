package com.example.vkdl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Поиск публичных видео VK Video (анонимная сессия, как в 0W-Tube / LiveChannelsView).
 * Превью + кнопка «Ещё» для следующих страниц.
 */
public final class SearchView extends LinearLayout {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final String VK_CLIENT_ID = "52461373";
    private static final String VK_API_VERSION = "5.282";
    private static final int PAGE_SIZE = 30;
    private static final int TIMEOUT_MS = 12_000;
    private static final int THUMB_W = 160;
    private static final int THUMB_H = 90;

    private static String anonymousToken;
    private static long tokenExpiresAt;

    /** Memory-кэш обложек (URL → Bitmap). */
    private static final Map<String, Bitmap> thumbCache = new ConcurrentHashMap<>();

    public interface OnVideoClickListener {
        void onVideoClick(String url, String title);
    }

    private final OnVideoClickListener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final EditText queryInput;
    private final Button searchBtn;
    private final ProgressBar progress;
    private final TextView status;
    private final LinearLayout results;
    private final Button moreBtn;

    private volatile int generation = 0;

    // Состояние текущей сессии поиска (для «Ещё»)
    private String currentQuery = "";
    private String sectionId = null;
    private String nextFrom = null;
    private boolean hasMore = false;
    private final Set<String> seenIds = new LinkedHashSet<>();
    private int totalShown = 0;

    public SearchView(Context context, OnVideoClickListener listener) {
        super(context);
        this.listener = listener;
        setOrientation(VERTICAL);
        setPadding(dp(8), dp(8), dp(8), dp(8));

        TextView heading = new TextView(context);
        heading.setText("Поиск VK Video");
        heading.setTextSize(20);
        heading.setTextColor(Color.BLACK);
        heading.setPadding(dp(4), dp(4), dp(4), dp(8));
        addView(heading);

        queryInput = new EditText(context);
        queryInput.setHint("Запрос (например: хоккей nhl)");
        queryInput.setSingleLine(true);
        queryInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        queryInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                    && event.getAction() == android.view.KeyEvent.ACTION_DOWN)) {
                doSearch(false);
                return true;
            }
            return false;
        });
        addView(queryInput, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        searchBtn = new Button(context);
        searchBtn.setText("ИСКАТЬ");
        searchBtn.setOnClickListener(v -> doSearch(false));
        addView(searchBtn, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(GONE);
        addView(progress);

        status = new TextView(context);
        status.setText("Введите запрос и нажмите «ИСКАТЬ»");
        status.setTextColor(Color.DKGRAY);
        status.setPadding(0, dp(4), 0, dp(8));
        addView(status);

        ScrollView scroll = new ScrollView(context);
        LinearLayout scrollContent = new LinearLayout(context);
        scrollContent.setOrientation(VERTICAL);
        results = new LinearLayout(context);
        results.setOrientation(VERTICAL);
        scrollContent.addView(results);

        moreBtn = new Button(context);
        moreBtn.setText("Ещё");
        moreBtn.setVisibility(GONE);
        moreBtn.setOnClickListener(v -> doSearch(true));
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        moreLp.setMargins(0, dp(8), 0, dp(16));
        scrollContent.addView(moreBtn, moreLp);

        scroll.addView(scrollContent);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
    }

    /**
     * @param loadMore true — догрузить следующую страницу, false — новый поиск
     */
    private void doSearch(boolean loadMore) {
        final String q = loadMore ? currentQuery : queryInput.getText().toString().trim();
        if (q.isEmpty()) {
            status.setText("Введите поисковый запрос");
            return;
        }
        if (loadMore && !hasMore) {
            status.setText("Больше результатов нет");
            return;
        }

        final int gen = ++generation;
        progress.setVisibility(VISIBLE);
        searchBtn.setEnabled(false);
        moreBtn.setEnabled(false);
        if (!loadMore) {
            status.setText("Поиск…");
            results.removeAllViews();
            seenIds.clear();
            totalShown = 0;
            sectionId = null;
            nextFrom = null;
            hasMore = false;
            currentQuery = q;
        } else {
            status.setText("Загрузка ещё…");
        }

        final String sid = sectionId;
        final String nf = nextFrom;
        final boolean isMore = loadMore;

        io.execute(() -> {
            try {
                PageResult page = fetchPage(q, isMore ? sid : null, isMore ? nf : null);
                ui.post(() -> {
                    if (gen != generation) return;
                    progress.setVisibility(GONE);
                    searchBtn.setEnabled(true);
                    moreBtn.setEnabled(true);

                    if (page.items.isEmpty() && !isMore) {
                        status.setText("Ничего не найдено по запросу «" + q + "»");
                        moreBtn.setVisibility(GONE);
                        return;
                    }

                    for (SearchItem item : page.items) {
                        results.addView(makeResultRow(item, gen));
                        totalShown++;
                    }
                    sectionId = page.sectionId;
                    nextFrom = page.nextFrom;
                    hasMore = page.sectionId != null && page.nextFrom != null
                            && !page.nextFrom.isEmpty() && !page.items.isEmpty();
                    moreBtn.setVisibility(hasMore ? VISIBLE : GONE);

                    if (totalShown == 0) {
                        status.setText("Ничего не найдено");
                    } else {
                        status.setText("Показано: " + totalShown
                                + (hasMore ? "  ·  нажмите «Ещё» для следующих" : "  ·  конец списка"));
                    }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (gen != generation) return;
                    progress.setVisibility(GONE);
                    searchBtn.setEnabled(true);
                    moreBtn.setEnabled(true);
                    status.setText("Ошибка: " + e.getMessage());
                    if (!isMore) moreBtn.setVisibility(GONE);
                });
            }
        });
    }

    private View makeResultRow(SearchItem item, int gen) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setPadding(dp(6), dp(8), dp(6), dp(8));
        row.setBackgroundColor(Color.parseColor("#F5F5F5"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(6));
        row.setLayoutParams(lp);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // Превью
        ImageView thumb = new ImageView(getContext());
        thumb.setBackgroundColor(Color.rgb(220, 225, 235));
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(THUMB_W), dp(THUMB_H));
        thumbLp.setMargins(0, 0, dp(10), 0);
        row.addView(thumb, thumbLp);

        if (item.imageUrl != null && !item.imageUrl.isEmpty()) {
            Bitmap cached = thumbCache.get(item.imageUrl);
            if (cached != null && !cached.isRecycled()) {
                thumb.setImageBitmap(cached);
            } else {
                imageExecutor.execute(() -> {
                    if (gen != generation) return;
                    Bitmap bmp = fetchBitmap(item.imageUrl);
                    if (bmp == null || gen != generation) return;
                    thumbCache.put(item.imageUrl, bmp);
                    ui.post(() -> {
                        if (gen == generation) thumb.setImageBitmap(bmp);
                    });
                });
            }
        }

        // Текст
        LinearLayout textCol = new LinearLayout(getContext());
        textCol.setOrientation(VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(getContext());
        title.setText(item.title);
        title.setTextSize(14);
        title.setTextColor(Color.BLACK);
        title.setTypeface(null, Typeface.BOLD);
        title.setMaxLines(3);
        textCol.addView(title);

        TextView meta = new TextView(getContext());
        String dur = item.durationSec > 0 ? formatDuration(item.durationSec) : "";
        meta.setText(dur.isEmpty() ? item.url : dur);
        meta.setTextSize(12);
        meta.setTextColor(Color.GRAY);
        meta.setPadding(0, dp(3), 0, 0);
        textCol.addView(meta);

        row.addView(textCol);

        row.setOnClickListener(v -> {
            if (listener != null) listener.onVideoClick(item.url, item.title);
        });
        row.setClickable(true);
        row.setFocusable(true);
        return row;
    }

    private static String formatDuration(long sec) {
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ——— Модель ———

    private static final class SearchItem {
        final String title;
        final String url;
        final String imageUrl;
        final long durationSec;

        SearchItem(String title, String url, String imageUrl, long durationSec) {
            this.title = title;
            this.url = url;
            this.imageUrl = imageUrl;
            this.durationSec = durationSec;
        }
    }

    private static final class PageResult {
        final List<SearchItem> items;
        final String sectionId;
        final String nextFrom;

        PageResult(List<SearchItem> items, String sectionId, String nextFrom) {
            this.items = items;
            this.sectionId = sectionId;
            this.nextFrom = nextFrom;
        }
    }

    // ——— VK search ———

    private PageResult fetchPage(String query, String sectionId, String nextFrom) throws Exception {
        JSONObject response;
        if (nextFrom == null || nextFrom.isEmpty()) {
            String base = "https://api.vkvideo.ru/method/catalog.getVideoSearchWeb2"
                    + "?v=" + VK_API_VERSION
                    + "&client_id=" + VK_CLIENT_ID
                    + "&count=" + PAGE_SIZE
                    + "&q=" + Uri.encode(query)
                    + "&content_type=video";
            response = authorizedGet(base);
        } else {
            String address = "https://api.vkvideo.ru/method/catalog.getSection"
                    + "?v=" + VK_API_VERSION
                    + "&client_id=" + VK_CLIENT_ID;
            String bodyBase = "section_id=" + Uri.encode(sectionId)
                    + "&start_from=" + Uri.encode(nextFrom);
            response = authorizedPost(address, bodyBase);
        }

        List<SearchItem> items = parsePage(response, seenIds);

        String newSectionId = null;
        String newNextFrom = null;
        JSONObject section = response.optJSONObject("section");
        if (section == null) {
            JSONObject catalog = response.optJSONObject("catalog");
            JSONArray sections = catalog == null ? null : catalog.optJSONArray("sections");
            section = sections == null ? null : sections.optJSONObject(0);
        }
        if (section != null) {
            newSectionId = section.optString("id", "");
            newNextFrom = section.optString("next_from", "");
            if (newSectionId.isEmpty()) newSectionId = null;
            if (newNextFrom == null || newNextFrom.isEmpty()) newNextFrom = null;
        }
        return new PageResult(items, newSectionId, newNextFrom);
    }

    private static List<SearchItem> parsePage(JSONObject response, Set<String> seen) {
        LinkedHashMap<String, JSONObject> videos = new LinkedHashMap<>();
        JSONArray catalogVideos = response.optJSONArray("catalog_videos");
        if (catalogVideos != null) {
            for (int i = 0; i < catalogVideos.length(); i++) {
                JSONObject wrapper = catalogVideos.optJSONObject(i);
                JSONObject video = wrapper == null ? null : wrapper.optJSONObject("video");
                if (video != null) {
                    String key = video.optLong("owner_id") + "_" + video.optLong("id");
                    videos.put(key, video);
                }
            }
        }

        Set<String> orderedIds = new LinkedHashSet<>();
        JSONObject catalog = response.optJSONObject("catalog");
        collectOrderedIds(catalog == null ? null : catalog.optJSONArray("sections"), orderedIds);
        collectOrderedIds(response.optJSONObject("section"), orderedIds);
        orderedIds.addAll(videos.keySet());

        List<SearchItem> result = new ArrayList<>();
        for (String id : orderedIds) {
            JSONObject video = videos.get(id);
            if (video == null || !seen.add(id)) continue;
            long ownerId = video.optLong("owner_id");
            long vid = video.optLong("id");
            String pageUrl = "https://vkvideo.ru/video" + ownerId + "_" + vid;
            String title = video.optString("title", "Видео VK");
            long duration = video.optLong("duration", 0);
            String image = videoImage(video);
            result.add(new SearchItem(title, pageUrl, image, duration));
            if (result.size() >= PAGE_SIZE) break;
        }
        return result;
    }

    private static String videoImage(JSONObject video) {
        JSONArray images = video.optJSONArray("image");
        String best = "";
        int bestWidth = -1;
        if (images != null) {
            for (int i = 0; i < images.length(); i++) {
                JSONObject image = images.optJSONObject(i);
                if (image == null) continue;
                String url = image.optString("url", "");
                int width = image.optInt("width");
                if (!url.isEmpty() && width > bestWidth && width <= 640) {
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

    private static void collectOrderedIds(JSONArray sections, Set<String> result) {
        if (sections == null) return;
        for (int i = 0; i < sections.length(); i++) {
            collectOrderedIds(sections.optJSONObject(i), result);
        }
    }

    private static void collectOrderedIds(JSONObject section, Set<String> result) {
        if (section == null) return;
        JSONArray blocks = section.optJSONArray("blocks");
        if (blocks == null) return;
        for (int j = 0; j < blocks.length(); j++) {
            JSONObject block = blocks.optJSONObject(j);
            JSONArray ids = block == null ? null : block.optJSONArray("videos_ids");
            if (ids == null) continue;
            for (int k = 0; k < ids.length(); k++) {
                result.add(ids.optString(k));
            }
        }
    }

    private static JSONObject authorizedGet(String baseAddress) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String address = baseAddress + "&access_token=" + Uri.encode(getAnonymousToken());
                JSONObject root = getJson(address);
                if (isAuthError(root)) {
                    clearAnonymousToken();
                    last = new IllegalStateException("authorization failed");
                    continue;
                }
                return unwrap(root);
            } catch (Exception e) {
                last = e;
                clearAnonymousToken();
            }
        }
        throw last != null ? last : new IllegalStateException("VK authorization failed");
    }

    private static JSONObject authorizedPost(String address, String bodyBase) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String body = bodyBase + "&access_token=" + Uri.encode(getAnonymousToken());
                JSONObject root = postJson(address, body);
                if (isAuthError(root)) {
                    clearAnonymousToken();
                    last = new IllegalStateException("authorization failed");
                    continue;
                }
                return unwrap(root);
            } catch (Exception e) {
                last = e;
                clearAnonymousToken();
            }
        }
        throw last != null ? last : new IllegalStateException("VK authorization failed");
    }

    private static boolean isAuthError(JSONObject root) {
        JSONObject error = root == null ? null : root.optJSONObject("error");
        if (error == null) return false;
        if (error.optInt("error_code") == 5) return true;
        return error.optString("error_msg", "").toLowerCase(Locale.ROOT)
                .contains("authorization failed");
    }

    private static JSONObject unwrap(JSONObject root) throws Exception {
        JSONObject error = root.optJSONObject("error");
        if (error != null) {
            throw new IllegalStateException("VK: " + error.optString("error_msg", "ошибка"));
        }
        JSONObject response = root.optJSONObject("response");
        if (response == null) throw new IllegalStateException("VK не вернул response");
        return response;
    }

    private static synchronized String getAnonymousToken() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        if (anonymousToken != null && now + 60 < tokenExpiresAt) return anonymousToken;

        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://login.vk.com/?act=get_anonym_token").openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("Origin", "https://vkvideo.ru");
            c.setRequestProperty("Referer", "https://vkvideo.ru/");
            byte[] body = ("client_id=" + VK_CLIENT_ID).getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("VK login HTTP " + code);
            }
            JSONObject root = new JSONObject(readStream(c.getInputStream()));
            if (!"okay".equals(root.optString("type"))) {
                throw new IllegalStateException("VK не выдал анонимную сессию");
            }
            JSONObject data = root.optJSONObject("data");
            anonymousToken = data == null ? null : data.optString("access_token", null);
            tokenExpiresAt = data == null ? 0 : data.optLong("expired_at");
            if (anonymousToken == null || anonymousToken.isEmpty()) {
                throw new IllegalStateException("VK не вернул токен");
            }
            return anonymousToken;
        } finally {
            c.disconnect();
        }
    }

    private static synchronized void clearAnonymousToken() {
        anonymousToken = null;
        tokenExpiresAt = 0;
    }

    private static JSONObject getJson(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            configure(c);
            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String body = in != null ? readStream(in) : "";
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + ": "
                        + body.substring(0, Math.min(120, body.length())));
            }
            return new JSONObject(body);
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject postJson(String address, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            configure(c);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String resp = in != null ? readStream(in) : "";
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + ": "
                        + resp.substring(0, Math.min(120, resp.length())));
            }
            return new JSONObject(resp);
        } finally {
            c.disconnect();
        }
    }

    private static void configure(HttpURLConnection c) {
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Origin", "https://vkvideo.ru");
        c.setRequestProperty("Referer", "https://vkvideo.ru/");
        c.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01");
        c.setRequestProperty("Accept-Language", "ru,en;q=0.8");
    }

    private static String readStream(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) >= 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private static Bitmap fetchBitmap(String address) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
            try {
                c.setConnectTimeout(10_000);
                c.setReadTimeout(15_000);
                c.setRequestProperty("User-Agent", UA);
                try (InputStream in = c.getInputStream()) {
                    return BitmapFactory.decodeStream(in);
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception ignored) {
            return null;
        }
    }
}
