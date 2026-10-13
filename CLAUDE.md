# CLAUDE.md — Projet watchface-defi

## Contexte
Cadran personnalisé "myPersonnalCadran" pour **OnePlus Watch 2** (Wear OS 5 / Android 14 depuis la
mise à jour OnePlus — le projet a démarré sous Wear OS 4 —, écran rond 466x466px).

## Architecture

### Approche : Java Canvas + Complications Wear OS
- **WFF (Watch Face Format XML) ne fonctionne PAS** sur OnePlus Watch 2 Wear OS 4 pour les cadrans tiers.
  Le moteur WFF du système n'intercepte pas les services tiers, provoquant un écran noir.
- Le cadran utilise `ListenableWatchFaceService` (API Java-friendly) de `androidx.wear.watchface`
  avec un `CanvasRenderer` pour le dessin et des `ComplicationSlots` pour les données.

### Données OHealth via Complications
Les données santé/météo viennent des **ComplicationProviders** de Heytap (OHealth) :

| Donnée | Provider | Slot ID |
|--------|----------|---------|
| Pas | `com.heytap.wearable.health/.complication.wearos.StepComplicationService` | 100 |
| Calories | `com.heytap.wearable.health/.complication.wearos.CaloriesComplicationService` | 101 |
| FC | `com.heytap.wearable.health/.complication.wearos.HeartRateComplicationService` | 102 |
| Météo | `com.heytap.wearable.weather/.complication.wearos.WeatherProviderService` | 103 |
| Batterie | Système (`DATA_SOURCE_WATCH_BATTERY`) | 104 |
| Température | `com.heytap.wearable.weather/.complication.wearos.TemperatureProviderService` | 105 |
| Activité | `com.heytap.wearable.health/.complication.wearos.DailyActivityComplicationService` | 106 |

**Important** : Les capteurs hardware (SensorManager) ne fonctionnent PAS en arrière-plan sur
l'OnePlus Watch 2 à cause de l'architecture bi-processeur (Snapdragon W5 + BES 2600 co-processeur).
Seules les apps OnePlus/OHealth ont accès au co-processeur. Les Complications sont le seul moyen
d'obtenir des données cohérentes avec OHealth.

### Notifications bridgées (téléphone → montre)
Les notifications bridgées via OHealth passent par le **co-processeur MCU (BES 2600)** et le SysUI OnePlus,
sans transiter par le `NotificationManager` Android. Le `NotificationListenerService` côté montre ne les voit pas.

**Solution** : module `mobile` (app compagnon téléphone) qui capture les notifs côté téléphone
et les envoie à la montre via la **Wearable DataLayer API** (`play-services-wearable`).
- La DataLayer envoie le contenu enrichi (titre, texte, picture), en **urgent** (`setUrgent()`)
- Le cadran affiche sa bulle notification avec les données DataLayer
- `ScreenWaker` allume l'écran à la réception (sauf Ne pas déranger), ce qui permet de couper
  le lever de poignet

### Allumage de l'écran — ce que fait vraiment le firmware (vérifié le 2026-10-13, Wear OS 5)
Lu dans le code décompilé (`wear-services.jar`, `services.jar`, `HeyClockworkSysUi.apk`) et mesuré :
- **Un cadran tiers interdit le mode co-processeur** : la SysUI pose `Settings.Global bs_third_wf=1`,
  et `BmState` refuse alors de basculer l'écran vers le MCU. La montre reste en `BM_ANDROID_STATE`
  (`adb shell dumpsys bm.manager -ms`), écran éteint compris : c'est une veille Android ordinaire.
- **La SysUI n'allume l'écran pour une notification que si l'AOD est affiché ou si le MCU tient
  l'écran** (`ScreenWakeupController.requestWakeup`). Cadran tiers + AOD coupé ⇒ jamais : d'où la
  dépendance au lever de poignet.
