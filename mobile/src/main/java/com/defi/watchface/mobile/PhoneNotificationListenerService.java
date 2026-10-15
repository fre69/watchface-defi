package com.defi.watchface.mobile;

import android.app.Notification;
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

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;

public class PhoneNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "DefiPhoneNotif";
    private static final String NOTIF_PATH = "/notification";

    /** Clé de la dernière notification envoyée, celle que signale la pastille du cadran */
    private String lastSentKey = null;

    @Override
    public void onListenerConnected() {
        Log.d(TAG, "Phone notification listener connected");
        // Ce service, que le système garde en vie, porte aussi le lien Home Assistant
        HaLink.get(this).start();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn.getPackageName().equals(getPackageName())) return;

        Notification notif = sbn.getNotification();
        // Skip service notifications (music players, apps running in background...)
        // and group summaries (duplicates of the individual notifications)
        if ((notif.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE
                | Notification.FLAG_NO_CLEAR | Notification.FLAG_GROUP_SUMMARY)) != 0) return;

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
        String text = txt != null ? txt.toString().trim() : "";

        // Picture
        Bitmap picture = null;
        if (extras.containsKey(Notification.EXTRA_PICTURE)) {
            Object picObj = extras.get(Notification.EXTRA_PICTURE);
            if (picObj instanceof Bitmap) {
                picture = (Bitmap) picObj;
            }
        }

        // Skip empty notifications
        if (text.isEmpty() && picture == null) return;

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
                Log.w(TAG, "Failed to load icon", e);
            }
        }

        Log.d(TAG, "Sending to watch: " + appName + " / " + title + " / " + text
                + " picture=" + (picture != null));

        lastSentKey = sbn.getKey();
        sendToWatch(sbn.getKey(), appName, title, text, iconBmp, picture);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Lue ou effacée sur le téléphone : la pastille du cadran n'a plus lieu d'être
        if (lastSentKey == null || !lastSentKey.equals(sbn.getKey())) return;
        lastSentKey = null;
        Log.d(TAG, "Signaled notification removed, clearing watch: " + sbn.getKey());
        sendClearToWatch(sbn.getKey());
    }

    private void sendToWatch(String key, String appName, String title, String text,
                             Bitmap icon, Bitmap picture) {
        new Thread(() -> {
            try {
                PutDataMapRequest mapRequest = PutDataMapRequest.create(NOTIF_PATH);
                mapRequest.getDataMap().putString("key", key);
                mapRequest.getDataMap().putString("appName", appName);
                mapRequest.getDataMap().putString("title", title);
                mapRequest.getDataMap().putString("text", text);
                mapRequest.getDataMap().putBoolean("cleared", false);
                mapRequest.getDataMap().putLong("timestamp", System.currentTimeMillis());

                if (icon != null) {
                    mapRequest.getDataMap().putAsset("icon", bitmapToAsset(icon, true));
                }
                if (picture != null) {
                    Bitmap scaled = scaleBitmap(picture, 200);
                    mapRequest.getDataMap().putAsset("picture", bitmapToAsset(scaled, false));
                }

                // Urgent : la montre allume son écran à la réception (lever de
                // poignet coupé) ; sans urgence, Google tolère 30 min de retard
                PutDataRequest request = mapRequest.asPutDataRequest();
                request.setUrgent();

                DataClient dataClient = Wearable.getDataClient(this);
                Tasks.await(dataClient.putDataItem(request));
                Log.d(TAG, "DataItem sent to watch");
            } catch (Exception e) {
                Log.e(TAG, "Failed to send to watch", e);
            }
        }).start();
    }

    /**
     * Même chemin que la notification : l'élément DataLayer garde le dernier état
     * (montre hors de portée : elle ne reçoit que l'effacement, sans allumer l'écran)
     */
    private void sendClearToWatch(String key) {
        new Thread(() -> {
            try {
                PutDataMapRequest mapRequest = PutDataMapRequest.create(NOTIF_PATH);
                mapRequest.getDataMap().putString("key", key);
                mapRequest.getDataMap().putBoolean("cleared", true);
                mapRequest.getDataMap().putLong("timestamp", System.currentTimeMillis());
                PutDataRequest request = mapRequest.asPutDataRequest();
                request.setUrgent();
                Tasks.await(Wearable.getDataClient(this).putDataItem(request));
                Log.d(TAG, "Clear sent to watch");
            } catch (Exception e) {
                Log.e(TAG, "Failed to send clear to watch", e);
            }
        }).start();
    }

    private Asset bitmapToAsset(Bitmap bitmap, boolean png) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (png) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos);
        } else {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, bos);
        }
        return Asset.createFromBytes(bos.toByteArray());
    }

    private Bitmap scaleBitmap(Bitmap src, int maxSize) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= maxSize && h <= maxSize) return src;
        float scale = Math.min((float) maxSize / w, (float) maxSize / h);
        return Bitmap.createScaledBitmap(src, (int) (w * scale), (int) (h * scale), true);
    }
}
