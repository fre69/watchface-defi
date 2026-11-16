package com.defi.watchface;

import static androidx.wear.protolayout.ColorBuilders.argb;
import static androidx.wear.protolayout.DimensionBuilders.dp;
import static androidx.wear.protolayout.DimensionBuilders.expand;

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

import java.util.List;

/**
 * Tuile Liste : tâches d'une liste Home Assistant (entité todo, réglée dans l'appli
 * du téléphone). Le rond ○ coche une tâche (✓ la décoche) ; un appui sur le texte
 * ouvre l'écran d'édition (renommer, supprimer) ; + ajoute une tâche. Les tâches
 * faites sont masquées : le bouton ✓ à côté du + les affiche (en gris, sous les
 * tâches à faire) jusqu'à ce qu'on quitte la tuile.
 */
public class TodoTileService extends TileService {

    private static final String RESOURCES_VERSION = "1";
    private static final String CHECK_PREFIX = "c:";
    private static final String UNCHECK_PREFIX = "u:";
    private static final String SHOW_DONE = "show_done";
    /**
     * Lignes de tâches qui tiennent dans le rond, sous le titre et au-dessus du + (à 5,
     * le + touchait le bas de l'écran)
     */
    private static final int MAX_ROWS = 4;
    private static final int ROW_WIDTH_DP = 168;
    private static final int BOX_DP = 24;

    private static final int C_TITLE = 0xFFAA88FF;
    private static final int C_TODO = 0xFFFFFFFF;
    private static final int C_DONE = 0xFF777780;
    private static final int C_BOX_BORDER = 0xFF666670;
    private static final int C_BOX_DONE = 0xFF44FF88;
    private static final int C_PENDING = 0xFF66BBFF;
    private static final int C_FAILED = 0xFFFF4466;

    @NonNull
    @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(@NonNull RequestBuilders.TileRequest request) {
        String clicked = request.getCurrentState().getLastClickableId();
        if (SHOW_DONE.equals(clicked)) {
            TileStore.todoShowDone = !TileStore.todoShowDone;
            return TileStore.logged("Liste", Futures.immediateFuture(tile()));
        }
        boolean check = clicked.startsWith(CHECK_PREFIX);
        if (check || clicked.startsWith(UNCHECK_PREFIX)) {
            // Nouvelle liste attendue du téléphone avant de redessiner
            ListenableFuture<Void> wait = TileStore.todoCommand(this, check ? "check" : "uncheck",
                    clicked.substring(2), null);
            return TileStore.logged("Liste", Futures.transform(wait, v -> tile(), MoreExecutors.directExecutor()));
        }
        return TileStore.logged("Liste", Futures.submit(() -> {
            TileStore.ensureLoaded(this);
            return tile();
        }, TileStore.BACKGROUND));
    }

    /** Tuile affichée : état frais demandé au téléphone (redessin à la réception) */
    @Override
    protected void onTileEnterEvent(@NonNull EventBuilders.TileEnterEvent event) {
        Log.d("DefiTiles", "Tuile affichée : " + getClass().getSimpleName());
        TileStore.requestFresh(this);
    }

