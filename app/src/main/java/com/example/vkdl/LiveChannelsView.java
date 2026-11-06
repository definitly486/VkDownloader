package com.example.vkdl;

import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Список каналов и плитки опубликованных в них видео.
 * Использует анонимную VK API-сессию; браузерный движок и HTML-сканирование не используются.
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
            new Channel("@36hockey", "https://vkvideo.ru/@36hockey"),
            new Channel("@tennisatpwta", "https://vkvideo.ru/@tennisatpwta")
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
    // Сначала рисуем строки с placeholder, потом подгружаем фото пачкой
    final ImageView[] avatars = new ImageView[CHANNELS.length];
    final TextView[] titles = new TextView[CHANNELS.length];
    for (int i = 0; i < CHANNELS.length; i++) {
        Channel channel = CHANNELS[i];
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
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // круглый клип
        avatar.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        avatar.setClipToOutline(true);
        row.addView(avatar, new LayoutParams(dp(54), dp(54)));
        avatars[i] = avatar;

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
        titles[i] = title;
        row.addView(labels, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> loadChannel(channel));
        content.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }
    loadChannelAvatars(avatars, titles);
}

  
private void loadChannelAvatars(final ImageView[] avatars, final TextView[] titles) {
    executor.execute(() -> {
        try {
            StringBuilder ids = new StringBuilder();
            for (Channel ch : CHANNELS) {
                String name = extractScreenName(ch.url);
                if (ids.length() > 0) ids.append(',');
                ids.append(name);
            }
            String address = "https://api.vkvideo.ru/method/groups.getById?v="
                    + VK_API_VERSION + "&client_id=" + VK_CLIENT_ID
                    + "&group_ids=" + android.net.Uri.encode(ids.toString())
                    + "&fields=photo_200,photo_100";
            JSONObject response = vkAuthorizedGet(address);
            org.json.JSONArray groups = response.optJSONArray("groups");
            if (groups == null) {
                // старый формат ответа — массив в response
                // vkAuthorizedGet уже вернул response-объект
                return;
            }

            // screen_name → photo + name
            java.util.Map<String, String> photos = new java.util.HashMap<>();
            java.util.Map<String, String> names = new java.util.HashMap<>();
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g == null) continue;
                String screen = g.optString("screen_name", "").toLowerCase(java.util.Locale.ROOT);
                String photo = g.optString("photo_200", "");
                if (photo.isEmpty()) photo = g.optString("photo_100", "");
                if (!screen.isEmpty() && !photo.isEmpty()) photos.put(screen, photo);
                String name = g.optString("name", "");
                if (!screen.isEmpty() && !name.isEmpty()) names.put(screen, name);
            }

            for (int i = 0; i < CHANNELS.length; i++) {
                final int index = i;
                String screen = extractScreenName(CHANNELS[i].url)
                        .toLowerCase(java.util.Locale.ROOT);
                final String photoUrl = photos.get(screen);
                final String realName = names.get(screen);
                if (realName != null && !realName.isEmpty()) {
                    postToUi(() -> titles[index].setText(realName));
                }
                if (photoUrl == null || photoUrl.isEmpty()) continue;
                final Bitmap bitmap = fetchBitmap(photoUrl);
                if (bitmap == null) continue;
                postToUi(() -> {
                    avatars[index].setImageBitmap(bitmap);
                    avatars[index].setBackground(null); // убрать серый круг
                });
            }
        } catch (Exception ignored) {
            // оставляем серые placeholder
        }
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
                        empty.setText("VK Video не вернул публичные видео для этого канала. "
                                + "Возможно, канал скрыт, требует авторизацию или поиск VK не нашёл его публикации.");
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

        FrameLayout preview = new FrameLayout(getContext());
        ImageView cover = new ImageView(getContext());
        cover.setBackgroundColor(Color.rgb(225, 230, 238));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.addView(cover, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(112)));

        if (video.live) {
            TextView liveBadge = new TextView(getContext());
            liveBadge.setText(" LIVE ");
            liveBadge.setTextSize(11);
            liveBadge.setTextColor(Color.WHITE);
            liveBadge.setTypeface(null, android.graphics.Typeface.BOLD);
            liveBadge.setGravity(Gravity.CENTER);
            GradientDrawable badgeBackground = new GradientDrawable();
            badgeBackground.setColor(Color.rgb(220, 35, 45));
            badgeBackground.setCornerRadius(dp(4));
            liveBadge.setBackground(badgeBackground);
            liveBadge.setPadding(dp(5), dp(3), dp(5), dp(3));
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START);
            badgeParams.setMargins(dp(6), dp(6), 0, 0);
            preview.addView(liveBadge, badgeParams);
        }
        tile.addView(preview, new LayoutParams(LayoutParams.MATCH_PARENT, dp(112)));

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
    private static final int VK_PAGE_SIZE = 30;
    private static final int VK_MAX_PAGES = 4;
    private static final int VK_TIMEOUT_MS = 10000;
    private static String anonymousToken;
    private static long tokenExpiresAt;

   private List<Video> scanVideos(String channelUrl) throws Exception {
    String screenName = extractScreenName(channelUrl);
    List<Video> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();

    // 1) Классический Live API (live.vkvideo.ru/@name) — молча, если пусто
    try {
        addCurrentLiveStream(result, seen, channelUrl);
    } catch (Exception ignored) {
        // не live-блог — нормально для групп
    }

    // 2) Основной путь для групп/каналов VK Video: owner_id → video.get
    Long ownerId = resolveOwnerId(screenName);
    if (ownerId != null) {
        addOwnerVideos(result, seen, ownerId);
    }

    // 3) Каталог-поиск — доп. ролики (если video.get пуст или мало)
    try {
        JSONObject first = vkSearchPage(screenName);
        List<JSONObject> firstItems = pageVideos(first);
        long targetOwner = ownerId != null ? ownerId : 0L;
        if (targetOwner == 0 && !firstItems.isEmpty()) {
            java.util.Map<Long, Integer> ownerCounts = new java.util.LinkedHashMap<>();
            for (JSONObject item : firstItems) {
                long oid = item.optLong("owner_id");
                if (oid != 0) {
                    ownerCounts.put(oid, ownerCounts.containsKey(oid)
                            ? ownerCounts.get(oid) + 1 : 1);
                }
            }
            int best = 0;
            for (java.util.Map.Entry<Long, Integer> e : ownerCounts.entrySet()) {
                if (e.getValue() > best) {
                    targetOwner = e.getKey();
                    best = e.getValue();
                }
            }
        }
        if (targetOwner != 0) {
            JSONObject page = first;
            for (int pageNumber = 0; pageNumber < VK_MAX_PAGES; pageNumber++) {
                for (JSONObject item : pageVideos(page)) {
                    if (item.optLong("owner_id") != targetOwner) continue;
                    addApiVideo(result, seen, item);
                }
                if (pageNumber + 1 >= VK_MAX_PAGES) break;
                String[] cursor = pageCursor(page);
                if (cursor == null) break;
                page = vkSectionPage(cursor[0], cursor[1]);
            }
        }
    } catch (Exception ignored) {
        // каталог опционален, если video.get уже дал результат
    }

    if (result.isEmpty()) {
        throw new IllegalStateException(
                "VK Video не нашёл публичные ролики по имени канала @" + screenName);
    }

    result = markLiveFromApi(result);
    return prioritizeLive(result);
}

