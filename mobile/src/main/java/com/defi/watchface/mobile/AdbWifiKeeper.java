package com.defi.watchface.mobile;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * Garde le débogage sans fil actif quand le téléphone passe d'un Wi-Fi de la
 * maison à l'autre : Android le coupe à chaque changement de réseau
 * (AdbDebuggingManager : « Detected wifi network change. Disabling adbwifi »),
 * et le téléphone bascule seul entre les deux points d'accès.
 *
 * Réactivé seulement s'il était actif juste avant (coupure à moins de 10 s
 * d'un changement de réseau : une coupure à la main reste une coupure), et sur
 * un réseau de confiance. Un réseau devient de confiance quand l'utilisateur y
 * active lui-même le débogage : aucun nom de réseau dans le code (dépôt public).
 *
 * Permissions accordées par adb : WRITE_SECURE_SETTINGS (écrire
 * adb_wifi_enabled), ACCESS_FINE_LOCATION et ACCESS_BACKGROUND_LOCATION (lire
 * le nom du réseau, que le système masque sans elles).
 */
final class AdbWifiKeeper {
    private static final String TAG = "DefiAdbWifi";
    private static final String KEY = "adb_wifi_enabled";
    private static final String PREFS = "adb_wifi";
    private static final String PREF_TRUSTED = "trusted_ssids";
    /** Écart toléré entre la coupure et le changement de réseau qui l'a causée */
    private static final long CHANGE_WINDOW_MS = 10_000L;
    /** Laisse le nouveau réseau s'établir avant de réactiver */
    private static final long CHECK_DELAY_MS = 3_000L;

    private static AdbWifiKeeper instance;

    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean lastOn;
    private long offAt;
    private long networkChangedAt;
    private String lastSsid;
    /** Écriture faite par nous : ne pas l'apprendre comme un choix de l'utilisateur */
    private boolean selfWrite;

    private AdbWifiKeeper(Context ctx) {
        app = ctx.getApplicationContext();
    }

    static synchronized void start(Context ctx) {
        if (instance != null)
            return;
        AdbWifiKeeper k = new AdbWifiKeeper(ctx);
        if (!k.allowed())
            return;
        instance = k;
        k.begin();
    }

    private boolean allowed() {
        boolean ok = app.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        if (!ok)
            Log.i(TAG, "WRITE_SECURE_SETTINGS non accordée : débogage sans fil non surveillé");
        return ok;
    }

    private void begin() {
        lastOn = isOn();
        lastSsid = currentSsid();
        if (lastOn)
            trust(lastSsid);
        ContentResolver cr = app.getContentResolver();
        cr.registerContentObserver(Settings.Global.getUriFor(KEY), false, new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                onSettingChanged();
            }
        });
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        cm.registerNetworkCallback(req, new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                handler.post(AdbWifiKeeper.this::onNetworkChanged);
            }

            @Override
            public void onLost(Network network) {
                handler.post(AdbWifiKeeper.this::onNetworkChanged);
            }
        }, handler);
        Log.i(TAG, "Surveillance du débogage sans fil (actif=" + lastOn + ")");
    }

    private boolean isOn() {
        return Settings.Global.getInt(app.getContentResolver(), KEY, 0) == 1;
    }

    private void onSettingChanged() {
        boolean on = isOn();
        if (on == lastOn)
            return;
        lastOn = on;
        if (on) {
            if (!selfWrite)
                trust(currentSsid());
            selfWrite = false;
        } else {
            offAt = System.currentTimeMillis();
            handler.postDelayed(this::maybeRestore, CHECK_DELAY_MS);
        }
    }

    private void onNetworkChanged() {
        String ssid = currentSsid();
        if (ssid != null && ssid.equals(lastSsid))
            return;
        lastSsid = ssid;
        networkChangedAt = System.currentTimeMillis();
        // Coupure déjà vue avant ce changement : vérifier une fois le réseau établi
        if (!lastOn && networkChangedAt - offAt < CHANGE_WINDOW_MS)
            handler.postDelayed(this::maybeRestore, CHECK_DELAY_MS);
    }

    private void maybeRestore() {
        if (isOn() || offAt == 0)
            return;
        if (Math.abs(offAt - networkChangedAt) > CHANGE_WINDOW_MS)
            return; // coupé à la main
        String ssid = currentSsid();
        if (ssid == null || !trusted().contains(ssid)) {
            Log.i(TAG, "Débogage sans fil coupé par le changement de réseau, réseau non de confiance");
            return;
        }
        try {
            selfWrite = true;
            offAt = 0;
            Settings.Global.putInt(app.getContentResolver(), KEY, 1);
            Log.i(TAG, "Débogage sans fil réactivé après un changement de réseau");
        } catch (RuntimeException e) {
            selfWrite = false;
            Log.w(TAG, "Réactivation du débogage sans fil impossible", e);
        }
    }

    /** Nom du Wi-Fi actuel, null s'il est inconnu (pas de Wi-Fi, ou localisation refusée) */
    @SuppressWarnings("deprecation")
    private String currentSsid() {
        WifiManager wm = app.getSystemService(WifiManager.class);
        WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
        String ssid = info != null ? info.getSSID() : null;
        if (ssid == null || ssid.isEmpty() || WifiManager.UNKNOWN_SSID.equals(ssid))
            return null;
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\""))
            ssid = ssid.substring(1, ssid.length() - 1);
        return ssid;
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private Set<String> trusted() {
        return prefs().getStringSet(PREF_TRUSTED, new HashSet<>());
    }

    private void trust(String ssid) {
        if (ssid == null)
            return;
        Set<String> set = new HashSet<>(trusted());
        if (set.add(ssid)) {
            prefs().edit().putStringSet(PREF_TRUSTED, set).apply();
            Log.i(TAG, "Réseau de confiance pour le débogage sans fil ajouté");
        }
    }
}
