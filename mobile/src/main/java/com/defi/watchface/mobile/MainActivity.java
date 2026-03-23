package com.defi.watchface.mobile;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        Button btnEnable = findViewById(R.id.btn_enable);

        btnEnable.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
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
