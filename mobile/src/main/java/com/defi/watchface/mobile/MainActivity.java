package com.defi.watchface.mobile;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    /** Boutons de la tuile Raccourcis (le micro d'Assist en plus) */
    private static final int SHORTCUTS = 13;

    private TextView statusText;
    private TextView haStatus;
    private EditText haUrl;
    private EditText haToken;
    private Button btnBattery;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        Button btnEnable = findViewById(R.id.btn_enable);

        btnEnable.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        });

        // --- Home Assistant ---
        haUrl = findViewById(R.id.ha_url);
        haToken = findViewById(R.id.ha_token);
        haStatus = findViewById(R.id.ha_status);
        SharedPreferences prefs = getSharedPreferences(HaLink.PREFS, MODE_PRIVATE);
        haUrl.setText(prefs.getString(HaLink.PREF_URL, ""));
        // Le jeton n'est jamais réaffiché : champ vide = garder le jeton enregistré
        if (!prefs.getString(HaLink.PREF_TOKEN, "").isEmpty())
            haToken.setHint("Jeton enregistré (laisser vide pour le garder)");

        findViewById(R.id.btn_ha_save).setOnClickListener(v -> {
            SharedPreferences.Editor ed = prefs.edit()
                    .putString(HaLink.PREF_URL, haUrl.getText().toString().trim());
            String token = haToken.getText().toString().trim();
            if (!token.isEmpty())
                ed.putString(HaLink.PREF_TOKEN, token);
            ed.apply();
            haToken.setText("");
            if (!prefs.getString(HaLink.PREF_TOKEN, "").isEmpty())
                haToken.setHint("Jeton enregistré (laisser vide pour le garder)");
            HaLink.get(this).restart();
        });

        // --- Tuiles de la montre (Raccourcis, Modèle 1 à 4, Thermostat, Liste) ---
        EditText[][] shortcuts = buildShortcutRows(findViewById(R.id.tile_entities));
        EditText[] templates = {
                findViewById(R.id.tile_template_1), findViewById(R.id.tile_template_2),
                findViewById(R.id.tile_template_3), findViewById(R.id.tile_template_4) };
        EditText tileClimate = findViewById(R.id.tile_climate);
        EditText tileTodo = findViewById(R.id.tile_todo);
        List<String[]> saved = HaLink.parseTileEntities(prefs.getString(HaLink.PREF_TILE_ENTITIES, ""));
        for (int i = 0; i < Math.min(saved.size(), SHORTCUTS); i++) {
            shortcuts[i][0].setText(saved.get(i)[0]);
            shortcuts[i][1].setText(saved.get(i)[1]);
        }
        tileClimate.setText(prefs.getString(HaLink.PREF_TILE_CLIMATE, ""));
        tileTodo.setText(prefs.getString(HaLink.PREF_TILE_TODO, HaLink.DEFAULT_TODO));
        for (int i = 0; i < templates.length; i++)
            templates[i].setText(prefs.getString(HaLink.PREF_TILE_TEMPLATE + (i + 1), ""));
        findViewById(R.id.btn_tiles_save).setOnClickListener(v -> {
            SharedPreferences.Editor ed = prefs.edit()
                    .putString(HaLink.PREF_TILE_ENTITIES, shortcutsText(shortcuts))
                    .putString(HaLink.PREF_TILE_CLIMATE, tileClimate.getText().toString().trim())
                    .putString(HaLink.PREF_TILE_TODO, tileTodo.getText().toString().trim());
            for (int i = 0; i < templates.length; i++)
                ed.putString(HaLink.PREF_TILE_TEMPLATE + (i + 1), templates[i].getText().toString().trim());
            ed.apply();
            // Nouvel abonnement aux entités et au modèle, puis envoi à la montre
            HaLink.get(this).restart();
        });

        // Le gestionnaire de batterie du téléphone coupe sinon le WebSocket
        btnBattery = findViewById(R.id.btn_battery);
        btnBattery.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()))));

        HaLink.get(this).start();
    }

    /** 9 lignes : numéro, entité, nom court ; rend les champs [ligne][0 = entité, 1 = nom] */
    private EditText[][] buildShortcutRows(LinearLayout container) {
        EditText[][] fields = new EditText[SHORTCUTS][2];
        for (int i = 0; i < SHORTCUTS; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView num = new TextView(this);
            num.setText((i + 1) + ".");
            num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            num.setMinWidth((int) (24 * getResources().getDisplayMetrics().density));
            row.addView(num);
            fields[i][0] = field(i == 0 ? "light.salon" : "entité");
            fields[i][1] = field(i == 0 ? "Salon" : "nom");
            row.addView(fields[i][0], new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3));
            row.addView(fields[i][1], new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2));
            // ▲ ▼ : échange la ligne avec sa voisine (ordre des boutons sur la tuile)
            final int index = i;
            row.addView(arrow("▲", i > 0, v -> swap(fields, index, index - 1)));
            row.addView(arrow("▼", i < SHORTCUTS - 1, v -> swap(fields, index, index + 1)));
            container.addView(row);
        }
        return fields;
    }

    private TextView arrow(String label, boolean enabled, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setGravity(Gravity.CENTER);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        t.setPadding(pad, pad, pad, pad);
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        t.setBackgroundResource(ripple.resourceId);
        // Première ligne sans ▲, dernière sans ▼ : la place reste, les colonnes restent alignées
        t.setVisibility(enabled ? View.VISIBLE : View.INVISIBLE);
        t.setOnClickListener(l);
        return t;
    }

    private static void swap(EditText[][] fields, int a, int b) {
        for (int c = 0; c < 2; c++) {
            CharSequence t = fields[a][c].getText().toString();
            fields[a][c].setText(fields[b][c].getText().toString());
            fields[b][c].setText(t);
        }
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return e;
    }

    /**
     * Enregistrement au format lu par HaLink : « entité | nom » par ligne, lignes vides
     * sautées (la tuile suit l'ordre des lignes remplies)
     */
    private static String shortcutsText(EditText[][] fields) {
        StringBuilder sb = new StringBuilder();
        for (EditText[] f : fields) {
            String id = f[0].getText().toString().trim();
            String name = f[1].getText().toString().replace('|', ' ').trim();
            if (id.isEmpty())
                continue;
            if (sb.length() > 0)
                sb.append('\n');
            sb.append(id);
            if (!name.isEmpty())
                sb.append(" | ").append(name);
        }
        return sb.toString();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        HaLink link = HaLink.get(this);
        link.setStatusListener(() -> haStatus.setText(link.getStatus()));
        haStatus.setText(link.getStatus());
        PowerManager pm = getSystemService(PowerManager.class);
        btnBattery.setVisibility(pm.isIgnoringBatteryOptimizations(getPackageName())
                ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onPause() {
        HaLink.get(this).setStatusListener(null);
        super.onPause();
    }

    private void updateStatus() {
        String listeners = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        String component = new ComponentName(this,
                PhoneNotificationListenerService.class).flattenToString();

        boolean enabled = listeners != null && listeners.contains(component);
        statusText.setText(enabled
                ? "Notification listener actif — les notifications sont transmises à la montre"
                : "Notification listener désactivé — activez-le ci-dessous");
    }
}