/** screen_name → owner_id (отрицательный для групп) */
private static Long resolveOwnerId(String screenName) {
    try {
        String address = "https://api.vkvideo.ru/method/utils.resolveScreenName?v="
                + VK_API_VERSION + "&client_id=" + VK_CLIENT_ID
                + "&screen_name=" + android.net.Uri.encode(screenName);
        JSONObject response = vkAuthorizedGet(address);
        String type = response.optString("type", "");
        long objectId = response.optLong("object_id");
        if (objectId == 0) return null;
        if ("group".equals(type) || "page".equals(type) || "event".equals(type)) {
            return -objectId;
        }
        if ("user".equals(type)) {
            return objectId;
        }
        return null;
    } catch (Exception e) {
        return null;
    }
}

/** Свежие видео владельца — основной источник live для спортивных групп */
private void addOwnerVideos(List<Video> result, Set<String> seen, long ownerId)
        throws Exception {
    String address = "https://api.vkvideo.ru/method/video.get?v="
            + VK_API_VERSION + "&client_id=" + VK_CLIENT_ID
            + "&owner_id=" + ownerId
            + "&count=" + VK_PAGE_SIZE
            + "&extended=0";
    JSONObject response = vkAuthorizedGet(address);
    org.json.JSONArray items = response.optJSONArray("items");
    if (items == null) return;
    for (int i = 0; i < items.length(); i++) {
        JSONObject item = items.optJSONObject(i);
        if (item != null) addApiVideo(result, seen, item);
    }
}


