package com.defi.watchface;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Vibration de la montre pendant qu'une alarme du téléphone sonne : OHealth ne
 * relaie pas celle de Google Horloge (FLAG_LOCAL_ONLY), la montre resterait
 * muette. Usage « alarme » : passe le mode Ne pas déranger, comme une alarme.
 * S'arrête au premier appui sur la bulle, à l'arrêt de l'alarme, ou seule au
 * bout d'une minute.
 */
final class AlarmVibrator {
    private static final long MAX_MS = 60_000L;
    /** 0,8 s de vibration, 1 s de pause, en boucle */
    private static final long[] PATTERN = { 0, 800, 1000 };
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable AUTO_STOP = AlarmVibrator::stopNow;
    private static Vibrator vibrator;

    private AlarmVibrator() {
    }

    static synchronized void start(Context ctx) {
        if (vibrator != null)
            return;
        Vibrator v = ctx.getApplicationContext().getSystemService(Vibrator.class);
        if (v == null || !v.hasVibrator())
            return;
        vibrator = v;
        v.vibrate(VibrationEffect.createWaveform(PATTERN, 0),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
        HANDLER.removeCallbacks(AUTO_STOP);
        HANDLER.postDelayed(AUTO_STOP, MAX_MS);
    }

    static void stop() {
        HANDLER.removeCallbacks(AUTO_STOP);
        stopNow();
    }

    private static synchronized void stopNow() {
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
    }
}
