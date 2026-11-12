package com.defi.watchface;

import static androidx.wear.protolayout.ColorBuilders.argb;
import static androidx.wear.protolayout.DimensionBuilders.dp;
import static androidx.wear.protolayout.DimensionBuilders.expand;
import static androidx.wear.protolayout.DimensionBuilders.sp;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.ModifiersBuilders;
import androidx.wear.protolayout.ResourceBuilders;
import androidx.wear.protolayout.TimelineBuilders;
import androidx.wear.tiles.EventBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.Locale;

/**
 * Tuile Thermostat, comme celle de l'appli Home Assistant : nom, action en
 * cours, consigne, température mesurée, boutons − et +. Un appui attend la
 * nouvelle consigne (2,5 s au plus) ; tant qu'elle n'est pas confirmée, elle
 * s'affiche en bleu.
 */
public class ThermostatTileService extends TileService {

    private static final String RESOURCES_VERSION = "1";
    private static final String CLICK_MINUS = "climate-";
    private static final String CLICK_PLUS = "climate+";
    private static final int C_BUTTON = 0xFF039BE5;

    @NonNull
    @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(@NonNull RequestBuilders.TileRequest request) {
        String clicked = request.getCurrentState().getLastClickableId();
        if (CLICK_MINUS.equals(clicked) || CLICK_PLUS.equals(clicked)) {
            ListenableFuture<Void> wait = TileStore.pressClimate(this, CLICK_PLUS.equals(clicked) ? 1 : -1);
            return Futures.transform(wait, v -> tile(), MoreExecutors.directExecutor());
        }
        return Futures.submit(() -> {
            TileStore.ensureLoaded(this);
            return tile();
        }, TileStore.BACKGROUND);
    }

    /** Tuile affichée : état frais demandé au téléphone (redessin à la réception) */
    @Override
    protected void onTileEnterEvent(@NonNull EventBuilders.TileEnterEvent event) {
        Log.d("DefiTiles", "Tuile affichée : " + getClass().getSimpleName());
        TileStore.requestFresh(this);
    }

    @NonNull
    @Override
    protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(
            @NonNull RequestBuilders.ResourcesRequest request) {
        return Futures.immediateFuture(new ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION).build());
    }

    private TileBuilders.Tile tile() {
        return new TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(0)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout()))
                .build();
    }

    private static String temp(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.1f °C", v);
    }

    private LayoutElementBuilders.LayoutElement layout() {
        TileStore.Thermostat t = TileStore.getThermostat();
        LayoutElementBuilders.Column.Builder col = new LayoutElementBuilders.Column.Builder();
        if (PhoneLink.isDown())
            col.addContent(ShortcutsTileService.text("📵 téléphone injoignable", 12, 0xFFFF4466, 1))
                    .addContent(ShortcutsTileService.spacer(4));
        if (t == null) {
            col.addContent(ShortcutsTileService.text("Thermostat à régler dans l'appli du téléphone",
                    14, 0xFFAAAAAA, 3));
        } else {
            int targetColor = TileStore.hasClimateFailed() ? 0xFFFF4466
                    : TileStore.isClimatePending() ? 0xFF66BBFF : 0xFFFFFFFF;
            col.addContent(ShortcutsTileService.text(t.name, 16, 0xFFFFFFFF, 1))
                    .addContent(ShortcutsTileService.spacer(6))
                    .addContent(ShortcutsTileService.text(t.available ? t.action : "Indisponible",
                            15, 0xFFAAAAAA, 1))
                    .addContent(new LayoutElementBuilders.Text.Builder()
                            .setText(temp(t.target))
                            .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                    .setSize(sp(30))
                                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                                    .setColor(argb(targetColor))
                                    .build())
                            .build())
                    .addContent(ShortcutsTileService.text(temp(t.current), 14, 0xFFAAAAAA, 1))
                    .addContent(ShortcutsTileService.spacer(8))
                    .addContent(new LayoutElementBuilders.Row.Builder()
                            .addContent(button(CLICK_MINUS, "−"))
                            .addContent(new LayoutElementBuilders.Spacer.Builder().setWidth(dp(20)).build())
                            .addContent(button(CLICK_PLUS, "+"))
                            .build());
        }
        return new LayoutElementBuilders.Box.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .addContent(col.build())
                .build();
    }

    private LayoutElementBuilders.LayoutElement button(String id, String label) {
        return new LayoutElementBuilders.Box.Builder()
                .setWidth(dp(46))
                .setHeight(dp(46))
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setBackground(new ModifiersBuilders.Background.Builder()
                                .setColor(argb(C_BUTTON))
                                .setCorner(new ModifiersBuilders.Corner.Builder().setRadius(dp(23)).build())
                                .build())
                        .setClickable(new ModifiersBuilders.Clickable.Builder()
                                .setId(id)
                                .setOnClick(new ActionBuilders.LoadAction.Builder().build())
                                .build())
                        .build())
                .addContent(ShortcutsTileService.text(label, 26, 0xFFFFFFFF, 1))
                .build();
    }
}
