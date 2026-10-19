package com.defi.watchface;

import android.app.PendingIntent;
import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.List;

/**
 * Notifications en attente, la plus récente en tête. Elles sont signalées par
 * une pastille sur le cadran ; un appui ouvre la plus récente dans la bulle
 * (60 s), dont le bouton passe à la suivante. Une notification lue ou effacée
 * sur le téléphone (ou la montre) quitte la file et ferme sa bulle.
 */
public final class NotificationHolder {
    /** Durée d'affichage de la bulle (ms), sauf fermeture plus tôt */
    public static final long DISPLAY_DURATION_MS = 60_000;
    /** Au-delà, les plus anciennes sont oubliées */
    private static final int MAX_PENDING = 20;

    private static final class Entry {
        /** Origine et clé de la notification (« phone:… », « local:… ») */
        final String key;
        final String appName;
        final String title;
        final String text;
        final Bitmap icon;
        final Bitmap picture;
        final PendingIntent contentIntent;

        Entry(String key, String appName, String title, String text, Bitmap icon,
                Bitmap picture, PendingIntent contentIntent) {
            this.key = key;
            this.appName = appName;
            this.title = title;
            this.text = text;
            this.icon = icon;
            this.picture = picture;
            this.contentIntent = contentIntent;
        }
    }

    private static final List<Entry> pending = new ArrayList<>();
    /** Notification affichée dans la bulle (null : fermée) */
    private static Entry shown = null;
    private static long openedAt = 0;
    /** Prévenu à chaque changement de la pastille (le cadran ne se redessine qu'une fois par minute) */
    private static Runnable onChange = null;

    public static synchronized void setOnChangeListener(Runnable listener) {
        onChange = listener;
    }

    /**
     * Nouvelle notification, en tête de file. Une notification mise à jour
     * (même clé : nouveau message d'une conversation) remplace l'ancienne.
     */
    public static void set(String k, String app, String t, String txt, Bitmap icn, Bitmap pic,
            PendingIntent intent) {
        synchronized (NotificationHolder.class) {
            remove(k);
            pending.add(0, new Entry(k, app, t, txt, icn, pic, intent));
            while (pending.size() > MAX_PENDING)
                pending.remove(pending.size() - 1);
        }
        notifyChange();
    }

    /** Ouvre la plus récente dans la bulle (appui sur la pastille ou le bouton suivante) */
    public static synchronized void open() {
        if (pending.isEmpty())
            return;
        shown = pending.remove(0);
        openedAt = System.currentTimeMillis();
    }

    /**
     * Retire de la file la notification lue ou effacée, et ferme la bulle si
     * elle l'affichait (« Tout effacer » sur la montre ou le téléphone)
     */
    public static void clearPending(String k) {
        boolean removed;
        synchronized (NotificationHolder.class) {
            removed = remove(k);
            if (shown != null && shown.key.equals(k)) {
                dismiss();
                removed = true;
            }
        }
        if (removed)
            notifyChange();
    }

    /** Ferme la bulle ; les notifications restantes gardent la pastille */
    public static synchronized void dismiss() {
        shown = null;
        openedAt = 0;
    }

    /** Bulle ouverte et pas encore expirée */
    public static synchronized boolean isActive() {
        return shown != null && (System.currentTimeMillis() - openedAt) < DISPLAY_DURATION_MS;
    }

    public static synchronized int getPendingCount() {
        return pending.size();
    }

    /** Icône de la plus récente en attente, pour la pastille */
    public static synchronized Bitmap getPendingIcon() {
        return pending.isEmpty() ? null : pending.get(0).icon;
    }

    /** Part du temps d'affichage restante, de 1 (ouverture) à 0 (expiration) */
    public static synchronized float getRemaining() {
        if (shown == null)
            return 0f;
        long elapsed = System.currentTimeMillis() - openedAt;
        return Math.max(0f, 1f - elapsed / (float) DISPLAY_DURATION_MS);
    }

    // --- Notification affichée dans la bulle ---

    public static synchronized String getAppName() {
        return shown != null ? shown.appName : "";
    }

    public static synchronized String getTitle() {
        return shown != null ? shown.title : "";
    }

    public static synchronized String getText() {
        return shown != null ? shown.text : "";
    }

    public static synchronized Bitmap getIcon() {
        return shown != null ? shown.icon : null;
    }

    public static synchronized Bitmap getPicture() {
        return shown != null ? shown.picture : null;
    }

    public static synchronized PendingIntent getContentIntent() {
        return shown != null ? shown.contentIntent : null;
    }

    private static boolean remove(String k) {
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i).key.equals(k)) {
                pending.remove(i);
                return true;
            }
        }
        return false;
    }

    private static void notifyChange() {
        Runnable listener;
        synchronized (NotificationHolder.class) {
            listener = onChange;
        }
        if (listener != null)
            listener.run();
    }
}
