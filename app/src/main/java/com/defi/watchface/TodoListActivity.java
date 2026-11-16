package com.defi.watchface;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Liste entière de la tuile Liste, qui défile (doigt ou couronne) : une tuile ne défile
 * pas et n'a la place que de 4 lignes. Mêmes gestes que la tuile : ○ coche, ✓ décoche,
 * le texte ouvre l'édition, + ajoute ; les tâches faites s'affichent à la demande.
 * Se redessine à chaque nouvelle liste reçue du téléphone.
 */
public class TodoListActivity extends Activity {

    private static final int C_TITLE = 0xFFAA88FF;
    private static final int C_DONE = 0xFF777780;
    private static final int C_BOX_BORDER = 0xFF666670;
    private static final int C_BOX_DONE = 0xFF44FF88;
    private static final int C_PENDING = 0xFF66BBFF;
    private static final int C_FAILED = 0xFFFF4466;

    private float density;
    private ScrollView scroll;
    private LinearLayout list;
    private boolean showDone;
    private final Runnable onChange = () -> runOnUiThread(this::render);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        // Ouverte depuis la tuile : mêmes tâches visibles (faites affichées ou non)
        showDone = TileStore.todoShowDone;
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setGravity(Gravity.CENTER_HORIZONTAL);
        // Écran rond : marges larges en haut et en bas, où le cercle est étroit
        list.setPadding(px(26), px(40), px(26), px(48));
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(list);
        setContentView(scroll);
        // La couronne fait défiler la vue qui a le focus
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        scroll.requestFocus();
        render();
        // Processus relancé : la liste gardée par la DataLayer est relue hors du fil principal
        TileStore.BACKGROUND.execute(() -> {
            TileStore.ensureLoaded(this);
            runOnUiThread(this::render);
        });
        TileStore.requestFresh(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        TileStore.todoListener = onChange;
        render();
    }

    @Override
    protected void onPause() {
        if (TileStore.todoListener == onChange)
            TileStore.todoListener = null;
        super.onPause();
    }

    private void render() {
        if (isFinishing())
            return;
        list.removeAllViews();
        TileStore.Todo todo = TileStore.getTodo();
        if (todo == null) {
            list.addView(label("Liste en attente du téléphone", 14, 0xFFAAAAAA));
            return;
        }
        int pending = 0;
        for (TileStore.TodoItem it : todo.items)
            if (!it.done)
                pending++;
        TextView title = label((todo.name.isEmpty() ? "Liste" : todo.name)
                + (pending > 0 ? " · " + pending : ""), 13, C_TITLE);
        title.setPadding(0, 0, 0, px(6));
        list.addView(title);
        if (PhoneLink.isDown())
            list.addView(label("📵 téléphone injoignable", 12, C_FAILED));
        String error = !todo.error.isEmpty() ? "⚠ " + todo.error : TileStore.todoError();
        if (error != null)
            list.addView(label(error, 12, C_FAILED));
        for (TileStore.TodoItem it : todo.items)
            if (!it.done || showDone)
                list.addView(row(it));
        if (pending == 0 && !showDone)
            list.addView(label("Rien à faire", 14, 0xFFAAAAAA));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, px(10), 0, 0);
        buttons.addView(roundButton("+", 22, 0xFF3A2A55, Color.WHITE, v -> startActivity(
                new Intent(this, TodoActivity.class).putExtra(TodoActivity.EXTRA_MODE, TodoActivity.MODE_ADD))));
        if (todo.doneCount > 0) {
            View gap = new View(this);
            buttons.addView(gap, new LinearLayout.LayoutParams(px(12), 1));
            TextView done = roundButton("✓" + (todo.doneCount > 9 ? "9+" : String.valueOf(todo.doneCount)),
                    12, showDone ? 0xFF1A3A26 : 0xFF2A2A35, showDone ? C_BOX_DONE : 0xFFAAAAAA, v -> {
                        showDone = !showDone;
                        render();
                    });
            buttons.addView(done);
        }
        list.addView(buttons);
    }

    /** ○ / ✓ (coche, décoche) puis le texte (édition) */
    private View row(TileStore.TodoItem it) {
        String key = TileStore.todoKey(it.uid);
        TextView box = new TextView(this);
        box.setText(it.done ? "✓" : "");
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.setTextColor(C_BOX_DONE);
        box.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(it.done ? 0xFF1A3A26 : Color.BLACK);
        bg.setStroke(px(2), TileStore.hasFailed(key) ? C_FAILED : TileStore.isPending(key) ? C_PENDING
                : it.done ? C_BOX_DONE : C_BOX_BORDER);
        box.setBackground(bg);
        box.setOnClickListener(v -> {
            TileStore.todoCommand(this, it.done ? "uncheck" : "check", it.uid, null);
            render();
        });

        TextView text = new TextView(this);
        text.setText(it.summary);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(it.done ? C_DONE : Color.WHITE);
        text.setMaxLines(2);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setPadding(px(8), px(4), 0, px(4));
        text.setOnClickListener(v -> startActivity(new Intent(this, TodoActivity.class)
                .putExtra(TodoActivity.EXTRA_MODE, TodoActivity.MODE_EDIT)
                .putExtra(TodoActivity.EXTRA_UID, it.uid)
                .putExtra(TodoActivity.EXTRA_SUMMARY, it.summary)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(3), 0, px(3));
        row.addView(box, new LinearLayout.LayoutParams(px(26), px(26)));
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView label(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView roundButton(String s, int sp, int color, int textColor, View.OnClickListener l) {
        TextView b = label(s, sp, textColor);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        b.setBackground(bg);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(px(40), px(40)));
        return b;
    }

    private int px(int dp) {
        return (int) (dp * density);
    }
}
