package com.defi.watchface.mobile;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

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

        // Le gestionnaire de batterie du téléphone coupe sinon le WebSocket
        btnBattery = findViewById(R.id.btn_battery);
        btnBattery.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()))));

        HaLink.get(this).start();
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
