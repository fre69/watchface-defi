package com.defi.watchface.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Lien permanent avec Home Assistant : un WebSocket abonné à quelques entités,
 * dont l'état est relayé à la montre par la DataLayer (/ha/weather, /ha/car).
 * Vit dans le processus de l'appli, que le NotificationListenerService garde
 * en vie ; se reconnecte seul (coupure, erreur, réseau retrouvé).
 * Tout l'état est manipulé sur un seul fil (handler).
 */
public final class HaLink {

    private static final String TAG = "DefiHa";

    static final String PREFS = "ha";
    static final String PREF_URL = "url";
    static final String PREF_TOKEN = "token";
    /** Entités de la tuile Raccourcis, une par ligne : « light.salon » ou « light.salon | Salon » */
    static final String PREF_TILE_ENTITIES = "tile_entities";
    /**
     * Modèles des tuiles Modèle 1 à 4 : clé + numéro (« tile_template_1 »). Un modèle Jinja,
     * ou un simple entity_id, dont on affiche alors l'attribut « texte » (sinon l'état)
     */
    static final String PREF_TILE_TEMPLATE = "tile_template_";
    static final int TEMPLATE_SLOTS = 4;
    /** Entité climate de la tuile Thermostat (« climate.cuisine ») */
    static final String PREF_TILE_CLIMATE = "tile_climate";
    /** Entité camera de la tuile Caméra : image téléchargée seulement quand la tuile s'affiche */
    static final String PREF_TILE_CAMERA = "tile_camera";

    /** Résultat d'une commande venue de la montre (basculer une entité, question à Assist) */
    public interface Callback {
        /** more : Home Assistant attend une suite (question en retour, continue_conversation) */
        void done(boolean ok, String text, boolean more);
    }

    // --- Entités suivies : les mêmes que les templates sensor.montre_meteo et
    // sensor.montre_bus_chemin de Home Assistant (template.yaml), mêmes règles ---
    private static final String E_TEMP = "sensor.esp_meteo_temperature";
    private static final String E_HUMID = "sensor.esp_meteo_relative_humidity";
    private static final String E_WEATHER = "weather.barcelonne";
    private static final String E_RAIN = "sensor.barcelonne_rain_chance";
    private static final String E_NEXT_RAIN = "sensor.barcelonne_next_rain";
    private static final String E_ALERT = "sensor.26_weather_alert";
    private static final String E_CAR = "image.chemin_car";
    /** « Véhicule — sens — HH:MM », écrit par l'automatisation frigate_chemin_event */
    private static final String E_CAR_LABEL = "input_text.derniere_voiture";
    private static final String E_CAR_PRESENT = "binary_sensor.chemin_car_occupancy";
    /** Agendas affichés sur le cadran (rendez-vous du jour) */
    private static final String[] CALENDARS = { "calendar.nautile", "calendar.francois_r",
            "calendar.famille", "calendar.frevillet26_gmail_com", "calendar.anniversaires",
            "calendar.jours_feries_en_france" };
    private static final String[] ENTITIES = { E_TEMP, E_HUMID, E_WEATHER, E_RAIN, E_NEXT_RAIN,
            E_ALERT, E_CAR, E_CAR_LABEL, E_CAR_PRESENT, CALENDARS[0], CALENDARS[1], CALENDARS[2],
            CALENDARS[3], CALENDARS[4], CALENDARS[5] };

    /** Phénomènes de vigilance Météo-France, dans l'ordre du template montre_meteo */
    private static final String[] PHENOMENA = { "Pluie-inondation", "Inondation", "Orages",
            "Vent violent", "Neige-verglas", "Canicule", "Grand-froid", "Avalanches",
            "Vagues-submersion" };

    private static final String WEATHER_PATH = "/ha/weather";
    private static final String CAR_PATH = "/ha/car";
    private static final String AGENDA_PATH = "/ha/agenda";
    private static final String TILES_PATH = "/ha/tiles";
    private static final String THERMOSTAT_PATH = "/ha/thermostat";
    private static final String CAMERA_PATH = "/ha/camera";
    /** Côté de l'image de la caméra envoyée à la montre (la tuile l'étire à l'écran) */
    private static final int CAMERA_MAX_PX = 320;
    /** Appuis successifs sur + ou − : la consigne visée sert de base pendant ce délai */
    private static final long CLIMATE_PENDING_MS = 4_000L;
    /** Suivi du numéro de l'emplacement : /ha/template/1 à /ha/template/4 */
    private static final String TEMPLATE_PATH = "/ha/template/";
    /** Commande venue de la montre alors que le lien est coupé : abandonnée après ce délai */
    private static final long COMMAND_WAIT_MS = 10_000L;
    /**
     * Tuiles : envois non urgents (aucun réveil de la montre), sauf pendant la minute qui
     * suit l'affichage d'une tuile et les 10 s qui suivent un appui, où l'on suit en direct
     */
    private static final long LIVE_AFTER_VIEW_MS = 60_000L;
    private static final long LIVE_AFTER_PRESS_MS = 10_000L;
    /** Assist garde le fil de la conversation (« et dans la cuisine ? ») pendant 5 min */
    private static final long CONVERSATION_KEEP_MS = 5 * 60_000L;
    /** Événements du jour redemandés au plus tard toutes les 15 min (et à chaque changement d'agenda) */
    private static final long AGENDA_REFRESH_MS = 15 * 60_000L;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    // La température bouge sans cesse : un envoi toutes les 5 min au plus.
    // Un envoi toutes les 30 min au moins, même sans changement : la montre
    // juge la fraîcheur des valeurs à leur date d'envoi
    private static final long WEATHER_MIN_INTERVAL_MS = 5 * 60_000L;
    private static final long WEATHER_HEARTBEAT_MS = 30 * 60_000L;
    private static final long RECONNECT_MIN_MS = 5_000L;
    private static final long RECONNECT_MAX_MS = 5 * 60_000L;
    /** Côté le plus long de la photo envoyée (vignette et vue agrandie) */
    private static final int CAR_MAX_PX = 320;

