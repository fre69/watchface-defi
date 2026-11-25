package com.defi.watchface;

import android.app.PendingIntent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Notifications en attente, la plus récente en tête. Elles sont signalées par
 * une pastille sur le cadran ; un appui ouvre la plus récente dans la bulle
 * (60 s), dont le bouton passe à la suivante. Une notification lue ou effacée
 * sur le téléphone (ou la montre) quitte la file et ferme sa bulle.
 *
 * Une appli installée des deux côtés (WhatsApp) envoie le même message deux
 * fois : par le téléphone et en notification locale. Les deux sont fusionnés
 * en une seule entrée, qui garde l'action de la locale (ouvrir l'appli de la
 * montre pour répondre).
 *
 * Une alarme qui sonne sur le téléphone s'ouvre d'elle-même, avec ses boutons
 * (« Répéter », « Arrêter »), et reste affichée jusqu'à son arrêt.
 */
public final class NotificationHolder {
    /** Durée d'affichage de la bulle (ms), sauf fermeture plus tôt */
    public static final long DISPLAY_DURATION_MS = 60_000;
    /**
     * Bulle ouverte d'elle-même à l'arrivée d'un message (les notifications OHealth,
     * qui faisaient office de popup, sont coupées) : refermée sans appui, le message
     * reste non lu, avec la pastille
     */
    public static final long PEEK_DURATION_MS = 15_000;
    /**
     * Alarme qui sonne sur le téléphone : la bulle reste ouverte jusqu'à son arrêt
     * (élément supprimé), au plus ce délai si la suppression n'arrive jamais
     * (Google Horloge coupe d'elle-même la sonnerie au bout de 10 min par défaut)
     */
    public static final long ALARM_DURATION_MS = 15 * 60_000L;
    /** Au-delà, les plus anciennes sont oubliées */
    private static final int MAX_PENDING = 20;
    /** Un doublon arrivé après la lecture de son jumeau est ignoré pendant ce délai */
    private static final long TWIN_DELAY_MS = 2 * 60_000L;

    // --- Réponse rapide depuis la bulle (envoyée par le téléphone) ---
    public static final int REPLY_NONE = 0;
    public static final int REPLY_SENDING = 1;
    public static final int REPLY_SENT = 2;
    public static final int REPLY_FAILED = 3;
    /** Sans accusé du téléphone dans ce délai : échec */
    private static final long REPLY_TIMEOUT_MS = 15_000L;
    /** Durée d'affichage de l'échec, avant le retour des boutons */
    private static final long REPLY_FAILED_SHOWN_MS = 3_000L;

    private static final class Entry {
        /** Origine et clé de chaque copie (« phone:… », « local:… ») */
        final List<String> keys = new ArrayList<>();
        final String appName;
        final String title;
        final String text;
        Bitmap icon;
        Bitmap picture;
        PendingIntent contentIntent;
        /** Chemin de la notification du téléphone quand elle permet de répondre, sinon null */
        String replyId;
        /** Lue (bulle ouverte) à cette date, 0 : pas encore */
        long readAt;
        /** Alarme qui sonne : libellés de ses boutons (« Répéter », « Arrêter »), sinon null */
        final String[] actions;
        /** Chemin de la notification du téléphone, pour en déclencher un bouton */
        final String actionId;

        Entry(String key, String appName, String title, String text, Bitmap icon,
                Bitmap picture, PendingIntent contentIntent, String replyId,
                String[] actions, String actionId) {
            keys.add(key);
            this.replyId = replyId;
            this.actions = actions;
            this.actionId = actionId;
            this.appName = appName;
            this.title = title;
            this.text = text;
            this.icon = icon;
            this.picture = picture;
            this.contentIntent = contentIntent;
        }

        /** Même message venu de l'autre côté (téléphone / montre) */
        boolean isTwin(String app, String t, String txt) {
            return actions == null && appName.equals(app) && title.equals(t) && text.equals(txt);
        }

        /** Complète l'entrée avec ce que la copie apporte en plus */
        void merge(String key, Bitmap icn, Bitmap pic, PendingIntent intent, String reply) {
            if (!keys.contains(key))
                keys.add(key);
            if (icon == null)
                icon = icn;
            if (picture == null)
                picture = pic;
            if (contentIntent == null)
                contentIntent = intent;
            if (replyId == null)
                replyId = reply;
        }
    }

