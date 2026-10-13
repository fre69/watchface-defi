package com.defi.watchface;

import android.app.NotificationManager;
import android.content.Context;
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
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        if (pm == null || pm.isInteractive())
            return;

        // Ne pas déranger / mode coucher : la bulle attend, l'écran reste éteint
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null && nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL) {
            Log.d(TAG, "Ne pas déranger actif : écran laissé éteint");
            return;
        }

        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "defi:notification");
        wl.acquire(HOLD_MS);
        Log.d(TAG, "Écran allumé pour une notification");
    }
}
