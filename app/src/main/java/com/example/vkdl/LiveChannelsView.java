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
import java.io.File;
import java.io.FileOutputStream;
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

    // Отдельные пулы: сканирование каналов не должно ждать загрузки обложек предыдущего канала.
    private final ExecutorService scanExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(4);
    private final LinearLayout content;
    private final OnVideoClickListener listener;
    private final List<Channel> channels = new ArrayList<>();

    /** Инкрементируется при смене экрана — устаревшие задачи игнорируют результат. */
    private volatile int loadGeneration = 0;

    private static final long AVATAR_CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000; // 7 дней
    private static final java.util.Map<String, Bitmap> avatarMemCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Memory-кэш обложек видео (URL → Bitmap). */
    private static final java.util.Map<String, Bitmap> thumbnailMemCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public LiveChannelsView(Context context, OnVideoClickListener listener) {
        super(context);
        this.listener = listener;
        channels.addAll(java.util.Arrays.asList(CHANNELS));
        loadSavedChannels();
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
    loadGeneration++; // отменяем фоновые загрузки обложек предыдущего канала
    content.removeAllViews();
    TextView add = new TextView(getContext());
    add.setText("＋  Добавить канал");
    add.setTextSize(16);
    add.setTextColor(Color.rgb(25, 95, 170));
    add.setGravity(Gravity.CENTER_VERTICAL);
    add.setPadding(dp(12), dp(14), dp(12), dp(14));
    add.setBackground(selectableBackground());
    add.setOnClickListener(v -> showAddChannelDialog());
    content.addView(add, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

    // Сначала рисуем строки с placeholder, потом подгружаем фото пачкой
    final List<Channel> channelSnapshot = new ArrayList<>(channels);
    final ImageView[] avatars = new ImageView[channelSnapshot.size()];
    final TextView[] titles = new TextView[channelSnapshot.size()];
    for (int i = 0; i < channelSnapshot.size(); i++) {
        Channel channel = channelSnapshot.get(i);
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

        // Показываем уже загруженный аватар прямо при построении списка.
        // Не ставим чтение памяти в общий executor: он может быть занят
        // долгим сканированием предыдущего канала.
        String avatarKey = extractScreenName(channel.url)
                .toLowerCase(java.util.Locale.ROOT);
        Bitmap memoryAvatar = avatarMemCache.get(avatarKey);
        if (memoryAvatar != null && !memoryAvatar.isRecycled()) {
            avatar.setImageBitmap(memoryAvatar);
            avatar.setBackground(null);
        }

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
        if (channel.removable) row.setOnLongClickListener(v -> {
            new android.app.AlertDialog.Builder(getContext())
                    .setMessage("Удалить канал " + channel.title + "?")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Удалить", (dialog, which) -> {
                        channels.remove(channel);
                        saveChannels();
                        showChannels();
                    }).show();
            return true;
        });
        content.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }
    loadChannelAvatars(avatars, titles, channelSnapshot);
}

  
private void loadChannelAvatars(final ImageView[] avatars, final TextView[] titles,
                                final List<Channel> channelSnapshot) {
    imageExecutor.execute(() -> {
        // 1) Сразу из кэша (память / диск) — без сети
        for (int i = 0; i < channelSnapshot.size(); i++) {
            final int index = i;
            String screen = extractScreenName(channelSnapshot.get(i).url)
                    .toLowerCase(java.util.Locale.ROOT);
            final Bitmap cached = loadAvatarCached(screen, null); // null = только кэш
            if (cached != null) {
                postToUi(() -> {
                    avatars[index].setImageBitmap(cached);
                    avatars[index].setBackground(null);
                });
            }
        }

        // 2) Потом сеть: имена + обновление фото при необходимости
        try {
            StringBuilder ids = new StringBuilder();
            for (Channel ch : channelSnapshot) {
                if (ids.length() > 0) ids.append(',');
                ids.append(extractScreenName(ch.url));
            }
            String address = "https://api.vkvideo.ru/method/groups.getById?v="
                    + VK_API_VERSION + "&client_id=" + VK_CLIENT_ID
                    + "&group_ids=" + android.net.Uri.encode(ids.toString())
                    + "&fields=photo_200,photo_100";
            JSONObject response = vkAuthorizedGet(address);
            org.json.JSONArray groups = response.optJSONArray("groups");
            if (groups == null) return;

            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g == null) continue;
                final String screen = g.optString("screen_name", "")
                        .toLowerCase(java.util.Locale.ROOT);
                if (screen.isEmpty()) continue;

                final String realName = g.optString("name", "");
                String photo = g.optString("photo_200", "");
                if (photo.isEmpty()) photo = g.optString("photo_100", "");
                final String photoUrl = photo;

                int found = -1;
                for (int c = 0; c < channelSnapshot.size(); c++) {
                    if (extractScreenName(channelSnapshot.get(c).url)
                            .toLowerCase(java.util.Locale.ROOT).equals(screen)) {
                        found = c;
                        break;
                    }
                }
                if (found < 0) continue;
                final int index = found;

                if (!realName.isEmpty()) {
                    postToUi(() -> titles[index].setText(realName));
                }

                // сеть только если в кэше ещё нет
                final Bitmap bitmap = loadAvatarCached(screen, photoUrl);
                if (bitmap != null) {
                    postToUi(() -> {
                        avatars[index].setImageBitmap(bitmap);
                        avatars[index].setBackground(null);
                    });
                }
            }
        } catch (Exception ignored) {
            // кэш уже показали выше
        }
    });
}


    private void showAddChannelDialog() {
        final android.widget.EditText input = new android.widget.EditText(getContext());
        input.setSingleLine(true);
        input.setHint("https://vkvideo.ru/@channel");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        int pad = dp(20);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(getContext());
        wrap.setPadding(pad, dp(8), pad, 0);
        wrap.addView(input);
        new android.app.AlertDialog.Builder(getContext())
                .setTitle("Добавить канал VK Video")
                .setMessage("Вставьте ссылку на канал или имя @канала")
                .setView(wrap)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Добавить", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    try {
                        String screen = normalizeChannelName(value);
                        String key = screen.toLowerCase(java.util.Locale.ROOT);
                        for (Channel existing : channels) {
                            if (extractScreenName(existing.url).equalsIgnoreCase(key)) {
                                android.widget.Toast.makeText(getContext(),
                                        "Этот канал уже добавлен", android.widget.Toast.LENGTH_SHORT).show();
                                return;
                            }
                        }
                        Channel channel = new Channel("@" + screen,
                                "https://vkvideo.ru/@" + screen, true);
                        channels.add(channel);
                        saveChannels();
                        showChannels();
                        loadChannel(channel);
                    } catch (Exception e) {
                        android.widget.Toast.makeText(getContext(),
                                "Не удалось распознать канал. Укажите ссылку вида vkvideo.ru/@имя",
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private String normalizeChannelName(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("empty");
        String name = value.trim();
        if (name.startsWith("@")) name = name.substring(1);
        else if (name.matches("(?i)^https?://.*")) {
            java.util.regex.Matcher matcher = Pattern.compile(
                    "(?i)(?:vkvideo\\.ru|live\\.vkvideo\\.ru)/@([^/?#]+)")
                    .matcher(name);
            if (!matcher.find()) throw new IllegalArgumentException("unsupported channel URL");
            name = matcher.group(1);
        } else if (name.contains("/") || name.contains(" ")) {
            throw new IllegalArgumentException("unsupported channel");
        }
        if (!name.matches("[A-Za-z0-9_.-]{2,64}")) throw new IllegalArgumentException("invalid name");
        return name;
    }

    private void loadSavedChannels() {
        try {
            String raw = getContext().getSharedPreferences("vk_live_channels",
                    Context.MODE_PRIVATE).getString("custom", "[]");
            org.json.JSONArray array = new org.json.JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String name = item.optString("name", "");
                String url = item.optString("url", "");
                if (!name.isEmpty() && !url.isEmpty()) channels.add(new Channel(name, url, true));
            }
        } catch (Exception ignored) { }
    }

    private void saveChannels() {
        try {
            org.json.JSONArray array = new org.json.JSONArray();
            for (Channel channel : channels) {
                if (!channel.removable) continue;
                org.json.JSONObject item = new org.json.JSONObject();
                item.put("name", channel.title);
                item.put("url", channel.url);
                array.put(item);
            }
            getContext().getSharedPreferences("vk_live_channels", Context.MODE_PRIVATE)
                    .edit().putString("custom", array.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void loadChannel(Channel channel) {
        final int generation = ++loadGeneration;
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

        scanExecutor.execute(() -> {
            try {
                List<Video> videos = scanVideos(channel.url);
                postToUi(() -> {
                    if (generation != loadGeneration) return;
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
                        addVideoGrid(videos, generation);
                    }
                });
            } catch (Exception e) {
                postToUi(() -> {
                    if (generation != loadGeneration) return;
                    status.setText("Ошибка загрузки канала: " + e.getMessage());
                });
            }
        });
    }

    private void addVideoGrid(List<Video> videos, int generation) {
        LinearLayout grid = new LinearLayout(getContext());
        grid.setOrientation(VERTICAL);
        content.addView(grid, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < videos.size(); i += 2) {
            LinearLayout line = new LinearLayout(getContext());
            line.setOrientation(HORIZONTAL);
            Video left = videos.get(i);
            line.addView(createTile(left, generation), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            if (i + 1 < videos.size()) {
                View gap = new View(getContext());
                line.addView(gap, new LayoutParams(dp(8), 1));
                line.addView(createTile(videos.get(i + 1), generation),
                        new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            } else {
                line.addView(new View(getContext()), new LayoutParams(0, 1, 1f));
            }
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            grid.addView(line, lp);
        }
    }

    private View createTile(Video video, int generation) {
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

        if (video.image != null) {
            // Сначала memory-кэш — без сети и без очереди
            Bitmap cached = thumbnailMemCache.get(video.image);
            if (cached != null && !cached.isRecycled()) {
                cover.setImageBitmap(cached);
            } else {
                imageExecutor.execute(() -> {
                    if (generation != loadGeneration) return;
                    Bitmap bitmap = fetchBitmap(video.image);
                    if (bitmap == null || generation != loadGeneration) return;
                    thumbnailMemCache.put(video.image, bitmap);
                    cover.post(() -> {
                        if (generation == loadGeneration) {
                            cover.setImageBitmap(bitmap);
                        }
                    });
                });
            }
        }
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
        for (Video v : videos) {
            if (isLiveApiEntry(v)) continue;
            String key = v.id;
            int first = key.indexOf('_');
            if (first <= 0) continue;
            int second = key.indexOf('_', first + 1);
            String shortId = second > 0 ? key.substring(0, second) : key;
            if (ids.length() > 0) ids.append(',');
            ids.append(shortId);
            if (ids.toString().split(",").length >= 50) break;
        }
        if (ids.length() == 0) return videos;

        // ВАЖНО: response — JSONArray, vkAuthorizedPost его ломает
        String token = getAnonymousToken();
        String address = "https://api.vkvideo.ru/method/video.getLiveStatus?v="
                + VK_API_VERSION + "&client_id=" + VK_CLIENT_ID;
        String body = "video_ids=" + android.net.Uri.encode(ids.toString())
                + "&access_token=" + android.net.Uri.encode(token);
        JSONObject root = vkPostJson(address, body);

        JSONObject error = root.optJSONObject("error");
        if (error != null) {
            if (isVkAuthorizationError(error)) {
                clearAnonymousToken();
                token = getAnonymousToken();
                body = "video_ids=" + android.net.Uri.encode(ids.toString())
                        + "&access_token=" + android.net.Uri.encode(token);
                root = vkPostJson(address, body);
                error = root.optJSONObject("error");
            }
            if (error != null) return demoteAllExceptLiveApi(videos);
        }

        org.json.JSONArray arr = root.optJSONArray("response");
        if (arr == null) return demoteAllExceptLiveApi(videos);

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
            if (isLiveApiEntry(v)) {
                updated.add(v.live ? v : new Video(v.id, v.title, v.image, true, v.url));
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
        return demoteAllExceptLiveApi(videos);
    }
}

/** Снять LIVE у всех, кроме записей из api.live.vkvideo.ru */
private static List<Video> demoteAllExceptLiveApi(List<Video> videos) {
    List<Video> out = new ArrayList<>(videos.size());
    for (Video v : videos) {
        if (v.live && !isLiveApiEntry(v)) {
            out.add(new Video(v.id, v.title, v.image, false, v.url));
        } else if (isLiveApiEntry(v) && !v.live) {
            out.add(new Video(v.id, v.title, v.image, true, v.url));
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
           String videoId = "live:" + channelName.toLowerCase(java.util.Locale.ROOT);
result.add(new Video(videoId, title, "", true,
        "https://live.vkvideo.ru/" + channelName));
            break;
        }
    }

private static boolean isLiveApiEntry(Video v) {
    return v.id != null && v.id.startsWith("live:")
            || (v.url != null && v.url.contains("live.vkvideo.ru/"));
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


/** Ключ: screen_name канала, например "36nfl" */
private File avatarCacheFile(String screenName) {
    File dir = new File(getContext().getCacheDir(), "channel_avatars");
    if (!dir.exists()) dir.mkdirs();
    return new File(dir, screenName.toLowerCase(java.util.Locale.ROOT) + ".jpg");
}

private Bitmap loadAvatarCached(String screenName, String photoUrl) {
    if (screenName == null || screenName.isEmpty()) return null;
    String key = screenName.toLowerCase(java.util.Locale.ROOT);

    // 1) память
    Bitmap mem = avatarMemCache.get(key);
    if (mem != null && !mem.isRecycled()) return mem;

    // 2) диск
    File file = avatarCacheFile(key);
    if (file.exists()
            && System.currentTimeMillis() - file.lastModified() < AVATAR_CACHE_TTL_MS) {
        try {
            Bitmap disk = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (disk != null) {
                avatarMemCache.put(key, disk);
                return disk;
            }
        } catch (Exception ignored) { }
    }

    // 3) сеть → сохранить
    if (photoUrl == null || photoUrl.isEmpty()) return null;
    Bitmap net = fetchBitmap(photoUrl);
    if (net == null) return null;
    avatarMemCache.put(key, net);
    try {
        java.io.FileOutputStream out = new java.io.FileOutputStream(file);
        try {
            net.compress(Bitmap.CompressFormat.JPEG, 90, out);
        } finally {
            out.close();
        }
    } catch (Exception ignored) { }
    return net;
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
        loadGeneration++;
        scanExecutor.shutdownNow();
        imageExecutor.shutdownNow();
        super.onDetachedFromWindow();
    }

    private static final class Channel {
        final String title, url;
        final boolean removable;
        Channel(String title, String url) { this(title, url, false); }
        Channel(String title, String url, boolean removable) {
            this.title = title;
            this.url = url;
            this.removable = removable;
        }
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
