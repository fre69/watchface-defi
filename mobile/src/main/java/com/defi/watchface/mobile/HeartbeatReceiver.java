package com.defi.watchface.mobile;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

/**
 * Battement de cœur vers la montre (/phone/alive) : prouve que l'écoute des
 * notifications tourne. Quand il s'arrête, le cadran affiche le témoin de lien
 * coupé. AlarmManager et non un Handler : le délai d'un Handler ne court pas
 * pendant la veille profonde du téléphone.
 */
public class HeartbeatReceiver extends BroadcastReceiver {

    private static final String TAG = "DefiHeartbeat";
    private static final String ALIVE_PATH = "/phone/alive";
    /** La montre tient le lien pour coupé au-delà d'1 h sans battement */
    private static final long INTERVAL_MS = 15 * 60_000L;

    /** Programme le prochain battement (remplace le précédent) */
    static void schedule(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0,
                new Intent(ctx, HeartbeatReceiver.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL_MS, pi);
    }

    /** Sans urgence : la montre tolère le retard de synchronisation de Google (30 min) */
    static Task<DataItem> send(Context ctx) {
        PutDataMapRequest req = PutDataMapRequest.create(ALIVE_PATH);
        req.getDataMap().putLong("sentAt", System.currentTimeMillis());
        return Wearable.getDataClient(ctx).putDataItem(req.asPutDataRequest());
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        schedule(ctx);
        if (!PhoneNotificationListenerService.isConnected()) {
            // Processus relancé par l'alarme sans écoute branchée : pas de battement
            // (la montre signalera la coupure si elle dure), et demande de rebranchement
            Log.w(TAG, "Écoute des notifications débranchée : rebranchement demandé");
            NotificationListenerService.requestRebind(
                    new ComponentName(ctx, PhoneNotificationListenerService.class));
            return;
        }
        PendingResult result = goAsync();
        send(ctx).addOnCompleteListener(t -> {
            if (!t.isSuccessful())
                Log.w(TAG, "Battement non envoyé", t.getException());
            result.finish();
        });
    }
}
