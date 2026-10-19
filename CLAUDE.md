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
- **Un élément DataLayer par notification** : `/notification/<clé sbn en Base64 URL>`, supprimé
  (`deleteDataItems`) quand la notif disparaît du téléphone ; la montre reçoit `TYPE_DELETED`.
  Un élément unique réécrit à chaque fois perdait des événements rapprochés (« Tout effacer »).
  `purgeStaleItems()` supprime au branchement de l'écoute les éléments de notifs disparues entre-temps
- Conversations (MessagingStyle) : texte = **dernier message** de `EXTRA_MESSAGES` (auteur en
  préfixe dans un groupe) ; `EXTRA_TEXT` n'est souvent qu'un résumé ou le premier message
- Le cadran garde une **file** (`NotificationHolder`, la plus récente en tête, 20 au plus ; même clé
  = mise à jour qui remplace). **Pastille** à droite de l'heure (icône de la plus récente, nombre en
  attente) ; un appui sur la pastille ou l'heure ouvre la plus récente (bulle 60 s). Le bouton du
  bas est un **›** tant qu'il en reste (« +N » à droite) et passe à la suivante, puis un ✕
- **Réponses rapides** : si la notif du téléphone a une action « Répondre » à saisie libre
  (`WearableExtender` d'abord, sinon `actions`), `canReply=true` ; la bulle propose Oui / Non / OK / ✎
  (✎ = `ReplyActivity`, saisie du système via `androidx.wear:wear-input`). La montre envoie
  `/reply` (MessageClient : « id du chemin\ntexte ») ; `ReplyListenerService` (téléphone) remplit le
  `RemoteInput` et déclenche l'action, comme Android Auto, puis accuse `/reply/ok|fail`. Vérifié le
  2026-10-19 avec Messages (SMS). Une notif mise à jour dont le dernier message est le sien (sans
  `sender` ni `sender_person`) n'est pas relayée : c'est l'écho de sa réponse
- Les petites icônes sont teintées en blanc côté téléphone (silhouettes : celle de Messages sortait
  noire sur noir)
- **Doublons montre / téléphone** : une appli installée des deux côtés envoie le même message deux
  fois (WhatsApp, désinstallé de la montre le 2026-10-19 : les réponses rapides le remplacent). Même appli + titre + texte = une seule entrée,
  qui garde l'action de la copie locale (appui = WhatsApp de la montre) ; un doublon arrivé jusqu'à
  2 min après la lecture de son jumeau est absorbé. Effacer une copie retire l'entrée
- Notification lue ou effacée (téléphone, ou notif locale de la montre) : retirée de la file
  **sans allumer l'écran**, et sa bulle se ferme si elle était affichée. Clés préfixées
  `phone:` / `local:`
- « Tout effacer » dans la zone de notifications **de la montre** : les notifs relayées par OHealth
  n'y sont pas des notifications Android (invisibles au listener de la montre), mais OHealth les
  efface aussi sur le téléphone, d'où la suppression DataLayer (vérifié le 2026-10-19 avec un SMS,
  moins d'1 s). Les notifs `cmd notification post` (paquet Shell) ne sont pas relayées par OHealth :
  inutilisables pour tester ce chemin
- `ScreenWaker` allume l'écran à la réception (sauf Ne pas déranger), ce qui permet de couper
  le lever de poignet

### Témoin de lien téléphone coupé (`PhoneLink`)
📵 rouge à gauche du pourcentage de batterie quand les notifications ne peuvent plus arriver :
- **Téléphone injoignable** : l'appli `mobile` annonce la capability `defi_phone`
  (`res/values/wear.xml`) ; `DataLayerListenerService.onCapabilityChanged` suit les nœuds joignables.
  Téléphone sans cette capability (ancienne appli) : état inconnu, pas de témoin.
- **Appli du téléphone muette** : `HeartbeatReceiver` écrit `/phone/alive` toutes les 15 min
  (AlarmManager `setAndAllowWhileIdle` : un `Handler` ne compte pas le temps de veille profonde),
  sans urgence, seulement si l'écoute des notifications est branchée (sinon `requestRebind`).
  Plus d'1 h sans battement : témoin.
- ⚠️ **Bluetooth perdu ≠ lien coupé** (vérifié le 2026-10-19) : Wear OS rallume aussitôt le Wi-Fi
  de la montre (`svc wifi disable` n'y résiste pas) et la DataLayer passe par le cloud
  (`hops=2, isNearby=false`) ; une notif y est arrivée en 5 s. Le témoin ne s'allume que si aucun
  chemin ne reste. Test réel : mode avion sur le téléphone.
- ⚠️ `pm disable-user` de l'appli du téléphone retire son accès aux notifications, et la
  capability n'est pas retirée aussitôt. Rétablir l'accès :
  `adb shell cmd notification allow_listener com.defi.watchface.distance/com.defi.watchface.mobile.PhoneNotificationListenerService`

### Google Fit coupé sur la montre (2026-10-19)
Les services Google (`FitRecordingBroker`) tenaient le capteur de pas en continu, en doublon
d'OHealth (aucune appli Fit sur le téléphone). Reconnaissance d'activité retirée au niveau de l'UID,
effective au prochain redémarrage des services Google :
`adb shell appops set --uid com.google.android.gms ACTIVITY_RECOGNITION ignore` (rétablir : `allow`).
Vérifier ensuite que `dumpsys sensorservice` ne montre plus `LocalSensorAdapter` sur `Step_Sensor`.

### Données Home Assistant (téléphone → montre)
L'appli `mobile` garde un WebSocket ouvert vers Home Assistant (`HaLink`, jeton longue durée,
`subscribe_entities`) et relaie l'état à la montre par la DataLayer ; la montre ne parle jamais à HA
(moins de batterie, et ça tient même si Wear OS ferme une appli). Le `NotificationListenerService`,
que le système garde en vie, porte le lien ; reconnexion seule (coupure, réseau retrouvé).
- **URL et jeton** se saisissent dans l'appli du téléphone (préférences privées, `allowBackup=false`) :
  jamais dans le dépôt, qui est public. Sans `https://`, l'appli l'ajoute.
- `/ha/weather` : station de la maison (`sensor.esp_meteo_*`, repli `weather.barcelonne`), ressenti,
  vent, pluie dans l'heure, vigilance Météo-France. Envoyé au plus toutes les 5 min (sauf changement
  de vigilance), au moins toutes les 30 min ; au-delà d'1 h sans envoi, le cadran revient à OHealth.
