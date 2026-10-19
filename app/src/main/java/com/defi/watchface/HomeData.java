package com.defi.watchface;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Données Home Assistant relayées par l'appli du téléphone (HaLink) :
 * météo de la maison (/ha/weather) et dernière voiture du chemin (/ha/car).
 * La DataLayer garde le dernier élément de chaque chemin : il est relu au
 * démarrage du cadran, sans attendre le prochain envoi du téléphone.
 */
public final class HomeData {
    private static final String TAG = "DefiHome";
    static final String WEATHER_PATH = "/ha/weather";
    static final String CAR_PATH = "/ha/car";
    static final String AGENDA_PATH = "/ha/agenda";

    /** Le téléphone renvoie la météo toutes les 30 min au moins : au-delà d'1 h, retour à OHealth */
    private static final long WEATHER_MAX_AGE_MS = 60 * 60_000L;
    /** Vignette de la voiture affichée pendant 24 h après son passage */
    private static final long CAR_MAX_AGE_MS = 24 * 3600_000L;

    /** Valeurs météo, NaN quand Home Assistant ne les a pas */
    public static final class Weather {
        public double temp = Double.NaN;
        public double humidity = Double.NaN;
        public double feels = Double.NaN;
        public double wind = Double.NaN;
        public double rain = Double.NaN;
        /** Minutes avant la pluie annoncée dans l'heure, -1 : rien */
        public int rainIn = -1;
        /** "", "Jaune", "Orange" ou "Rouge" */
        public String alertLevel = "";
        public String alertPhenomena = "";
        long sentAt;
    }

    /** Événement du jour, tiré des agendas Home Assistant */
    public static final class Event {
        public final String title;
        public final long start;
        public final long end;
        public final boolean allDay;

        Event(String title, long start, long end, boolean allDay) {
            this.title = title;
            this.start = start;
            this.end = end;
            this.allDay = allDay;
        }
    }

    private static Weather weather = null;
    private static List<Event> agenda = new ArrayList<>();
    private static Bitmap carPicture = null;
    private static long carPassedAt = 0;
    private static String carLabel = "";
    private static boolean carPresent = false;
    private static Runnable onChange = null;

    public static synchronized void setOnChangeListener(Runnable listener) {
        onChange = listener;
    }

    /** Météo de la maison si elle est récente, sinon null (le cadran garde OHealth) */
    public static synchronized Weather getWeather() {
        if (weather == null || System.currentTimeMillis() - weather.sentAt > WEATHER_MAX_AGE_MS)
            return null;
        return weather;
    }

    /**
     * Rendez-vous à afficher : celui en cours ou le prochain du jour, sinon
     * l'événement « journée entière » du jour ; null : rien aujourd'hui.
     * Les événements d'un autre jour sont ignorés (téléphone muet depuis minuit)
     */
    public static synchronized Event getAgendaEvent() {
        long now = System.currentTimeMillis();
        long endOfDay = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        Event timed = null, allDay = null;
        for (Event e : agenda) {
            if (e.end <= now || e.start >= endOfDay)
                continue;
            if (e.allDay) {
                if (allDay == null)
                    allDay = e;
            } else if (timed == null || e.start < timed.start) {
                timed = e;
            }
        }
        return timed != null ? timed : allDay;
    }

    /** Photo de la dernière voiture si elle date de moins de 24 h, sinon null */
    public static synchronized Bitmap getCarPicture() {
        if (carPicture == null || System.currentTimeMillis() - carPassedAt > CAR_MAX_AGE_MS)
            return null;
        return carPicture;
    }

    public static synchronized long getCarPassedAt() {
        return carPassedAt;
    }

    /** « Véhicule — sens — HH:MM » (input_text.derniere_voiture), éventuellement vide */
    public static synchronized String getCarLabel() {
        return carLabel;
    }

    /** Véhicule encore sur le chemin (binary_sensor.chemin_car_occupancy) */
    public static synchronized boolean isCarPresent() {
        return carPresent;
    }

    /** Relit les derniers éléments gardés par la DataLayer (démarrage du cadran) */
    public static void load(Context ctx) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                DataItemBuffer items = Tasks.await(Wearable.getDataClient(app).getDataItems());
                try {
                    for (DataItem item : items)
                        apply(app, item);
                } finally {
                    items.release();
                }
            } catch (Exception e) {
                Log.w(TAG, "Lecture de la DataLayer impossible", e);
            }
        }).start();
    }

    /**
     * Applique un élément reçu ; false s'il ne concerne pas Home Assistant.
     * Bloquant (lecture de la photo) : jamais sur le fil principal.
     */
    public static boolean apply(Context ctx, DataItem item) {
        String path = item.getUri().getPath();
        if (WEATHER_PATH.equals(path)) {
            DataMap m = DataMapItem.fromDataItem(item).getDataMap();
            Weather w = new Weather();
            w.temp = m.getDouble("temp", Double.NaN);
            w.humidity = m.getDouble("humidity", Double.NaN);
            w.feels = m.getDouble("feels", Double.NaN);
            w.wind = m.getDouble("wind", Double.NaN);
            w.rain = m.getDouble("rain", Double.NaN);
            w.rainIn = m.getInt("rainIn", -1);
            w.alertLevel = m.getString("alertLevel", "");
            w.alertPhenomena = m.getString("alertPhenomena", "");
            w.sentAt = m.getLong("sentAt", 0);
            synchronized (HomeData.class) {
                if (weather != null && weather.sentAt > w.sentAt)
                    return true;
                weather = w;
            }
            notifyChange();
            return true;
        }
        if (AGENDA_PATH.equals(path)) {
            DataMap m = DataMapItem.fromDataItem(item).getDataMap();
            List<Event> events = new ArrayList<>();
            ArrayList<DataMap> list = m.getDataMapArrayList("events");
            if (list != null) {
                for (DataMap e : list)
                    events.add(new Event(e.getString("title", ""), e.getLong("start"),
                            e.getLong("end"), e.getBoolean("allDay")));
            }
            synchronized (HomeData.class) {
                agenda = events;
            }
            notifyChange();
            return true;
        }
        if (CAR_PATH.equals(path)) {
            DataMap m = DataMapItem.fromDataItem(item).getDataMap();
            Bitmap picture = loadAsset(ctx, m.getAsset("picture"));
            synchronized (HomeData.class) {
                if (picture != null)
                    carPicture = picture;
                carPassedAt = m.getLong("passedAt", 0);
                carLabel = m.getString("label", "");
                carPresent = m.getBoolean("present", false);
            }
            notifyChange();
            return true;
        }
        return false;
    }

    private static void notifyChange() {
        Runnable listener;
        synchronized (HomeData.class) {
            listener = onChange;
        }
        if (listener != null)
            listener.run();
    }

    private static Bitmap loadAsset(Context ctx, Asset asset) {
        if (asset == null)
            return null;
        try (InputStream is = Tasks.await(Wearable.getDataClient(ctx).getFdForAsset(asset))
                .getInputStream()) {
            return BitmapFactory.decodeStream(is);
        } catch (Exception e) {
            Log.w(TAG, "Lecture de la photo impossible", e);
            return null;
        }
    }
}
