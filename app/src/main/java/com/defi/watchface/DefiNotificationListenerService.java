package com.defi.watchface;

import android.app.Notification;
import android.app.Person;
import android.app.PendingIntent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcelable;
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
        if (sbn.getPackageName().equals(getPackageName()))
            return;

        Notification notif = sbn.getNotification();
        // Notifications de service (« Batterie / Santé en cours d'exécution », séance,
        // média) : pas de bulle. Les services OnePlus ne posent que FOREGROUND_SERVICE
        // et NO_CLEAR, sans ONGOING_EVENT. Résumés de groupe : doublons.
        if ((notif.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE
                | Notification.FLAG_NO_CLEAR | Notification.FLAG_GROUP_SUMMARY)) != 0)
            return;
        Bundle extras = notif.extras;
        // Mise à jour après sa propre réponse : pas de nouvelle notification
        if (lastMessageFromSelf(extras))
            return;

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

        // Picture (photo attachée : WhatsApp, MMS, Instagram, etc.)
        Bitmap picture = null;
        if (extras.containsKey(Notification.EXTRA_PICTURE)) {
            Object picObj = extras.get(Notification.EXTRA_PICTURE);
            if (picObj instanceof Bitmap) {
                picture = (Bitmap) picObj;
            }
        }

        // Skip notifications without body text AND without picture
        if (text.isEmpty() && picture == null)
            return;

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
        NotificationHolder.set("local:" + sbn.getKey(), appName, title, text, iconBmp, picture, contentIntent, null);
        ScreenWaker.wake(this);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Effacée sur la montre (fiche système) : plus de pastille
        NotificationHolder.clearPending("local:" + sbn.getKey());
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

    /**
     * Conversation dont le dernier message est le sien : la notification est
     * mise à jour après une réponse (depuis la bulle ou ailleurs). Rien de
     * nouveau à signaler. Un message de soi n'a ni sender ni sender_person.
     */
    static boolean lastMessageFromSelf(Bundle extras) {
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages == null || messages.length == 0
                || !(messages[messages.length - 1] instanceof Bundle))
            return false;
        Bundle last = (Bundle) messages[messages.length - 1];
        return last.getCharSequence("sender") == null && last.getParcelable("sender_person") == null;
    }
}