    private static final List<Entry> pending = new ArrayList<>();
    /** Notification affichée dans la bulle (null : fermée) */
    private static Entry shown = null;
    private static long openedAt = 0;
    /** Bulle ouverte d'elle-même : le message reste dans la file tant qu'on n'y touche pas */
    private static boolean peek = false;
    private static final Handler main = new Handler(Looper.getMainLooper());
    /** Redessine le cadran à la fin de la bulle automatique, sans attendre la minute */
    private static final Runnable PEEK_END = NotificationHolder::notifyChange;
    /** Dernières entrées lues, pour reconnaître un doublon arrivé en retard */
    private static final List<Entry> recentlyRead = new ArrayList<>();
    private static int replyState = REPLY_NONE;
    private static String replyText = "";
    private static long replyAt = 0;
    /** Prévenu à chaque changement de la pastille (le cadran ne se redessine qu'une fois par minute) */
    private static Runnable onChange = null;

    public static synchronized void setOnChangeListener(Runnable listener) {
        onChange = listener;
    }

    /**
     * Nouvelle notification, en tête de file. Une notification mise à jour
     * (même clé : nouveau message d'une conversation) remplace l'ancienne ;
     * le même message venu de l'autre côté est fusionné. replyId : chemin de la
     * notification du téléphone si elle permet de répondre, sinon null.
     */
    /**
     * @return true pour un message nouveau (à signaler par une vibration) ; false
     *         pour un doublon absorbé ou une notification republiée à l'identique
     */
    public static boolean set(String k, String app, String t, String txt, Bitmap icn, Bitmap pic,
            PendingIntent intent, String replyId) {
        boolean isNew;
        synchronized (NotificationHolder.class) {
            boolean unchanged = false;
            for (Entry e : pending) {
                if (e.keys.contains(k)) {
                    unchanged = e.isTwin(app, t, txt);
                    break;
                }
            }
            removeKey(k);
            Entry twin = findTwin(app, t, txt);
            if (twin != null) {
                twin.merge(k, icn, pic, intent, replyId);
            } else {
                add(new Entry(k, app, t, txt, icn, pic, intent, replyId, null, null));
            }
            isNew = twin == null && !unchanged;
        }
        notifyChange();
        return isNew;
    }

    /**
     * Alarme qui sonne sur le téléphone : ouverte aussitôt dans la bulle, à la
     * place de celle affichée, avec ses boutons. actionId : chemin de la
     * notification du téléphone, renvoyé avec le bouton choisi
     */
    public static void setAlarm(String k, String app, String t, String txt, Bitmap icn,
            String[] actions, String actionId) {
        synchronized (NotificationHolder.class) {
            removeKey(k);
            add(new Entry(k, app, t, txt, icn, null, null, null, actions, actionId));
            open();
        }
        notifyChange();
    }

    private static void add(Entry e) {
        pending.add(0, e);
        while (pending.size() > MAX_PENDING)
            pending.remove(pending.size() - 1);
    }

    /** Ouvre la plus récente dans la bulle (appui sur la pastille ou le bouton suivante) */
    public static synchronized void open() {
        if (pending.isEmpty())
            return;
        peek = false;
        shown = pending.remove(0);
        openedAt = System.currentTimeMillis();
        replyState = REPLY_NONE;
        shown.readAt = openedAt;
        recentlyRead.add(0, shown);
        while (recentlyRead.size() > MAX_PENDING)
            recentlyRead.remove(recentlyRead.size() - 1);
    }

    /**
     * Arrivée d'un message : la plus récente s'ouvre 15 s sans quitter la file,
     * sauf si une bulle ouverte à la main (ou une alarme) est déjà à l'écran
     */
    public static void peek() {
        synchronized (NotificationHolder.class) {
            if (pending.isEmpty() || (isActive() && !peek))
                return;
            peek = true;
            shown = pending.get(0);
            openedAt = System.currentTimeMillis();
            replyState = REPLY_NONE;
        }
        main.removeCallbacks(PEEK_END);
        main.postDelayed(PEEK_END, PEEK_DURATION_MS + 50);
        notifyChange();
    }

    /** Appui sur une bulle ouverte d'elle-même : elle devient une bulle lue, ouverte à la main */
    public static synchronized void confirmPeek() {
        if (!peek || !isActive())
            return;
        peek = false;
        pending.remove(shown);
        openedAt = System.currentTimeMillis();
        shown.readAt = openedAt;
        recentlyRead.add(0, shown);
        while (recentlyRead.size() > MAX_PENDING)
            recentlyRead.remove(recentlyRead.size() - 1);
    }

    /**
     * Retire de la file la notification lue ou effacée (une seule de ses copies
     * suffit), et ferme la bulle si elle l'affichait (« Tout effacer »)
     */
    public static void clearPending(String k) {
        boolean removed;
        synchronized (NotificationHolder.class) {
            removed = removeEntry(k);
            if (shown != null && shown.keys.contains(k)) {
                dismiss();
                removed = true;
            }
        }
        if (removed)
            notifyChange();
    }

    /** Ferme la bulle ; les notifications restantes gardent la pastille */
    public static synchronized void dismiss() {
        peek = false;
        shown = null;
        openedAt = 0;
        replyState = REPLY_NONE;
    }

