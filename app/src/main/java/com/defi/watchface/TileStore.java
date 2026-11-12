package com.defi.watchface;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.wear.tiles.TileService;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Données des tuiles Home Assistant, relayées par l'appli du téléphone (HaLink) :
 * état des entités de la tuile Raccourcis (/ha/tiles) et texte des tuiles
 * Modèle 1 à 4 (/ha/template/1 à 4). Le téléphone ne les envoie que quand elles changent ;
 * la montre ne parle jamais à Home Assistant.
 */
public final class TileStore {
    private static final String TAG = "DefiTiles";
    static final String TILES_PATH = "/ha/tiles";
    static final String TEMPLATE_PATH = "/ha/template/";
    static final int TEMPLATE_SLOTS = 4;
    static final String ACTION_PATH = "/ha/action";
    /** Une tuile s'affiche : le téléphone renvoie aussitôt l'état qu'il connaît */
    static final String TILES_GET_PATH = "/ha/tiles/get";
    static final String THERMOSTAT_PATH = "/ha/thermostat";
    static final String CAMERA_PATH = "/ha/camera";
    static final String CLIMATE_PATH = "/ha/climate";
    /** Image de la caméra mise à la taille de l'écran rond (pixels RGB_565, sans transparence) */
    private static final int CAMERA_PX = 240;
    /**
     * Délai maximal d'une lecture de la DataLayer : sans lui, une pièce jointe pas encore
     * arrivée bloque le fil des tuiles, et plus aucune tuile ne répond
     */
    private static final long AWAIT_S = 5;
    /** Passages répétés d'une tuile à l'autre : une seule demande par période */
    private static final long REFRESH_MIN_INTERVAL_MS = 5_000L;

    /** Appui sans nouvel état reçu au-delà de ce délai : la tuile reprend l'état connu */
    static final long PENDING_MS = 5_000L;
    /** Attente du nouvel état avant de redessiner la tuile après un appui */
    static final long PRESS_WAIT_MS = 2_500L;

    public static final class Entity {
        public final String id;
        public final String name;
        public final String state;
        public final boolean active;
        public final boolean available;
        public final boolean stateless;
        /** Icône MDI dessinée par le téléphone (« mdi:pool|ffffffff »), vide si aucune */
        public final String iconKey;

        Entity(DataMap m) {
            id = m.getString("id", "");
            name = m.getString("name", id);
            state = m.getString("state", "");
            active = m.getBoolean("active", false);
            available = m.getBoolean("available", false);
            stateless = m.getBoolean("stateless", false);
            iconKey = m.getString("iconKey", "");
        }
    }

    private static List<Entity> entities = Collections.emptyList();

    /** Image prête pour la tuile : pixels bruts, ARGB_8888 (icônes) ou RGB_565 (caméra) */
    public static final class Icon {
        public final int width;
        public final int height;
        public final byte[] argb;
        public final boolean rgb565;

        Icon(Bitmap bmp) {
            width = bmp.getWidth();
            height = bmp.getHeight();
            rgb565 = bmp.getConfig() == Bitmap.Config.RGB_565;
            ByteBuffer buf = ByteBuffer.allocate(bmp.getByteCount());
            bmp.copyPixelsToBuffer(buf);
            argb = buf.array();
        }
    }

    /** Tuile Thermostat */
    public static final class Thermostat {
        public final String name;
        public final String action;
        public final double target;
        public final double current;
        public final boolean available;

        Thermostat(DataMap m) {
            name = m.getString("name", "");
            action = m.getString("action", "");
            target = m.getDouble("target", Double.NaN);
            current = m.getDouble("current", Double.NaN);
            available = m.getBoolean("available", false);
        }
    }

    private static Thermostat thermostat;
    private static long climatePressedAt;
    private static long climateFailedAt;
    private static Icon camera;
    private static long cameraAt;
    /** Dernière demande d'état frais, par genre de tuile (« » ou « camera ») */
    private static final Map<String, Long> lastRefresh = new HashMap<>();

