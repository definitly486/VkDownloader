package com.example.vkdl;

import android.content.Context;
import android.content.Intent;

/** Передача этапов фоновой загрузки на экран приложения. */
final class DownloadProgress {
    static final String ACTION = "com.example.vkdl.DOWNLOAD_PROGRESS";
    static final String EXTRA_DETAILS = "details";
    static final String EXTRA_PERCENT = "percent";
    static final String EXTRA_FINISHED = "finished";
    static final String EXTRA_FAILED = "failed";
    private DownloadProgress() {}
    static void send(Context context, String details, int percent, boolean finished, boolean failed) {
        Intent event = new Intent(ACTION).setPackage(context.getPackageName());
        event.putExtra(EXTRA_DETAILS, details);
        event.putExtra(EXTRA_PERCENT, percent);
        event.putExtra(EXTRA_FINISHED, finished);
        event.putExtra(EXTRA_FAILED, failed);
        context.sendBroadcast(event);
    }
}
