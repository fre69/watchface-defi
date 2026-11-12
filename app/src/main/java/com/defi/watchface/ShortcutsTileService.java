package com.defi.watchface;

import static androidx.wear.protolayout.ColorBuilders.argb;
import static androidx.wear.protolayout.DimensionBuilders.dp;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tuile Raccourcis : jusqu'à 7 entités Home Assistant avec leur état réel
 * (allumé en couleur), plus un bouton micro pour Assist, en rangées 2-3-3
 * comme la tuile de l'appli Home Assistant. Un appui bascule
 * l'entité ; la tuile attend le nouvel état (au plus 2,5 s) avant de se
 * redessiner, et cercle de bleu une entité dont l'appui n'est pas confirmé.
 */
public class ShortcutsTileService extends TileService {

    private static final String CLICK_PREFIX = "e:";
    private static final int MAX_ENTITIES = 7;
    private static final int CIRCLE_DP = 50;
    private static final int GAP_DP = 5;

    private static final int C_ACTIVE = 0xFFFFC107;
    private static final int C_IDLE = 0xFF2A2A35;
    private static final int C_UNAVAILABLE = 0xFF141418;
    private static final int C_ACTION = 0xFF1E3A5F;
    private static final int C_PENDING = 0xFF66BBFF;
    private static final int C_FAILED = 0xFFFF4466;

    @NonNull
    @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(@NonNull RequestBuilders.TileRequest request) {
        String clicked = request.getCurrentState().getLastClickableId();
        if (clicked.startsWith(CLICK_PREFIX)) {
            // Nouvel état attendu du téléphone avant de redessiner
            ListenableFuture<Void> wait = TileStore.press(this, clicked.substring(CLICK_PREFIX.length()));
            return TileStore.logged("Raccourcis",
                    Futures.transform(wait, v -> tile(), MoreExecutors.directExecutor()));
        }
        // Premier affichage après un redémarrage : relire la DataLayer hors du fil principal
        return TileStore.logged("Raccourcis", Futures.submit(() -> {
            TileStore.ensureLoaded(this);
            return tile();
        }, TileStore.BACKGROUND));
    }

    /** Tuile affichée : état frais demandé au téléphone (redessin à la réception) */
    @Override
    protected void onTileEnterEvent(@NonNull EventBuilders.TileEnterEvent event) {
        Log.d("DefiTiles", "Tuile affichée : " + getClass().getSimpleName());
        TileStore.requestFresh(this, "");
    }

    @NonNull
    @Override
    protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(
            @NonNull RequestBuilders.ResourcesRequest request) {
        // Icônes des entités, dessinées par le téléphone (pixels ARGB_8888)
        ResourceBuilders.Resources.Builder res = new ResourceBuilders.Resources.Builder()
                .setVersion(TileStore.iconsVersion());
        for (Map.Entry<String, TileStore.Icon> e : TileStore.getIcons().entrySet()) {
            TileStore.Icon icon = e.getValue();
            res.addIdToImageMapping(TileStore.iconResId(e.getKey()), new ResourceBuilders.ImageResource.Builder()
                    .setInlineResource(new ResourceBuilders.InlineImageResource.Builder()
                            .setData(icon.argb)
                            .setWidthPx(icon.width)
                            .setHeightPx(icon.height)
                            .setFormat(ResourceBuilders.IMAGE_FORMAT_ARGB_8888)
                            .build())
                    .build());
        }
        return Futures.immediateFuture(res.build());
    }

