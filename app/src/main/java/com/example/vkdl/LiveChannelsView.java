package com.example.vkdl;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Список избранных live-каналов VK Video. */
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

    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    public LiveChannelsView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(8), dp(8), dp(8), dp(8));

        TextView heading = new TextView(context);
        heading.setText("LIVE — каналы VK Video");
        heading.setTextSize(20);
        heading.setTextColor(Color.BLACK);
        heading.setPadding(dp(8), dp(8), dp(8), dp(12));
        addView(heading, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(VERTICAL);
        scroll.addView(list);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        for (Channel channel : CHANNELS) addChannelRow(list, channel);
    }

    private void addChannelRow(LinearLayout list, Channel channel) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setMinimumHeight(dp(76));
        row.setBackground(selectableBackground());
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(channel.url));
                getContext().startActivity(intent);
            } catch (Exception ignored) {}
        });

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
        title.setText(channel.fallbackTitle);
        title.setTextSize(16);
        title.setTextColor(Color.BLACK);
        title.setMaxLines(1);

        TextView subtitle = new TextView(getContext());
        subtitle.setText("Канал VK Video");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.DKGRAY);

        labels.addView(title);
        labels.addView(subtitle);
        row.addView(labels, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        list.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        executor.execute(() -> loadChannel(channel, title, avatar));
    }

    private void loadChannel(Channel channel, TextView title, ImageView avatar) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(channel.url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(18000);
            connection.setRequestProperty("User-Agent", UA);
            connection.setRequestProperty("Accept-Language", "ru,en;q=0.8");
            String html;
            try (InputStream in = connection.getInputStream()) {
                html = readText(in);
            }
            String pageTitle = metaContent(html, "og:title");
            if (pageTitle == null) pageTitle = titleTag(html);
            String imageUrl = metaContent(html, "og:image");
            if (imageUrl == null) imageUrl = metaContent(html, "twitter:image");

            final String displayTitle = cleanTitle(pageTitle, channel.fallbackTitle);
            title.post(() -> title.setText(displayTitle));

            if (imageUrl != null && (imageUrl.startsWith("https://") || imageUrl.startsWith("http://"))) {
                Bitmap bitmap = loadBitmap(imageUrl);
                if (bitmap != null) avatar.post(() -> avatar.setImageBitmap(bitmap));
            }
        } catch (Exception ignored) {
            // Если VK не отдаёт публичные метаданные, остаётся название канала из списка.
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private Bitmap loadBitmap(String imageUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(imageUrl).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", UA);
            try (InputStream in = connection.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readText(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toString("UTF-8");
    }

    private static String metaContent(String html, String property) {
        String escaped = Pattern.quote(property);
        Pattern first = Pattern.compile(
                "<meta[^>]+(?:property|name)\\s*=\\s*[\"']" + escaped
                        + "[\"'][^>]+content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = first.matcher(html);
        if (matcher.find()) return decodeHtml(matcher.group(1));

        Pattern second = Pattern.compile(
                "<meta[^>]+content\\s*=\\s*[\"']([^\"']+)[\"'][^>]+(?:property|name)\\s*=\\s*[\"']"
                        + escaped + "[\"'][^>]*>",
                Pattern.CASE_INSENSITIVE);
        matcher = second.matcher(html);
        return matcher.find() ? decodeHtml(matcher.group(1)) : null;
    }

    private static String titleTag(String html) {
        Matcher matcher = Pattern.compile("<title[^>]*>(.*?)</title>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        return matcher.find() ? decodeHtml(matcher.group(1).trim()) : null;
    }

    private static String cleanTitle(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        String result = value.replaceAll("\\s*[|—-]\\s*VK Видео.*$", "").trim();
        return result.isEmpty() ? fallback : result;
    }

    private static String decodeHtml(String value) {
        return value.replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
    }

    private android.graphics.drawable.Drawable selectableBackground() {
        android.util.TypedValue value = new android.util.TypedValue();
        if (getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
            return getContext().getDrawable(value.resourceId);
        }
        return null;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class Channel {
        final String fallbackTitle;
        final String url;

        Channel(String fallbackTitle, String url) {
            this.fallbackTitle = fallbackTitle;
            this.url = url;
        }
    }
}
