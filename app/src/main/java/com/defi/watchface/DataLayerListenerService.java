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

public class DataLayerListenerService extends WearableListenerService {

    private static final String TAG = "DefiDataLayer";
    /** Une notification du téléphone par élément : /notification/<clé encodée> */
    private static final String NOTIF_PREFIX = "/notification/";

    /** Accusé du téléphone pour une réponse rapide */
    @Override
    public void onMessageReceived(MessageEvent event) {
        if ((ReplySender.REPLY_PATH + "/ok").equals(event.getPath()))
            NotificationHolder.replyResult(true);
        else if ((ReplySender.REPLY_PATH + "/fail").equals(event.getPath()))
            NotificationHolder.replyResult(false);
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
                }
                continue;
            }
            if (event.getType() != DataEvent.TYPE_CHANGED) continue;
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