    private static HaLink instance;

    public static synchronized HaLink get(Context ctx) {
        if (instance == null)
            instance = new HaLink(ctx.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler handler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder()
            .pingInterval(60, TimeUnit.SECONDS)
            .build();

    private boolean started;
    private WebSocket socket;
    /** Incrémenté à chaque (re)connexion : les rappels d'un ancien socket sont ignorés */
    private int generation;
    private boolean authenticated;
    private long reconnectDelay = RECONNECT_MIN_MS;
    /** État compressé de subscribe_entities : {"s": état, "a": attributs, "lc": …} */
    private final Map<String, JSONObject> states = new HashMap<>();

    private String lastWeatherSig;
    private String lastAlertSig;
    private long lastWeatherSentAt;
    private boolean weatherPending;
    private String lastCarSent;
    private String lastCarPicture;
    private Asset carAsset;
    private long carPassedAt;
    /** Identifiant du prochain message WebSocket (1 : abonnement) */
    private int nextId = 2;
    private int agendaRequestId = -1;
    private long lastAgendaFetch;
    private LocalDate lastAgendaDay;
    private String lastAgendaSig;

    /** Entités de la tuile : { entity_id, nom choisi (peut être vide) }, relues à chaque connexion */
    private List<String[]> tileEntities = new ArrayList<>();
    private String lastTilesSig;
    /** Icônes déjà dessinées, par « nom|couleur » : redessinées seulement si elles changent */
    private final Map<String, Asset> iconCache = new HashMap<>();
    /** Côté de l'icône dessinée (26 dp sur l'écran 320 dpi de la montre) */
    private static final int ICON_PX = 52;
    /** Identifiant de l'abonnement render_template de chaque emplacement (-1 : aucun) */
    private final int[] templateSubIds = { -1, -1, -1, -1 };
    private final String[] lastTemplateSent = new String[TEMPLATE_SLOTS];
    /** Dernier résultat de chaque modèle, renvoyé quand la montre affiche une tuile */
    private final String[] currentTemplate = { "", "", "", "" };
    /** Jusqu'à quand les envois des tuiles sont urgents (tuile affichée, appui récent) */
    private long tilesLiveUntil;
    private String climateEntity = "";
    private String lastThermostatSig;
    /** Consigne demandée par le dernier appui, avant que Home Assistant ne la confirme */
    private double climatePendingTarget = Double.NaN;
    private long climatePendingAt;
    /** Réponses attendues aux commandes de la montre, par identifiant de message */
    private final Map<Integer, Callback> pendingCommands = new HashMap<>();
    /** Commandes arrivées pendant une coupure, lancées à la reconnexion */
    private final List<Runnable> waitingCommands = new ArrayList<>();
    /** Questions à Assist en cours : la réponse arrive en événements du pipeline, pas en result */
    private final Map<Integer, Callback> pendingPipelines = new HashMap<>();
    private String conversationId;
    private long conversationAt;

    private volatile String status = "Non configuré";
    private Runnable statusListener;

    private HaLink(Context app) {
        this.app = app;
        HandlerThread thread = new HandlerThread("ha-link");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /** Démarre le lien (sans effet s'il tourne déjà) */
    public void start() {
        handler.post(() -> {
            if (started)
                return;
            started = true;
            ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    // Réseau retrouvé : inutile d'attendre la fin du délai de reconnexion
                    handler.post(() -> {
                        if (socket == null) {
                            handler.removeCallbacks(reconnect);
                            reconnectDelay = RECONNECT_MIN_MS;
                            connect();
                        }
                    });
                }
            }, handler);
            connect();
            handler.postDelayed(heartbeat, WEATHER_HEARTBEAT_MS);
        });
    }

    /** Reconnexion avec la configuration enregistrée (URL ou jeton modifié) */
    public void restart() {
        handler.post(() -> {
            closeSocket();
            states.clear();
            lastCarSent = null;
            lastCarPicture = null;
            carAsset = null;
            // Tuiles peut-être reconfigurées : tout renvoyer à la montre
            lastTilesSig = null;
            lastThermostatSig = null;
            java.util.Arrays.fill(lastTemplateSent, null);
            reconnectDelay = RECONNECT_MIN_MS;
            if (started)
                connect();
        });
        start();
    }

    public String getStatus() {
        return status;
    }

    /** Appelé sur le fil principal à chaque changement d'état de la connexion */
    public void setStatusListener(Runnable listener) {
        mainHandler.post(() -> statusListener = listener);
    }

    private void setStatus(String s) {
        status = s;
        mainHandler.post(() -> {
            if (statusListener != null)
                statusListener.run();
        });
    }

    // =========================================================================
    // Connexion
    // =========================================================================

