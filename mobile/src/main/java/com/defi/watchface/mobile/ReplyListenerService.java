package com.defi.watchface.mobile;

import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.charset.StandardCharsets;

/**
 * Réponse rapide choisie sur la montre (/reply : « chemin de la notif\ntexte ») :
 * envoyée par l'action « Répondre » de la notification d'origine, puis accusée
 * à la montre (/reply/ok ou /reply/fail).
 */
public class ReplyListenerService extends WearableListenerService {

    private static final String TAG = "DefiReply";
    private static final String REPLY_PATH = "/reply";

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (!REPLY_PATH.equals(event.getPath()))
            return;
        String payload = new String(event.getData(), StandardCharsets.UTF_8);
        int nl = payload.indexOf('\n');
        boolean ok = false;
        if (nl > 0) {
            String key = PhoneNotificationListenerService.keyFromPathId(payload.substring(0, nl));
            ok = PhoneNotificationListenerService.reply(key, payload.substring(nl + 1));
            Log.d(TAG, "Reply " + (ok ? "sent" : "failed") + " for " + key);
        }
        Wearable.getMessageClient(this).sendMessage(event.getSourceNodeId(),
                REPLY_PATH + (ok ? "/ok" : "/fail"), event.getData());
    }
}