    private static Map<String, Icon> icons = new HashMap<>();
    private static final String[] templates = { "", "", "", "" };
    private static boolean loaded;
    /** Appuis en attente du nouvel état : entity_id → heure de l'appui */
    private static final Map<String, Long> pressed = new HashMap<>();
    /** Échecs signalés par le téléphone : entity_id → heure */
    private static final Map<String, Long> failed = new HashMap<>();
    private static final List<SettableFuture<Void>> waiters = new ArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    /** Fil des lectures de la DataLayer pour les tuiles (jamais le fil principal) */
    static final Executor BACKGROUND = Executors.newSingleThreadExecutor();

    private TileStore() {
    }

    public static synchronized List<Entity> getEntities() {
        return entities;
    }

    /** Texte (HTML restreint) de la tuile Modèle n° slot, de 1 à 4 */
    public static synchronized String getTemplate(int slot) {
        return templates[slot - 1];
    }

    public static synchronized Map<String, Icon> getIcons() {
        return icons;
    }

    /** Version des ressources de la tuile : change quand le jeu d'icônes change */
    public static synchronized String iconsVersion() {
        return Integer.toHexString(new TreeMap<>(icons).keySet().hashCode());
    }

    /** Identifiant de ressource d'une icône (les clés contiennent « : » et « | ») */
    public static String iconResId(String key) {
        return "icon_" + Integer.toHexString(key.hashCode());
    }

    public static synchronized Thermostat getThermostat() {
        return thermostat;
    }

    /** + ou − pas encore confirmé par une nouvelle consigne */
    public static synchronized boolean isClimatePending() {
        return System.currentTimeMillis() - climatePressedAt < PENDING_MS;
    }

    public static synchronized boolean hasClimateFailed() {
        return System.currentTimeMillis() - climateFailedAt < PENDING_MS;
    }

    public static synchronized Icon getCamera() {
        return camera;
    }

    /** Version des ressources de la tuile Caméra : change avec l'image */
    public static synchronized String cameraVersion() {
        return Long.toHexString(cameraAt);
    }

    /** Appui en cours, pas encore confirmé par un nouvel état */
    public static synchronized boolean isPending(String id) {
        Long t = pressed.get(id);
        return t != null && System.currentTimeMillis() - t < PENDING_MS;
    }

    /** Échec signalé par le téléphone dans les 5 dernières secondes */
    public static synchronized boolean hasFailed(String id) {
        Long t = failed.get(id);
        return t != null && System.currentTimeMillis() - t < PENDING_MS;
    }

    /**
     * Relit les derniers éléments gardés par la DataLayer (montre redémarrée,
     * appli relancée) ; une seule fois. Bloquant : jamais sur le fil principal.
     */
    static void ensureLoaded(Context ctx) {
        synchronized (TileStore.class) {
            if (loaded)
                return;
            loaded = true;
        }
        long t0 = System.currentTimeMillis();
        try {
            DataItemBuffer items = Tasks.await(Wearable.getDataClient(ctx.getApplicationContext()).getDataItems(),
                    AWAIT_S, TimeUnit.SECONDS);
            try {
                for (DataItem item : items) {
                    // L'image de la caméra est redemandée à l'affichage de sa tuile : inutile de
                    // retarder ici toutes les tuiles (elles partagent le même fil) pour la relire
                    if (CAMERA_PATH.equals(item.getUri().getPath()))
                        continue;
                    apply(ctx, item);
                }
            } finally {
                items.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "Lecture de la DataLayer impossible", e);
        }
        Log.d(TAG, "Données des tuiles relues en " + (System.currentTimeMillis() - t0) + " ms");
    }

