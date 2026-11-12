package com.defi.watchface.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Icônes Material Design Icons (« mdi:pool »), comme dans Home Assistant :
 * police MDI 7.4.47 (assets/mdi.ttf, Apache 2.0, assets/mdi-LICENSE.txt) et
 * table nom → caractère (assets/mdi.txt, tirée du CSS de @mdi/font).
 * Dessinées ici, sur le téléphone, et envoyées en PNG à la montre.
 */
final class MdiIcons {
    private static final String TAG = "DefiMdi";
    private static Typeface typeface;
    private static Map<String, Integer> codes;

    private MdiIcons() {
    }

    private static synchronized boolean load(Context ctx) {
        if (codes != null)
            return typeface != null;
        codes = new HashMap<>();
        try {
            typeface = Typeface.createFromAsset(ctx.getAssets(), "mdi.ttf");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    ctx.getAssets().open("mdi.txt"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int sp = line.indexOf(' ');
                    if (sp > 0)
                        codes.put(line.substring(0, sp), Integer.parseInt(line.substring(sp + 1), 16));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Police MDI illisible", e);
            typeface = null;
        }
        return typeface != null;
    }

    /**
     * Icône par défaut d'une entité sans attribut icon, d'après son domaine et
     * son état (règles simplifiées du frontend de Home Assistant)
     */
    static String defaultIcon(String domain, String state, boolean active) {
        switch (domain) {
            case "light": return active ? "lightbulb" : "lightbulb-outline";
            case "switch": return active ? "toggle-switch-variant" : "toggle-switch-variant-off";
            case "input_boolean": return active ? "check-circle-outline" : "close-circle-outline";
            case "script": return "script-text-outline";
            case "scene": return "palette";
            case "button":
            case "input_button": return "gesture-tap-button";
            case "cover": return active ? "window-shutter-open" : "window-shutter";
            case "lock": return "unlocked".equals(state) ? "lock-open-variant" : "lock";
            case "fan": return active ? "fan" : "fan-off";
            case "climate": return "thermostat";
            case "media_player": return active ? "cast-connected" : "cast";
            case "automation": return active ? "robot" : "robot-off";
            case "vacuum": return "robot-vacuum";
            default: return "bookmark";
        }
    }

    /** PNG carré de l'icône (« mdi:pool » ou « pool ») dans la couleur donnée, null si inconnue */
    static byte[] png(Context ctx, String icon, int color, int sizePx) {
        if (!load(ctx))
            return null;
        String name = icon.startsWith("mdi:") ? icon.substring(4) : icon;
        Integer code = codes.get(name);
        if (code == null)
            return null;
        String glyph = new String(Character.toChars(code));
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(typeface);
        p.setColor(color);
        p.setTextSize(sizePx);
        p.setTextAlign(Paint.Align.CENTER);
        // Centrage vertical sur la boîte réelle du glyphe
        Rect b = new Rect();
        p.getTextBounds(glyph, 0, glyph.length(), b);
        canvas.drawText(glyph, sizePx / 2f, sizePx / 2f - b.exactCenterY(), p);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        bmp.recycle();
        return out.toByteArray();
    }
}
