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
 * Envoie au téléphone une réponse rapide choisie dans la bulle (/reply :
 * « chemin de la notification\ntexte ») ; le téléphone la passe à l'action
 * « Répondre » de la notification d'origine et accuse réception (/reply/ok).
 */
final class ReplySender {
    private static final String TAG = "DefiReply";
    static final String REPLY_PATH = "/reply";

    private ReplySender() {
    }

    static void send(Context ctx, String replyId, String text) {
        NotificationHolder.startReply(text);
        Context app = ctx.getApplicationContext();
        byte[] payload = (replyId + "\n" + text).getBytes(StandardCharsets.UTF_8);
        new Thread(() -> {
            try {
                Set<Node> nodes = Tasks.await(Wearable.getCapabilityClient(app)
                        .getCapability(PhoneLink.CAPABILITY, CapabilityClient.FILTER_REACHABLE))
                        .getNodes();
                if (nodes.isEmpty()) {
                    Log.w(TAG, "Téléphone injoignable : réponse non envoyée");
                    NotificationHolder.replyResult(false);
                    return;
                }
                for (Node n : nodes)
                    Tasks.await(Wearable.getMessageClient(app).sendMessage(n.getId(), REPLY_PATH, payload));
            } catch (Exception e) {
                Log.w(TAG, "Réponse non envoyée", e);
                NotificationHolder.replyResult(false);
            }
        }).start();
    }
}