/** Запрос video.getLiveStatus — тот же метод, что в HAR с vksport.vkvideo.ru */
private List<Video> markLiveFromApi(List<Video> videos) {
    if (videos.isEmpty()) return videos;
    try {
        StringBuilder ids = new StringBuilder();
        List<String> keys = new ArrayList<>();
        for (Video v : videos) {
            String key = v.id;
            // пропускаем только записи Live API без owner_id_video_id
            if (key.startsWith("live:")) continue;
            int first = key.indexOf('_');
            if (first <= 0) continue;
            int second = key.indexOf('_', first + 1);
            String shortId = second > 0 ? key.substring(0, second) : key;
            if (ids.length() > 0) ids.append(',');
            ids.append(shortId);
            keys.add(shortId);
            if (keys.size() >= 50) break; // можно больше, API принимает пачку
        }
        if (keys.isEmpty()) return videos;

        String address = "https://api.vkvideo.ru/method/video.getLiveStatus?v="
                + VK_API_VERSION + "&client_id=" + VK_CLIENT_ID;
        String body = "video_ids=" + android.net.Uri.encode(ids.toString());
        JSONObject root = vkAuthorizedPost(address, body);
        org.json.JSONArray arr = root.optJSONArray("response");
        if (arr == null) arr = root.optJSONArray("items");
        if (arr == null) {
            // нет ответа — снимаем все live, кроме live.vkvideo.ru
            return demoteAllExceptLiveApi(videos);
        }

        Set<String> liveKeys = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            String status = item.optString("live_status", "");
            if ("started".equalsIgnoreCase(status) || "waiting".equalsIgnoreCase(status)) {
                long oid = item.optLong("owner_id");
                long vid = item.optLong("id");
                if (oid != 0 && vid != 0) liveKeys.add(oid + "_" + vid);
            }
        }

        List<Video> updated = new ArrayList<>(videos.size());
        for (Video v : videos) {
            if (v.id.startsWith("live:")) {
                updated.add(v); // из Live API — оставляем
                continue;
            }
            int first = v.id.indexOf('_');
            int second = first > 0 ? v.id.indexOf('_', first + 1) : -1;
            String shortId = second > 0 ? v.id.substring(0, second) : v.id;
            boolean isLive = liveKeys.contains(shortId);
            if (isLive == v.live) {
                updated.add(v);
            } else {
                updated.add(new Video(v.id, v.title, v.image, isLive, v.url));
            }
        }
        return updated;
    } catch (Exception e) {
        // при ошибке API лучше снять сомнительные live, чем показывать ложные
        return demoteAllExceptLiveApi(videos);
    }
}

/** Снять LIVE у всех, кроме записей из api.live.vkvideo.ru */
private static List<Video> demoteAllExceptLiveApi(List<Video> videos) {
    List<Video> out = new ArrayList<>(videos.size());
    for (Video v : videos) {
        if (v.live && !v.id.startsWith("live:")) {
            out.add(new Video(v.id, v.title, v.image, false, v.url));
        } else {
            out.add(v);
        }
    }
    return out;
}

