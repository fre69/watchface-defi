package com.defi.watchface;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.BatteryManager;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;
import androidx.wear.watchface.CanvasType;
import androidx.wear.watchface.ComplicationSlot;
import androidx.wear.watchface.ComplicationSlotsManager;
import androidx.wear.watchface.DrawMode;
import androidx.wear.watchface.ListenableWatchFaceService;
import androidx.wear.watchface.RenderParameters;
import androidx.wear.watchface.Renderer;
import androidx.wear.watchface.TapEvent;
import androidx.wear.watchface.TapType;
import androidx.wear.watchface.WatchFace;
import androidx.wear.watchface.WatchFaceType;
import androidx.wear.watchface.WatchState;
import androidx.wear.watchface.complications.ComplicationSlotBounds;
import androidx.wear.watchface.complications.DefaultComplicationDataSourcePolicy;
import androidx.wear.watchface.complications.SystemDataSources;
import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData;
import androidx.wear.watchface.complications.data.RangedValueComplicationData;
import androidx.wear.watchface.complications.data.ShortTextComplicationData;
import androidx.wear.watchface.style.CurrentUserStyleRepository;
import androidx.wear.watchface.style.UserStyleSchema;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.time.ZonedDateTime;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class DefiWatchFaceService extends ListenableWatchFaceService {

    private static final String TAG = "DefiWF";

    // --- IDs des ComplicationSlots ---
    public static final int COMPL_STEPS = 100;
    public static final int COMPL_CALORIES = 101;
    public static final int COMPL_HR = 102;
    public static final int COMPL_WEATHER = 103;
    public static final int COMPL_BATTERY = 104;
    public static final int COMPL_TEMP = 105;
    public static final int COMPL_UV = 106;
    public static final int COMPL_HUMID = 107;

    // --- Fournisseurs OHealth OnePlus ---
    private static final ComponentName OHEALTH_STEPS = new ComponentName(
            "com.heytap.wearable.health",
            "com.heytap.wearable.health.complication.wearos.StepComplicationService");
    private static final ComponentName OHEALTH_CALORIES = new ComponentName(
            "com.heytap.wearable.health",
            "com.heytap.wearable.health.complication.wearos.CaloriesComplicationService");
    private static final ComponentName OHEALTH_HR = new ComponentName(
            "com.heytap.wearable.health",
            "com.heytap.wearable.health.complication.wearos.HeartRateComplicationService");
    private static final ComponentName OHEALTH_WEATHER = new ComponentName(
            "com.heytap.wearable.weather",
            "com.heytap.wearable.weather.complication.wearos.WeatherProviderService");
    private static final ComponentName OHEALTH_TEMP = new ComponentName(
            "com.heytap.wearable.weather",
            "com.heytap.wearable.weather.complication.wearos.TemperatureProviderService");
    private static final ComponentName OHEALTH_UV = new ComponentName(
            "com.heytap.wearable.weather",
            "com.heytap.wearable.weather.complication.wearos.UVIndexProviderService");
    private static final ComponentName OHEALTH_HUMID = new ComponentName(
            "com.heytap.wearable.weather",
            "com.heytap.wearable.weather.complication.wearos.HumidityProviderService");

    @NonNull
    @Override
    protected UserStyleSchema createUserStyleSchema() {
        return new UserStyleSchema(Collections.emptyList());
    }

    @NonNull
    @Override
    protected ComplicationSlotsManager createComplicationSlotsManager(
            @NonNull CurrentUserStyleRepository styleRepo) {

        List<ComplicationSlot> slots = new ArrayList<>();

        // Pas (OHealth par défaut, fallback système)
        slots.add(createSlot(COMPL_STEPS, OHEALTH_STEPS,
                SystemDataSources.DATA_SOURCE_STEP_COUNT,
                0.1f, 0.05f, 0.45f, 0.15f));

        // Calories (RANGED_VALUE prioritaire car SHORT_TEXT ne fonctionne pas)
        slots.add(createSlotRanged(COMPL_CALORIES, OHEALTH_CALORIES,
                0.55f, 0.05f, 0.9f, 0.15f));

        // Fréquence cardiaque
        slots.add(createSlot(COMPL_HR, OHEALTH_HR,
                SystemDataSources.DATA_SOURCE_STEP_COUNT,
                0.25f, 0.18f, 0.75f, 0.25f));

        // Météo (conditions)
        slots.add(createSlot(COMPL_WEATHER, OHEALTH_WEATHER,
                SystemDataSources.DATA_SOURCE_DAY_OF_WEEK,
                0.1f, 0.58f, 0.5f, 0.68f));

        // Température
        slots.add(createSlot(COMPL_TEMP, OHEALTH_TEMP,
                SystemDataSources.DATA_SOURCE_DAY_OF_WEEK,
                0.3f, 0.58f, 0.7f, 0.68f));

        // Indice UV
        slots.add(createSlot(COMPL_UV, OHEALTH_UV,
                SystemDataSources.DATA_SOURCE_DAY_OF_WEEK,
                0.6f, 0.58f, 0.9f, 0.68f));

        // Humidité (RANGED_VALUE prioritaire, pas de fallback système)
        slots.add(ComplicationSlot.createRoundRectComplicationSlotBuilder(
                COMPL_HUMID,
                new androidx.wear.watchface.CanvasComplicationFactory() {
                    @NonNull
                    @Override
                    public androidx.wear.watchface.CanvasComplication create(
                            @NonNull WatchState ws,
                            @NonNull androidx.wear.watchface.CanvasComplication.InvalidateCallback cb) {
                        return new NoopCanvasComplication();
                    }
                },
                Arrays.asList(
                        ComplicationType.RANGED_VALUE,
                        ComplicationType.SHORT_TEXT,
                        ComplicationType.LONG_TEXT),
                new DefaultComplicationDataSourcePolicy(
                        OHEALTH_HUMID, ComplicationType.RANGED_VALUE,
                        SystemDataSources.DATA_SOURCE_STEP_COUNT,
                        ComplicationType.SHORT_TEXT),
                new ComplicationSlotBounds(new RectF(0.1f, 0.68f, 0.5f, 0.78f))).build());

        // Batterie
        slots.add(ComplicationSlot.createRoundRectComplicationSlotBuilder(
                COMPL_BATTERY,
                new androidx.wear.watchface.CanvasComplicationFactory() {
                    @NonNull
                    @Override
                    public androidx.wear.watchface.CanvasComplication create(
                            @NonNull WatchState ws,
                            @NonNull androidx.wear.watchface.CanvasComplication.InvalidateCallback cb) {
                        return new NoopCanvasComplication();
                    }
                },
                Arrays.asList(
                        ComplicationType.RANGED_VALUE,
                        ComplicationType.SHORT_TEXT),
                new DefaultComplicationDataSourcePolicy(
                        SystemDataSources.DATA_SOURCE_WATCH_BATTERY,
                        ComplicationType.RANGED_VALUE),
                new ComplicationSlotBounds(new RectF(0.2f, 0.78f, 0.8f, 0.88f))).build());

        return new ComplicationSlotsManager(slots, styleRepo);
    }

    private ComplicationSlot createSlotRanged(int id, ComponentName ohealth,
            float l, float t, float r, float b) {
        return ComplicationSlot.createRoundRectComplicationSlotBuilder(
                id,
                new androidx.wear.watchface.CanvasComplicationFactory() {
                    @NonNull
                    @Override
                    public androidx.wear.watchface.CanvasComplication create(
                            @NonNull WatchState ws,
                            @NonNull androidx.wear.watchface.CanvasComplication.InvalidateCallback cb) {
                        return new NoopCanvasComplication();
                    }
                },
                Arrays.asList(
                        ComplicationType.RANGED_VALUE,
                        ComplicationType.SHORT_TEXT,
                        ComplicationType.LONG_TEXT),
                new DefaultComplicationDataSourcePolicy(
                        ohealth, ComplicationType.RANGED_VALUE,
                        SystemDataSources.DATA_SOURCE_STEP_COUNT,
                        ComplicationType.SHORT_TEXT),
                new ComplicationSlotBounds(new RectF(l, t, r, b))).build();
    }

    private ComplicationSlot createSlot(int id, ComponentName ohealth,
            int systemFallback, float l, float t, float r, float b) {
        return ComplicationSlot.createRoundRectComplicationSlotBuilder(
                id,
                new androidx.wear.watchface.CanvasComplicationFactory() {
                    @NonNull
                    @Override
                    public androidx.wear.watchface.CanvasComplication create(
                            @NonNull WatchState ws,
                            @NonNull androidx.wear.watchface.CanvasComplication.InvalidateCallback cb) {
                        return new NoopCanvasComplication();
                    }
                },
                Arrays.asList(
                        ComplicationType.SHORT_TEXT,
                        ComplicationType.RANGED_VALUE,
                        ComplicationType.LONG_TEXT),
                new DefaultComplicationDataSourcePolicy(
                        ohealth, ComplicationType.SHORT_TEXT,
                        systemFallback, ComplicationType.SHORT_TEXT),
                new ComplicationSlotBounds(new RectF(l, t, r, b))).build();
    }

    @NonNull
    @Override
    protected ListenableFuture<WatchFace> createWatchFaceFuture(
            @NonNull SurfaceHolder surfaceHolder,
            @NonNull WatchState watchState,
            @NonNull ComplicationSlotsManager complicationSlotsManager,
            @NonNull CurrentUserStyleRepository styleRepo) {

        DefiRenderer renderer = new DefiRenderer(
                surfaceHolder, watchState, styleRepo, complicationSlotsManager);

        WatchFace watchFace = new WatchFace(WatchFaceType.DIGITAL, renderer);
        watchFace.setTapListener(new WatchFace.TapListener() {
            @Override
            public void onTapEvent(int tapType, @NonNull TapEvent tapEvent,
                    ComplicationSlot complicationSlot) {
                if (tapType != TapType.UP)
                    return;
                if (!NotificationHolder.isActive()) {
                    // Photo de la voiture agrandie : tout appui la ferme
                    if (renderer.isCarViewOpen()) {
                        renderer.closeCarView();
                    } else if (renderer.isOnCarThumb(tapEvent.getXPos(), tapEvent.getYPos())) {
                        renderer.openCarView();
                    } else if (NotificationHolder.getPendingCount() > 0
                            && renderer.isOnBadgeZone(tapEvent.getYPos())) {
                        // Notification en attente : la pastille (ou l'heure) ouvre la bulle
                        NotificationHolder.open();
                    } else {
                        return;
                    }
                    renderer.invalidate();
                    return;
                }
                // Tout appui ferme la bulle ; hors du bouton ✕, il ouvre aussi
                // la notification quand elle en porte l'action (notifs locales)
                android.app.PendingIntent intent = NotificationHolder.getContentIntent();
                if (intent != null && !renderer.isOnCloseButton(tapEvent.getXPos(), tapEvent.getYPos())) {
                    try {
                        intent.send();
                    } catch (android.app.PendingIntent.CanceledException e) {
                        Log.w(TAG, "Notification intent cancelled", e);
                    }
                }
                NotificationHolder.dismiss();
                renderer.invalidate();
            }
        });
        return Futures.immediateFuture(watchFace);
    }

    // =========================================================================
    // RENDERER — dessin du cadran sur Canvas
    // =========================================================================
    private class DefiRenderer extends Renderer.CanvasRenderer {

        // --- Palette ---
        private static final int COL_BG = 0xFF000000;
        private static final int COL_STEPS = 0xFF44FF88;
        private static final int COL_CAL = 0xFFFFAA33;
        private static final int COL_HEALTH = 0xFFFF4466;
        private static final int COL_WHITE = 0xFFFFFFFF;
        private static final int COL_VIOLET = 0xFFAA88FF;
        private static final int COL_GREY = 0xFFAAAAAA;
        private static final int COL_BLUE = 0xFF66BBFF;
        private static final int COL_SEP = 0xFF2A2A35;
        // Vigilance Météo-France et véhicule présent : couleurs des templates
        // sensor.montre_meteo / montre_bus_chemin de Home Assistant
        private static final int COL_ALERT_YELLOW = 0xFFFFD600;
        private static final int COL_ALERT_ORANGE = 0xFFFF9100;
        private static final int COL_ALERT_RED = 0xFFFF1744;

        private static final int STEP_GOAL = 10000;
        private static final int CAL_GOAL = 800;

        // Une image par minute (pas de secondes) ; toutes les 5 s pendant la
        // bulle, pour le décompte. Nouvelle notification ou fermeture : aussitôt
        private static final long REFRESH_NORMAL_MS = 60_000L;
        private static final long REFRESH_NOTIF_MS = 5_000L;

        private final ComplicationSlotsManager complMgr;
        private final BatteryManager batteryMgr;

        private float CX, CY;
        private int W;
        private final RectF arcOuter = new RectF();
        private final RectF arcInner = new RectF();
        private final RectF closeRing = new RectF();
        private final RectF badgeIcon = new RectF();

        // --- Caches : recalculés seulement quand leur source change ---
        private ComplicationData weatherIconSrc;
        private int weatherIconSize;
        private Bitmap weatherIcon;
        private Bitmap pictureSrc;
        private Bitmap pictureScaled;
        private String notifTextSrc;
        private StaticLayout notifTextLayout;
        private Bitmap carThumbSrc;
        private Bitmap carThumb;
        private Bitmap carViewSrc;
        private Bitmap carViewScaled;
        private final RectF carRect = new RectF();
        private final android.graphics.Path carClip = new android.graphics.Path();
        private final android.graphics.Path alertPath = new android.graphics.Path();

        /** Ouverture de la photo agrandie de la voiture (0 : fermée) */
        private long carViewOpenedAt = 0;

        // --- Paints ---
        private final Paint pArcSteps = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pArcCal = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pValue = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pGoal = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pHealth = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pTime = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pInfo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pDate = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBattTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pLinkDown = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pSep = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBarBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBarFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pAmbTime = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pAmbDate = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pAmbBatt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pNotifTime = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pNotifApp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pNotifTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint pNotifText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pCloseBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pCloseX = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pCloseRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBadgeDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBadgeCount = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBitmap = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint pHomeLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pAlert = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pAlertMark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pCarBand = new Paint();
        private final Paint pCarTime = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pCarBorder = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Bouton ✕ de la bulle (fractions de l'écran) : au-dessus de l'éclair de
        // charge et des points d'indication que le système dessine en bas
        private static final float CLOSE_Y = 0.835f;
        private static final float CLOSE_RADIUS = 0.05f;

        // Pastille de notification en attente, à droite de l'heure
        private static final float BADGE_DX = 0.335f;
        private static final float BADGE_Y = 0.442f;
        private static final float BADGE_RADIUS = 0.06f;
        // Bande de l'heure, sensible à l'appui tant qu'une notification attend
        private static final float BADGE_ZONE_TOP = 0.36f;
        private static final float BADGE_ZONE_BOTTOM = 0.56f;

        // Vignette ronde de la dernière voiture, à gauche de l'heure (centre x
        // relatif au centre de l'écran, en fractions de l'écran) : symétrique de la pastille
        private static final float CAR_DX = -0.322f;
        private static final float CAR_Y = 0.452f;
        private static final float CAR_RADIUS = 0.082f;
        private static final int COL_CAR_BORDER = 0xFFD0D0D0;
        /** Durée d'affichage de la photo agrandie, sauf appui plus tôt */
        private static final long CAR_VIEW_MS = 30_000L;

        DefiRenderer(@NonNull SurfaceHolder holder,
                @NonNull WatchState watchState,
                @NonNull CurrentUserStyleRepository styleRepo,
                @NonNull ComplicationSlotsManager complMgr) {
            super(holder, styleRepo, watchState, CanvasType.HARDWARE,
                    REFRESH_NORMAL_MS, false);
            this.complMgr = complMgr;
            batteryMgr = (BatteryManager) getSystemService(BATTERY_SERVICE);
            initPaints();
            // Afficher la pastille dès réception, sans attendre la minute suivante
            NotificationHolder.setOnChangeListener(this::postInvalidate);
            // Données Home Assistant : dernier état gardé par la DataLayer, puis chaque envoi
            HomeData.setOnChangeListener(this::postInvalidate);
            HomeData.load(DefiWatchFaceService.this);
            // Témoin de lien téléphone coupé
            PhoneLink.setOnChangeListener(this::postInvalidate);
            PhoneLink.load(DefiWatchFaceService.this);
        }

        @Override
        public void onDestroy() {
            NotificationHolder.setOnChangeListener(null);
            HomeData.setOnChangeListener(null);
            PhoneLink.setOnChangeListener(null);
            super.onDestroy();
        }

        @Override
        public void renderHighlightLayer(@NonNull Canvas canvas,
                @NonNull Rect bounds,
                @NonNull ZonedDateTime zdt) {
            // pas de highlight layer
        }

        @Override
        public void render(@NonNull Canvas c, @NonNull Rect bounds,
                @NonNull ZonedDateTime zdt) {
            if (W != bounds.width()) {
                W = Math.min(bounds.width(), bounds.height());
                CX = bounds.centerX();
                CY = bounds.centerY();
                float ro = W * 0.48f;
                float ri = W * 0.455f;
                arcOuter.set(CX - ro, CY - ro, CX + ro, CY + ro);
                arcInner.set(CX - ri, CY - ri, CX + ri, CY + ri);
            }

            boolean ambient = getRenderParameters().getDrawMode() == DrawMode.AMBIENT;
            boolean notif = !ambient && NotificationHolder.isActive();
            boolean carView = !ambient && !notif && isCarViewOpen();

            long refresh = notif || carView ? REFRESH_NOTIF_MS : REFRESH_NORMAL_MS;
            if (getInteractiveDrawModeUpdateDelayMillis() != refresh)
                setInteractiveDrawModeUpdateDelayMillis(refresh);

            int battPct = batteryMgr.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);

            if (ambient) {
                c.drawColor(0xFF000000);
                drawAmbient(c, zdt, battPct);
            } else if (notif) {
                c.drawColor(COL_BG);
                drawNotificationMode(c, zdt);
            } else if (carView) {
                c.drawColor(COL_BG);
                drawCarView(c, zdt);
            } else {
                // Lire les données des complications
                int steps = readInt(COMPL_STEPS);
                int cals = readInt(COMPL_CALORIES);
                int hr = readInt(COMPL_HR);
                // temp, uv et icône météo sont lus dans drawInfoLine
                c.drawColor(COL_BG);
                drawArcs(c, steps, cals);
                drawStepsCal(c, steps, cals);
                drawHealth(c, hr);
                drawTime(c, zdt);
                drawCarThumb(c);
                HomeData.Weather home = HomeData.getWeather();
                drawInfoLine(c, home);
                drawHomeLine(c, home);
                drawDate(c, zdt);
                drawBattery(c, battPct);
                int pending = NotificationHolder.getPendingCount();
                if (pending > 0)
                    drawPendingBadge(c, pending);
            }
        }

        // --- Lecture des données complications ---
        // Appelées à chaque image : aucun log ni allocation inutile ici

        private ComplicationData data(int slotId) {
            ComplicationSlot slot = complMgr.get(slotId);
            return slot != null ? slot.getComplicationData().getValue() : null;
        }

        private int readInt(int slotId) {
            ComplicationData data = data(slotId);
            if (data == null)
                return 0;
            try {
                if (data instanceof RangedValueComplicationData) {
                    return (int) ((RangedValueComplicationData) data).getValue();
                }
                if (data instanceof ShortTextComplicationData) {
                    CharSequence txt = ((ShortTextComplicationData) data)
                            .getText().getTextAt(getResources(), java.time.Instant.now());
                    if (txt != null) {
                        String s = txt.toString().replaceAll("[^0-9]", "");
                        if (!s.isEmpty())
                            return Integer.parseInt(s);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "readInt " + slotId, e);
            }
            return 0;
        }

        private String readText(int slotId) {
            ComplicationData data = data(slotId);
            if (data == null)
                return "";
            try {
                if (data instanceof ShortTextComplicationData) {
                    CharSequence txt = ((ShortTextComplicationData) data)
                            .getText().getTextAt(getResources(), java.time.Instant.now());
                    return txt != null ? txt.toString() : "";
                }
                if (data instanceof RangedValueComplicationData) {
                    RangedValueComplicationData rv = (RangedValueComplicationData) data;
                    if (rv.getText() != null) {
                        CharSequence t = rv.getText().getTextAt(getResources(), java.time.Instant.now());
                        return t != null ? t.toString() : "";
                    }
                    return String.valueOf((int) rv.getValue());
                }
            } catch (Exception e) {
                Log.w(TAG, "readText " + slotId, e);
            }
            return "";
        }

        /** Icône météo, reconstruite seulement quand la complication change */
        private Bitmap weatherIcon(int sizePx) {
            ComplicationData data = data(COMPL_WEATHER);
            if (data != weatherIconSrc || sizePx != weatherIconSize) {
                weatherIcon = readIcon(data, COMPL_WEATHER, sizePx);
                weatherIconSrc = data;
                weatherIconSize = sizePx;
            }
            return weatherIcon;
        }

        /**
         * Read the MonochromaticImage icon from a complication and draw it as a Bitmap
         */
        private Bitmap readIcon(ComplicationData data, int slotId, int sizePx) {
            if (!(data instanceof ShortTextComplicationData))
                return null;
            try {
                ShortTextComplicationData st = (ShortTextComplicationData) data;
                Icon icon = null;
                boolean isColor = false;
                // Préférer l'image couleur (smallImage) si disponible
                if (st.getSmallImage() != null) {
                    icon = st.getSmallImage().getImage();
                    isColor = true;
                } else if (st.getMonochromaticImage() != null) {
                    icon = st.getMonochromaticImage().getImage();
                }
                if (icon != null) {
                    Drawable drawable = icon.loadDrawable(DefiWatchFaceService.this);
                    if (drawable != null) {
                        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
                        Canvas tmpCanvas = new Canvas(bmp);
                        drawable.setBounds(0, 0, sizePx, sizePx);
                        if (!isColor) {
                            int tint = (slotId == COMPL_WEATHER) ? 0xFFFFD700 : COL_VIOLET;
                            drawable.setColorFilter(new PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN));
                        }
                        drawable.draw(tmpCanvas);
                        return bmp;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "readIcon " + slotId, e);
            }
            return null;
        }

        // --- Position helper ---
        private float y(float pct) {
            return CY + (pct - 0.5f) * W;
        }

        // --- AMBIENT ---
        private void drawAmbient(Canvas c, ZonedDateTime z, int batt) {
            c.drawText(fmt("%02d:%02d", z.getHour(), z.getMinute()),
                    CX, CY + 15, pAmbTime);
            c.drawText(z.getDayOfMonth() + " " + monthShort(z.getMonthValue() - 1),
                    CX, CY + 60, pAmbDate);
            c.drawText(batt + "%", CX, CY + 90, pAmbBatt);
        }

        // --- ARCS ---
        private void drawArcs(Canvas c, int steps, int cals) {
            float sp = Math.min(steps / (float) STEP_GOAL, 1f);
            float cp = Math.min(cals / (float) CAL_GOAL, 1f);
            pArcSteps.setAlpha(75);
            c.drawArc(arcOuter, 135, 270, false, pArcSteps);
            pArcSteps.setAlpha(230);
            c.drawArc(arcOuter, 135, sp * 270, false, pArcSteps);
            pArcCal.setAlpha(75);
            c.drawArc(arcInner, 135, 270, false, pArcCal);
            pArcCal.setAlpha(230);
            c.drawArc(arcInner, 135, cp * 270, false, pArcCal);
        }

        // --- PAS | CAL ---
        private void drawStepsCal(Canvas c, int steps, int cals) {
            float lx = CX - W * 0.14f, rx = CX + W * 0.14f;
            float yLab = y(0.135f), yVal = y(0.200f), yGoal = y(0.245f);

            pLabel.setColor(COL_STEPS);
            c.drawText("PAS", lx, yLab, pLabel);
            pValue.setColor(COL_STEPS);
            pValue.setAlpha(255);
            c.drawText(String.valueOf(steps), lx, yVal, pValue);
            pGoal.setColor(COL_STEPS);
            pGoal.setAlpha(115);
            c.drawText("/10k", lx, yGoal, pGoal);

            c.drawLine(CX, yLab - 8, CX, yGoal + 2, pSep);

            pLabel.setColor(COL_CAL);
            c.drawText("CAL", rx, yLab, pLabel);
            pValue.setColor(COL_CAL);
            pValue.setAlpha(255);
            c.drawText(cals > 0 ? String.valueOf(cals) : "---", rx, yVal, pValue);
            pGoal.setColor(COL_CAL);
            pGoal.setAlpha(115);
            c.drawText("/" + CAL_GOAL, rx, yGoal, pGoal);
        }

        // --- FC ---
        private void drawHealth(Canvas c, int hr) {
            float baseY = y(0.33f);
            String num = hr > 0 ? String.valueOf(hr) : "--";
            String left = "\u2665 ";
            String right = " bpm";
            float oldSize = pHealth.getTextSize();
            float bigSize = 34;
            float numW = bigSize / oldSize * pHealth.measureText(num);
            float leftW = pHealth.measureText(left);
            float rightW = pHealth.measureText(right);
            float totalW = leftW + numW + rightW;
            float startX = CX - totalW / 2f;
            pHealth.setTextAlign(Paint.Align.LEFT);
            c.drawText(left, startX, baseY, pHealth);
            pHealth.setTextSize(bigSize);
            c.drawText(num, startX + leftW, baseY, pHealth);
            pHealth.setTextSize(oldSize);
            c.drawText(right, startX + leftW + numW, baseY, pHealth);
            pHealth.setTextAlign(Paint.Align.CENTER);
        }

        // --- HEURE ---
        private void drawTime(Canvas c, ZonedDateTime z) {
            // Pas de secondes : elles imposeraient un rendu chaque seconde
            String hhmm = fmt("%02d:%02d", z.getHour(), z.getMinute());
            c.drawText(hhmm, CX, y(0.505f), pTime);
        }

        // --- UNE LIGNE EN 3 ZONES : [icône] | temp° UV x hum% | 🌙 ou ⚠ vigilance ---
        // Température et humidité : station de la maison (Home Assistant) si
        // elle a répondu dans l'heure, sinon OHealth ; UV et icône : OHealth
        private void drawInfoLine(Canvas c, HomeData.Weather home) {
            float lineY = y(0.63f);
            String tempTxt = home != null && !Double.isNaN(home.temp)
                    ? fmt("%.1f°", home.temp) : readText(COMPL_TEMP);
            String uvTxt = readText(COMPL_UV);
            String humTxt = home != null && !Double.isNaN(home.humidity)
                    ? fmt("%d%%", Math.round(home.humidity)) : readText(COMPL_HUMID);

            float iconSizePx = W * 0.15f;
            Bitmap weatherIcn = weatherIcon((int) iconSizePx);

            // Zone gauche : icône
            float leftX = CX - W * 0.4f;
            pInfo.setTextAlign(Paint.Align.LEFT);
            float textStartX = leftX;
            if (weatherIcn != null) {
                c.drawBitmap(weatherIcn, leftX, lineY - iconSizePx / 2f - pInfo.getTextSize() / 3f, null);
                textStartX = leftX + iconSizePx + 3;
            }

            // Zone centre : Temp · UV · hum%
            pInfo.setTextAlign(Paint.Align.CENTER);
            String center = (tempTxt != null && !tempTxt.isEmpty()) ? tempTxt : "--";
            if (uvTxt != null && !uvTxt.isEmpty())
                center += "  UV " + uvTxt;
            if (humTxt != null && !humTxt.isEmpty() && !humTxt.contains("dim"))
                center += "  " + humTxt;
            c.drawText(center, CX - W * 0.00f, lineY, pInfo);

            // Zone droite : phase de la lune, ou triangle de vigilance à sa place
            float rightX = CX + W * 0.4f;
            int alertCol = home != null ? alertColor(home.alertLevel) : 0;
            if (alertCol != 0) {
                drawAlertIcon(c, rightX, lineY - pInfo.getTextSize() * 0.35f, alertCol);
            } else {
                pInfo.setTextAlign(Paint.Align.RIGHT);
                float oldSize = pInfo.getTextSize();
                pInfo.setTextSize(oldSize * 1.25f);
                c.drawText(moonPhase(), rightX, lineY, pInfo);
                pInfo.setTextSize(oldSize);
            }

            // Reset align
            pInfo.setTextAlign(Paint.Align.CENTER);
        }

        /** Couleur du niveau de vigilance, 0 sans vigilance */
        private int alertColor(String level) {
            switch (level) {
                case "Jaune":
                    return COL_ALERT_YELLOW;
                case "Orange":
                    return COL_ALERT_ORANGE;
                case "Rouge":
                    return COL_ALERT_RED;
                default:
                    return 0;
            }
        }

        /** Triangle « ! » de la couleur du niveau, bord droit sur right, centré sur cy */
        private void drawAlertIcon(Canvas c, float right, float cy, int color) {
            float s = W * 0.09f;
            float cx = right - s * 0.55f;
            alertPath.reset();
            alertPath.moveTo(cx, cy - s * 0.5f);
            alertPath.lineTo(cx + s * 0.55f, cy + s * 0.45f);
            alertPath.lineTo(cx - s * 0.55f, cy + s * 0.45f);
            alertPath.close();
            pAlert.setColor(color);
            c.drawPath(alertPath, pAlert);
            pAlertMark.setTextSize(s * 0.62f);
            c.drawText("!", cx, cy + s * 0.36f, pAlertMark);
        }

        // --- LIGNE MAISON : ressenti · vent · pluie, ou la vigilance en cours ---
        private void drawHomeLine(Canvas c, HomeData.Weather home) {
            if (home == null)
                return;
            String txt;
            int alertCol = alertColor(home.alertLevel);
            if (alertCol != 0) {
                txt = home.alertPhenomena.isEmpty()
                        ? "Vigilance " + home.alertLevel.toLowerCase(Locale.FRENCH)
                        : home.alertLevel + " : " + home.alertPhenomena;
                pHomeLine.setColor(alertCol);
            } else {
                StringBuilder sb = new StringBuilder();
                if (!Double.isNaN(home.feels))
                    sb.append(fmt("ressenti %d°", Math.round(home.feels)));
                if (!Double.isNaN(home.wind))
                    sb.append(sb.length() > 0 ? " · " : "").append(fmt("vent %d km/h", Math.round(home.wind)));
                String rain = home.rainIn == 0 ? "pluie en cours"
                        : home.rainIn > 0 ? fmt("pluie dans %d min", home.rainIn)
                        : !Double.isNaN(home.rain) ? fmt("pluie %d %%", Math.round(home.rain)) : "";
                if (!rain.isEmpty())
                    sb.append(sb.length() > 0 ? " · " : "").append(rain);
                txt = sb.toString();
                pHomeLine.setColor(COL_VIOLET);
                pHomeLine.setAlpha(190);
            }
            if (txt.isEmpty())
                return;
            // Réduire la taille plutôt que déborder du cercle
            float size = W * 0.043f;
            pHomeLine.setTextSize(size);
            float maxW = W * 0.78f, w = pHomeLine.measureText(txt);
            if (w > maxW)
                pHomeLine.setTextSize(size * maxW / w);
            c.drawText(txt, CX, y(0.685f), pHomeLine);
        }

        // --- VIGNETTE : dernière voiture du chemin (moins de 24 h) ---
        private void drawCarThumb(Canvas c) {
            Bitmap pic = HomeData.getCarPicture();
            if (pic == null)
                return;
            float cx = CX + W * CAR_DX, cy = y(CAR_Y), r = W * CAR_RADIUS;
            carRect.set(cx - r, cy - r, cx + r, cy + r);
            Bitmap thumb = carThumb(pic, Math.round(carRect.width()), Math.round(carRect.height()));
            c.save();
            carClip.reset();
            carClip.addCircle(cx, cy, r, android.graphics.Path.Direction.CW);
            c.clipPath(carClip);
            c.drawBitmap(thumb, carRect.left, carRect.top, pBitmap);
            // Heure du passage sur un bandeau sombre en bas du cercle
            float band = W * 0.05f;
            c.drawRect(carRect.left, carRect.bottom - band, carRect.right, carRect.bottom, pCarBand);
            pCarTime.setTextSize(band * 0.72f);
            c.drawText(hhmm(HomeData.getCarPassedAt()), cx, carRect.bottom - band * 0.3f, pCarTime);
            c.restore();
            // Cerclage orange tant que le véhicule est sur le chemin
            pCarBorder.setColor(HomeData.isCarPresent() ? COL_ALERT_ORANGE : COL_CAR_BORDER);
            c.drawCircle(cx, cy, r, pCarBorder);
        }

        /** Photo recadrée au centre aux dimensions de la vignette, une fois par photo */
        private Bitmap carThumb(Bitmap src, int w, int h) {
            if (src != carThumbSrc || carThumb == null || carThumb.getWidth() != w
                    || carThumb.getHeight() != h) {
                float k = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
                int sw = Math.min(src.getWidth(), Math.round(w / k));
                int sh = Math.min(src.getHeight(), Math.round(h / k));
                Bitmap crop = Bitmap.createBitmap(src, (src.getWidth() - sw) / 2,
                        (src.getHeight() - sh) / 2, sw, sh);
                carThumb = Bitmap.createScaledBitmap(crop, w, h, true);
                carThumbSrc = src;
            }
            return carThumb;
        }

        boolean isOnCarThumb(int x, int y) {
            if (HomeData.getCarPicture() == null)
                return false;
            // Zone élargie pour un doigt
            float dx = x - (CX + W * CAR_DX), dy = y - y(CAR_Y);
            float hit = W * (CAR_RADIUS + 0.04f);
            return dx * dx + dy * dy <= hit * hit;
        }

        boolean isCarViewOpen() {
            return carViewOpenedAt > 0
                    && System.currentTimeMillis() - carViewOpenedAt < CAR_VIEW_MS;
        }

        void openCarView() {
            carViewOpenedAt = System.currentTimeMillis();
        }

        void closeCarView() {
            carViewOpenedAt = 0;
            carViewSrc = null;
            carViewScaled = null;
        }

        // --- PHOTO AGRANDIE DE LA VOITURE (appui sur la vignette) ---
        private void drawCarView(Canvas c, ZonedDateTime z) {
            c.drawText(fmt("%02d:%02d", z.getHour(), z.getMinute()), CX, y(0.12f), pNotifTime);
            // « Véhicule — sens — HH:MM » de Home Assistant, sinon l'heure de la photo
            String label = HomeData.getCarLabel();
            c.drawText(label.isEmpty() ? "Chemin · " + hhmm(HomeData.getCarPassedAt())
                    : label.replace(" — ", " · "), CX, y(0.2f), pNotifApp);

            Bitmap pic = HomeData.getCarPicture();
            if (pic != null) {
                if (pic != carViewSrc) {
                    float k = Math.min(W * 0.8f / pic.getWidth(), W * 0.5f / pic.getHeight());
                    carViewScaled = Bitmap.createScaledBitmap(pic, Math.round(pic.getWidth() * k),
                            Math.round(pic.getHeight() * k), true);
                    carViewSrc = pic;
                }
                c.drawBitmap(carViewScaled, CX - carViewScaled.getWidth() / 2f,
                        y(0.48f) - carViewScaled.getHeight() / 2f, pBitmap);
            }
            if (HomeData.isCarPresent()) {
                pHomeLine.setColor(COL_ALERT_ORANGE);
                pHomeLine.setTextSize(W * 0.05f);
                c.drawText("Véhicule présent", CX, y(0.78f), pHomeLine);
            }
            long elapsed = System.currentTimeMillis() - carViewOpenedAt;
            drawCloseButton(c, Math.max(0f, 1f - elapsed / (float) CAR_VIEW_MS));
        }

        private String hhmm(long epochMs) {
            java.time.LocalTime t = java.time.Instant.ofEpochMilli(epochMs)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalTime();
            return fmt("%02d:%02d", t.getHour(), t.getMinute());
        }

        // --- DATE ---
        private void drawDate(Canvas c, ZonedDateTime z) {
            int week = z.get(WeekFields.ISO.weekOfWeekBasedYear());
            String d = dayShort(z.getDayOfWeek().getValue()) + "  " +
                    z.getDayOfMonth() + "  " + monthShort(z.getMonthValue() - 1) + "  S" + week;
            c.drawText(d, CX, y(0.78f), pDate);
        }

        // --- BATTERIE ---
        private void drawBattery(Canvas c, int batt) {
            float bw = W * 0.50f, bh = 8;
            float left = CX - bw / 2f;
            float barY = y(0.82f);
            int battCol = batt > 20 ? COL_BLUE : COL_HEALTH;
            pBarBg.setColor(battCol);
            pBarBg.setAlpha(75);
            c.drawRoundRect(left, barY, left + bw, barY + bh, 4, 4, pBarBg);
            float fill = (batt / 100f) * bw;
            pBarFill.setColor(battCol);
            pBarFill.setAlpha(230);
            c.drawRoundRect(left, barY, left + fill, barY + bh, 4, 4, pBarFill);
            pBattTxt.setColor(batt > 20 ? COL_BLUE : COL_HEALTH);
            String txt = batt + "%";
            c.drawText(txt, CX, y(0.90f), pBattTxt);
            // Lien téléphone coupé : plus de notifications ni de données Home Assistant
            if (PhoneLink.isDown())
                c.drawText("\uD83D\uDCF5", CX - pBattTxt.measureText(txt) / 2f - 8, y(0.90f), pLinkDown);
        }

        // --- PASTILLE : notification en attente, ouverte par un appui ---
        private void drawPendingBadge(Canvas c, int count) {
            float r = W * BADGE_RADIUS;
            float bx = CX + W * BADGE_DX, by = y(BADGE_Y);
            c.drawCircle(bx, by, r, pCloseBg);
            c.drawCircle(bx, by, r, pCloseRing);
            Bitmap icon = NotificationHolder.getIcon();
            if (icon != null) {
                float h = r * 0.62f;
                badgeIcon.set(bx - h, by - h, bx + h, by + h);
                c.drawBitmap(icon, null, badgeIcon, pBitmap);
            } else {
                c.drawCircle(bx, by, r * 0.3f, pBadgeDot);
            }
            if (count > 1) {
                float cr = r * 0.45f;
                float nx = bx + r * 0.75f, ny = by - r * 0.75f;
                c.drawCircle(nx, ny, cr, pBadgeDot);
                c.drawText(count > 9 ? "9+" : String.valueOf(count), nx, ny + cr * 0.4f, pBadgeCount);
            }
        }

        /** Zone d'appui de la pastille : toute la bande de l'heure, pour un doigt */
        boolean isOnBadgeZone(int y) {
            return y >= y(BADGE_ZONE_TOP) && y <= y(BADGE_ZONE_BOTTOM);
        }

        // --- NOTIFICATION MODE ---
        private void drawNotificationMode(Canvas c, ZonedDateTime z) {
            // Heure en petit en haut
            String hhmm = fmt("%02d:%02d", z.getHour(), z.getMinute());
            c.drawText(hhmm, CX, y(0.12f), pNotifTime);

            // Icône de l'app
            Bitmap icon = NotificationHolder.getIcon();
            if (icon != null) {
                float iconSize = W * 0.10f;
                c.drawBitmap(icon, CX - iconSize / 2f, y(0.18f), null);
            }

            // Nom de l'app
            String appName = NotificationHolder.getAppName();
            c.drawText(appName, CX, y(0.32f), pNotifApp);

            // Titre
            String title = NotificationHolder.getTitle();
            c.drawText(title, CX, y(0.42f), pNotifTitle);

            // Picture (photo de la notification)
            Bitmap picture = scaledPicture(NotificationHolder.getPicture());
            float contentY = y(0.42f) + 12;
            if (picture != null) {
                c.drawBitmap(picture, CX - picture.getWidth() / 2f, contentY, null);
                contentY += picture.getHeight() + 8;
            }

            // Texte (word-wrap avec StaticLayout)
            StaticLayout layout = notifTextLayout(NotificationHolder.getText());
            if (layout != null) {
                c.save();
                c.translate(CX - layout.getWidth() / 2f, contentY);
                layout.draw(c);
                c.restore();
            }

            drawCloseButton(c, NotificationHolder.getRemaining());
        }

        /** Bouton ✕, cerclé de la part du temps d'affichage restante (1 → 0) */
        private void drawCloseButton(Canvas c, float remaining) {
            float r = W * CLOSE_RADIUS;
            float cy = y(CLOSE_Y);
            c.drawCircle(CX, cy, r, pCloseBg);
            float rr = r + 5;
            closeRing.set(CX - rr, cy - rr, CX + rr, cy + rr);
            c.drawArc(closeRing, -90, 360 * remaining, false, pCloseRing);
            float k = r * 0.4f;
            c.drawLine(CX - k, cy - k, CX + k, cy + k, pCloseX);
            c.drawLine(CX - k, cy + k, CX + k, cy - k, pCloseX);
        }

        /** Zone d'appui du bouton ✕, plus large que son dessin pour un doigt */
        boolean isOnCloseButton(int x, int y) {
            float dx = x - CX, dy = y - y(CLOSE_Y);
            float hit = W * CLOSE_RADIUS * 2f;
            return dx * dx + dy * dy <= hit * hit;
        }

        /** Photo redimensionnée une seule fois par notification (pas à chaque image) */
        private Bitmap scaledPicture(Bitmap picture) {
            if (picture == null)
                return null;
            if (picture != pictureSrc) {
                float drawH = 200;
                float drawW = picture.getWidth() * (drawH / picture.getHeight());
                // Limiter la largeur à 75% de l'écran
                if (drawW > W * 0.75f) {
                    drawW = W * 0.75f;
                    drawH = picture.getHeight() * (drawW / picture.getWidth());
                }
                pictureScaled = Bitmap.createScaledBitmap(picture, (int) drawW, (int) drawH, true);
                pictureSrc = picture;
            }
            return pictureScaled;
        }

        /** Mise en page du texte, refaite seulement quand le texte change */
        private StaticLayout notifTextLayout(String text) {
            if (text == null || text.isEmpty())
                return null;
            int textWidth = (int) (W * 0.75f);
            if (!text.equals(notifTextSrc) || notifTextLayout.getWidth() != textWidth) {
                notifTextLayout = StaticLayout.Builder
                        .obtain(text, 0, text.length(), pNotifText, textWidth)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setMaxLines(4)
                        .build();
                notifTextSrc = text;
            }
            return notifTextLayout;
        }

        // --- PAINTS ---
        private void initPaints() {
            Typeface bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
            Typeface normal = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
            // Normal mode settings
            setupStroke(pArcSteps, COL_STEPS, 12);
            setupStroke(pArcCal, COL_CAL, 8);
            setupText(pLabel, COL_STEPS, 16, bold);
            setupText(pValue, COL_STEPS, 36, bold);
            setupText(pGoal, COL_STEPS, 24, normal);
            setupText(pHealth, COL_HEALTH, 26, bold);
            setupText(pTime, COL_WHITE, 80, bold);
            setupText(pInfo, COL_VIOLET, 32, bold);
            setupText(pDate, COL_WHITE, 32, bold);
            setupText(pBattTxt, COL_BLUE, 32, bold);
            setupText(pLinkDown, COL_HEALTH, 28, bold);
            pLinkDown.setTextAlign(Paint.Align.RIGHT);
            // Ambient mode settings
            pSep.setColor(COL_GREY);
            pSep.setAlpha(115);
            pSep.setStrokeWidth(2f);
            setupText(pAmbTime, COL_WHITE, 80, bold);
            pAmbTime.setAlpha(180);
            setupText(pAmbDate, COL_GREY, 32, normal);
            pAmbDate.setAlpha(128);
            setupText(pAmbBatt, COL_BLUE, 18, normal);
            pAmbBatt.setAlpha(128);
            // Notification mode settings
            setupText(pNotifTime, COL_WHITE, 50, bold);
            setupText(pNotifApp, COL_VIOLET, 24, bold);
            setupText(pNotifTitle, COL_WHITE, 28, bold);
            pNotifText.setColor(COL_WHITE);
            pNotifText.setTextSize(28);
            pNotifText.setTypeface(normal);
            pNotifText.setTextAlign(Paint.Align.LEFT);
            pCloseBg.setColor(COL_SEP);
            setupStroke(pCloseX, COL_WHITE, 4);
            setupStroke(pCloseRing, COL_VIOLET, 4);
            pBadgeDot.setColor(COL_VIOLET);
            setupText(pBadgeCount, COL_BG, 18, bold);
            // Home Assistant : ligne maison, vigilance, vignette de la voiture
            setupText(pHomeLine, COL_VIOLET, 20, bold);
            pAlert.setStyle(Paint.Style.FILL);
            setupText(pAlertMark, COL_BG, 24, bold);
            pCarBand.setColor(0x99000000);
            setupText(pCarTime, COL_WHITE, 16, bold);
            setupStroke(pCarBorder, COL_CAR_BORDER, 2.5f);
        }

        private void setupStroke(Paint p, int c, float w) {
            p.setStyle(Paint.Style.STROKE);
            p.setColor(c);
            p.setStrokeWidth(w);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        private void setupText(Paint p, int c, float s, Typeface tf) {
            p.setColor(c);
            p.setTextSize(s);
            p.setTypeface(tf);
            p.setTextAlign(Paint.Align.CENTER);
        }

        // --- UTILS ---
        private String moonPhase() {
            long days = ChronoUnit.DAYS.between(LocalDate.of(2000, 1, 6), LocalDate.now());
            double age = ((days % 29.53) + 29.53) % 29.53;
            String[] e = { "\uD83C\uDF11", "\uD83C\uDF12", "\uD83C\uDF13", "\uD83C\uDF14",
                    "\uD83C\uDF15", "\uD83C\uDF16", "\uD83C\uDF17", "\uD83C\uDF18" };
            return e[Math.min((int) (age / 3.69), 7)];
        }

        private String dayShort(int iso) {
            return new String[] { "Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim" }[iso - 1];
        }

        private String monthShort(int m) {
            return new String[] { "Jan", "F\u00e9v", "Mar", "Avr", "Mai", "Jun",
                    "Jul", "Ao\u00fb", "Sep", "Oct", "Nov", "D\u00e9c" }[m];
        }

        private String fmt(String f, Object... a) {
            return String.format(Locale.getDefault(), f, a);
        }
    }

    // =========================================================================
    // NoopCanvasComplication — on dessine nous-mêmes, pas besoin du rendu par
    // défaut
    // =========================================================================
    static class NoopCanvasComplication implements androidx.wear.watchface.CanvasComplication {
        @Override
        public void render(@NonNull Canvas c, @NonNull Rect b,
                @NonNull ZonedDateTime z, @NonNull RenderParameters rp, int slotId) {
        }

        @Override
        public void drawHighlight(@NonNull Canvas c, @NonNull Rect b,
                int boundsType, @NonNull ZonedDateTime z, int color) {
        }

        @NonNull
        @Override
        public ComplicationData getData() {
            return new androidx.wear.watchface.complications.data.NoDataComplicationData();
        }

        @Override
        public void loadData(@NonNull ComplicationData data, boolean loadDrawablesAsync) {
        }
    }
}