- **Un WakeLock `SCREEN_BRIGHT | ACQUIRE_CAUSES_WAKEUP` fonctionne** (permission `WAKE_LOCK` seule) :
  `PowerGroup` reçoit `WAKE_REASON_APPLICATION` ; en mode MCU il appellerait même
  `BmManager.switchToAndroidIfNeeded`. `TURN_SCREEN_ON` n'est pas exigée
  (`REQUIRE_TURN_SCREEN_ON_PERMISSION` : `enableSinceTargetSdk=10000`).
- Lever de poignet : `Settings.Global ambient_tilt_to_wake` (0/1), surveillé en direct et synchronisé
  vers le MCU (`BmSettingsChioce`). Toucher pour réveiller : `Settings.System touch_to_wake_switch`.
  `secure doze_pulse_on_notifications` et `system enable_notification_without_tap_or_tilt` ne sont
  lus par aucun composant de la montre : sans effet.
- En charge (USB compris), la montre ne quitte jamais l'état Android (`frw: charging`) ; pour tester
  la veille sans débrancher : `adb shell dumpsys battery unplug` (puis `dumpsys battery reset`).

### Fichiers clés

**Module `app` (montre, Wear OS)** :
- `app/src/main/java/com/defi/watchface/DefiWatchFaceService.java` — Service + Renderer + Complications
- `app/src/main/java/com/defi/watchface/DataLayerListenerService.java` — Réception notifs DataLayer
- `app/src/main/java/com/defi/watchface/DefiNotificationListenerService.java` — Listener notifs locales
- `app/src/main/java/com/defi/watchface/NotificationHolder.java` — Données notification partagées
- `app/src/main/java/com/defi/watchface/ScreenWaker.java` — Allumage de l'écran à l'arrivée d'une notif
- `app/src/main/AndroidManifest.xml` — Déclaration des services
- `app/build.gradle` — Dépendances (watchface, watchface-guava, guava, play-services-wearable)

**Module `mobile` (téléphone)** :
- `mobile/src/main/java/com/defi/watchface/mobile/PhoneNotificationListenerService.java` — Capture notifs + envoi DataLayer
- `mobile/src/main/java/com/defi/watchface/mobile/MainActivity.java` — Activation du listener
- `mobile/src/main/AndroidManifest.xml`
- `mobile/build.gradle`

**Design** :
- `design-reference/DESIGN_SPEC.md` — Spécifications couleurs et layout
- `design-reference/watchface-v4-mockup.jsx` — Mockup React du rendu cible

### Palette couleurs
| Zone | Couleur | Hex |
|------|---------|-----|
| Pas | Vert | `#44FF88` |
| Calories | Orange | `#FFAA33` |
| Santé/FC | Rouge | `#FF4466` |
| Heure | Blanc | `#FFFFFF` |
| Météo/Info | Violet | `#AA88FF` |
| Date | Gris | `#AAAAAA` |
| Batterie | Bleu | `#66BBFF` |
| Fond | Noir pur OLED | `#000000` |
| Séparateurs | Gris foncé | `#2A2A35` |

## Build & Deploy

```bash
# Build les deux modules
gradlew.bat :app:assembleDebug :mobile:assembleDebug

# Ou via le Gradle caché si gradlew ne marche pas
"C:/Users/frevi/.gradle/wrapper/dists/gradle-8.4-bin/1w5dpkrfk8irigvoxmyhowfim/gradle-8.4/bin/gradle" --project-dir "c:/Users/frevi/Desktop/Mes projets/watchface-defi" :app:assembleDebug :mobile:assembleDebug

# Install montre (USB connecté)
adb uninstall com.defi.watchface.distance
adb install app/build/outputs/apk/debug/app-debug.apk

# Install téléphone (USB ou WiFi debug)
adb -s <phone-serial> install mobile/build/outputs/apk/debug/mobile-debug.apk

# Screenshot montre
adb exec-out screencap -p > screenshot.png
```