    /** Applique un élément reçu ; false s'il ne concerne pas les tuiles */
    static boolean apply(Context ctx, DataItem item) {
        String path = item.getUri().getPath();
        if (TILES_PATH.equals(path)) {
            DataMap m = DataMapItem.fromDataItem(item).getDataMap();
            List<Entity> list = new ArrayList<>();
            Map<String, Icon> newIcons = new HashMap<>();
            Map<String, Icon> known = getIcons();
            ArrayList<DataMap> maps = m.getDataMapArrayList("entities");
            if (maps != null) {
                for (DataMap e : maps) {
                    Entity ent = new Entity(e);
                    list.add(ent);
                    if (ent.iconKey.isEmpty() || newIcons.containsKey(ent.iconKey))
                        continue;
                    // Icône déjà connue : réutilisée ; sinon lue dans l'élément (bloquant, hors fil principal)
                    Icon icon = known.get(ent.iconKey);
                    if (icon == null)
                        icon = loadIcon(ctx, e.getAsset("icon"));
                    if (icon != null)
                        newIcons.put(ent.iconKey, icon);
                }
            }
            synchronized (TileStore.class) {
                icons = newIcons;
                // Un état qui change confirme l'appui (scène, bouton : leur état est l'heure du dernier appui)
                for (Entity e : list) {
                    Entity old = find(entities, e.id);
                    if (old == null || !old.state.equals(e.state))
                        pressed.remove(e.id);
                }
                entities = list;
            }
            changed(ctx, ShortcutsTileService.class);
            return true;
        }
        if (THERMOSTAT_PATH.equals(path)) {
            Thermostat t = new Thermostat(DataMapItem.fromDataItem(item).getDataMap());
            synchronized (TileStore.class) {
                // Consigne changée : le + ou − est confirmé
                if (thermostat == null || thermostat.target != t.target)
                    climatePressedAt = 0;
                thermostat = t;
            }
            changed(ctx, ThermostatTileService.class);
            return true;
        }
        if (CAMERA_PATH.equals(path)) {
            DataMap m = DataMapItem.fromDataItem(item).getDataMap();
            Icon picture = loadCamera(ctx, m.getAsset("picture"));
            if (picture != null) {
                synchronized (TileStore.class) {
                    camera = picture;
                    cameraAt = m.getLong("sentAt", System.currentTimeMillis());
                }
                changed(ctx, CameraTileService.class);
            }
            return true;
        }
        if (path != null && path.startsWith(TEMPLATE_PATH)) {
            int slot;
            try {
                slot = Integer.parseInt(path.substring(TEMPLATE_PATH.length()));
            } catch (NumberFormatException e) {
                return true;
            }
            if (slot < 1 || slot > TEMPLATE_SLOTS)
                return true;
            String text = DataMapItem.fromDataItem(item).getDataMap().getString("text", "");
            synchronized (TileStore.class) {
                templates[slot - 1] = text;
            }
            changed(ctx, TemplateTileService.slotClass(slot));
            return true;
        }
        return false;
    }

    /** Image de la caméra recadrée en carré et réduite, en RGB_565 (moitié moins lourde) */
    private static Icon loadCamera(Context ctx, Asset asset) {
        if (asset == null)
            return null;
        try (InputStream is = Tasks.await(Wearable.getDataClient(ctx.getApplicationContext())
                .getFdForAsset(asset), AWAIT_S, TimeUnit.SECONDS).getInputStream()) {
            Bitmap src = BitmapFactory.decodeStream(is);
            if (src == null)
                return null;
            int side = Math.min(src.getWidth(), src.getHeight());
            Bitmap square = Bitmap.createBitmap(src, (src.getWidth() - side) / 2,
                    (src.getHeight() - side) / 2, side, side);
            Bitmap scaled = Bitmap.createScaledBitmap(square, CAMERA_PX, CAMERA_PX, true);
            return new Icon(scaled.copy(Bitmap.Config.RGB_565, false));
        } catch (Exception e) {
            Log.w(TAG, "Image de la caméra illisible", e);
            return null;
        }
    }

    private static Icon loadIcon(Context ctx, Asset asset) {
        if (asset == null)
            return null;
        try (InputStream is = Tasks.await(Wearable.getDataClient(ctx.getApplicationContext())
                .getFdForAsset(asset), AWAIT_S, TimeUnit.SECONDS).getInputStream()) {
            Bitmap bmp = BitmapFactory.decodeStream(is);
            return bmp != null ? new Icon(bmp.copy(Bitmap.Config.ARGB_8888, false)) : null;
        } catch (Exception e) {
            Log.w(TAG, "Icône illisible", e);
            return null;
        }
    }

    /** Le téléphone n'a pas pu exécuter l'appui (Home Assistant injoignable, refus) */
    static void actionFailed(Context ctx, String id) {
        synchronized (TileStore.class) {
            pressed.remove(id);
            failed.put(id, System.currentTimeMillis());
        }
        changed(ctx, ShortcutsTileService.class);
    }

    private static Entity find(List<Entity> list, String id) {
        for (Entity e : list)
            if (e.id.equals(id))
                return e;
        return null;
    }

