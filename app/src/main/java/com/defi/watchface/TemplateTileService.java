package com.defi.watchface;

import static androidx.wear.protolayout.ColorBuilders.argb;
import static androidx.wear.protolayout.DimensionBuilders.dp;
import static androidx.wear.protolayout.DimensionBuilders.expand;
import static androidx.wear.protolayout.DimensionBuilders.sp;

import android.graphics.Color;

import android.util.Log;

import androidx.annotation.NonNull;
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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tuiles Modèle 1 à 4 : texte d'un modèle Home Assistant, calculé par le
 * serveur et poussé par le téléphone chaque fois que le résultat change
 * (render_template). Le texte peut porter le HTML restreint des tuiles de
 * l'appli Home Assistant : <b>, <big>, <small>, <font color>, <br>, et <p> qui sépare
 * des paragraphes d'un petit interligne.
 */
public abstract class TemplateTileService extends TileService {

    private static final String RESOURCES_VERSION = "1";
    /**
     * Texte normal à la taille par défaut des tuiles (aucune taille imposée, ≈ 18 sp mesuré),
     * <big> comme l'appli Home Assistant (1,25 × 16 sp). <small> plus grand que chez elle
     * (0,8 × 16 = 12,8 sp, illisible au poignet) : 16 sp, un cran sous le texte normal.
     * Une ligne <small> doit rester courte (≈ 22 caractères) pour tenir dans le rond :
     * les modèles coupent les longues par <br> (bus et délai de sensor.montre_ecole).
     */
    private static final float SIZE_DEFAULT = 0f;
    private static final float SIZE_BIG = 1.25f * 16;
    private static final float SIZE_SMALL = 16f;
    /** Hauteur de l'interligne d'un <p> : une ligne vide en tout petit */
    private static final float SIZE_GAP = 8f;
    private static final Pattern TAG = Pattern.compile("<\\s*(/?)\\s*([a-zA-Z]+)([^>]*)>");
    private static final Pattern COLOR = Pattern.compile("color\\s*=\\s*['\"]?([#a-zA-Z0-9]+)");

    /** Numéro de l'emplacement, de 1 à 4 (un service par tuile du carrousel) */
    protected abstract int slot();

    public static class Slot1 extends TemplateTileService {
        @Override protected int slot() { return 1; }
    }

    public static class Slot2 extends TemplateTileService {
        @Override protected int slot() { return 2; }
    }

    public static class Slot3 extends TemplateTileService {
        @Override protected int slot() { return 3; }
    }

    public static class Slot4 extends TemplateTileService {
        @Override protected int slot() { return 4; }
    }

    static Class<? extends TileService> slotClass(int slot) {
        switch (slot) {
            case 1: return Slot1.class;
            case 2: return Slot2.class;
            case 3: return Slot3.class;
            default: return Slot4.class;
        }
    }

