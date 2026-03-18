package com.defi.watchface;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

public class DefiNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "DefiNotifListener";

    @Override
    public void onListenerConnected() {
        Log.d(TAG, "Notification listener connected");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn.getPackageName().equals(getPackageName())) return;

        Notification notif = sbn.getNotification();
        Bundle extras = notif.extras;

        // App name
        String appName = "";
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(sbn.getPackageName(), 0);
            appName = pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            appName = sbn.getPackageName();
        }

        // Title & text
        CharSequence t = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence txt = extras.getCharSequence(Notification.EXTRA_TEXT);
        String title = t != null ? t.toString() : "";
        String text = txt != null ? txt.toString() : "";

        // Icon
        Bitmap iconBmp = null;
        Icon smallIcon = notif.getSmallIcon();
        if (smallIcon != null) {
            try {
                Drawable d = smallIcon.loadDrawable(this);
                if (d != null) {
                    iconBmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(iconBmp);
                    d.setBounds(0, 0, 48, 48);
                    d.draw(c);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to load notification icon", e);
            }
        }

        // Content intent (for tap-to-open)
        PendingIntent contentIntent = notif.contentIntent;

        Log.d(TAG, "Notification: " + appName + " / " + title + " / " + text);
        NotificationHolder.set(appName, title, text, iconBmp, contentIntent);
    }
}
