package com.defi.watchface;

import android.app.PendingIntent;
import android.graphics.Bitmap;

public final class NotificationHolder {
    private static String appName = "";
    private static String title = "";
    private static String text = "";
    private static Bitmap icon = null;
    private static Bitmap picture = null;
    private static PendingIntent contentIntent = null;
    /** Bulle affichée jusqu'à fermeture par l'utilisateur (l'écran ne s'allume pas tout seul) */
    private static boolean active = false;
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
            active = true;
            listener = onChange;
        }
        if (listener != null)
            listener.run();
    }

    /** Ferme la bulle (appui sur le cadran) */
    public static synchronized void dismiss() {
        active = false;
        picture = null;
        contentIntent = null;
    }

    public static synchronized boolean isActive() {
        return active;
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
