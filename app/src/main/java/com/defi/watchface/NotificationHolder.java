package com.defi.watchface;

import android.app.PendingIntent;
import android.graphics.Bitmap;

public final class NotificationHolder {
    /** Durée d'affichage de la notification (ms) */
    public static final long DISPLAY_DURATION_MS = 60_000;

    private static String appName = "";
    private static String title = "";
    private static String text = "";
    private static Bitmap icon = null;
    private static PendingIntent contentIntent = null;
    private static long timestamp = 0;

    public static synchronized void set(String app, String t, String txt, Bitmap icn, PendingIntent intent) {
        appName = app;
        title = t;
        text = txt;
        icon = icn;
        contentIntent = intent;
        timestamp = System.currentTimeMillis();
    }

    public static synchronized boolean isActive() {
        return timestamp > 0 && (System.currentTimeMillis() - timestamp) < DISPLAY_DURATION_MS;
    }

    public static synchronized String getAppName() {
        return appName;
    }

    public static synchronized String getTitle() {
        return title;
    }

    public static synchronized String getText() {
        return text;
    }

    public static synchronized Bitmap getIcon() {
        return icon;
    }

    public static synchronized PendingIntent getContentIntent() {
        return contentIntent;
    }

    public static synchronized long getTimestamp() {
        return timestamp;
    }
}