    private final Runnable reconnect = this::connect;

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            publishWeather();
            maybeFetchAgenda();
            handler.postDelayed(this, WEATHER_HEARTBEAT_MS);
        }
    };

    private SharedPreferences prefs() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String baseUrl() {
        String url = prefs().getString(PREF_URL, "").trim();
        while (url.endsWith("/"))
            url = url.substring(0, url.length() - 1);
        // « ha.exemple.fr » saisi sans protocole : HTTPS
        if (!url.isEmpty() && !url.matches("(?i)^https?://.*"))
            url = "https://" + url;
        return url;
    }

    private String token() {
        return prefs().getString(PREF_TOKEN, "").trim();
    }

    private void connect() {
        handler.removeCallbacks(reconnect);
        closeSocket();
        String base = baseUrl();
        if (base.isEmpty() || token().isEmpty()) {
            setStatus("Non configuré");
            return;
        }
        final int gen = generation;
        setStatus("Connexion…");
        Request request;
        try {
            request = new Request.Builder().url(base + "/api/websocket").build();
        } catch (IllegalArgumentException e) {
            setStatus("Adresse invalide");
            return;
        }
        socket = http.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket ws, String text) {
                handler.post(() -> onText(gen, text));
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                handler.post(() -> onLost(gen, "fermée par le serveur"));
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                handler.post(() -> onLost(gen, t.getClass().getSimpleName()));
            }
        });
    }

    private void closeSocket() {
        generation++;
        authenticated = false;
        java.util.Arrays.fill(templateSubIds, -1);
        failPendingCommands();
        if (socket != null) {
            socket.close(1000, null);
            socket = null;
        }
    }

    private void onLost(int gen, String why) {
        if (gen != generation)
            return;
        socket = null;
        authenticated = false;
        java.util.Arrays.fill(templateSubIds, -1);
        failPendingCommands();
        Log.d(TAG, "Connexion perdue (" + why + "), nouvel essai dans " + reconnectDelay / 1000 + " s");
        setStatus("Déconnecté (" + why + ") — nouvel essai dans " + reconnectDelay / 1000 + " s");
        handler.postDelayed(reconnect, reconnectDelay);
        reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_MS);
    }

    private void onText(int gen, String text) {
        if (gen != generation || socket == null)
            return;
        try {
            JSONObject msg = new JSONObject(text);
            switch (msg.optString("type")) {
                case "auth_required":
                    socket.send(new JSONObject()
                            .put("type", "auth")
                            .put("access_token", token()).toString());
                    break;
                case "auth_ok": {
                    authenticated = true;
                    reconnectDelay = RECONNECT_MIN_MS;
                    tileEntities = readTileEntities();
                    climateEntity = prefs().getString(PREF_TILE_CLIMATE, "").trim().toLowerCase(Locale.ROOT);
                    JSONArray ids = new JSONArray(ENTITIES);
                    for (String[] e : tileEntities)
                        ids.put(e[0]);
                    if (climateEntity.contains("."))
                        ids.put(climateEntity);
                    socket.send(new JSONObject()
                            .put("id", 1)
                            .put("type", "subscribe_entities")
                            .put("entity_ids", ids).toString());
                    subscribeTemplates();
                    setStatus("Connecté");
                    fetchAgenda();
                    runWaitingCommands();
                    break;
                }
                case "auth_invalid":
                    // Inutile d'insister avec un jeton refusé : attendre une nouvelle configuration
                    closeSocket();
                    setStatus("Jeton refusé par Home Assistant");
                    break;
                case "event": {
                    JSONObject ev = msg.getJSONObject("event");
                    if (pendingPipelines.containsKey(msg.optInt("id"))) {
                        onPipelineEvent(msg.optInt("id"), ev);
                        break;
                    }
                    int slot = templateSlot(msg.optInt("id"));
                    if (slot >= 0) {
                        onTemplate(slot, ev);
                        break;
                    }
                    applyEvent(ev);
                    publishTiles();
                    publishThermostat();
                    publishWeather();
                    publishCar();
                    // Agenda modifié (événement ajouté, commencé, fini) : aussitôt
                    if (touchesCalendar(ev))
                        fetchAgenda();
                    else
                        maybeFetchAgenda();
                    break;
                }
                case "result":
                    if (msg.optInt("id") == agendaRequestId) {
                        onAgenda(msg);
                    } else if (pendingCommands.containsKey(msg.optInt("id"))) {
                        onCommandResult(msg);
                    } else if (pendingPipelines.containsKey(msg.optInt("id"))) {
                        // Pipeline lancé : la réponse suit en événements ; seul un refus finit ici
                        if (!msg.optBoolean("success", false)) {
                            JSONObject err = msg.optJSONObject("error");
                            Callback cb = pendingPipelines.remove(msg.optInt("id"));
                            cb.done(false, err != null ? err.optString("message", "Refusé") : "Refusé", false);
                        }
                    } else if (!msg.optBoolean("success", true)) {
                        Log.w(TAG, "Abonnement refusé : " + msg.optJSONObject("error"));
                    }
                    break;
                default:
                    break;
            }
        } catch (JSONException e) {
            Log.w(TAG, "Message illisible", e);
        }
    }

    /** Applique un événement subscribe_entities : a = ajout, c = différence, r = retrait */
    private void applyEvent(JSONObject ev) throws JSONException {
        JSONObject added = ev.optJSONObject("a");
        if (added != null) {
            for (Iterator<String> it = added.keys(); it.hasNext();) {
                String id = it.next();
                states.put(id, added.getJSONObject(id));
            }
        }
        JSONObject changed = ev.optJSONObject("c");
        if (changed != null) {
            for (Iterator<String> it = changed.keys(); it.hasNext();) {
                String id = it.next();
                JSONObject cur = states.get(id);
                if (cur == null)
                    continue;
                JSONObject diff = changed.getJSONObject(id);
                JSONObject attrs = cur.optJSONObject("a");
                if (attrs == null) {
                    attrs = new JSONObject();
                    cur.put("a", attrs);
                }
                JSONObject plus = diff.optJSONObject("+");
                if (plus != null) {
                    for (Iterator<String> k = plus.keys(); k.hasNext();) {
                        String key = k.next();
                        if (!"a".equals(key)) {
                            cur.put(key, plus.get(key));
                            continue;
                        }
                        JSONObject pa = plus.getJSONObject("a");
                        for (Iterator<String> ka = pa.keys(); ka.hasNext();) {
                            String attr = ka.next();
                            attrs.put(attr, pa.get(attr));
                        }
                    }
                }
                JSONObject minus = diff.optJSONObject("-");
                JSONArray removedAttrs = minus != null ? minus.optJSONArray("a") : null;
                if (removedAttrs != null) {
                    for (int i = 0; i < removedAttrs.length(); i++)
                        attrs.remove(removedAttrs.getString(i));
                }
            }
        }
        JSONArray removed = ev.optJSONArray("r");
        if (removed != null) {
            for (int i = 0; i < removed.length(); i++)
                states.remove(removed.getString(i));
        }
    }

    // =========================================================================
    // Envoi à la montre
    // =========================================================================

    private String state(String id) {
        JSONObject s = states.get(id);
        return s != null ? s.optString("s", "") : "";
    }

    private JSONObject attrs(String id) {
        JSONObject s = states.get(id);
        JSONObject a = s != null ? s.optJSONObject("a") : null;
        return a != null ? a : new JSONObject();
    }

    /** Valeur numérique d'un état, NaN si indisponible */
    private double num(String id) {
        try {
            return Double.parseDouble(state(id));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static boolean isAlertLevel(String level) {
        return "Jaune".equals(level) || "Orange".equals(level) || "Rouge".equals(level);
    }

    /**
     * Minutes avant la première échéance de l'heure à venir où Météo-France
     * annonce autre chose que du sec (-1 : rien), comme le template montre_meteo
     */
    private int rainInMinutes() {
        JSONObject f = attrs(E_NEXT_RAIN).optJSONObject("1_hour_forecast");
        if (f == null)
            return -1;
        int first = -1;
        // Clés « 0 min », « 5 min »… : l'ordre d'un JSONObject n'est pas garanti
        for (Iterator<String> it = f.keys(); it.hasNext();) {
            String key = it.next();
            String v = f.optString(key);
            if ("Temps sec".equals(v) || "Données indisponibles".equals(v))
                continue;
            try {
                int m = Integer.parseInt(key.replaceAll("[^0-9]", ""));
                if (first < 0 || m < first)
                    first = m;
            } catch (NumberFormatException ignored) {
            }
        }
        return first;
    }

    private void publishWeather() {
        if (!authenticated || weatherPending)
            return;
        JSONObject w = attrs(E_WEATHER);
        // Station de la maison ; prévision Météo-France si elle ne répond pas
        double temp = num(E_TEMP);
        if (Double.isNaN(temp))
            temp = w.optDouble("temperature", Double.NaN);
        double humid = num(E_HUMID);
        if (Double.isNaN(humid))
            humid = w.optDouble("humidity", Double.NaN);
        double feels = w.optDouble("apparent_temperature", Double.NaN);
        double wind = w.optDouble("wind_speed", Double.NaN);
        double rain = num(E_RAIN);
        int rainIn = rainInMinutes();

        String level = state(E_ALERT);
        List<String> phenomena = new ArrayList<>();
        if (isAlertLevel(level)) {
            JSONObject a = attrs(E_ALERT);
            for (String p : PHENOMENA) {
                if (isAlertLevel(a.optString(p)))
                    phenomena.add(p);
            }
        } else {
            level = "";
        }
        String phenomenaTxt = String.join(", ", phenomena);

        // Signature des valeurs telles que la montre les affiche (arrondies)
        String sig = String.format(Locale.ROOT, "%.1f|%.0f|%.0f|%.0f|%.0f|%d",
                temp, humid, feels, wind, rain, rainIn);
        String alertSig = level + "|" + phenomenaTxt;
        long now = System.currentTimeMillis();
        boolean alertChanged = !alertSig.equals(lastAlertSig);
        if (!alertChanged && sig.equals(lastWeatherSig)
                && now - lastWeatherSentAt < WEATHER_HEARTBEAT_MS)
            return;
        long wait = lastWeatherSentAt + WEATHER_MIN_INTERVAL_MS - now;
        if (!alertChanged && wait > 0) {
            // Trop tôt : envoyer les valeurs du moment à la fin de l'intervalle
            weatherPending = true;
            handler.postDelayed(() -> {
                weatherPending = false;
                publishWeather();
            }, wait);
            return;
        }

        PutDataMapRequest req = PutDataMapRequest.create(WEATHER_PATH);
        DataMap map = req.getDataMap();
        map.putDouble("temp", temp);
        map.putDouble("humidity", humid);
        map.putDouble("feels", feels);
        map.putDouble("wind", wind);
        map.putDouble("rain", rain);
        map.putInt("rainIn", rainIn);
        map.putString("alertLevel", level);
        map.putString("alertPhenomena", phenomenaTxt);
        map.putLong("sentAt", now);
        if (send(req)) {
            lastWeatherSig = sig;
            lastAlertSig = alertSig;
            lastWeatherSentAt = now;
        }
    }

    // =========================================================================
    // Agenda : événements du jour (calendar.get_events, plus complet que l'état
    // des entités, qui n'expose qu'un événement par agenda)
    // =========================================================================

    private static boolean touchesCalendar(JSONObject ev) {
        for (String part : new String[] { "a", "c" }) {
            JSONObject o = ev.optJSONObject(part);
            if (o == null)
                continue;
            for (Iterator<String> it = o.keys(); it.hasNext();)
                if (it.next().startsWith("calendar."))
                    return true;
        }
        return false;
    }

    /** Redemande au changement de jour, et toutes les 15 min au plus tard */
    private void maybeFetchAgenda() {
        if (!LocalDate.now().equals(lastAgendaDay)
                || System.currentTimeMillis() - lastAgendaFetch > AGENDA_REFRESH_MS)
            fetchAgenda();
    }

    private void fetchAgenda() {
        if (!authenticated || socket == null)
            return;
        ZonedDateTime start = LocalDate.now().atStartOfDay(ZoneId.systemDefault());
        try {
            agendaRequestId = nextId++;
            lastAgendaFetch = System.currentTimeMillis();
            lastAgendaDay = start.toLocalDate();
            socket.send(new JSONObject()
                    .put("id", agendaRequestId)
                    .put("type", "call_service")
                    .put("domain", "calendar")
                    .put("service", "get_events")
                    .put("target", new JSONObject().put("entity_id", new JSONArray(CALENDARS)))
                    .put("service_data", new JSONObject()
                            .put("start_date_time", start.format(ISO))
                            .put("end_date_time", start.plusDays(1).format(ISO)))
                    .put("return_response", true).toString());
        } catch (JSONException e) {
            Log.w(TAG, "Demande d'agenda impossible", e);
        }
    }

    /** Réponse de get_events : { "calendar.x": { "events": [ {summary, start, end} ] } } */
    private void onAgenda(JSONObject msg) {
        JSONObject result = msg.optJSONObject("result");
        JSONObject response = result != null ? result.optJSONObject("response") : null;
        if (!msg.optBoolean("success", false) || response == null) {
            Log.w(TAG, "Agenda refusé : " + msg.optJSONObject("error"));
            return;
        }
        List<DataMap> events = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Iterator<String> it = response.keys(); it.hasNext();) {
            JSONObject cal = response.optJSONObject(it.next());
            JSONArray list = cal != null ? cal.optJSONArray("events") : null;
            if (list == null)
                continue;
            for (int i = 0; i < list.length(); i++) {
                JSONObject e = list.optJSONObject(i);
                if (e == null)
                    continue;
                String title = e.optString("summary", "").trim();
                String start = e.optString("start", ""), end = e.optString("end", "");
                long s = parseTime(start), en = parseTime(end);
                // Même événement dans deux agendas (famille et perso) : une fois
                String sig = title + "|" + s;
                if (title.isEmpty() || s < 0 || en < 0 || seen.contains(sig))
                    continue;
                seen.add(sig);
                DataMap m = new DataMap();
                m.putString("title", title);
                m.putLong("start", s);
                m.putLong("end", en);
                // « 2026-10-25 » : journée entière ; sinon date et heure
                m.putBoolean("allDay", start.length() == 10);
                events.add(m);
            }
        }
        events.sort((a, b) -> Long.compare(a.getLong("start"), b.getLong("start")));
        String sig = lastAgendaDay + "|" + String.join(";", seen);
        if (sig.equals(lastAgendaSig))
            return;
        PutDataMapRequest req = PutDataMapRequest.create(AGENDA_PATH);
        req.getDataMap().putDataMapArrayList("events", new ArrayList<>(events));
        req.getDataMap().putLong("sentAt", System.currentTimeMillis());
        if (send(req)) {
            lastAgendaSig = sig;
            Log.d(TAG, "Agenda envoyé à la montre : " + events.size() + " événement(s)");
        }
    }

    /** Date seule (journée entière, minuit local) ou date et heure ISO ; -1 si illisible */
    private static long parseTime(String s) {
        try {
            if (s.length() == 10)
                return LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (Exception e) {
            return -1;
        }
    }

    private void publishCar() {
        if (!authenticated)
            return;
        JSONObject car = states.get(E_CAR);
        if (car == null)
            return;
        String s = car.optString("s", "");
        if (s.isEmpty() || "unavailable".equals(s) || "unknown".equals(s))
            return;
        String label = state(E_CAR_LABEL);
        if ("unknown".equals(label) || "unavailable".equals(label))
            label = "";
        boolean present = "on".equals(state(E_CAR_PRESENT));
        String sig = s + "|" + label + "|" + present;
        if (sig.equals(lastCarSent))
            return;

        // Nouvelle photo seulement quand l'image change ; la légende et la
        // présence changent seules, la photo déjà envoyée est réutilisée
        if (!s.equals(lastCarPicture) || carAsset == null) {
            // entity_picture porte un jeton d'accès temporaire : ne jamais le journaliser
            String picture = attrs(E_CAR).optString("entity_picture", "");
            if (picture.isEmpty())
                return;
            Bitmap bmp = download(baseUrl() + picture);
            if (bmp == null)
                return;
            carAsset = toJpeg(scale(bmp, CAR_MAX_PX));
            lastCarPicture = s;
            // lc : changement d'état = nouvelle photo. Pas lu, que le jeton de
            // entity_picture, renouvelé toutes les 5 min, fait avancer
            carPassedAt = (long) (car.optDouble("lc", 0) * 1000);
        }
        PutDataMapRequest req = PutDataMapRequest.create(CAR_PATH);
        req.getDataMap().putAsset("picture", carAsset);
        req.getDataMap().putLong("passedAt", carPassedAt);
        req.getDataMap().putString("label", label);
        req.getDataMap().putBoolean("present", present);
        if (send(req)) {
            lastCarSent = sig;
            Log.d(TAG, "Voiture envoyée à la montre (présente=" + present + ")");
        }
    }

    // =========================================================================
    // Tuiles de la montre : Raccourcis (état des entités choisies) et Modèle
    // =========================================================================

    /** Lignes « entity_id » ou « entity_id | Nom » (virgule acceptée) ; lignes vides et # ignorées */
    private List<String[]> readTileEntities() {
        List<String[]> list = new ArrayList<>();
        for (String line : prefs().getString(PREF_TILE_ENTITIES, "").split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#"))
                continue;
            String id = line, name = "";
            int sep = line.indexOf('|') >= 0 ? line.indexOf('|') : line.indexOf(',');
            if (sep > 0) {
                id = line.substring(0, sep).trim();
                name = line.substring(sep + 1).trim();
            }
            if (id.contains("."))
                list.add(new String[] { id.toLowerCase(Locale.ROOT), name });
        }
        return list;
    }

    /** États « allumés » : la tuile les dessine en couleur */
    private static boolean isActive(String state) {
        switch (state) {
            case "on": case "open": case "opening": case "unlocked": case "playing":
            case "home": case "heat": case "cool": case "heat_cool": case "auto": case "dry":
            case "fan_only": case "cleaning": case "active":
                return true;
            default:
                return state.startsWith("armed_");
        }
    }

    /** Domaines sans état allumé/éteint : un appui lance une action */
    private static boolean isStateless(String domain) {
        return "scene".equals(domain) || "script".equals(domain) || "button".equals(domain)
                || "input_button".equals(domain);
    }

    /**
     * Envoie l'état des entités de la tuile, seulement s'il a changé : la montre
     * ne se réveille que pour un changement visible (nom, état, disponibilité)
     */
    private void publishTiles() {
        if (!authenticated)
            return;
        ArrayList<DataMap> list = new ArrayList<>();
        StringBuilder sig = new StringBuilder();
        for (String[] e : tileEntities) {
            String id = e[0];
            JSONObject st = states.get(id);
            String domain = id.substring(0, id.indexOf('.'));
            String state = st != null ? st.optString("s", "") : "";
            String name = !e[1].isEmpty() ? e[1] : attrs(id).optString("friendly_name", id);
            boolean available = st != null && !"unavailable".equals(state) && !"unknown".equals(state);
            boolean active = available && isActive(state);
            // Même couleur que le texte de la tuile : noir sur le jaune d'une entité allumée
            int color = !available && !isStateless(domain) ? 0xFF666670 : active ? 0xFF000000 : 0xFFFFFFFF;
            String icon = attrs(id).optString("icon", "");
            if (icon.isEmpty())
                icon = MdiIcons.defaultIcon(domain, state, active);
            String iconKey = icon + "|" + Integer.toHexString(color);
            Asset iconAsset = iconCache.get(iconKey);
            if (iconAsset == null && !iconCache.containsKey(iconKey)) {
                byte[] png = MdiIcons.png(app, icon, color, ICON_PX);
                iconAsset = png != null ? Asset.createFromBytes(png) : null;
                iconCache.put(iconKey, iconAsset);
            }
            DataMap m = new DataMap();
            m.putString("id", id);
            m.putString("name", name);
            m.putString("state", state);
            m.putBoolean("active", active);
            if (iconAsset != null) {
                m.putString("iconKey", iconKey);
                m.putAsset("icon", iconAsset);
            }
            m.putBoolean("available", available || (st != null && isStateless(domain)));
            m.putBoolean("stateless", isStateless(domain));
            list.add(m);
            sig.append(id).append('=').append(name).append('/').append(state).append('/')
                    .append(iconKey).append(';');
        }
        if (sig.toString().equals(lastTilesSig))
            return;
        PutDataMapRequest req = PutDataMapRequest.create(TILES_PATH);
        req.getDataMap().putDataMapArrayList("entities", list);
        req.getDataMap().putLong("sentAt", System.currentTimeMillis());
        if (send(req, tilesLive())) {
            lastTilesSig = sig.toString();
            Log.d(TAG, "Tuile Raccourcis envoyée : " + list.size() + " entité(s)");
        }
    }

    /** Emplacement (0 à 3) dont l'abonnement porte cet identifiant, -1 sinon */
    private int templateSlot(int id) {
        for (int i = 0; i < TEMPLATE_SLOTS; i++)
            if (templateSubIds[i] == id)
                return i;
        return -1;
    }

    /** Un entity_id seul devient le modèle de son attribut « texte » (tuiles HA d'avant) */
    static String expandTemplate(String raw) {
        String t = raw.trim();
        if (t.matches("[a-z_]+\\.[a-z0-9_]+"))
            return "{{ state_attr('" + t + "', 'texte') or states('" + t + "') }}";
        return t;
    }

    /**
     * render_template : Home Assistant renvoie le résultat à chaque changement
     * des entités dont le modèle dépend, sans aucune interrogation périodique
     */
    private void subscribeTemplates() throws JSONException {
        for (int i = 0; i < TEMPLATE_SLOTS; i++) {
            String template = expandTemplate(prefs().getString(PREF_TILE_TEMPLATE + (i + 1), ""));
            if (template.isEmpty()) {
                publishTemplate(i, "");
                continue;
            }
            templateSubIds[i] = nextId++;
            socket.send(new JSONObject()
                    .put("id", templateSubIds[i])
                    .put("type", "render_template")
                    .put("template", template)
                    .put("report_errors", true).toString());
        }
    }

    private void onTemplate(int slot, JSONObject ev) {
        if (ev.has("error")) {
            Log.w(TAG, "Modèle " + (slot + 1) + " refusé : " + ev.optString("error"));
            publishTemplate(slot, "⚠ " + ev.optString("error"));
            return;
        }
        Object result = ev.opt("result");
        publishTemplate(slot, result == null || result == JSONObject.NULL ? "" : result.toString().trim());
    }

    private void publishTemplate(int slot, String text) {
        currentTemplate[slot] = text;
        if (text.equals(lastTemplateSent[slot]))
            return;
        PutDataMapRequest req = PutDataMapRequest.create(TEMPLATE_PATH + (slot + 1));
        req.getDataMap().putString("text", text);
        req.getDataMap().putLong("sentAt", System.currentTimeMillis());
        if (send(req, tilesLive()))
            lastTemplateSent[slot] = text;
    }

    private boolean tilesLive() {
        return System.currentTimeMillis() < tilesLiveUntil;
    }

    /**
     * La montre affiche une tuile : renvoi immédiat (urgent) de l'état connu, puis
     * suivi en direct pendant une minute. L'heure d'envoi change à chaque fois :
     * la DataLayer transmet donc l'élément même si son contenu n'a pas bougé.
     */
    public void refreshTiles(String kind) {
        handler.post(() -> {
            tilesLiveUntil = System.currentTimeMillis() + LIVE_AFTER_VIEW_MS;
            // Caméra : l'image n'a pas d'état qui change, elle se télécharge à l'affichage
            if ("camera".equals(kind)) {
                publishCamera();
                return;
            }
            lastTilesSig = null;
            publishTiles();
            lastThermostatSig = null;
            publishThermostat();
            for (int i = 0; i < TEMPLATE_SLOTS; i++) {
                lastTemplateSent[i] = null;
                publishTemplate(i, currentTemplate[i]);
            }
        });
    }

    /** Termes français de hvac_action, comme la tuile de l'appli Home Assistant */
    private static String hvacActionLabel(String action, String state) {
        switch (action) {
            case "idle": return "Inactif";
            case "heating": return "Chauffe";
            case "preheating": return "Préchauffe";
            case "cooling": return "Refroidit";
            case "drying": return "Déshumidifie";
            case "fan": return "Ventile";
            case "off": return "Arrêt";
            case "": return "off".equals(state) ? "Arrêt" : state;
            default: return action;
        }
    }

    /** Tuile Thermostat : nom, action en cours, consigne, température mesurée */
    private void publishThermostat() {
        if (!authenticated || !climateEntity.contains("."))
            return;
        JSONObject st = states.get(climateEntity);
        String state = st != null ? st.optString("s", "") : "";
        JSONObject a = attrs(climateEntity);
        boolean available = st != null && !"unavailable".equals(state) && !"unknown".equals(state);
        double target = a.optDouble("temperature", Double.NaN);
        double current = a.optDouble("current_temperature", Double.NaN);
        double step = a.optDouble("target_temp_step", 0.5);
        String name = a.optString("friendly_name", climateEntity);
        String action = hvacActionLabel(a.optString("hvac_action", ""), state);
        // Consigne confirmée : l'appui en attente est soldé
        if (!Double.isNaN(climatePendingTarget) && Math.abs(target - climatePendingTarget) < 0.01)
            climatePendingTarget = Double.NaN;
        String sig = name + "|" + action + "|" + target + "|" + current + "|" + available;
        if (sig.equals(lastThermostatSig))
            return;
        PutDataMapRequest req = PutDataMapRequest.create(THERMOSTAT_PATH);
        DataMap m = req.getDataMap();
        m.putString("name", name);
        m.putString("action", action);
        m.putDouble("target", target);
        m.putDouble("current", current);
        m.putDouble("step", step);
        m.putBoolean("available", available);
        m.putLong("sentAt", System.currentTimeMillis());
        if (send(req, tilesLive()))
            lastThermostatSig = sig;
    }

    /** Tuile Caméra : image du moment, envoyée en urgent (la tuile est à l'écran) */
    private void publishCamera() {
        String camera = prefs().getString(PREF_TILE_CAMERA, "").trim().toLowerCase(Locale.ROOT);
        if (camera.isEmpty() || baseUrl().isEmpty())
            return;
        // camera_proxy accepte le jeton longue durée ; ne jamais journaliser l'URL
        Bitmap bmp = download(baseUrl() + "/api/camera_proxy/" + camera);
        if (bmp == null)
            return;
        PutDataMapRequest req = PutDataMapRequest.create(CAMERA_PATH);
        req.getDataMap().putAsset("picture", toJpeg(scale(bmp, CAMERA_MAX_PX)));
        req.getDataMap().putLong("sentAt", System.currentTimeMillis());
        if (send(req, true))
            Log.d(TAG, "Image de la caméra envoyée à la montre");
    }

    /** + ou − sur la tuile Thermostat : consigne décalée d'un pas (target_temp_step, sinon 0,5) */
    public void climateStep(int direction, Callback cb) {
        handler.post(() -> whenConnected(() -> {
            if (!climateEntity.contains(".")) {
                cb.done(false, "Thermostat non réglé", false);
                return;
            }
            JSONObject a = attrs(climateEntity);
            double target = a.optDouble("temperature", Double.NaN);
            double step = a.optDouble("target_temp_step", 0.5);
            long now = System.currentTimeMillis();
            // Appuis rapprochés : on part de la consigne déjà demandée, pas de l'ancienne
            if (!Double.isNaN(climatePendingTarget) && now - climatePendingAt < CLIMATE_PENDING_MS)
                target = climatePendingTarget;
            if (Double.isNaN(target)) {
                cb.done(false, "Consigne inconnue", false);
                return;
            }
            double next = Math.round((target + direction * step) / step) * step;
            double min = a.optDouble("min_temp", Double.NaN), max = a.optDouble("max_temp", Double.NaN);
            if (!Double.isNaN(min))
                next = Math.max(min, next);
            if (!Double.isNaN(max))
                next = Math.min(max, next);
            climatePendingTarget = next;
            climatePendingAt = now;
            tilesLiveUntil = Math.max(tilesLiveUntil, now + LIVE_AFTER_PRESS_MS);
            try {
                sendCommand(new JSONObject()
                        .put("type", "call_service")
                        .put("domain", "climate")
                        .put("service", "set_temperature")
                        .put("target", new JSONObject().put("entity_id", climateEntity))
                        .put("service_data", new JSONObject().put("temperature", next)), cb);
            } catch (JSONException e) {
                cb.done(false, "Commande illisible", false);
            }
        }, cb));
    }

    // =========================================================================
    // Commandes venues de la montre
    // =========================================================================

    /** Bascule une entité comme le ferait un appui dans l'appli Home Assistant */
    public void toggle(String entityId, Callback cb) {
        handler.post(() -> whenConnected(() -> {
            // Le nouvel état doit revenir aussitôt : la tuile l'attend pour se redessiner
            tilesLiveUntil = Math.max(tilesLiveUntil, System.currentTimeMillis() + LIVE_AFTER_PRESS_MS);
            String domain = entityId.contains(".") ? entityId.substring(0, entityId.indexOf('.')) : "";
            String svcDomain = "homeassistant", service = "toggle";
            switch (domain) {
                case "lock":
                    svcDomain = "lock";
                    service = "locked".equals(state(entityId)) ? "unlock" : "lock";
                    break;
                case "cover":
                    svcDomain = "cover";
                    break;
                case "scene":
                case "script":
                    svcDomain = domain;
                    service = "turn_on";
                    break;
                case "button":
                case "input_button":
                    svcDomain = domain;
                    service = "press";
                    break;
                default:
                    break;
            }
            try {
                JSONObject msg = new JSONObject()
                        .put("type", "call_service")
                        .put("domain", svcDomain)
                        .put("service", service)
                        .put("target", new JSONObject().put("entity_id", entityId));
                sendCommand(msg, cb);
            } catch (JSONException e) {
                cb.done(false, "Commande illisible", false);
            }
        }, cb));
    }

    /**
     * Question à Assist par le pipeline préféré de Home Assistant, étape « intent »
     * seule (texte → réponse), comme l'appli HA : son agent (IA ou intégré), sa
     * langue, ses commandes locales en priorité. conversation/process sans agent_id
     * prendrait l'agent intégré, qui ne suit pas le fil (« éteins-le »).
     */
    public void assist(String text, Callback cb) {
        handler.post(() -> whenConnected(() -> {
            try {
                JSONObject msg = new JSONObject()
                        .put("type", "assist_pipeline/run")
                        .put("start_stage", "intent")
                        .put("end_stage", "intent")
                        .put("input", new JSONObject().put("text", text));
                if (conversationId != null
                        && System.currentTimeMillis() - conversationAt < CONVERSATION_KEEP_MS)
                    msg.put("conversation_id", conversationId);
                int id = nextId++;
                pendingPipelines.put(id, cb);
                socket.send(msg.put("id", id).toString());
            } catch (JSONException e) {
                cb.done(false, "Question illisible", false);
            }
        }, cb));
    }

    /**
     * Événements du pipeline : intent-end porte la réponse (intent_output, comme le
     * résultat de conversation/process) ; error ou run-end sans réponse = échec
     */
    private void onPipelineEvent(int id, JSONObject ev) {
        String type = ev.optString("type");
        JSONObject data = ev.optJSONObject("data");
        if ("intent-end".equals(type)) {
            Callback cb = pendingPipelines.remove(id);
            JSONObject out = data != null ? data.optJSONObject("intent_output") : null;
            deliverConversation(out, cb);
        } else if ("error".equals(type)) {
            Callback cb = pendingPipelines.remove(id);
            String why = data != null ? data.optString("message", "Erreur d'Assist") : "Erreur d'Assist";
            Log.w(TAG, "Assist en erreur : " + why);
            cb.done(false, why, false);
        } else if ("run-end".equals(type)) {
            Callback cb = pendingPipelines.remove(id);
            if (cb != null)
                cb.done(false, "Pas de réponse d'Assist", false);
        }
    }

    /** Réponse d'un agent : { response: { speech: { plain: { speech } } }, conversation_id, continue_conversation } */
    private void deliverConversation(JSONObject result, Callback cb) {
        String speech = "";
        boolean more = false;
        if (result != null) {
            more = result.optBoolean("continue_conversation", false);
            JSONObject response = result.optJSONObject("response");
            JSONObject plain = response != null && response.optJSONObject("speech") != null
                    ? response.optJSONObject("speech").optJSONObject("plain") : null;
            if (plain != null)
                speech = plain.optString("speech", "");
            if (result.has("conversation_id") && !result.isNull("conversation_id")) {
                conversationId = result.optString("conversation_id", null);
                conversationAt = System.currentTimeMillis();
            }
        }
        cb.done(true, speech, more);
    }

    /** Bouton ↺ d'Assist : la prochaine question ouvre une nouvelle conversation */
    public void newConversation() {
        handler.post(() -> {
            conversationId = null;
            conversationAt = 0;
        });
    }

    /** Lance la commande tout de suite, ou à la reconnexion si elle arrive dans les 10 s */
    private void whenConnected(Runnable command, Callback cb) {
        if (authenticated && socket != null) {
            command.run();
            return;
        }
        start();
        waitingCommands.add(command);
        handler.postDelayed(() -> {
            if (waitingCommands.remove(command))
                cb.done(false, "Home Assistant injoignable", false);
        }, COMMAND_WAIT_MS);
    }

    private void runWaitingCommands() {
        List<Runnable> list = new ArrayList<>(waitingCommands);
        waitingCommands.clear();
        for (Runnable r : list)
            r.run();
    }

    private void sendCommand(JSONObject msg, Callback cb) throws JSONException {
        int id = nextId++;
        pendingCommands.put(id, cb);
        socket.send(msg.put("id", id).toString());
    }

    private void onCommandResult(JSONObject msg) {
        Callback cb = pendingCommands.remove(msg.optInt("id"));
        if (!msg.optBoolean("success", false)) {
            JSONObject err = msg.optJSONObject("error");
            String why = err != null ? err.optString("message", "Refusé") : "Refusé";
            Log.w(TAG, "Commande refusée : " + why);
            cb.done(false, why, false);
            return;
        }
        cb.done(true, "", false);
    }

    /** Connexion perdue : les commandes en cours n'auront jamais de réponse */
    private void failPendingCommands() {
        List<Callback> list = new ArrayList<>(pendingCommands.values());
        list.addAll(pendingPipelines.values());
        pendingCommands.clear();
        pendingPipelines.clear();
        for (Callback cb : list)
            cb.done(false, "Connexion à Home Assistant perdue", false);
    }

    private Bitmap download(String url) {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + token())
                .build();
        try (Response response = http.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                Log.w(TAG, "Photo refusée : HTTP " + response.code());
                return null;
            }
            return BitmapFactory.decodeStream(body.byteStream());
        } catch (Exception e) {
            Log.w(TAG, "Téléchargement de la photo impossible", e);
            return null;
        }
    }

    /** Urgent : sans urgence, Google tolère 30 min de retard ; le débit est déjà limité ici */
    private boolean send(PutDataMapRequest req) {
        return send(req, true);
    }

    /**
     * Non urgent : la DataLayer attend que la montre soit réveillée pour autre chose
     * (aucun réveil provoqué), jusqu'à 30 min de retard
     */
    private boolean send(PutDataMapRequest req, boolean urgent) {
        try {
            PutDataRequest request = req.asPutDataRequest();
            if (urgent)
                request.setUrgent();
            Tasks.await(Wearable.getDataClient(app).putDataItem(request), 30, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Envoi à la montre impossible", e);
            return false;
        }
    }

    private static Asset toJpeg(Bitmap bmp) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 75, bos);
        return Asset.createFromBytes(bos.toByteArray());
    }

    private static Bitmap scale(Bitmap src, int maxSize) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxSize && h <= maxSize)
            return src;
        float k = Math.min((float) maxSize / w, (float) maxSize / h);
        return Bitmap.createScaledBitmap(src, Math.round(w * k), Math.round(h * k), true);
    }
}
