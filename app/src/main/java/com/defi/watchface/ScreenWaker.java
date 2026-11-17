package com.defi.watchface;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

/**
 * Allume l'écran à l'arrivée d'une notification, pour pouvoir couper le lever
 * de poignet (ambient_tilt_to_wake) qui l'allume des centaines de fois par jour.
 *
 * Avec un cadran tiers, le système OnePlus ne bascule jamais l'écran vers le
 * co-processeur (bs_third_wf=1) : un WakeLock ACQUIRE_CAUSES_WAKEUP suffit.
 */
final class ScreenWaker {
    private static final String TAG = "DefiScreenWaker";
    /** Le WakeLock se libère seul ; ON_AFTER_RELEASE garde ensuite l'écran le délai normal */
    private static final long HOLD_MS = 1_000L;

    private ScreenWaker() {
    }

    @SuppressWarnings("deprecation") // SCREEN_BRIGHT_WAKE_LOCK : seul moyen d'allumer depuis un service
    static void wake(Context ctx) {
        wake(ctx, false);
    }

    /** alarm : alarme du téléphone qui sonne, l'écran s'allume même en Ne pas déranger */
    static void wake(Context ctx, boolean alarm) {
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        if (pm == null || pm.isInteractive())
            return;

        // Ne pas déranger / mode coucher : la bulle attend, l'écran reste éteint
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (!alarm && nm != null
                && nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL) {
            Log.d(TAG, "Ne pas déranger actif : écran laissé éteint");
            return;
        }

        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "defi:notification");
        wl.acquire(HOLD_MS);
        Log.d(TAG, "Écran allumé pour une notification");
    }

    /**
     * Ramène le cadran au premier plan (tuile ou appli affichée) : l'écran
     * d'alarme y est dessiné. Même intent que la touche accueil de la montre.
     * Sans FROM_HOME_KEY, la SysUI (dont les tuiles font partie) reçoit
     * l'intent mais reste sur la tuile. Autorisé depuis l'arrière-plan : la
     * DataLayer livre ses événements avec un jeton de lancement d'activité
     */
    static void showWatchFace(Context ctx) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .putExtra("android.intent.extra.FROM_HOME_KEY", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Log.d(TAG, "Cadran ramené au premier plan");
        } catch (RuntimeException e) {
            Log.w(TAG, "Retour au cadran impossible", e);
        }
    }
}
