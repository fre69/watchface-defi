package com.defi.watchface;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Envoie au téléphone un geste de la bulle sur une de ses notifications ; il
 * l'exécute et accuse réception (chemin + /ok ou /fail) :
 * - /reply : « chemin de la notification\ntexte », réponse rapide passée à
 *   l'action « Répondre » de la notification d'origine ;
 * - /action : « chemin de la notification\nrang\nlibellé », bouton d'une alarme
 *   qui sonne (« Répéter », « Arrêter »).
 */
final class ReplySender {
    private static final String TAG = "DefiReply";
    static final String REPLY_PATH = "/reply";
    static final String ACTION_PATH = "/action";

    private ReplySender() {
    }

    static void send(Context ctx, String replyId, String text) {
        NotificationHolder.startReply(text);
        post(ctx, REPLY_PATH, replyId + "\n" + text);
    }

    static void sendAction(Context ctx, String actionId, int index, String title) {
        NotificationHolder.startReply(title);
        post(ctx, ACTION_PATH, actionId + "\n" + index + "\n" + title);
    }

    private static void post(Context ctx, String path, String text) {
        Context app = ctx.getApplicationContext();
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        new Thread(() -> {
            try {
                Set<Node> nodes = Tasks.await(Wearable.getCapabilityClient(app)
                        .getCapability(PhoneLink.CAPABILITY, CapabilityClient.FILTER_REACHABLE))
                        .getNodes();
                if (nodes.isEmpty()) {
                    Log.w(TAG, "Téléphone injoignable : " + path + " non envoyé");
                    NotificationHolder.replyResult(false);
                    return;
                }
                for (Node n : nodes)
                    Tasks.await(Wearable.getMessageClient(app).sendMessage(n.getId(), path, payload));
            } catch (Exception e) {
                Log.w(TAG, path + " non envoyé", e);
                NotificationHolder.replyResult(false);
            }
        }).start();
    }
}