## Commandes ADB (non disponibles dans les menus de la montre)

```bash
# --- DEPLOY ---
adb uninstall com.defi.watchface.distance
adb install app/build/outputs/apk/debug/app-debug.apk
adb exec-out screencap -p > screenshot.png

# --- PERMISSIONS (à accorder après chaque install) ---
# Notification listener (lecture des notifications pour le cadran)
adb shell cmd notification allow_listener com.defi.watchface.distance/com.defi.watchface.DefiNotificationListenerService

# Runtime permissions (complications OHealth : pas, calories, FC, etc.)
adb shell pm grant com.defi.watchface.distance android.permission.BODY_SENSORS
adb shell pm grant com.defi.watchface.distance android.permission.ACTIVITY_RECOGNITION
adb shell pm grant com.defi.watchface.distance com.google.android.wearable.permission.RECEIVE_COMPLICATION_DATA

# --- RÉGLAGES MONTRE (options absentes des paramètres UI) ---
# ⚠️ heads_up_notifications_enabled / notification_bubbles : la SysUI OnePlus ne lit
# pas ces clés (vérifié dans HeyClockworkSysUi.apk), elles ne coupent aucun popup

# Lever de poignet : 0 = coupé (ScreenWaker allume l'écran sur notification), 1 = actif
adb shell settings put global ambient_tilt_to_wake 0

# État de bascule écran Android / co-processeur (BM_ANDROID_STATE attendu avec ce cadran)
adb shell dumpsys bm.manager -ms
```

## Configuration technique
- **Résolution écran** : 466x466 (détectée dynamiquement via `onSurfaceChanged`)
- **compileSdk** : 34
- **minSdk** : 33 (Wear OS 4)
- **Java** : 17 (Eclipse Adoptium)
- **Gradle** : 8.4
- **AGP** : 8.2.0

## Problèmes résolus
- **Calories + Météo** : Résolu en ajoutant `<queries>` (visibilité packages Android 11+)
  et la permission `RECEIVE_COMPLICATION_DATA` dans le manifest. Les données remontent
  maintenant correctement via les ComplicationProviders OHealth.
- **Notifications bridgées** : Le `NotificationListenerService` côté montre ne reçoit pas
  les notifs bridgées via MCU/OHealth. Résolu avec un module `mobile` compagnon qui capture
  les notifs sur le téléphone et les envoie via Wearable DataLayer API.
- **Allumage de l'écran sans lever de poignet** : `ScreenWaker` (WakeLock `ACQUIRE_CAUSES_WAKEUP`)
  à l'arrivée d'une notification. L'ancien constat « WakeLock inopérant, AP coupé » (mars 2026,
  Wear OS 4) est faux sur Wear OS 5 : cf. « Allumage de l'écran » plus haut.

## Problèmes connus
- **Sommeil/Stress** : Pas de ComplicationProvider dédié trouvé sur la montre. `DailyActivityComplicationService` renvoie les pas au lieu de l'activité complète.
- **Popup notifications OHealth** : Impossible de désactiver les popups du SysUI OnePlus via ADB (`heads_up_notifications_enabled`, `notification_bubbles`). Le SysUI gère l'affichage directement depuis le MCU, indépendamment des réglages Android.
- **Fiche système par-dessus le cadran** : pour une notification *locale* à la montre, la SysUI
  affiche sa propre fiche (titre, texte, « Effacer ») quand l'écran s'allume. Les notifs du
  téléphone reçues par DataLayer n'ont pas de notification locale : c'est la bulle du cadran.

## Historique des tentatives
1. **WFF XML déclaratif** : ne fonctionne pas sur OnePlus Watch 2 (Wear OS 4 ne supporte pas WFF tiers)
2. **WallpaperService + Canvas brut** : fonctionne visuellement mais pas d'accès aux données OHealth
3. **ListenableWatchFaceService + Complications** : approche actuelle, données OHealth via le système de complications standard Wear OS
