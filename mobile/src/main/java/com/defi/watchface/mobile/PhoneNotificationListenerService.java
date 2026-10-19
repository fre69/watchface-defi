package com.defi.watchface.mobile;

import android.app.Notification;
import android.app.Person;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PhoneNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "DefiPhoneNotif";
    /**
     * Un élément DataLayer par notification (/notification/<clé>), supprimé quand
     * elle disparaît du téléphone : la montre reçoit chaque arrivée et chaque
     * effacement, même groupés (« Tout effacer »), sans qu'ils s'écrasent
     */
    private static final String NOTIF_PREFIX = "/notification/";

    /** Écoute branchée par le système : condition du battement de cœur vers la montre */
    private static volatile boolean connected = false;

    static boolean isConnected() {
        return connected;
    }

    @Override
    public void onListenerConnected() {
        Log.d(TAG, "Phone notification listener connected");
        connected = true;
        // Battement aussitôt (la montre retire le témoin de lien coupé), puis toutes les 15 min
        HeartbeatReceiver.send(this);
        HeartbeatReceiver.schedule(this);
        // Ce service, que le système garde en vie, porte aussi le lien Home Assistant
        HaLink.get(this).start();
        purgeStaleItems();
    }

    @Override
    public void onListenerDisconnected() {
        Log.w(TAG, "Phone notification listener disconnected");
        connected = false;
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
        String message = lastMessage(extras);
        if (message != null)
            text = message;

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

        sendToWatch(sbn.getKey(), appName, title, text, iconBmp, picture);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Lue ou effacée sur le téléphone : la montre la retire de sa file (sans
        // élément envoyé pour cette clé, la suppression ne fait rien)
        if (sbn.getPackageName().equals(getPackageName())) return;
        deleteFromWatch(itemUri(sbn.getKey()));
    }

    /** Chemin DataLayer d'une notification : clé encodée, sans caractère réservé */
    static String itemPath(String key) {
        return NOTIF_PREFIX + Base64.encodeToString(key.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static Uri itemUri(String key) {
        return new Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(itemPath(key)).build();
    }

    private void sendToWatch(String key, String appName, String title, String text,
                             Bitmap icon, Bitmap picture) {
        new Thread(() -> {
            try {
                PutDataMapRequest mapRequest = PutDataMapRequest.create(itemPath(key));
                mapRequest.getDataMap().putString("appName", appName);
                mapRequest.getDataMap().putString("title", title);
                mapRequest.getDataMap().putString("text", text);
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

    /** Montre hors de portée : elle reçoit la suppression à la reconnexion */
    private void deleteFromWatch(Uri uri) {
        Wearable.getDataClient(this).deleteDataItems(uri).addOnSuccessListener(n -> {
            if (n > 0)
                Log.d(TAG, "Notification removed, deleted for watch: " + uri.getPath());
        });
    }

    /**
     * Supprime les éléments des notifications disparues pendant que l'écoute
     * était débranchée (processus tué), et l'ancien élément unique /notification
     */
    private void purgeStaleItems() {
        new Thread(() -> {
            try {
                Set<String> active = new HashSet<>();
                StatusBarNotification[] sbns = getActiveNotifications();
                if (sbns != null)
                    for (StatusBarNotification sbn : sbns)
                        active.add(itemPath(sbn.getKey()));
                DataClient dc = Wearable.getDataClient(this);
                Uri prefix = new Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME)
                        .path("/notification").build();
                List<Uri> stale = new ArrayList<>();
                DataItemBuffer items = Tasks.await(dc.getDataItems(prefix, DataClient.FILTER_PREFIX));
                try {
                    for (DataItem item : items)
                        if (!active.contains(item.getUri().getPath()))
                            stale.add(item.getUri());
                } finally {
                    items.release();
                }
                for (Uri uri : stale)
                    Tasks.await(dc.deleteDataItems(uri));
                Log.d(TAG, "Stale watch items deleted: " + stale.size());
            } catch (Exception e) {
                Log.w(TAG, "Failed to purge watch items", e);
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

    /**
     * Conversation (MessagingStyle) : le dernier message, préfixé de son auteur
     * dans un groupe. EXTRA_TEXT n'est souvent qu'un résumé (« 3 messages »)
     * ou le premier message. null : pas une conversation.
     */
    static String lastMessage(Bundle extras) {
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages == null || messages.length == 0
                || !(messages[messages.length - 1] instanceof Bundle))
            return null;
        Bundle last = (Bundle) messages[messages.length - 1];
        CharSequence text = last.getCharSequence("text");
        if (text == null)
            return null;
        CharSequence sender = last.getCharSequence("sender");
        Person person = last.getParcelable("sender_person");
        if (person != null && person.getName() != null)
            sender = person.getName();
        if (sender != null && extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION))
            return sender + " : " + text.toString().trim();
        return text.toString().trim();
    }
}