private static List<Video> prioritizeLive(List<Video> videos) {
    Collections.sort(videos, new Comparator<Video>() {
        @Override public int compare(Video left, Video right) {
            if (left.live == right.live) return 0;
            return left.live ? -1 : 1;
        }
    });
    return videos;
}




    private void addCurrentLiveStream(List<Video> result, Set<String> seen,
                                      String channelUrl) throws Exception {
        String channelName = extractScreenName(channelUrl);
        // Correct VK Video Live endpoint (same endpoint used by Streamlink):
        // /v1/blog/{channel_name}/public_video_stream
        String address = "https://api.live.vkvideo.ru/v1/blog/"
                + android.net.Uri.encode(channelName) + "/public_video_stream";
        JSONObject root = liveApiGet(address);
        org.json.JSONArray streams = root.optJSONArray("data");
        if (streams == null || streams.length() == 0) return;

        String channelTitle = clean(root.optString("title", ""), channelName);
        for (int i = 0; i < streams.length(); i++) {
            JSONObject stream = streams.optJSONObject(i);
            if (stream == null) continue;
            String liveId = stream.optString("vid", "");
            if (liveId.isEmpty()) continue;

            org.json.JSONArray urls = stream.optJSONArray("playerUrls");
            boolean hasLiveHls = false;
            if (urls != null) {
                for (int j = 0; j < urls.length(); j++) {
                    JSONObject player = urls.optJSONObject(j);
                    if (player != null
                            && "live_hls".equalsIgnoreCase(player.optString("type", ""))
                            && !player.optString("url", "").isEmpty()) {
                        hasLiveHls = true;
                        break;
                    }
                }
            }
            if (!hasLiveHls) continue;

            String key = "live:" + channelName.toLowerCase(java.util.Locale.ROOT);
            if (!seen.add(key)) continue;
            String title = clean(channelTitle, "Прямой эфир " + channelName);
            // The Live API returns a stream id, not a VK Video owner_id_video_id.
            // Keep the channel URL as the click target; do not fabricate a VK video id.
            result.add(new Video(liveId, title, "", true,
                    "https://live.vkvideo.ru/" + channelName));
            break;
        }
    }

    private static JSONObject liveApiGet(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            // Streamlink's HTTP client follows redirects for this endpoint.
            // VK Live may redirect API requests to its canonical host.
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(VK_TIMEOUT_MS);
            connection.setReadTimeout(VK_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", UA);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Referer", "https://live.vkvideo.ru/");
            int code = connection.getResponseCode();
            if (code == 404) return new JSONObject();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("VK Live API HTTP " + code
                        + " for " + connection.getURL().getHost());
            }
            JSONObject response = new JSONObject(readVkResponse(connection));
            if (response.has("error")) {
                throw new IllegalStateException("VK Live API: "
                        + response.optString("error_description",
                        response.optString("error", "unknown error")));
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private void addApiVideo(List<Video> result, Set<String> seen, JSONObject item) {
        long ownerId = item.optLong("owner_id");
        long videoId = item.optLong("id");
        if (ownerId == 0 || videoId == 0) return;
        String key = ownerId + "_" + videoId;
        if (!seen.add(key)) return;
        String accessKey = item.optString("access_key", "");
        String fullId = key + (accessKey.isEmpty() ? "" : "_" + accessKey);
        String title = clean(item.optString("title", ""), "Видео " + key);
        result.add(new Video(fullId, title, videoImage(item), isLiveVideo(item),
                "https://vkvideo.ru/video" + fullId));
    }

    /**
     * VK API describes live broadcasts by the optional "live" property
     * (BasePropertyExists). It may be represented as an object or a scalar
     * depending on the API response version.
     */
private static boolean isLiveVideo(JSONObject video) {
    String status = video.optString("live_status", "");
    // started = в эфире, waiting = скоро / ожидание
    if ("started".equalsIgnoreCase(status) || "waiting".equalsIgnoreCase(status)) {
        return true;
    }
    // postlive / finished / failed / пусто — НЕ live
    // Поле live:1 у VK остаётся и после окончания трансляции — игнорируем его.
    return false;
}

    private static List<JSONObject> pageVideos(JSONObject response) {
        List<JSONObject> result = new ArrayList<>();
        org.json.JSONArray wrappers = response.optJSONArray("catalog_videos");
        if (wrappers == null) return result;
        for (int i = 0; i < wrappers.length(); i++) {
            JSONObject wrapper = wrappers.optJSONObject(i);
            JSONObject video = wrapper == null ? null : wrapper.optJSONObject("video");
            if (video != null) result.add(video);
        }
        return result;
    }

    private static String[] pageCursor(JSONObject response) {
        JSONObject section = response.optJSONObject("section");
        if (section == null) {
            JSONObject catalog = response.optJSONObject("catalog");
            org.json.JSONArray sections = catalog == null ? null : catalog.optJSONArray("sections");
            section = sections == null ? null : sections.optJSONObject(0);
        }
        if (section == null) return null;
        String id = section.optString("id", "");
        String next = section.optString("next_from", "");
        return id.isEmpty() || next.isEmpty() ? null : new String[]{id, next};
    }

    private static JSONObject vkSearchPage(String query) throws Exception {
        String address = "https://api.vkvideo.ru/method/catalog.getVideoSearchWeb2?v="
                + VK_API_VERSION + "&client_id=" + VK_CLIENT_ID
                + "&count=" + VK_PAGE_SIZE + "&q=" + android.net.Uri.encode(query)
                + "&content_type=video";
        return vkAuthorizedGet(address);
    }

    private static JSONObject vkSectionPage(String sectionId, String nextFrom) throws Exception {
        String address = "https://api.vkvideo.ru/method/catalog.getSection?v="
                + VK_API_VERSION + "&client_id=" + VK_CLIENT_ID;
        String body = "section_id=" + android.net.Uri.encode(sectionId)
                + "&start_from=" + android.net.Uri.encode(nextFrom);
        return vkAuthorizedPost(address, body);
    }

    private static JSONObject vkAuthorizedGet(String address) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = getAnonymousToken();
            JSONObject root = vkGetJson(address + "&access_token=" + android.net.Uri.encode(token));
            JSONObject error = root.optJSONObject("error");
            if (error == null) {
                JSONObject response = root.optJSONObject("response");
                if (response == null) throw new IllegalStateException("VK Video не вернул результаты");
                return response;
            }
            if (isVkAuthorizationError(error) && attempt == 0) {
                clearAnonymousToken();
                continue;
            }
            throw new IllegalStateException("VK Video: "
                    + error.optString("error_msg", "ошибка поиска"));
        }
        throw new IllegalStateException("Не удалось обновить анонимную сессию VK");
    }

    private static JSONObject vkAuthorizedPost(String address, String body) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = getAnonymousToken();
            JSONObject root = vkPostJson(address,
                    body + "&access_token=" + android.net.Uri.encode(token));
            JSONObject error = root.optJSONObject("error");
            if (error == null) {
                JSONObject response = root.optJSONObject("response");
                if (response == null) throw new IllegalStateException("VK Video не вернул страницу");
                return response;
            }
            if (isVkAuthorizationError(error) && attempt == 0) {
                clearAnonymousToken();
                continue;
            }
            throw new IllegalStateException("VK Video: "
                    + error.optString("error_msg", "ошибка загрузки страницы"));
        }
        throw new IllegalStateException("Не удалось обновить анонимную сессию VK");
    }

    private static boolean isVkAuthorizationError(JSONObject error) {
        return error.optInt("error_code") == 5
                || error.optString("error_msg").toLowerCase(java.util.Locale.ROOT)
                .contains("authorization failed");
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
            configureVkConnection(connection);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("VK Video HTTP " + code + " (redirects disabled)");
            }
            return new JSONObject(readVkResponse(connection));
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject vkPostJson(String address, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            configureVkConnection(connection);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setRequestProperty("Origin", "https://vkvideo.ru");
            connection.setRequestProperty("Referer", "https://vkvideo.ru/");
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("VK Video HTTP " + code + " (redirects disabled)");
            }
            return new JSONObject(readVkResponse(connection));
        } finally {
            connection.disconnect();
        }
    }

    private static void configureVkConnection(HttpURLConnection connection) {
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(VK_TIMEOUT_MS);
        connection.setReadTimeout(VK_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", UA);
        connection.setRequestProperty("Accept", "application/json,*/*");
        connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9");
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
        final boolean live;
        Video(String id, String title, String image, boolean live, String url) {
            this.id = id;
            this.title = title;
            this.image = image;
            this.live = live;
            this.url = url;
        }
    }
}
