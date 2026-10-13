package com.defi.watchface;

import android.app.PendingIntent;
import android.graphics.Bitmap;

public final class NotificationHolder {
    /** Durée d'affichage de la bulle (ms), sauf fermeture plus tôt par ✕ */
    public static final long DISPLAY_DURATION_MS = 60_000;

    private static String appName = "";
    private static String title = "";
    private static String text = "";
    private static Bitmap icon = null;
    private static Bitmap picture = null;
    private static PendingIntent contentIntent = null;
    private static long timestamp = 0;
    /** Prévenu à chaque nouvelle notification (le cadran ne se redessine qu'une fois par minute) */
    private static Runnable onChange = null;

    public static synchronized void setOnChangeListener(Runnable listener) {
        onChange = listener;
    }

    public static void set(String app, String t, String txt, Bitmap icn, Bitmap pic, PendingIntent intent) {
        Runnable listener;
        synchronized (NotificationHolder.class) {
            appName = app;
            title = t;
            text = txt;
            icon = icn;
            picture = pic;
            contentIntent = intent;
            timestamp = System.currentTimeMillis();
            listener = onChange;
        }
        if (listener != null)
            listener.run();
    }

    /** Ferme la bulle (appui sur le cadran) */
    public static synchronized void dismiss() {
        timestamp = 0;
        picture = null;
        contentIntent = null;
    }

    public static synchronized boolean isActive() {
        return timestamp > 0 && (System.currentTimeMillis() - timestamp) < DISPLAY_DURATION_MS;
    }

    /** Part du temps d'affichage restante, de 1 (arrivée) à 0 (expiration) */
    public static synchronized float getRemaining() {
        if (timestamp == 0)
            return 0f;
        long elapsed = System.currentTimeMillis() - timestamp;
        return Math.max(0f, 1f - elapsed / (float) DISPLAY_DURATION_MS);
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

    public static synchronized Bitmap getPicture() {
        return picture;
    }

    public static synchronized PendingIntent getContentIntent() {
        return contentIntent;
    }
}
