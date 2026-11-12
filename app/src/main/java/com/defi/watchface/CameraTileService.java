package com.defi.watchface;

import static androidx.wear.protolayout.DimensionBuilders.dp;
import static androidx.wear.protolayout.DimensionBuilders.expand;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.ResourceBuilders;
import androidx.wear.protolayout.TimelineBuilders;
import androidx.wear.tiles.EventBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * Tuile Caméra : image d'une caméra Home Assistant. Elle n'a pas d'état qui
 * change à suivre : le téléphone la télécharge seulement quand la tuile
 * s'affiche (onTileEnterEvent), et la montre se redessine à la réception.
 * En dehors de ça, aucun échange.
 */
public class CameraTileService extends TileService {

    private static final String RES_CAMERA = "camera";

    @NonNull
    @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(@NonNull RequestBuilders.TileRequest request) {
        return Futures.submit(() -> new TileBuilders.Tile.Builder()
                .setResourcesVersion(TileStore.cameraVersion())
                .setFreshnessIntervalMillis(0)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout()))
                .build(), TileStore.BACKGROUND);
    }

    /** Tuile affichée : image du moment demandée au téléphone */
    @Override
    protected void onTileEnterEvent(@NonNull EventBuilders.TileEnterEvent event) {
        Log.d("DefiTiles", "Tuile affichée : " + getClass().getSimpleName());
        TileStore.requestFresh(this, "camera");
    }

    @NonNull
    @Override
    protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(
            @NonNull RequestBuilders.ResourcesRequest request) {
        ResourceBuilders.Resources.Builder res = new ResourceBuilders.Resources.Builder()
                .setVersion(TileStore.cameraVersion());
        TileStore.Icon picture = TileStore.getCamera();
        if (picture != null)
            res.addIdToImageMapping(RES_CAMERA, new ResourceBuilders.ImageResource.Builder()
                    .setInlineResource(new ResourceBuilders.InlineImageResource.Builder()
                            .setData(picture.argb)
                            .setWidthPx(picture.width)
                            .setHeightPx(picture.height)
                            .setFormat(ResourceBuilders.IMAGE_FORMAT_RGB_565)
                            .build())
                    .build());
        return Futures.immediateFuture(res.build());
    }

    private LayoutElementBuilders.LayoutElement layout() {
        LayoutElementBuilders.Box.Builder box = new LayoutElementBuilders.Box.Builder()
                .setWidth(expand())
                .setHeight(expand());
        if (TileStore.getCamera() == null) {
            box.addContent(ShortcutsTileService.text(PhoneLink.isDown() ? "📵 téléphone injoignable"
                    : "Image de la caméra…", 14, 0xFFAAAAAA, 2));
        } else {
            // Écran rond de 233 dp : l'image carrée le remplit
            box.addContent(new LayoutElementBuilders.Image.Builder()
                    .setResourceId(RES_CAMERA)
                    .setWidth(dp(233))
                    .setHeight(dp(233))
                    .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_CROP)
                    .build());
        }
        return box.build();
    }
}
