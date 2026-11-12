package com.defi.watchface.mobile;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.charset.StandardCharsets;

/**
 * Commandes de la montre pour Home Assistant, exécutées par HaLink :
 * - /ha/action : « entity_id » (appui sur la tuile Raccourcis). Le nouvel état
 *   revient par /ha/tiles ; seul un échec est accusé (/ha/action/fail).
 * - /ha/assist : texte dicté, posé à Assist ; réponse en /ha/assist/reply
 *   (réussite « 1 »/« 0 », suite attendue « 1 »/« 0 », puis le texte à afficher).
 * - /ha/assist/new : bouton ↺ d'Assist, la conversation repart de zéro.
 * - /ha/tiles/get : une tuile vient de s'afficher, renvoyer l'état frais
 *   (« camera » : télécharger l'image de la caméra).
 * - /ha/climate : « + » ou « - » sur la tuile Thermostat ; échec accusé en
 *   /ha/climate/fail, la nouvelle consigne revient par /ha/thermostat.
 */
public class HaCommandService extends WearableListenerService {

    private static final String TAG = "DefiHaCmd";
    private static final String ACTION_PATH = "/ha/action";
    private static final String ASSIST_PATH = "/ha/assist";
    private static final String TILES_GET_PATH = "/ha/tiles/get";
    private static final String CLIMATE_PATH = "/ha/climate";
    private static final String ASSIST_NEW_PATH = "/ha/assist/new";

    @Override
    public void onMessageReceived(MessageEvent event) {
        String path = event.getPath();
        String payload = new String(event.getData(), StandardCharsets.UTF_8).trim();
        String node = event.getSourceNodeId();
        Context app = getApplicationContext();
        HaLink link = HaLink.get(app);
        link.start();
        if (ACTION_PATH.equals(path)) {
            link.toggle(payload, (ok, text, more) -> {
                Log.d(TAG, "Action " + payload + (ok ? " faite" : " refusée : " + text));
                if (!ok)
                    reply(app, node, ACTION_PATH + "/fail", payload);
            });
        } else if (ASSIST_NEW_PATH.equals(path)) {
            link.newConversation();
        } else if (TILES_GET_PATH.equals(path)) {
            link.refreshTiles(payload);
        } else if (CLIMATE_PATH.equals(path)) {
            link.climateStep("-".equals(payload) ? -1 : 1, (ok, text, more) -> {
                if (!ok)
                    reply(app, node, CLIMATE_PATH + "/fail", text);
            });
        } else if (ASSIST_PATH.equals(path)) {
            // « 1 » ou « 0 » (réussite), « 1 » ou « 0 » (Home Assistant attend une suite), texte
            link.assist(payload, (ok, text, more) -> reply(app, node, ASSIST_PATH + "/reply",
                    (ok ? "1" : "0") + (more ? "1" : "0") + (text.isEmpty() && ok ? "C'est fait." : text)));
        }
    }

    private static void reply(Context app, String node, String path, String text) {
        Wearable.getMessageClient(app).sendMessage(node, path, text.getBytes(StandardCharsets.UTF_8))
                .addOnFailureListener(e -> Log.w(TAG, "Réponse à la montre impossible", e));
    }
}
