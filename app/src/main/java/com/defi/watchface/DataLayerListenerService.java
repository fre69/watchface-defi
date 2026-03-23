package com.defi.watchface;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.InputStream;

public class DataLayerListenerService extends WearableListenerService {

    private static final String TAG = "DefiDataLayer";
    private static final String NOTIF_PATH = "/notification";

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            if (event.getType() != DataEvent.TYPE_CHANGED) continue;
            if (!NOTIF_PATH.equals(event.getDataItem().getUri().getPath())) continue;

            DataMap map = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();

            String appName = map.getString("appName", "");
            String title = map.getString("title", "");
            String text = map.getString("text", "");

            Log.d(TAG, "Received from phone: " + appName + " / " + title + " / " + text);

            Bitmap icon = loadAssetBitmap(map.getAsset("icon"));
            Bitmap picture = loadAssetBitmap(map.getAsset("picture"));

            NotificationHolder.set(appName, title, text, icon, picture, null);
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
