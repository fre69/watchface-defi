package com.defi.watchface;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;

/**
 * Vibration de la montre pendant qu'une alarme du téléphone sonne : OHealth ne
 * relaie pas celle de Google Horloge (FLAG_LOCAL_ONLY), la montre resterait
 * muette. Usage « alarme » : passe le mode Ne pas déranger, comme une alarme.
 * L'écran reste allumé tant qu'elle vibre. S'arrête au premier appui sur
 * l'écran d'alarme, à l'arrêt de l'alarme sur le téléphone, ou seule au bout
 * d'une minute (assez pour réveiller, sans vider la batterie).
 *
 * Le sommeil profond OnePlus (Android éteint la nuit) coupe toutes les
 * vibrations au redémarrage du matin (vibrate_on=0, DeepinSleepManager) tant
 * que la montre croit l'utilisateur endormi : l'alarme serait refusée
 * (ignored_for_settings). Le réglage est donc remis à 1 pendant l'alarme, et
 * de nouveau s'il est recoupé, avec l'accès WRITE_SETTINGS accordé par adb.
 */
final class AlarmVibrator {
    private static final String TAG = "DefiAlarm";
    private static final long MAX_MS = 60_000L;
    /** 0,8 s de vibration, 1 s de pause, en boucle */
    private static final long[] PATTERN = { 0, 800, 1000 };
    private static final String VIBRATE_ON = "vibrate_on";
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable AUTO_STOP = AlarmVibrator::stopNow;

    private static Context app;
    private static Vibrator vibrator;
    private static PowerManager.WakeLock screen;
    private static ContentObserver vibrateOnObserver;

    private AlarmVibrator() {
    }

    @SuppressWarnings("deprecation") // SCREEN_BRIGHT_WAKE_LOCK : seul moyen de tenir l'écran depuis un service
    static synchronized void start(Context ctx) {
        if (vibrator != null)
            return;
        app = ctx.getApplicationContext();
        Vibrator v = app.getSystemService(Vibrator.class);
        if (v == null || !v.hasVibrator())
            return;
        vibrator = v;

        // Écran tenu pendant toute l'alarme : réveillé, on la voit encore
        PowerManager pm = app.getSystemService(PowerManager.class);
        if (pm != null) {
            screen = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "defi:alarm");
            screen.acquire(MAX_MS);
        }

        allowVibration();
        watchVibrateOn();
        vibrate();
        HANDLER.removeCallbacks(AUTO_STOP);
        HANDLER.postDelayed(AUTO_STOP, MAX_MS);
    }

    static void stop() {
        HANDLER.removeCallbacks(AUTO_STOP);
        stopNow();
    }

    private static synchronized void stopNow() {
        if (vibrateOnObserver != null) {
            app.getContentResolver().unregisterContentObserver(vibrateOnObserver);
            vibrateOnObserver = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
        if (screen != null) {
            if (screen.isHeld())
                screen.release();
            screen = null;
        }
    }

    private static void vibrate() {
        vibrator.vibrate(VibrationEffect.createWaveform(PATTERN, 0),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
    }

    /** vibrate_on coupé (sommeil profond) : remis à 1, sinon l'alarme est refusée */
    private static boolean allowVibration() {
        ContentResolver cr = app.getContentResolver();
        if (Settings.System.getInt(cr, VIBRATE_ON, 1) != 0)
            return false;
        if (!Settings.System.canWrite(app)) {
            Log.w(TAG, "Vibrations coupées par le système et accès WRITE_SETTINGS refusé : alarme muette");
            return false;
        }
        try {
            Settings.System.putInt(cr, VIBRATE_ON, 1);
            Log.i(TAG, "Vibrations rétablies pour l'alarme (vibrate_on était à 0)");
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Impossible de rétablir vibrate_on", e);
            return false;
        }
    }

    /** Recoupé pendant l'alarme : rétabli et vibration relancée (elle a été annulée) */
    private static void watchVibrateOn() {
        vibrateOnObserver = new ContentObserver(HANDLER) {
            @Override
            public void onChange(boolean selfChange) {
                synchronized (AlarmVibrator.class) {
                    if (vibrator != null && allowVibration())
                        vibrate();
                }
            }
        };
        app.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(VIBRATE_ON), false, vibrateOnObserver);
    }
}
