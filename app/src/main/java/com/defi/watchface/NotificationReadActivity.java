package com.defi.watchface;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Lecture d'une notification en entier : la bulle du cadran coupe le texte à
 * 4 lignes, et un cadran ne reçoit que des appuis (un glissement ouvre la zone
 * de notifications du système). Cette page défile au doigt ou à la couronne,
 * avec l'indicateur de défilement rond de Wear OS, et propose les réponses
 * rapides en bas. Ouverte d'un appui sur la bulle ; un glissement vers la
 * droite ramène au cadran.
 */
public class NotificationReadActivity extends Activity {

    private static final String[] QUICK_REPLIES = { "Oui", "Non", "OK", "✎" };
    private static final int C_APP = 0xFFAA88FF;
    private static final int C_TEXT = 0xFFDDDDDD;
    private static final int C_CHIP = 0xFF2A2A35;

    private float density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        // Écran rond : marge large en bas, où le cercle est étroit. En haut, juste ce qu'il
        // faut pour la ligne courte icône + appli (26/11/2026 : 36 dp et deux lignes avant)
        col.setPadding(px(30), px(14), px(30), px(56));

        // Icône et nom de l'appli sur une seule ligne, comme dans la bulle
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER);
        Bitmap icon = NotificationHolder.getIcon();
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(icon);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(18), px(18));
            lp.rightMargin = px(5);
            head.addView(iv, lp);
        }
        TextView app = text(NotificationHolder.getAppName(), 13, C_APP, true);
        app.setSingleLine(true);
        app.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(app);
        col.addView(head);
        TextView title = text(NotificationHolder.getTitle(), 17, Color.WHITE, true);
        // Un peu d'air entre la ligne de l'appli et le titre
        title.setPadding(0, px(6), 0, 0);
        col.addView(title);

        Bitmap picture = NotificationHolder.getPicture();
        if (picture != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(picture);
            iv.setAdjustViewBounds(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = px(6);
            col.addView(iv, lp);
        }

        TextView body = text(NotificationHolder.getText(), 15, C_TEXT, false);
        // Centré : aligné à gauche, le début des lignes est rogné par le bord rond
        body.setPadding(0, px(8), 0, px(8));
        col.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        String replyId = NotificationHolder.getReplyId();
        if (replyId != null)
            col.addView(replyRow(replyId));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(col);
        setContentView(scroll);
        // La couronne fait défiler la vue qui a le focus
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        scroll.requestFocus();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s != null ? s : "");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        if (bold)
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    /** Oui / Non / OK envoyés tels quels, ✎ ouvre la saisie du système ; retour au cadran */
    private LinearLayout replyRow(String replyId) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, px(6), 0, 0);
        for (String label : QUICK_REPLIES) {
            TextView chip = text(label, 14, Color.WHITE, true);
            chip.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(C_CHIP);
            bg.setCornerRadius(px(16));
            chip.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(40), px(32));
            lp.setMargins(px(2), 0, px(2), 0);
            chip.setOnClickListener(v -> {
                if ("✎".equals(label)) {
                    startActivity(new Intent(this, ReplyActivity.class)
                            .putExtra(ReplyActivity.EXTRA_REPLY_ID, replyId));
                } else {
                    ReplySender.send(this, replyId, label);
                }
                finish();
            });
            row.addView(chip, lp);
        }
        return row;
    }

    private int px(int dp) {
        return (int) (dp * density);
    }
}