    /** Notification suivante s'il en reste, sinon fermeture de la bulle */
    public static synchronized void next() {
        if (pending.isEmpty())
            dismiss();
        else
            open();
    }

    /** Chemin de la notification affichée si l'on peut y répondre, sinon null */
    public static synchronized String getReplyId() {
        return shown != null ? shown.replyId : null;
    }

    /** Réponse partie vers le téléphone ; la bulle repart pour 60 s */
    public static synchronized void startReply(String text) {
        replyState = REPLY_SENDING;
        replyText = text;
        replyAt = System.currentTimeMillis();
        if (shown != null)
            openedAt = replyAt;
    }

    /** Accusé du téléphone (ou échec d'envoi) */
    public static void replyResult(boolean ok) {
        synchronized (NotificationHolder.class) {
            if (replyState != REPLY_SENDING)
                return;
            replyState = ok ? REPLY_SENT : REPLY_FAILED;
            replyAt = System.currentTimeMillis();
        }
        notifyChange();
    }

    /** État de la réponse ; un envoi sans accusé finit en échec, un échec s'efface */
    public static synchronized int getReplyState() {
        long now = System.currentTimeMillis();
        if (replyState == REPLY_SENDING && now - replyAt > REPLY_TIMEOUT_MS) {
            replyState = REPLY_FAILED;
            replyAt = now;
        }
        if (replyState == REPLY_FAILED && now - replyAt > REPLY_FAILED_SHOWN_MS)
            replyState = REPLY_NONE;
        return replyState;
    }

    public static synchronized String getReplyText() {
        return replyText;
    }

    public static synchronized long getReplyAt() {
        return replyAt;
    }

    /** Bulle ouverte et pas encore expirée */
    public static synchronized boolean isActive() {
        return shown != null && (System.currentTimeMillis() - openedAt) < duration();
    }

    /** Alarme qui sonne affichée dans la bulle */
    public static synchronized boolean isAlarmShown() {
        return isActive() && shown.actions != null;
    }

    /** Boutons de l'alarme affichée, null : pas une alarme */
    public static synchronized String[] getActions() {
        return shown != null ? shown.actions : null;
    }

    /**
     * Rang du bouton qui arrête l'alarme affichée (« Arrêter », « Stop »,
     * « Ignorer »…), sinon le dernier (Google Horloge : Répéter, Arrêter) ;
     * -1 : pas une alarme. « Répéter » n'est pas proposé sur la montre
     */
    public static synchronized int getStopIndex() {
        if (shown == null || shown.actions == null || shown.actions.length == 0)
            return -1;
        for (int i = 0; i < shown.actions.length; i++) {
            String a = shown.actions[i].toLowerCase(java.util.Locale.ROOT);
            if (a.startsWith("arr") || a.startsWith("stop") || a.startsWith("ignor")
                    || a.startsWith("dismiss") || a.startsWith("désactiv"))
                return i;
        }
        return shown.actions.length - 1;
    }

    /** Chemin de l'alarme affichée sur le téléphone, null : pas une alarme */
    public static synchronized String getActionId() {
        return shown != null ? shown.actionId : null;
    }

    private static long duration() {
        if (shown != null && shown.actions != null)
            return ALARM_DURATION_MS;
        return peek ? PEEK_DURATION_MS : DISPLAY_DURATION_MS;
    }

    /** En attente, sans compter le message affiché par la bulle automatique */
    public static synchronized int getPendingCount() {
        return pending.size() - (peek && isActive() && pending.contains(shown) ? 1 : 0);
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
        return Math.max(0f, 1f - elapsed / (float) duration());
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

    /**
     * Jumeau du message : en attente, affiché dans la bulle, ou lu depuis
     * moins de 2 min (le doublon en retard rejoint alors l'entrée lue, sans
     * revenir dans la file)
     */
    private static Entry findTwin(String app, String t, String txt) {
        for (Entry e : pending)
            if (e.isTwin(app, t, txt))
                return e;
        long now = System.currentTimeMillis();
        for (Entry e : recentlyRead)
            if (now - e.readAt < TWIN_DELAY_MS && e.isTwin(app, t, txt))
                return e;
        return null;
    }

    /** Retire une copie (mise à jour de la notification) ; l'entrée vide disparaît */
    private static void removeKey(String k) {
        for (int i = 0; i < pending.size(); i++) {
            Entry e = pending.get(i);
            if (e.keys.remove(k)) {
                if (e.keys.isEmpty())
                    pending.remove(i);
                return;
            }
        }
    }

    /** Retire l'entrée entière dont une copie porte cette clé */
    private static boolean removeEntry(String k) {
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i).keys.contains(k)) {
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
