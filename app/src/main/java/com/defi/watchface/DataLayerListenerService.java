package com.defi.watchface;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class DataLayerListenerService extends WearableListenerService {

    private static final String TAG = "DefiDataLayer";
    /** Une notification du téléphone par élément : /notification/<clé encodée> */
    private static final String NOTIF_PREFIX = "/notification/";

    /** Accusés du téléphone : réponse rapide, bouton d'alarme, appui sur la tuile Raccourcis, réponse d'Assist */
    @Override
    public void onMessageReceived(MessageEvent event) {
        String path = event.getPath();
        if ((ReplySender.REPLY_PATH + "/ok").equals(path) || (ReplySender.ACTION_PATH + "/ok").equals(path))
            NotificationHolder.replyResult(true);
        else if ((ReplySender.REPLY_PATH + "/fail").equals(path) || (ReplySender.ACTION_PATH + "/fail").equals(path))
            NotificationHolder.replyResult(false);
        else if ((TileStore.ACTION_PATH + "/fail").equals(path))
            TileStore.actionFailed(this, new String(event.getData(), StandardCharsets.UTF_8));
        else if ((TileStore.CLIMATE_PATH + "/fail").equals(path))
            TileStore.climateFailed(this);
        else if ((TileStore.TODO_PATH + "/fail").equals(path))
            TileStore.todoFailed(this, new String(event.getData(), StandardCharsets.UTF_8));
        else if (AssistActivity.REPLY_PATH.equals(path))
            AssistActivity.onReply(event.getData());
    }

    /** Téléphone joignable ou non : témoin de lien coupé sur le cadran */
    @Override
    public void onCapabilityChanged(CapabilityInfo info) {
        if (PhoneLink.CAPABILITY.equals(info.getName()))
            PhoneLink.setReachable(!info.getNodes().isEmpty());
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            String path = event.getDataItem().getUri().getPath();
            boolean notif = path != null && path.startsWith(NOTIF_PREFIX);
            String key = notif ? "phone:" + path.substring(NOTIF_PREFIX.length()) : null;

            // Notification lue ou effacée sur le téléphone : élément supprimé, la
            // montre la retire de sa file, sans allumer l'écran
            if (event.getType() == DataEvent.TYPE_DELETED) {
                if (notif) {
                    Log.d(TAG, "Notification removed on phone: " + key);
                    NotificationHolder.clearPending(key);
                    // Alarme arrêtée sur le téléphone (ou depuis la bulle) : fin de la vibration
                    if (!NotificationHolder.isAlarmShown())
                        AlarmVibrator.stop();
                }
                continue;
            }
            if (event.getType() != DataEvent.TYPE_CHANGED) continue;
            // Tuiles Raccourcis et Modèle : redessinées sans allumer l'écran
            if (TileStore.apply(this, event.getDataItem())) continue;
            // Météo et voiture de Home Assistant : affichées sans allumer l'écran
            if (HomeData.apply(this, event.getDataItem())) continue;
            // Battement de cœur du téléphone : témoin de lien coupé
            if (PhoneLink.apply(event.getDataItem())) continue;
            if (!notif) continue;

            DataMap map = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();

            String appName = map.getString("appName", "");
            String title = map.getString("title", "");
            String text = map.getString("text", "");

            Log.d(TAG, "Received from phone: " + appName + " / " + title + " / " + text);

            Bitmap icon = loadAssetBitmap(map.getAsset("icon"));

            // Alarme qui sonne : bulle ouverte d'office avec ses boutons, vibration,
            // écran allumé même en Ne pas déranger (une alarme le traverse)
            java.util.ArrayList<String> actions = map.getStringArrayList("actions");
            if (map.getBoolean("alarm", false) && actions != null && !actions.isEmpty()) {
                NotificationHolder.setAlarm(key, appName, title, text, icon,
                        actions.toArray(new String[0]), path.substring(NOTIF_PREFIX.length()));
                ScreenWaker.wake(this, true);
                AlarmVibrator.start(this);
                continue;
            }

            Bitmap picture = loadAssetBitmap(map.getAsset("picture"));

            // Réponse possible : la bulle propose Oui / Non / OK / ✎ (envoyés par le téléphone)
            String replyId = map.getBoolean("canReply", false)
                    ? path.substring(NOTIF_PREFIX.length()) : null;
            NotificationHolder.set(key, appName, title, text, icon, picture, null, replyId);
            ScreenWaker.wake(this);
        }
    }

    private Bitmap loadAssetBitmap(Asset asset) {
        if (asset == null) return null;
        try {
            DataClient client = Wearable.getDataClient(this);
            InputStream is = Tasks.await(client.getFdForAsset(asset)).getInputStream();
            return BitmapFactory.decodeStream(is);
        } catch (Exception e) {
            Log.w(TAG, "Failed to load asset bitmap", e);
            return null;
        }
    }
}
