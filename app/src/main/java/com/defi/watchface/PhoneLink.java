package com.defi.watchface;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

/**
 * Lien avec l'appli du téléphone, dont dépendent les notifications et les
 * données Home Assistant. Coupé quand le téléphone n'est plus joignable
 * (capability defi_phone), ou quand son battement de cœur (/phone/alive,
 * toutes les 15 min) s'arrête : appli tuée, accès aux notifications retiré.
 */
public final class PhoneLink {
    private static final String TAG = "DefiPhoneLink";
    static final String CAPABILITY = "defi_phone";
    static final String ALIVE_PATH = "/phone/alive";

    /** Battement toutes les 15 min, sans urgence : Google tolère 30 min de retard */
    private static final long ALIVE_MAX_AGE_MS = 60 * 60_000L;

    /** null : inconnu (appli du téléphone sans capability, ou pas encore lu) */
    private static Boolean reachable = null;
    /** Date du dernier battement (0 : aucun reçu, appli du téléphone ancienne) */
    private static long aliveAt = 0;
    private static Runnable onChange = null;

    public static synchronized void setOnChangeListener(Runnable listener) {
        onChange = listener;
    }

    /** Lien coupé : téléphone hors de portée, ou silencieux depuis plus d'1 h */
    public static synchronized boolean isDown() {
        if (Boolean.FALSE.equals(reachable))
            return true;
        return aliveAt > 0 && System.currentTimeMillis() - aliveAt > ALIVE_MAX_AGE_MS;
    }

    static void setReachable(boolean r) {
        boolean wasDown;
        synchronized (PhoneLink.class) {
            wasDown = isDown();
            reachable = r;
        }
        Log.d(TAG, "Téléphone " + (r ? "joignable" : "hors de portée"));
        notifyIfChanged(wasDown);
    }

    /** Applique un élément reçu ; false s'il n'est pas un battement */
    static boolean apply(DataItem item) {
        if (!ALIVE_PATH.equals(item.getUri().getPath()))
            return false;
        long sentAt = DataMapItem.fromDataItem(item).getDataMap().getLong("sentAt", 0);
        boolean wasDown;
        synchronized (PhoneLink.class) {
            wasDown = isDown();
            aliveAt = Math.max(aliveAt, sentAt);
        }
        notifyIfChanged(wasDown);
        return true;
    }

    /** Relit l'état du lien et le dernier battement (démarrage du cadran) */
    public static void load(Context ctx) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                CapabilityClient cc = Wearable.getCapabilityClient(app);
                // Aucun téléphone n'annonce la capability (appli ancienne) : état inconnu,
                // pas de témoin
                CapabilityInfo all = Tasks.await(cc.getCapability(CAPABILITY, CapabilityClient.FILTER_ALL));
                if (!all.getNodes().isEmpty()) {
                    CapabilityInfo near = Tasks.await(
                            cc.getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE));
                    setReachable(!near.getNodes().isEmpty());
                }
                Uri uri = new Uri.Builder().scheme("wear").path(ALIVE_PATH).build();
                DataItemBuffer items = Tasks.await(Wearable.getDataClient(app).getDataItems(uri));
                try {
                    for (DataItem item : items)
                        apply(item);
                } finally {
                    items.release();
                }
            } catch (Exception e) {
                Log.w(TAG, "Lecture de l'état du lien impossible", e);
            }
        }).start();
    }

    /**
     * Redessin seulement quand le témoin apparaît ou disparaît ; son apparition
     * faute de battement, elle, attend le redessin de la minute
     */
    private static void notifyIfChanged(boolean wasDown) {
        Runnable listener;
        synchronized (PhoneLink.class) {
            if (isDown() == wasDown)
                return;
            listener = onChange;
        }
        if (listener != null)
            listener.run();
    }
}
