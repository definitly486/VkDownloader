package com.example.vkdl;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

/**
 * Opens VK channel pages inside the app using Mozilla GeckoView.
 * This deliberately does not use android.webkit.WebView or an external browser intent.
 */
public final class ChannelBrowserActivity extends Activity {
    private static final String EXTRA_URL = "channel_url";
    private static final String EXTRA_TITLE = "channel_title";
    private static GeckoRuntime runtime;

    private GeckoSession session;
    private GeckoView geckoView;

    public static void start(Context context, String url, String title) {
        Intent intent = new Intent(context, ChannelBrowserActivity.class);
        intent.putExtra(EXTRA_URL, url);
        intent.putExtra(EXTRA_TITLE, title);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (url == null || !(url.startsWith("https://vkvideo.ru/")
                || url.startsWith("https://vk.com/")
                || url.startsWith("https://m.vk.com/"))) {
            finish();
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        int padding = (int) (8 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(padding, padding, padding, padding);

        Button back = new Button(this);
        back.setText("‹");
        back.setMinWidth((int) (44 * density));
        back.setOnClickListener(v -> {
            if (session != null) session.goBack();
        });

        TextView pageTitle = new TextView(this);
        pageTitle.setText(title == null ? "VK Video" : title);
        pageTitle.setTextColor(Color.BLACK);
        pageTitle.setTextSize(16);
        pageTitle.setSingleLine(true);
        pageTitle.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(back);

        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMargins(padding, 0, padding, 0);
        toolbar.addView(pageTitle, titleParams);

        Button reload = new Button(this);
        reload.setText("↻");
        reload.setMinWidth((int) (44 * density));
        reload.setOnClickListener(v -> {
            if (session != null) session.reload();
        });
        toolbar.addView(reload);

        ProgressBar progress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.VISIBLE);

        geckoView = new GeckoView(this);
        root.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (2 * density)));
        root.addView(geckoView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        if (runtime == null) runtime = GeckoRuntime.create(this);
        session = new GeckoSession();
        session.setContentDelegate(new GeckoSession.ContentDelegate() {});
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession current, String pageUrl) {
                runOnUiThread(() -> progress.setVisibility(View.VISIBLE));
            }

            @Override
            public void onPageStop(GeckoSession current, boolean success) {
                runOnUiThread(() -> progress.setVisibility(View.GONE));
            }
        });
        session.open(runtime);
        geckoView.setSession(session);
        session.loadUri(url);
    }

    @Override
    public void onBackPressed() {
        if (session != null) {
            session.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (geckoView != null) geckoView.releaseSession();
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }
}
