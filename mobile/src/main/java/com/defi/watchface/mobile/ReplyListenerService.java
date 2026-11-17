package com.defi.watchface.mobile;

import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.charset.StandardCharsets;

/**
 * Gestes de la bulle de la montre sur une notification du téléphone, accusés à
 * la montre (chemin + /ok ou /fail, même contenu) :
 * - /reply : « chemin de la notif\ntexte », réponse rapide envoyée par l'action
 *   « Répondre » de la notification d'origine ;
 * - /action : « chemin de la notif\nrang\nlibellé », bouton d'une alarme qui
 *   sonne (« Répéter », « Arrêter ») déclenché comme dans le volet.
 */
public class ReplyListenerService extends WearableListenerService {

    private static final String TAG = "DefiReply";
    private static final String REPLY_PATH = "/reply";
    private static final String ACTION_PATH = "/action";

    @Override
    public void onMessageReceived(MessageEvent event) {
        String path = event.getPath();
        String payload = new String(event.getData(), StandardCharsets.UTF_8);
        String[] parts = payload.split("\n", 3);
        boolean ok = false;
        if (REPLY_PATH.equals(path)) {
            if (parts.length >= 2 && !parts[0].isEmpty()) {
                String key = PhoneNotificationListenerService.keyFromPathId(parts[0]);
                ok = PhoneNotificationListenerService.reply(key, payload.substring(parts[0].length() + 1));
                Log.d(TAG, "Reply " + (ok ? "sent" : "failed") + " for " + key);
            }
        } else if (ACTION_PATH.equals(path)) {
            if (parts.length == 3) {
                String key = PhoneNotificationListenerService.keyFromPathId(parts[0]);
                try {
                    ok = PhoneNotificationListenerService.runAction(key, Integer.parseInt(parts[1]), parts[2]);
                } catch (NumberFormatException e) {
                    ok = false;
                }
                Log.d(TAG, "Action « " + parts[2] + " » " + (ok ? "done" : "failed") + " for " + key);
            }
        } else {
            return;
        }
        Wearable.getMessageClient(this).sendMessage(event.getSourceNodeId(),
                path + (ok ? "/ok" : "/fail"), event.getData());
    }
}