    private TileBuilders.Tile tile() {
        return new TileBuilders.Tile.Builder()
                .setResourcesVersion(TileStore.iconsVersion())
                // Aucun rafraîchissement périodique : le téléphone pousse chaque changement
                .setFreshnessIntervalMillis(0)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout()))
                .build();
    }

    private LayoutElementBuilders.LayoutElement layout() {
        List<TileStore.Entity> list = TileStore.getEntities();
        LayoutElementBuilders.Column.Builder col = new LayoutElementBuilders.Column.Builder();
        if (PhoneLink.isDown())
            col.addContent(text("📵 téléphone injoignable", 12, 0xFFFF4466, 1))
                    .addContent(spacer(6));
        List<LayoutElementBuilders.LayoutElement> items = new ArrayList<>();
        for (int i = 0; i < Math.min(list.size(), MAX_ENTITIES); i++)
            items.add(button(list.get(i)));
        if (items.isEmpty())
            col.addContent(text("Raccourcis à régler dans l'appli du téléphone", 14, 0xFFAAAAAA, 3))
                    .addContent(spacer(GAP_DP));
        items.add(micButton());
        // Écran rond : rangées plus courtes en haut et en bas
        int[] rows = rowSizes(items.size());
        int k = 0;
        for (int r = 0; r < rows.length; r++) {
            LayoutElementBuilders.Row.Builder row = new LayoutElementBuilders.Row.Builder();
            for (int i = 0; i < rows[r]; i++, k++) {
                if (i > 0)
                    row.addContent(new LayoutElementBuilders.Spacer.Builder().setWidth(dp(GAP_DP)).build());
                row.addContent(items.get(k));
            }
            if (r > 0)
                col.addContent(spacer(GAP_DP));
            col.addContent(row.build());
        }
        return new LayoutElementBuilders.Box.Builder()
                .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
                .setHeight(androidx.wear.protolayout.DimensionBuilders.expand())
                // Écran rond : le texte ne doit pas toucher le bord
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setPadding(new ModifiersBuilders.Padding.Builder().setAll(dp(20)).build())
                        .build())
                .addContent(col.build())
                .build();
    }

    /** Nombre de cercles par rangée pour n cercles (micro compris) */
    private static int[] rowSizes(int n) {
        switch (n) {
            case 8: return new int[] { 2, 3, 3 };
            case 7: return new int[] { 2, 3, 2 };
            case 6: return new int[] { 3, 3 };
            case 5: return new int[] { 2, 3 };
            case 4: return new int[] { 2, 2 };
            default: return new int[] { n };
        }
    }

    private LayoutElementBuilders.LayoutElement button(TileStore.Entity e) {
        int bg, fg = 0xFFFFFFFF;
        if (!e.available) {
            bg = C_UNAVAILABLE;
            fg = 0xFF666670;
        } else if (e.stateless) {
            bg = C_ACTION;
        } else if (e.active) {
            bg = C_ACTIVE;
            fg = 0xFF000000;
        } else {
            bg = C_IDLE;
        }
        ModifiersBuilders.Modifiers.Builder mods = new ModifiersBuilders.Modifiers.Builder()
                .setBackground(new ModifiersBuilders.Background.Builder()
                        .setColor(argb(bg))
                        .setCorner(new ModifiersBuilders.Corner.Builder().setRadius(dp(CIRCLE_DP / 2f)).build())
                        .build())
                .setClickable(new ModifiersBuilders.Clickable.Builder()
                        .setId(CLICK_PREFIX + e.id)
                        .setOnClick(new ActionBuilders.LoadAction.Builder().build())
                        .build());
        // Appui pas encore confirmé (bleu) ou refusé (rouge) : l'état dessiné est incertain
        if (TileStore.hasFailed(e.id) || TileStore.isPending(e.id))
            mods.setBorder(new ModifiersBuilders.Border.Builder()
                    .setWidth(dp(3))
                    .setColor(argb(TileStore.hasFailed(e.id) ? C_FAILED : C_PENDING))
                    .build());
        LayoutElementBuilders.LayoutElement content;
        if (!e.iconKey.isEmpty() && TileStore.getIcons().containsKey(e.iconKey)) {
            // Icône de l'entité, comme dans l'appli Home Assistant, et son nom en petit
            content = new LayoutElementBuilders.Column.Builder()
                    .addContent(new LayoutElementBuilders.Image.Builder()
                            .setResourceId(TileStore.iconResId(e.iconKey))
                            .setWidth(dp(24))
                            .setHeight(dp(24))
                            .build())
                    .addContent(text(e.name, 8, fg, 1))
                    .build();
        } else {
            content = text(e.name, 10, fg, 2);
        }
        return new LayoutElementBuilders.Box.Builder()
                .setWidth(dp(CIRCLE_DP))
                .setHeight(dp(CIRCLE_DP))
                .setModifiers(mods.build())
                .addContent(content)
                .build();
    }

    private LayoutElementBuilders.LayoutElement micButton() {
        return new LayoutElementBuilders.Box.Builder()
                .setWidth(dp(CIRCLE_DP))
                .setHeight(dp(CIRCLE_DP))
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setBackground(new ModifiersBuilders.Background.Builder()
                                .setColor(argb(0xFF3A2A55))
                                .setCorner(new ModifiersBuilders.Corner.Builder().setRadius(dp(CIRCLE_DP / 2f)).build())
                                .build())
                        .setClickable(new ModifiersBuilders.Clickable.Builder()
                                .setId("assist")
                                .setOnClick(new ActionBuilders.LaunchAction.Builder()
                                        .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                                                .setPackageName(getPackageName())
                                                .setClassName(AssistActivity.class.getName())
                                                .build())
                                        .build())
                                .build())
                        .build())
                .addContent(text("🎤", 18, 0xFFFFFFFF, 1))
                .build();
    }

    static LayoutElementBuilders.Text text(String s, int size, int color, int maxLines) {
        return new LayoutElementBuilders.Text.Builder()
                .setText(s)
                .setMaxLines(maxLines)
                .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                        .setSize(sp(size))
                        .setColor(argb(color))
                        .build())
                .build();
    }

    static LayoutElementBuilders.Spacer spacer(int heightDp) {
        return new LayoutElementBuilders.Spacer.Builder().setHeight(dp(heightDp)).build();
    }
}
