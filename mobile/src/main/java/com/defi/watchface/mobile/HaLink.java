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
    private static final String[] ENTITIES = { E_TEMP, E_HUMID, E_WEATHER, E_RAIN, E_NEXT_RAIN,
            E_ALERT, E_CAR, E_CAR_LABEL, E_CAR_PRESENT };

    /** Phénomènes de vigilance Météo-France, dans l'ordre du template montre_meteo */
    private static final String[] PHENOMENA = { "Pluie-inondation", "Inondation", "Orages",
            "Vent violent", "Neige-verglas", "Canicule", "Grand-froid", "Avalanches",
            "Vagues-submersion" };

    private static final String WEATHER_PATH = "/ha/weather";
    private static final String CAR_PATH = "/ha/car";

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
                case "auth_ok":
                    authenticated = true;
                    reconnectDelay = RECONNECT_MIN_MS;
                    socket.send(new JSONObject()
                            .put("id", 1)
                            .put("type", "subscribe_entities")
                            .put("entity_ids", new JSONArray(ENTITIES)).toString());
                    setStatus("Connecté");
                    break;
                case "auth_invalid":
                    // Inutile d'insister avec un jeton refusé : attendre une nouvelle configuration
                    closeSocket();
                    setStatus("Jeton refusé par Home Assistant");
                    break;
                case "event":
                    applyEvent(msg.getJSONObject("event"));
                    publishWeather();
                    publishCar();
                    break;
                case "result":
                    if (!msg.optBoolean("success", true))
                        Log.w(TAG, "Abonnement refusé : " + msg.optJSONObject("error"));
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
        try {
            PutDataRequest request = req.asPutDataRequest();
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