    /**
     * Appui sur une entité de la tuile : l'envoie au téléphone et rend une
     * attente qui se termine au nouvel état, ou au bout de 2,5 s
     */
    static ListenableFuture<Void> press(Context ctx, String id) {
        SettableFuture<Void> f = SettableFuture.create();
        synchronized (TileStore.class) {
            pressed.put(id, System.currentTimeMillis());
            failed.remove(id);
            waiters.add(f);
        }
        main.postDelayed(() -> f.set(null), PRESS_WAIT_MS);
        Context app = ctx.getApplicationContext();
        sendToPhone(app, ACTION_PATH, id.getBytes(StandardCharsets.UTF_8), () -> actionFailed(app, id));
        return f;
    }

    /**
     * Tuile affichée (onTileEnterEvent) : demande l'état frais au téléphone. Le
     * téléphone n'envoie sinon les changements qu'en non urgent, sans réveiller
     * la montre ; la tuile se redessine à la réception
     */
    static void requestFresh(Context ctx, String kind) {
        synchronized (TileStore.class) {
            long now = System.currentTimeMillis();
            Long last = lastRefresh.get(kind);
            if (last != null && now - last < REFRESH_MIN_INTERVAL_MS)
                return;
            lastRefresh.put(kind, now);
        }
        sendToPhone(ctx.getApplicationContext(), TILES_GET_PATH, kind.getBytes(StandardCharsets.UTF_8), null);
    }

    /** + (1) ou − (-1) sur la tuile Thermostat ; attente de la nouvelle consigne */
    static ListenableFuture<Void> pressClimate(Context ctx, int direction) {
        SettableFuture<Void> f = SettableFuture.create();
        synchronized (TileStore.class) {
            climatePressedAt = System.currentTimeMillis();
            climateFailedAt = 0;
            waiters.add(f);
        }
        main.postDelayed(() -> f.set(null), PRESS_WAIT_MS);
        Context app = ctx.getApplicationContext();
        sendToPhone(app, CLIMATE_PATH, (direction < 0 ? "-" : "+").getBytes(StandardCharsets.UTF_8),
                () -> climateFailed(app));
        return f;
    }

    static void climateFailed(Context ctx) {
        synchronized (TileStore.class) {
            climatePressedAt = 0;
            climateFailedAt = System.currentTimeMillis();
        }
        changed(ctx, ThermostatTileService.class);
    }

    /** Message au téléphone ; onFail (peut être null) si aucun téléphone ne le reçoit */
    private static void sendToPhone(Context app, String path, byte[] payload, Runnable onFail) {
        new Thread(() -> {
            try {
                Set<Node> nodes = Tasks.await(Wearable.getCapabilityClient(app)
                        .getCapability(PhoneLink.CAPABILITY, CapabilityClient.FILTER_REACHABLE)).getNodes();
                if (nodes.isEmpty()) {
                    Log.w(TAG, "Téléphone injoignable : " + path + " non envoyé");
                    if (onFail != null)
                        onFail.run();
                    return;
                }
                for (Node n : nodes)
                    Tasks.await(Wearable.getMessageClient(app).sendMessage(n.getId(), path, payload));
            } catch (Exception e) {
                Log.w(TAG, path + " non envoyé", e);
                if (onFail != null)
                    onFail.run();
            }
        }).start();
    }

    /**
     * Journalise l'issue d'une demande de tuile : une exception dans la construction
     * d'une tuile ne laisse sinon qu'un « no valid tile data » côté système
     */
    static <T> ListenableFuture<T> logged(String tile, ListenableFuture<T> f) {
        Futures.addCallback(f, new FutureCallback<T>() {
            @Override
            public void onSuccess(T result) {
                Log.d(TAG, tile + " : tuile fournie");
            }

            @Override
            public void onFailure(Throwable t) {
                Log.e(TAG, tile + " : échec de la tuile", t);
            }
        }, MoreExecutors.directExecutor());
        return f;
    }

    /** Redessine la tuile et libère les attentes d'appui */
    private static void changed(Context ctx, Class<? extends TileService> tile) {
        List<SettableFuture<Void>> list;
        synchronized (TileStore.class) {
            list = new ArrayList<>(waiters);
            waiters.clear();
        }
        for (SettableFuture<Void> f : list)
            f.set(null);
        TileService.getUpdater(ctx.getApplicationContext()).requestUpdate(tile);
    }
}