- `/ha/car` : photo Frigate `image.chemin_car` réduite à 320 px, légende `input_text.derniere_voiture`,
  présence `binary_sensor.chemin_car_occupancy` (cadre orange). Vignette affichée 24 h, appui = photo
  en grand 30 s. Heure du passage : `lc` de l'entité, pas `lu` (le jeton de `entity_picture`, renouvelé
  toutes les 5 min, fait avancer `lu`).
- `/ha/agenda` : événements **du jour** de 6 agendas (`CALENDARS` dans `HaLink` : Nautile,
  Francois r, Famille, Gmail perso, Anniversaires, Jours fériés) par `calendar.get_events`
  (`return_response`) : l'état d'une entité `calendar.*` n'expose qu'un événement. Redemandé au
  changement d'un agenda, de jour, et toutes les 15 min ; envoyé seulement s'il change. Le cadran
  affiche, à la place de l'ancienne ligne FC, le rendez-vous en cours ou le prochain
  (« 16:30 Kiné »), sinon l'événement « journée entière » du jour ; la FC est montée tout en haut
- Mêmes règles que les tuiles HA Wear OS (`sensor.montre_meteo`, `sensor.montre_bus_chemin` dans
  `template.yaml` de HA) : couleurs de vigilance `#ffd600`/`#ff9100`/`#ff1744`, ordre des phénomènes.
- La DataLayer garde le dernier élément de chaque chemin : `HomeData.load()` le relit au démarrage du cadran.

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
- `app/src/main/java/com/defi/watchface/HomeData.java` — Météo de la maison et dernière voiture reçues de HA
- `app/src/main/java/com/defi/watchface/PhoneLink.java` — Témoin de lien téléphone coupé
- `app/src/main/java/com/defi/watchface/ReplySender.java` / `ReplyActivity.java` — Réponses rapides depuis la bulle
- `app/src/main/AndroidManifest.xml` — Déclaration des services
- `app/build.gradle` — Dépendances (watchface, watchface-guava, guava, play-services-wearable)

**Module `mobile` (téléphone)** :
- `mobile/src/main/java/com/defi/watchface/mobile/PhoneNotificationListenerService.java` — Capture notifs + envoi DataLayer
- `mobile/src/main/java/com/defi/watchface/mobile/HaLink.java` — WebSocket Home Assistant + envoi DataLayer
- `mobile/src/main/java/com/defi/watchface/mobile/HeartbeatReceiver.java` — Battement de cœur vers la montre
- `mobile/src/main/java/com/defi/watchface/mobile/ReplyListenerService.java` — Exécution des réponses rapides
- `mobile/src/main/java/com/defi/watchface/mobile/MainActivity.java` — Activation du listener, URL/jeton HA, batterie
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

# Écran de charge plein écran (fenêtre SysUI.Charging de com.heytap.wearable.offload) :
# aucun réglage dans l'UI. Ce drapeau d'usine n'est lu que par ChargeManager.startCharge()
# (vérifié dans HeyOffload.apk) et ne bloque que cet écran. Rétablir : settings delete …
adb shell settings put global aging_test_systemui 1

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