    /** Tuile quittée : les tâches faites seront de nouveau masquées au retour */
    @Override
    protected void onTileLeaveEvent(@NonNull EventBuilders.TileLeaveEvent event) {
        if (TileStore.todoShowDone) {
            TileStore.todoShowDone = false;
            getUpdater(this).requestUpdate(TodoTileService.class);
        }
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
                // Aucun rafraîchissement périodique : le téléphone pousse chaque changement
                .setFreshnessIntervalMillis(0)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout()))
                .build();
    }

    private LayoutElementBuilders.LayoutElement layout() {
        TileStore.Todo todo = TileStore.getTodo();
        LayoutElementBuilders.Column.Builder col = new LayoutElementBuilders.Column.Builder();
        if (PhoneLink.isDown())
            col.addContent(ShortcutsTileService.text("📵 téléphone injoignable", 12, C_FAILED, 1))
                    .addContent(ShortcutsTileService.spacer(4));
        String error = TileStore.todoError();
        if (todo == null) {
            col.addContent(ShortcutsTileService.text("Liste en attente du téléphone", 14, 0xFFAAAAAA, 3));
        } else if (!todo.error.isEmpty()) {
            col.addContent(ShortcutsTileService.text("⚠ " + todo.error, 13, C_FAILED, 4));
        } else {
            int pending = 0;
            for (TileStore.TodoItem it : todo.items)
                if (!it.done)
                    pending++;
            // Titre : ouvre aussi la liste entière
            col.addContent(new LayoutElementBuilders.Box.Builder()
                    .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                            .setClickable(new ModifiersBuilders.Clickable.Builder()
                                    .setId("list")
                                    .setOnClick(launchList())
                                    .build())
                            .build())
                    .addContent(ShortcutsTileService.text(
                            (todo.name.isEmpty() ? "Liste" : todo.name) + (pending > 0 ? " · " + pending : ""),
                            12, C_TITLE, 1))
                    .build());
            col.addContent(ShortcutsTileService.spacer(4));
            if (error != null)
                col.addContent(ShortcutsTileService.text(error, 11, C_FAILED, 1));
            List<TileStore.TodoItem> items = new java.util.ArrayList<>();
            for (TileStore.TodoItem it : todo.items)
                if (!it.done || TileStore.todoShowDone)
                    items.add(it);
            // Une tuile ne défile pas : au-delà de 4 lignes, la dernière ouvre la liste qui défile
            int shown = items.size() > MAX_ROWS ? MAX_ROWS - 1 : items.size();
            for (int i = 0; i < shown; i++)
                col.addContent(row(items.get(i)));
            if (items.size() > shown)
                col.addContent(new LayoutElementBuilders.Box.Builder()
                        .setWidth(dp(ROW_WIDTH_DP))
                        .setHeight(dp(BOX_DP + 4))
                        .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                                .setClickable(new ModifiersBuilders.Clickable.Builder()
                                        .setId("more")
                                        .setOnClick(launchList())
                                        .build())
                                .build())
                        .addContent(ShortcutsTileService.text("Tout voir (+" + (items.size() - shown) + ") ›",
                                14, C_TITLE, 1))
                        .build());
            if (!todo.loaded)
                col.addContent(ShortcutsTileService.text("Chargement…", 13, 0xFFAAAAAA, 1));
            else if (items.isEmpty())
                col.addContent(ShortcutsTileService.text("Rien à faire", 14, 0xFFAAAAAA, 1));
        }
        col.addContent(ShortcutsTileService.spacer(6));
        LayoutElementBuilders.Row.Builder buttons = new LayoutElementBuilders.Row.Builder()
                .addContent(addButton());
        if (todo != null && todo.doneCount > 0)
            buttons.addContent(new LayoutElementBuilders.Spacer.Builder().setWidth(dp(12)).build())
                    .addContent(showDoneButton(todo.doneCount));
        col.addContent(buttons.build());
        return new LayoutElementBuilders.Box.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setPadding(new ModifiersBuilders.Padding.Builder()
                                .setTop(dp(22)).setBottom(dp(14)).setStart(dp(10)).setEnd(dp(10)).build())
                        .build())
                .addContent(col.build())
                .build();
    }

    /** ○ ou ✓ (appui : coche ou décoche), puis le texte (appui : écran d'édition) */
    private LayoutElementBuilders.LayoutElement row(TileStore.TodoItem it) {
        String key = TileStore.todoKey(it.uid);
        ModifiersBuilders.Modifiers.Builder box = new ModifiersBuilders.Modifiers.Builder()
                .setBackground(new ModifiersBuilders.Background.Builder()
                        .setColor(argb(it.done ? 0xFF1A3A26 : 0xFF000000))
                        .setCorner(new ModifiersBuilders.Corner.Builder().setRadius(dp(BOX_DP / 2f)).build())
                        .build())
                .setBorder(new ModifiersBuilders.Border.Builder()
                        .setWidth(dp(2))
                        .setColor(argb(TileStore.hasFailed(key) ? C_FAILED
                                : TileStore.isPending(key) ? C_PENDING
                                : it.done ? C_BOX_DONE : C_BOX_BORDER))
                        .build())
                .setClickable(new ModifiersBuilders.Clickable.Builder()
                        .setId((it.done ? UNCHECK_PREFIX : CHECK_PREFIX) + it.uid)
                        .setOnClick(new ActionBuilders.LoadAction.Builder().build())
                        .build());
        LayoutElementBuilders.Box.Builder circle = new LayoutElementBuilders.Box.Builder()
                .setWidth(dp(BOX_DP))
                .setHeight(dp(BOX_DP))
                .setModifiers(box.build());
        if (it.done)
            circle.addContent(ShortcutsTileService.text("✓", 13, C_BOX_DONE, 1));

        LayoutElementBuilders.Text label = new LayoutElementBuilders.Text.Builder()
                .setText(it.summary)
                .setMaxLines(1)
                .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                        .setSize(androidx.wear.protolayout.DimensionBuilders.sp(15))
                        .setColor(argb(it.done ? C_DONE : C_TODO))
                        .build())
                .build();
        LayoutElementBuilders.Box text = new LayoutElementBuilders.Box.Builder()
                .setWidth(dp(ROW_WIDTH_DP - BOX_DP - 6))
                .setHeight(dp(BOX_DP + 4))
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setClickable(new ModifiersBuilders.Clickable.Builder()
                                .setId("e:" + it.uid)
                                .setOnClick(launch(TodoActivity.MODE_EDIT, it))
                                .build())
                        .build())
                .addContent(label)
                .build();
        return new LayoutElementBuilders.Row.Builder()
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(circle.build())
                .addContent(new LayoutElementBuilders.Spacer.Builder().setWidth(dp(6)).build())
                .addContent(text)
                .build();
    }

    private LayoutElementBuilders.LayoutElement addButton() {
        return new LayoutElementBuilders.Box.Builder()
                .setWidth(dp(36))
                .setHeight(dp(36))
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setBackground(new ModifiersBuilders.Background.Builder()
                                .setColor(argb(0xFF3A2A55))
                                .setCorner(new ModifiersBuilders.Corner.Builder().setRadius(dp(18)).build())
                                .build())
                        .setClickable(new ModifiersBuilders.Clickable.Builder()
                                .setId("add")
                                .setOnClick(launch(TodoActivity.MODE_ADD, null))
                                .build())
                        .build())
                .addContent(ShortcutsTileService.text("+", 22, 0xFFFFFFFF, 1))
                .build();
    }

    /** ✓ et le nombre de tâches faites : les affiche ou les masque (cerclé de vert si affichées) */
    private LayoutElementBuilders.LayoutElement showDoneButton(int count) {
        boolean shown = TileStore.todoShowDone;
        ModifiersBuilders.Modifiers.Builder mods = new ModifiersBuilders.Modifiers.Builder()
                .setBackground(new ModifiersBuilders.Background.Builder()
                        .setColor(argb(shown ? 0xFF1A3A26 : 0xFF2A2A35))
                        .setCorner(new ModifiersBuilders.Corner.Builder().setRadius(dp(18)).build())
                        .build())
                .setClickable(new ModifiersBuilders.Clickable.Builder()
                        .setId(SHOW_DONE)
                        .setOnClick(new ActionBuilders.LoadAction.Builder().build())
                        .build());
        if (shown)
            mods.setBorder(new ModifiersBuilders.Border.Builder()
                    .setWidth(dp(2)).setColor(argb(C_BOX_DONE)).build());
        return new LayoutElementBuilders.Box.Builder()
                .setWidth(dp(36))
                .setHeight(dp(36))
                .setModifiers(mods.build())
                .addContent(ShortcutsTileService.text("✓" + (count > 9 ? "9+" : String.valueOf(count)),
                        12, shown ? C_BOX_DONE : 0xFFAAAAAA, 1))
                .build();
    }

    /** Liste entière, qui défile (TodoListActivity) */
    private ActionBuilders.Action launchList() {
        return new ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(getPackageName())
                        .setClassName(TodoListActivity.class.getName())
                        .build())
                .build();
    }

    private ActionBuilders.Action launch(String mode, TileStore.TodoItem it) {
        ActionBuilders.AndroidActivity.Builder act = new ActionBuilders.AndroidActivity.Builder()
                .setPackageName(getPackageName())
                .setClassName(TodoActivity.class.getName())
                .addKeyToExtraMapping(TodoActivity.EXTRA_MODE, string(mode));
        if (it != null)
            act.addKeyToExtraMapping(TodoActivity.EXTRA_UID, string(it.uid))
                    .addKeyToExtraMapping(TodoActivity.EXTRA_SUMMARY, string(it.summary));
        return new ActionBuilders.LaunchAction.Builder().setAndroidActivity(act.build()).build();
    }

    private static ActionBuilders.AndroidStringExtra string(String v) {
        return new ActionBuilders.AndroidStringExtra.Builder().setValue(v).build();
    }
}