    @NonNull
    @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(@NonNull RequestBuilders.TileRequest request) {
        return TileStore.logged("Modèle " + slot(), Futures.submit(() -> {
            TileStore.ensureLoaded(this);
            return new TileBuilders.Tile.Builder()
                    .setResourcesVersion(RESOURCES_VERSION)
                    // Aucun rafraîchissement périodique : le téléphone pousse chaque changement
                    .setFreshnessIntervalMillis(0)
                    .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout()))
                    .build();
        }, TileStore.BACKGROUND));
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

    private LayoutElementBuilders.LayoutElement layout() {
        String text = TileStore.getTemplate(slot());
        LayoutElementBuilders.Column.Builder col = new LayoutElementBuilders.Column.Builder();
        if (PhoneLink.isDown())
            col.addContent(ShortcutsTileService.text("📵 téléphone injoignable", 12, 0xFFFF4466, 1))
                    .addContent(ShortcutsTileService.spacer(6));
        col.addContent(text.isEmpty()
                ? ShortcutsTileService.text("Modèle " + slot() + " à régler dans l'appli du téléphone",
                        14, 0xFFAAAAAA, 3)
                : html(text));
        return new LayoutElementBuilders.Box.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        // Marges latérales étroites, comme l'appli HA : une ligne y tient entière
                        .setPadding(new ModifiersBuilders.Padding.Builder()
                                .setTop(dp(24)).setBottom(dp(24)).setStart(dp(6)).setEnd(dp(6)).build())
                        .build())
                .addContent(col.build())
                .build();
    }

    /** Style en cours pendant la lecture du HTML */
    private static final class Style {
        int bold;
        final Deque<Float> sizes = new ArrayDeque<>();
        final Deque<Integer> colors = new ArrayDeque<>();
        /** Texte déjà écrit, et s'il finit par un retour à la ligne */
        boolean started;
        boolean atLineStart = true;

        float size() {
            return sizes.isEmpty() ? SIZE_DEFAULT : sizes.peek();
        }

        int color() {
            return colors.isEmpty() ? 0xFFFFFFFF : colors.peek();
        }
    }

    /** HTML restreint des tuiles Home Assistant → texte mis en forme de la tuile */
    static LayoutElementBuilders.Spannable html(String src) {
        LayoutElementBuilders.Spannable.Builder sp = new LayoutElementBuilders.Spannable.Builder()
                .setMaxLines(14)
                .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END);
        Style st = new Style();
        StringBuilder pending = new StringBuilder();
        Matcher m = TAG.matcher(src);
        int pos = 0;
        while (m.find()) {
            pending.append(decode(src.substring(pos, m.start())));
            pos = m.end();
            boolean closing = !m.group(1).isEmpty();
            String tag = m.group(2).toLowerCase();
            if ("br".equals(tag)) {
                pending.append('\n');
                continue;
            }
            if ("p".equals(tag)) {
                if (!closing)
                    gap(sp, pending, st);
                continue;
            }
            // Le style change : le texte accumulé part avec l'ancien style
            flush(sp, pending, st);
            switch (tag) {
                case "b":
                case "strong":
                    st.bold += closing ? (st.bold > 0 ? -1 : 0) : 1;
                    break;
                case "big":
                case "small":
                    if (closing) {
                        if (!st.sizes.isEmpty())
                            st.sizes.pop();
                    } else {
                        st.sizes.push("big".equals(tag) ? SIZE_BIG : SIZE_SMALL);
                    }
                    break;
                case "font":
                    if (closing) {
                        if (!st.colors.isEmpty())
                            st.colors.pop();
                    } else {
                        st.colors.push(parseColor(m.group(3), st.color()));
                    }
                    break;
                default:
                    break;
            }
        }
        pending.append(decode(src.substring(pos)));
        flush(sp, pending, st);
        return sp.build();
    }

    /**
     * <p> : nouveau paragraphe, séparé du précédent par un petit interligne (une ligne vide
     * entière, comme l'appli HA, prendrait trop de hauteur sur la montre). Rien en tête de texte.
     */
    private static void gap(LayoutElementBuilders.Spannable.Builder sp, StringBuilder pending, Style st) {
        if (pending.length() == 0 && !st.started)
            return;
        flush(sp, pending, st);
        // La hauteur d'une ligne suit sa plus grande police : le retour qui finit la ligne en
        // cours et la ligne d'interligne (une espace insécable) sont en petit, sinon le retour
        // pris dans la police normale agrandirait la ligne qu'il termine
        sp.addSpan(new LayoutElementBuilders.SpanText.Builder()
                .setText((st.atLineStart ? "" : "\n") + " \n")
                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder().setSize(sp(SIZE_GAP)).build())
                .build());
        st.atLineStart = true;
    }

    private static void flush(LayoutElementBuilders.Spannable.Builder sp, StringBuilder pending, Style st) {
        if (pending.length() == 0)
            return;
        st.started = true;
        st.atLineStart = pending.charAt(pending.length() - 1) == '\n';
        LayoutElementBuilders.FontStyle.Builder font = new LayoutElementBuilders.FontStyle.Builder()
                .setColor(argb(st.color()));
        if (st.size() > 0)
            font.setSize(sp(st.size()));
        if (st.bold > 0)
            font.setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD);
        sp.addSpan(new LayoutElementBuilders.SpanText.Builder()
                .setText(pending.toString())
                .setFontStyle(font.build())
                .build());
        pending.setLength(0);
    }

    private static int parseColor(String attrs, int fallback) {
        Matcher c = COLOR.matcher(attrs);
        if (!c.find())
            return fallback;
        try {
            return Color.parseColor(c.group(1));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * Comme l'appli Home Assistant : un retour à la ligne du texte devient un saut de ligne
     * (<br>) ; les autres blancs se réduisent à une espace, comme en HTML ; entités courantes
     */
    private static String decode(String s) {
        return s.replaceAll("\\r\\n|\\r", "\n")
                .replaceAll("[ \\t\\f]+", " ")
                .replaceAll(" ?\n ?", "\n")
                .replace("&nbsp;", "\u00A0")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
    }
}
