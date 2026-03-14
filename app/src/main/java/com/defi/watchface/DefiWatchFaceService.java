package com.defi.watchface;

import android.content.ComponentName;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
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
    public static final int COMPL_STEPS    = 100;
    public static final int COMPL_CALORIES = 101;
    public static final int COMPL_HR       = 102;
    public static final int COMPL_WEATHER  = 103;
    public static final int COMPL_BATTERY  = 104;
    public static final int COMPL_TEMP     = 105;
    public static final int COMPL_UV       = 106;

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

        // Batterie
        slots.add(ComplicationSlot.createRoundRectComplicationSlotBuilder(
                COMPL_BATTERY,
                new androidx.wear.watchface.CanvasComplicationFactory() {
                    @NonNull @Override
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
                new ComplicationSlotBounds(new RectF(0.2f, 0.78f, 0.8f, 0.88f))
        ).build());

        return new ComplicationSlotsManager(slots, styleRepo);
    }

    private ComplicationSlot createSlotRanged(int id, ComponentName ohealth,
                                               float l, float t, float r, float b) {
        return ComplicationSlot.createRoundRectComplicationSlotBuilder(
                id,
                new androidx.wear.watchface.CanvasComplicationFactory() {
                    @NonNull @Override
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
                new ComplicationSlotBounds(new RectF(l, t, r, b))
        ).build();
    }

    private ComplicationSlot createSlot(int id, ComponentName ohealth,
                                         int systemFallback, float l, float t, float r, float b) {
        return ComplicationSlot.createRoundRectComplicationSlotBuilder(
                id,
                new androidx.wear.watchface.CanvasComplicationFactory() {
                    @NonNull @Override
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
                new ComplicationSlotBounds(new RectF(l, t, r, b))
        ).build();
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

        return Futures.immediateFuture(
                new WatchFace(WatchFaceType.DIGITAL, renderer));
    }

    // =========================================================================
    //  RENDERER — dessin du cadran sur Canvas
    // =========================================================================
    private class DefiRenderer extends Renderer.CanvasRenderer {

        // --- Palette ---
        private static final int COL_BG     = 0xFF0B0B12;
        private static final int COL_STEPS  = 0xFF44FF88;
        private static final int COL_CAL    = 0xFFFFAA33;
        private static final int COL_HEALTH = 0xFFFF4466;
        private static final int COL_WHITE  = 0xFFFFFFFF;
        private static final int COL_VIOLET = 0xFFAA88FF;
        private static final int COL_GREY   = 0xFFAAAAAA;
        private static final int COL_BLUE   = 0xFF66BBFF;
        private static final int COL_SEP    = 0xFF2A2A35;

        private static final int STEP_GOAL = 10000;
        private static final int CAL_GOAL  = 800;

        private final ComplicationSlotsManager complMgr;
        private final BatteryManager batteryMgr;

        private float CX, CY;
        private int W;
        private final RectF arcOuter = new RectF();
        private final RectF arcInner = new RectF();

        // --- Paints ---
        private final Paint pArcSteps = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pArcCal   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pLabel    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pValue    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pGoal     = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pHealth   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pTime     = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pTimeSec  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pInfo     = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pDate     = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBattTxt  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pSep      = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBarBg    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBarFill  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pAmbTime  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pAmbDate  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pAmbBatt  = new Paint(Paint.ANTI_ALIAS_FLAG);

        DefiRenderer(@NonNull SurfaceHolder holder,
                     @NonNull WatchState watchState,
                     @NonNull CurrentUserStyleRepository styleRepo,
                     @NonNull ComplicationSlotsManager complMgr) {
            super(holder, styleRepo, watchState, CanvasType.HARDWARE,
                    1000L, false);
            this.complMgr = complMgr;
            batteryMgr = (BatteryManager) getSystemService(BATTERY_SERVICE);
            initPaints();
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

            // Lire les données des complications
            int steps = readInt(COMPL_STEPS);
            int cals = readInt(COMPL_CALORIES);
            int hr = readInt(COMPL_HR);
            String weatherTxt = readText(COMPL_WEATHER);
            String tempTxt = readText(COMPL_TEMP);
            String uvTxt = readText(COMPL_UV);
            int battPct = batteryMgr.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);

            if (ambient) {
                c.drawColor(0xFF000000);
                drawAmbient(c, zdt, battPct);
            } else {
                c.drawColor(COL_BG);
                drawArcs(c, steps, cals);
                drawStepsCal(c, steps, cals);
                drawHealth(c, hr);
                drawTime(c, zdt);
                drawWeather(c, tempTxt, weatherTxt, uvTxt, zdt);
                drawDate(c, zdt);
                drawBattery(c, battPct);
            }
        }

        // --- Lecture des données complications ---

        private int readInt(int slotId) {
            ComplicationSlot slot = complMgr.get(slotId);
            if (slot == null) return 0;
            ComplicationData data = slot.getComplicationData().getValue();
            if (data == null) return 0;
            try {
                if (data instanceof RangedValueComplicationData) {
                    return (int) ((RangedValueComplicationData) data).getValue();
                }
                if (data instanceof ShortTextComplicationData) {
                    CharSequence txt = ((ShortTextComplicationData) data)
                            .getText().getTextAt(getResources(), java.time.Instant.now());
                    if (txt != null) {
                        String s = txt.toString().replaceAll("[^0-9]", "");
                        if (!s.isEmpty()) return Integer.parseInt(s);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "readInt " + slotId, e);
            }
            return 0;
        }

        private String readText(int slotId) {
            ComplicationSlot slot = complMgr.get(slotId);
            if (slot == null) return "";
            ComplicationData data = slot.getComplicationData().getValue();
            if (data == null) return "";
            try {
                if (data instanceof ShortTextComplicationData) {
                    CharSequence txt = ((ShortTextComplicationData) data)
                            .getText().getTextAt(getResources(), java.time.Instant.now());
                    return txt != null ? txt.toString() : "";
                }
            } catch (Exception e) {
                Log.w(TAG, "readText " + slotId, e);
            }
            return "";
        }

        // --- Position helper ---
        private float y(float pct) { return CY + (pct - 0.5f) * W; }

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
            pArcSteps.setAlpha(35);
            c.drawArc(arcOuter, 135, 270, false, pArcSteps);
            pArcSteps.setAlpha(230);
            c.drawArc(arcOuter, 135, sp * 270, false, pArcSteps);
            pArcCal.setAlpha(35);
            c.drawArc(arcInner, 135, 270, false, pArcCal);
            pArcCal.setAlpha(230);
            c.drawArc(arcInner, 135, cp * 270, false, pArcCal);
        }

        // --- PAS | CAL ---
        private void drawStepsCal(Canvas c, int steps, int cals) {
            float lx = CX - W * 0.16f, rx = CX + W * 0.16f;
            float yLab = y(0.135f), yVal = y(0.195f), yGoal = y(0.235f);

            pLabel.setColor(COL_STEPS);
            c.drawText("PAS", lx, yLab, pLabel);
            pValue.setColor(COL_STEPS); pValue.setAlpha(255);
            c.drawText(String.valueOf(steps), lx, yVal, pValue);
            pGoal.setColor(COL_STEPS); pGoal.setAlpha(115);
            c.drawText("/10k", lx, yGoal, pGoal);

            c.drawLine(CX, yLab - 8, CX, yGoal + 2, pSep);

            pLabel.setColor(COL_CAL);
            c.drawText("CAL", rx, yLab, pLabel);
            pValue.setColor(COL_CAL); pValue.setAlpha(255);
            c.drawText(cals > 0 ? String.valueOf(cals) : "---", rx, yVal, pValue);
            pGoal.setColor(COL_CAL); pGoal.setAlpha(115);
            c.drawText("/" + CAL_GOAL, rx, yGoal, pGoal);
        }

        // --- FC ---
        private void drawHealth(Canvas c, int hr) {
            String hrTxt = hr > 0 ? ("\u2665 " + hr + " bpm") : "\u2665 -- bpm";
            c.drawText(hrTxt, CX, y(0.30f), pHealth);
        }

        // --- HEURE ---
        private void drawTime(Canvas c, ZonedDateTime z) {
            String hhmm = fmt("%02d:%02d", z.getHour(), z.getMinute());
            String ss = fmt(":%02d", z.getSecond());
            float timeY = y(0.475f);
            c.drawText(hhmm, CX - 18, timeY, pTime);
            float hw = pTime.measureText(hhmm) / 2f;
            c.drawText(ss, CX - 18 + hw + 3, timeY, pTimeSec);
        }

        // --- MÉTÉO (Temp° | Conditions | UV) comme le mockup ---
        private void drawWeather(Canvas c, String temp, String weather, String uv, ZonedDateTime z) {
            float infoY = y(0.635f);
            boolean hasTemp = temp != null && !temp.isEmpty();
            boolean hasWeather = weather != null && !weather.isEmpty();
            boolean hasUv = uv != null && !uv.isEmpty();

            if (hasTemp || hasWeather || hasUv) {
                // Layout 3 colonnes : Temp | Météo | UV
                float col1 = CX - W * 0.20f;
                float col2 = CX;
                float col3 = CX + W * 0.20f;

                if (hasTemp) c.drawText(temp, col1, infoY, pInfo);

                if (hasTemp && (hasWeather || hasUv))
                    c.drawLine(CX - W * 0.10f, infoY - 10, CX - W * 0.10f, infoY + 5, pSep);

                if (hasWeather) c.drawText(weather, col2, infoY, pInfo);

                if ((hasTemp || hasWeather) && hasUv)
                    c.drawLine(CX + W * 0.10f, infoY - 10, CX + W * 0.10f, infoY + 5, pSep);

                if (hasUv) {
                    String uvLabel = uv.toLowerCase().contains("uv") ? uv : "UV " + uv;
                    c.drawText(uvLabel, col3, infoY, pInfo);
                }
            } else {
                // Fallback : lune + semaine (pas de météo disponible)
                float spread = W * 0.14f;
                c.drawText(moonPhase(), CX - spread, infoY, pInfo);
                c.drawLine(CX, infoY - 10, CX, infoY + 5, pSep);
                int week = z.get(WeekFields.ISO.weekOfWeekBasedYear());
                c.drawText("Sem " + week, CX + spread, infoY, pInfo);
            }
        }

        // --- DATE ---
        private void drawDate(Canvas c, ZonedDateTime z) {
            String d = dayShort(z.getDayOfWeek().getValue()) + "  " +
                    z.getDayOfMonth() + "  " + monthShort(z.getMonthValue() - 1);
            c.drawText(d, CX, y(0.74f), pDate);
        }

        // --- BATTERIE ---
        private void drawBattery(Canvas c, int batt) {
            float bw = W * 0.50f, bh = 8;
            float left = CX - bw / 2f;
            float barY = y(0.80f);
            c.drawRoundRect(left, barY, left + bw, barY + bh, 4, 4, pBarBg);
            float fill = (batt / 100f) * bw;
            pBarFill.setColor(batt > 20 ? COL_BLUE : COL_HEALTH);
            c.drawRoundRect(left, barY, left + fill, barY + bh, 4, 4, pBarFill);
            pBattTxt.setColor(batt > 20 ? COL_BLUE : COL_HEALTH);
            c.drawText(batt + "%", CX, y(0.87f), pBattTxt);
        }

        // --- PAINTS ---
        private void initPaints() {
            Typeface bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
            Typeface normal = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
            setupStroke(pArcSteps, COL_STEPS, 12);
            setupStroke(pArcCal, COL_CAL, 8);
            setupText(pLabel, COL_STEPS, 16, bold);
            setupText(pValue, COL_STEPS, 36, bold);
            setupText(pGoal, COL_STEPS, 14, normal); pGoal.setAlpha(115);
            setupText(pHealth, COL_HEALTH, 20, bold);
            setupText(pTime, COL_WHITE, 80, bold);
            setupText(pTimeSec, COL_WHITE, 32, normal);
            pTimeSec.setAlpha(90); pTimeSec.setTextAlign(Paint.Align.LEFT);
            setupText(pInfo, COL_VIOLET, 22, bold);
            setupText(pDate, COL_GREY, 28, bold);
            setupText(pBattTxt, COL_BLUE, 28, bold);
            pSep.setColor(COL_SEP); pSep.setStrokeWidth(2f);
            pBarBg.setColor(COL_BLUE); pBarBg.setAlpha(35);
            pBarFill.setColor(COL_BLUE);
            setupText(pAmbTime, COL_WHITE, 80, bold); pAmbTime.setAlpha(180);
            setupText(pAmbDate, COL_GREY, 22, normal); pAmbDate.setAlpha(128);
            setupText(pAmbBatt, COL_BLUE, 18, normal); pAmbBatt.setAlpha(128);
        }

        private void setupStroke(Paint p, int c, float w) {
            p.setStyle(Paint.Style.STROKE); p.setColor(c);
            p.setStrokeWidth(w); p.setStrokeCap(Paint.Cap.ROUND);
        }

        private void setupText(Paint p, int c, float s, Typeface tf) {
            p.setColor(c); p.setTextSize(s);
            p.setTypeface(tf); p.setTextAlign(Paint.Align.CENTER);
        }

        // --- UTILS ---
        private String moonPhase() {
            long days = ChronoUnit.DAYS.between(LocalDate.of(2000, 1, 6), LocalDate.now());
            double age = ((days % 29.53) + 29.53) % 29.53;
            String[] e = {"\uD83C\uDF11","\uD83C\uDF12","\uD83C\uDF13","\uD83C\uDF14",
                    "\uD83C\uDF15","\uD83C\uDF16","\uD83C\uDF17","\uD83C\uDF18"};
            return e[Math.min((int)(age / 3.69), 7)];
        }

        private String dayShort(int iso) {
            return new String[]{"Lun","Mar","Mer","Jeu","Ven","Sam","Dim"}[iso - 1];
        }

        private String monthShort(int m) {
            return new String[]{"Jan","F\u00e9v","Mar","Avr","Mai","Jun",
                    "Jul","Ao\u00fb","Sep","Oct","Nov","D\u00e9c"}[m];
        }

        private String fmt(String f, Object... a) {
            return String.format(Locale.getDefault(), f, a);
        }
    }

    // =========================================================================
    //  NoopCanvasComplication — on dessine nous-mêmes, pas besoin du rendu par défaut
    // =========================================================================
    static class NoopCanvasComplication implements androidx.wear.watchface.CanvasComplication {
        @Override public void render(@NonNull Canvas c, @NonNull Rect b,
                                      @NonNull ZonedDateTime z, @NonNull RenderParameters rp, int slotId) {}
        @Override public void drawHighlight(@NonNull Canvas c, @NonNull Rect b,
                                             int boundsType, @NonNull ZonedDateTime z, int color) {}
        @NonNull @Override
        public ComplicationData getData() {
            return new androidx.wear.watchface.complications.data.NoDataComplicationData();
        }
        @Override public void loadData(@NonNull ComplicationData data, boolean loadDrawablesAsync) {}
    }
}
