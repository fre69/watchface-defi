package com.defi.watchface;

import android.app.Activity;
import android.app.RemoteInput;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.wear.input.RemoteInputIntentHelper;

import java.util.Collections;

/**
 * Tuile Liste : ajout d'une tâche (saisie du système directement : dictée ou clavier)
 * ou édition d'une tâche (texte modifiable, ✓ enregistre, 🗑 supprime après
 * confirmation). Les commandes partent au téléphone ; la tuile se redessine quand
 * la nouvelle liste revient.
 */
public class TodoActivity extends Activity {
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_UID = "uid";
    static final String EXTRA_SUMMARY = "summary";
    static final String MODE_ADD = "add";
    static final String MODE_EDIT = "edit";
    private static final String RESULT_KEY = "todo";
    private static final int REQUEST_INPUT = 1;

    private float density;
    private EditText field;
    private TextView delete;
    private boolean confirmDelete;
    /** ✓ du clavier touché : enregistrer dès que le texte saisi arrive dans le champ */
    private boolean saveOnCommit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        if (!MODE_EDIT.equals(getIntent().getStringExtra(EXTRA_MODE))) {
            if (savedInstanceState == null)
                askNewItem();
            return;
        }
        buildEditor();
    }

    // =========================================================================
    // Ajout : saisie du système, sans écran propre
    // =========================================================================

    private void askNewItem() {
        RemoteInput input = new RemoteInput.Builder(RESULT_KEY).setLabel("Nouvelle tâche").build();
        Intent intent = RemoteInputIntentHelper.createActionRemoteInputIntent();
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, Collections.singletonList(input));
        startActivityForResult(intent, REQUEST_INPUT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Bundle results = data != null ? RemoteInput.getResultsFromIntent(data) : null;
        CharSequence text = results != null ? results.getCharSequence(RESULT_KEY) : null;
        if (text != null && text.toString().trim().length() > 0)
            TileStore.todoCommand(this, "add", null, capitalize(text.toString().trim()));
        finish();
    }

    /** La dictée rend « pain » : la liste HA les écrit avec une majuscule */
    private static String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    // =========================================================================
    // Édition : texte modifiable, enregistrer ou supprimer
    // =========================================================================

    private void buildEditor() {
        String uid = getIntent().getStringExtra(EXTRA_UID);
        String summary = getIntent().getStringExtra(EXTRA_SUMMARY);
        if (uid == null) {
            finish();
            return;
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(px(30), px(20), px(30), px(20));

        TextView title = new TextView(this);
        title.setText("Modifier la tâche");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTextColor(0xFFAA88FF);
        title.setGravity(Gravity.CENTER);
        col.addView(title);

        // Appui sur le champ : clavier ou dictée du système, texte actuel conservé
        field = new EditText(this);
        field.setText(summary != null ? summary : "");
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        field.setTextColor(Color.WHITE);
        field.setGravity(Gravity.CENTER);
        // Une seule ligne de saisie (↵ du clavier = enregistrer), affichée sur 3 lignes au plus :
        // avec TYPE_TEXT_FLAG_MULTI_LINE, ↵ insère un retour à la ligne et ne valide rien
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setHorizontallyScrolling(false);
        field.setMaxLines(3);
        // ✓ du clavier : le clavier plein écran de la montre annonce la fin avant de recopier
        // le texte dans le champ (encore l'ancien 400 ms après). On enregistre quand le texte
        // arrive, ou au bout de 1,5 s s'il n'a pas changé
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveOnCommit = true;
                field.postDelayed(() -> save(uid, summary), 1500);
            }
            return false;
        });
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (saveOnCommit)
                    field.post(() -> save(uid, summary));
            }
        });
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fp.topMargin = px(6);
        fp.bottomMargin = px(10);
        col.addView(field, fp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        delete = roundButton("🗑", 0xFF3A1A22, v -> deletePressed(uid));
        buttons.addView(delete);
        View gap = new View(this);
        buttons.addView(gap, new LinearLayout.LayoutParams(px(20), 1));
        buttons.addView(roundButton("✓", 0xFF1A3A26, v -> save(uid, summary)));
        col.addView(buttons);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void save(String uid, String old) {
        if (isFinishing())
            return;
        String text = field.getText().toString().replace('\n', ' ').trim();
        android.util.Log.d("DefiTiles", "Tâche : « " + old + " » → « " + text + " »");
        if (!text.isEmpty() && !text.equals(old))
            TileStore.todoCommand(this, "rename", uid, text);
        finish();
    }

    /** Premier appui : demande de confirmation ; second : suppression */
    private void deletePressed(String uid) {
        if (!confirmDelete) {
            confirmDelete = true;
            delete.setText("Sûr ?");
            delete.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            ((GradientDrawable) delete.getBackground()).setColor(0xFFFF4466);
            return;
        }
        TileStore.todoCommand(this, "delete", uid, null);
        finish();
    }

    private TextView roundButton(String label, int color, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        b.setBackground(bg);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(px(48), px(48)));
        return b;
    }

    private int px(int dp) {
        return (int) (dp * density);
    }
}
