# CLAUDE.md — Projet watchface-defi

## Contexte
Cadran personnalisé "myPersonnalCadran" pour **OnePlus Watch 2** (Wear OS 5 / Android 14 depuis la
mise à jour OnePlus — le projet a démarré sous Wear OS 4 —, écran rond 466x466px, 320 dpi = 233 dp).
Deux modules : `app` (montre : cadran, tuiles Home Assistant, Assist) et `mobile` (téléphone :
relais des notifications, lien avec Home Assistant, réglages). Le téléphone fait tout le travail
réseau ; la montre ne parle jamais à Home Assistant. Vue d'ensemble pour un humain : `README.md`.

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
- ⚠️ **Notifications sensibles masquées** (Android 15+, téléphone sous Android 16) : l'assistant de
  notifications classe certaines notifs sensibles (`mSensitiveContent=true` dans `dumpsys notification
  --noredact`) : codes de vérification, mais aussi un SMS publicitaire ordinaire. Leur contenu est
  remplacé par « Contenu privé » pour tout écouteur sans `RECEIVE_SENSITIVE_NOTIFICATIONS`
  (`signature|role`). Notre appli la déclare et la reçoit du rôle compagnon de montre, qu'OHealth détient
  aussi : `adb shell cmd role add-role-holder android.app.role.COMPANION_DEVICE_WATCH
  com.defi.watchface.distance 0` (à refaire après une désinstallation). Vérifié le 24/11/2026 avec un
  faux code : texte complet sur la montre
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
  noire sur noir), sauf une icône pleine (> 85 % opaque) gardée en couleurs : Google Horloge donne
  son icône d'appli, qui sortait en carré blanc
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
- **Vibration** (`ScreenWaker.buzz`, deux impulsions de 120 ms) pour un message **nouveau** seulement
  (`NotificationHolder.set` renvoie faux pour un doublon absorbé ou une republication identique) :
  les notifications OHealth, qui vibraient, sont coupées depuis le 24/11/2026. ⚠️ Pas l'usage
  `NOTIFICATION` : les services Wear OS (`NotificationCollectorService`) suppriment en permanence les
  effets des notifications (`mListenersDisablingEffects` hint 2 dans `dumpsys notification`), la
  vibration est refusée (`ignored_app_ops`). Usage `COMMUNICATION_REQUEST`, Ne pas déranger vérifié
  par le code. Les notifs locales de la montre vibrent déjà par le système

### Réveil du téléphone arrêté depuis la montre (17/11/2026)
Le réveil est **Google Horloge** (`com.google.android.deskclock`), pour que les automatisations HA
puissent régler l'heure de l'alarme. OHealth n'en relaie pas la sonnerie, contrairement à l'horloge
OnePlus qu'il synchronise lui-même : la notification qui sonne porte **`FLAG_LOCAL_ONLY`** (« ne pas
envoyer aux montres »), qu'aucune autre appli ne peut retirer, et Google Horloge n'a pas de réglage pour ça.
- Notif qui sonne (relevée par `dumpsys notification --noredact`) : canal `Firing`, catégorie `alarm`,
  `fullScreenIntent`, drapeaux `NO_CLEAR|FOREGROUND_SERVICE|LOCAL_ONLY`, actions `[Répéter, Arrêter]`,
  texte « mar. 07:00 • Balayez pour arrêter » (coupé avant « • »). L'« Alarme à venir » (canal
  `Upcoming Alarms v2`, action « Ignorer l'alarme ») est une notif ordinaire, relayée comme les autres.
- `PhoneNotificationListenerService.isRingingAlarm` la laisse passer malgré le filtre des notifs de
  service, et l'envoie avec `alarm=true` et `actions` (libellés), **sans horodatage** (republiée à
  l'identique, elle ne relance rien). Le `fullScreenIntent` écarte les **minuteurs** (canal `Timers v2`,
  ni titre ni texte, actions Arrêter / Ajouter 1 min) : un minuteur en cours ne doit pas faire vibrer.
- Montre : `NotificationHolder.setAlarm` ouvre aussitôt un **écran d'alarme** (heure en gros, nom de
  l'alarme, un seul gros bouton « Arrêter ») ; **tout l'écran** est le bouton (demande de François :
  au réveil, pas de précision), « Répéter » n'est pas proposé (`getStopIndex` : Arrêter / Stop /
  Ignorer…, sinon le dernier). Écran tenu 15 min au plus (Google Horloge coupe seule à 10 min).
  `AlarmVibrator` vibre en usage `ALARM` (passe Ne pas déranger) et **tient l'écran allumé** (WakeLock
  `defi:alarm`) jusqu'au premier appui, à l'arrêt, ou 1 min (choix de François : plus long coûte trop
  de batterie) ; `ScreenWaker.wake(ctx, true)` allume même en Ne pas déranger. Au début, **5 s du son
  d'alarme de la montre** (`RingtoneManager.TYPE_ALARM`, usage `ALARM` : flux alarme, que le mode
  silencieux ne coupe pas), créé et arrêté sur le fil principal : le fil de la DataLayer disparaît
  ensuite (« Handler on a dead thread » à l'arrêt).
- ⚠️ **Le sommeil profond coupe les vibrations au réveil** : au redémarrage de 6:00,
  `DeepinSleepManager` passe `Settings.System vibrate_on` à 0 tant qu'il croit l'utilisateur endormi,
  et le service de vibration refuse alors tout, alarme comprise (`ignored_for_settings` dans
  `dumpsys vibrator_manager`, constaté le 24/11/2026 à 6:30). `AlarmVibrator` le remet à 1 à l'arrivée
  de l'alarme, et de nouveau s'il est recoupé. Il faut l'accès spécial « modifier les paramètres
  système » : `adb shell appops set com.defi.watchface.distance WRITE_SETTINGS allow` (sinon journal
  `DefiAlarm` : « accès WRITE_SETTINGS refusé »). Vérifié le 24/11/2026 avec `vibrate_on=0`.
- Régler une alarme d'essai sans toucher le téléphone : `notify.mobile_app_cph2609` avec
  `message: command_activity`, `intent_package_name: com.google.android.deskclock`,
  `intent_action: android.intent.action.SET_ALARM`, `intent_extras:
  android.intent.extra.alarm.HOUR:9,android.intent.extra.alarm.MINUTES:43,android.intent.extra.alarm.MESSAGE:Test,android.intent.extra.alarm.SKIP_UI:true`
  (même appel que l'automatisation du réveil d'école, `Q:\automations.yaml`).
- Appui : `/action` « id du chemin\nrang\nlibellé » ; `ReplyListenerService` vérifie le libellé et
  envoie le `PendingIntent` de l'action, accuse `/action/ok|fail` (échec : « Réessayer »). L'arrêt
  supprime la notif, donc l'élément DataLayer, ce qui ferme l'écran. Vérifié le 17/11/2026 : arrêt
  par un appui hors du bouton, notif disparue du téléphone en moins d'1 s.
- L'écran d'alarme vit dans le **cadran** : si une tuile ou une appli est affichée,
  `ScreenWaker.showWatchFace` ramène le cadran par l'intent de la touche accueil (`MAIN`/`HOME`
  avec l'extra **`android.intent.extra.FROM_HOME_KEY`** : sans lui, la SysUI, dont les tuiles font
  partie, reçoit l'intent et reste sur la tuile). Le lancement depuis l'arrière-plan est accepté
  (`BAL_ALLOW_PERMISSION process allowed by token` : la DataLayer livre ses événements avec un jeton).
  Vérifié le 17/11/2026, montre sur une tuile.
- Tester sans attendre : `am start -a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR 7
  --ei android.intent.extra.alarm.MINUTES 0 --es android.intent.extra.alarm.MESSAGE "'Test'"
  --ez android.intent.extra.alarm.SKIP_UI true -p com.google.android.deskclock` sur le téléphone ;
  arrêt de secours : `am start -a android.intent.action.DISMISS_ALARM --es
  android.intent.extra.alarm.SEARCH_MODE android.label --es android.intent.extra.alarm.MESSAGE "'Test'"`.

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
⚠️ **Le blocage saute à chaque redémarrage de la montre, donc chaque matin à la sortie du sommeil
profond** (cf. « Allumage de l'écran ») ; constaté le 23/11/2026 : la permission de GMS est `SYSTEM_FIXED`, et Android recale le mode appops sur
la permission au démarrage. Le processus GMS, persistant, garde son abonnement tant qu'il n'est pas
relancé (`am kill` n'y fait rien). Après chaque redémarrage : remettre l'appops, puis
`adb shell am force-stop com.google.android.gms` (GMS repart seul ; la DataLayer est coupée quelques
secondes). Pas de solution durable sans root : `pm disable …/.fitness.service.recording.FitRecordingBroker`
est refusé (`Shell cannot change component state`, Android 14). Coût mesuré quand Fit tourne : ≈ 5 mAh/jour (surtout les
capteurs, 4,5), soit ≈ 4 % de la consommation quotidienne de la montre. Le reste de GMS (≈ 1,7 mAh)
n'est pas à couper : il porte la DataLayer (`Wear_Transport`), dont dépend tout le projet.

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
  en grand 30 s. Vignette agrandie le 12/11/2026 (rayon 0,095 de l'écran, le plus grand cercle entre
  l'anneau des calories et l'heure) à la place d'une tuile Caméra (`camera.chemin_last_motion`),
  retirée : un instantané de caméra n'a ni état ni heure dans HA, et l'heure de capture n'est connue
  que pour les voitures (`input_text.derniere_voiture`), ce que la vignette montre déjà. Heure du passage : `lc` de l'entité, pas `lu` (le jeton de `entity_picture`, renouvelé
  toutes les 5 min, fait avancer `lu`).
- `/ha/agenda` : événements **du jour** de 6 agendas (`CALENDARS` dans `HaLink` : Nautile,
  Francois r, Famille, Gmail perso, Anniversaires, Jours fériés) par `calendar.get_events`
  (`return_response`) : l'état d'une entité `calendar.*` n'expose qu'un événement. Redemandé au
  changement d'un agenda, de jour, et toutes les 15 min ; envoyé seulement s'il change. Le cadran
  affiche, à la place de l'ancienne ligne FC, le rendez-vous en cours ou le prochain
  (« 16:30 Kiné »), sinon l'événement « journée entière » du jour ; la FC est montée tout en haut
- Couleurs de vigilance `#ffd600`/`#ff9100`/`#ff1744` et ordre des phénomènes : ceux de
  l'ancien capteur HA `sensor.montre_meteo` (retiré le 12/11/2026 avec sa tuile).
- La DataLayer garde le dernier élément de chaque chemin : `HomeData.load()` le relit au démarrage du cadran.

### Tuiles et Assist Home Assistant (remplacent l'appli HA de la montre, 12/11/2026)
L'appli HA officielle de la montre ne sert plus : ses notifications ne marchent pas (OnePlus coupe son
processus écran éteint) et elle parle à HA par le Wi-Fi de la montre. Ses tuiles et son Assist sont
repris ici, sur le même principe que le reste : **le téléphone parle à HA, la montre jamais**.
Un fork de l'appli HA a été essayé puis supprimé le 12/11/2026 : sa connexion manuelle depuis la
montre était cassée par PKCE (`Code verifier required`, correctif home-assistant/android#7775).

| Tuile (carrousel) | Service | Données (téléphone → montre) | Commande (montre → téléphone) |
|---|---|---|---|
| Raccourcis HA (13 entités + 🎤, rangées 3-4-3 jusqu'à 2-3-4-3-2, cercles de 44 dp au-delà de 8 ; à partir de 4 rangées, deux rangées décalées d'un demi-pas s'emboîtent sans écart, sinon le bord du rond rogne la première et la dernière) | `ShortcutsTileService` | `/ha/tiles` : état, nom, icône | `/ha/action` « entity_id », échec `/ha/action/fail` |
| Modèle HA 1 à 4 | `TemplateTileService$Slot1…4` | `/ha/template/1…4` | — |
| Thermostat HA | `ThermostatTileService` | `/ha/thermostat` | `/ha/climate` « + » / « - », échec `/ha/climate/fail` |
| Liste HA (entité `todo`) | `TodoTileService`, `TodoListActivity`, `TodoActivity` | `/ha/todo` : tâches à faire, 20 faites, nombre de faites | `/ha/todo` « check\|uncheck\|delete⏎uid », « add⏎texte », « rename⏎uid⏎texte », échec `/ha/todo/fail` |
| Assist (appli + 🎤) | `AssistActivity` | réponse `/ha/assist/reply` | `/ha/assist` « texte » |

- **Réglages dans l'appli du téléphone** (section « Tuiles de la montre ») : entités des Raccourcis
  (13 lignes « entité / nom court / pastille de couleur », ▲ ▼ pour changer l'ordre ; enregistrées
  dans une seule préférence `tile_entities`, `entity_id | nom | couleur` par ligne, lue par
  `HaLink.parseTileEntities`). Couleur (`HaLink.TILE_COLORS`) : fond d'une action ou d'une entité
  allumée, à la place du jaune ; **rouge = action sensible**, lancée seulement au second appui dans
  les 4 s (« Sûr ? », `TileStore.confirm`). Ex. : `script.urgence_montre | Urgence | rouge`, qui
  déclenche l'automatisation du bouton jaune 1 de la télécommande (`Q:\scripts.yaml`) ; un capteur
  (`binary_sensor`) ne s'actionne pas depuis la tuile ; l'icône vient de l'attribut `icon` de
  l'entité (cf. « Icônes » plus bas), d'où `icon: mdi:alarm-light` sur le script. Puis
  4 modèles (Jinja, ou un simple `sensor.x` dont l'attribut
  `texte` est affiché : `sensor.montre_imprimante`, `sensor.montre_ecole` de `template.yaml`), le `climate`
  et la liste `todo` (réglage absent : `todo.liste_de_francois_revillet`, `HaLink.DEFAULT_TODO` ;
  champ vidé : pas de liste). L'appli du téléphone est en thème sombre (`Theme.AppCompat`).
- **Liste** : l'état d'une entité `todo` n'est que le nombre de tâches à faire ; les tâches
  viennent de l'abonnement WebSocket `todo/item/subscribe` (liste entière renvoyée à chaque
  changement). Sur la tuile : ○ coche (appui confirmé par la nouvelle liste, contour bleu en
  attente, rouge si refusé), ✓ décoche, le texte ouvre `TodoActivity` (champ modifiable : clavier ou
  dictée du système ; 🗑 supprime au second appui), + ouvre directement la saisie du système.
  Tâches faites **masquées** : le bouton « ✓N » à côté du + les affiche jusqu'à ce qu'on quitte la
  tuile (`onTileLeaveEvent`). Une tuile ne défile pas : 4 lignes au plus (à 5, le + touchait le bas
  du rond) ; au-delà, « Tout voir » (et le titre) ouvre `TodoListActivity`, la liste entière qui
  défile, mêmes gestes, redessinée à chaque liste reçue (`TileStore.todoListener`).
  Services `todo.update_item` (`status`, `rename`), `todo.add_item`, `todo.remove_item`, la tâche
  désignée par son `uid`.
- ⚠️ **Une activité lancée depuis une tuile doit être `exported="true"`**, sinon le système refuse
  sans erreur visible (`ProtoTilesPlTileViewInstance: Activity constraints not met`).
- ⚠️ Le clavier plein écran de la montre annonce ✓ (`IME_ACTION_DONE`) **avant** de recopier le texte
  dans le champ (encore l'ancien 400 ms après) : `TodoActivity` enregistre à l'arrivée du texte
  (`TextWatcher`), repli au bout de 1,5 s (vérifié à la main le 16/11/2026). Un test par `adb shell input text` n'atteint jamais le
  champ (texte resté dans la zone du clavier) : tester le renommage à la main.
- **Rafraîchissement** : abonnements HA (`subscribe_entities`, `render_template` qui ne renvoie que
  quand le résultat change). Les changements partent **non urgents** (aucun réveil de la montre,
  livrés quand elle est éveillée). Quand une tuile s'affiche (`onTileEnterEvent`, au plus 1 fois / 5 s),
  la montre envoie `/ha/tiles/get` et le téléphone renvoie l'état en urgent (≈ 0,6 s mesuré), puis reste
  en direct 1 min ; 10 s après un appui aussi.
- **Appui** : la tuile attend le nouvel état (2,5 s au plus) avant de se redessiner ; contour bleu =
  appui pas encore confirmé, rouge = refusé. Bascule comme l'appli HA (`lock`/`unlock`,
  `cover.toggle`, `turn_on` des scènes et scripts, `press` des boutons, sinon `homeassistant.toggle`).
  Thermostat : appuis cumulés depuis la consigne demandée (`target_temp_step`, bornes min/max), gardée
  2,5 min ou jusqu'à ce que HA la relise : ⚠️ **Netatmo (`climate.cuisine`) ne relit le cloud qu'une
  fois par minute**, HA garde l'ancienne consigne jusque-là (un délai de 4 s faisait repartir chaque
  appui de l'ancienne valeur). Depuis le 16/11/2026, l'automatisation HA `netatmo_setpoint_webhook`
  applique la consigne dès le webhook Netatmo (≈ 2 s), que l'intégration ignore (cf. `Q:\CLAUDE.md`). Le téléphone renvoie aussitôt la consigne demandée (`requested`,
  affichée en bleu) ; refus de HA : retour à sa consigne. Une égalité vue **pendant** l'appel
  `set_temperature` ne confirme rien (`climateCallsInFlight`) : l'intégration Netatmo réécrit son
  ancienne consigne dans l'état juste avant de répondre.
- **Icônes** : attribut `icon` de l'entité (sinon icône par défaut du domaine, `MdiIcons.defaultIcon`),
  dessinée sur le téléphone avec la police MDI 7.4.47 (`mobile/src/main/assets/mdi.ttf`, Apache 2.0,
  table `mdi.txt` tirée du CSS de `@mdi/font`) dans la couleur de l'état, envoyée en PNG ; la montre
  la passe en ARGB_8888 brut pour la tuile.
- **Modèles** : HTML restreint des tuiles HA (`<b>`, `<big>`, `<small>`, `<font color>`, `<br>`) rendu en
  `Spannable` (`TemplateTileService.html`) : texte normal à la taille par défaut des tuiles (aucune
  imposée, ≈ 18 sp mesuré), `<big>` 20 sp comme l'appli HA ; `<small>` 16 sp, plus grand que dans
  l'appli HA (12,8 sp, illisible au poignet). Une ligne `<small>` doit rester courte (≈ 22 caractères)
  pour tenir dans le rond : le modèle la coupe par `<br>` (bus et délai de `sensor.montre_ecole`).
  `<p>` (ajout propre à la montre) sépare deux blocs d'un petit interligne : une ligne d'espace
  insécable en 8 sp, retour qui termine la ligne précédente compris (dans la police normale, il
  agrandirait cette ligne). Retours à la ligne gardés ;
  marges latérales étroites (une ligne comme « 274 dans ~16 min (17:37) » y tient).
- **Assist** : dictée du système (`RecognizerIntent`, Gboard en fr-FR) ; côté téléphone,
  `assist_pipeline/run` (étape `intent` seule) sur le **pipeline préféré** de HA, comme l'appli HA :
  son agent (« Assistant Maison » : commandes locales d'abord, puis Claude), sa langue. ⚠️ Pas
  `conversation/process` sans `agent_id` : il prend l'agent intégré de HA (phrases types, sans IA),
  qui ne suit pas le fil (« éteins-le » → « je ne connais pas cet appareil »). Réponse attendue 30 s. Dialogue : `conversation_id` gardé 5 min par le téléphone, historique gardé autant à
  l'écran ; écoute relancée seule quand HA attend une suite (`continue_conversation`). Boutons
  toujours visibles en bas : 🎤 écoute **dans la page** (`SpeechRecognizer`, permission
  `RECORD_AUDIO`, `<queries>` sur `android.speech.RecognitionService`), texte affiché pendant qu'on
  parle et envoyé dès qu'on se tait, sans l'écran de Gboard et son ✓ ; ✎ saisie du système
  (dictée ou clavier, modifiable avant envoi) pour corriger ; ↺ nouvelle conversation (écran vidé,
  `/ha/assist/new` : le téléphone oublie le `conversation_id`).
  `singleTask` : relancer Assist (tuile, bouton) reprend la page ouverte (`onNewIntent` → dictée)
  au lieu d'en empiler une nouvelle. ⚠️ **Jamais de filtre `ASSIST` / `VOICE_ASSIST`** (retiré le
  16/11/2026) : avec deux activités candidates, l'appui long sur le bouton ouvre un sélecteur à chaque
  fois, et Gemini (`com.google.android.wearable.assistant`, titulaire du rôle `ASSISTANT`) n'y figure
  pas. Assist HA se lance par le 🎤 des Raccourcis ou la liste des applis.
- Téléphone éteint ou sans réseau : les tuiles gardent la dernière valeur et affichent « 📵 ».
- ⚠️ **Toute lecture de la DataLayer a un délai** (`Tasks.await(…, 5 s)`) : les tuiles partagent un seul
  fil (`TileStore.BACKGROUND`), et une pièce jointe pas encore arrivée (l'image d'une ancienne tuile
  Caméra) les bloquait toutes, sans erreur.
- ⚠️ **Après une réinstallation**, le système garde un moment des liaisons mortes vers l'ancien
  processus (`dumpsys activity services` : `ConnectionRecord … DEAD`) : une tuile reste sur l'icône
  d'attente (« no valid tile data »). Elle revient d'elle-même en repassant dessus ; ce n'est pas un
  défaut de la tuile. `DefiTiles` journalise chaque tuile fournie ou en échec (`TileStore.logged`).
- Retirer une tuile sans la montre : même commande que l'ajout, `--es operation remove-tile`.

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
- **Mode intelligent** : `Settings.System smart_mode_switch`, 1 = `SMART_MODE_BALANCE` (défaut),
  0 = `SMART_MODE_PERFORMANCE`. À 0, `BmManagerService.setSmartMode` coupe le mode BM du noyau
  (`BmSwitchUtil.setKernelBmMode(false)`), `noteGoToWatchFace` ne bascule jamais vers le MCU et la
  surveillance `BmStateWatchdog` s'arrête. À 1 avec notre cadran, cette surveillance retente la
  bascule toutes les 3 s et se la voit refuser (`frw: third wf or widget`). La ligne `high_pref` de
  `batterystats` (`HighPrefPowerCalculator`) n'est qu'un calcul : temps passé à 0 × 0,72 mA
  (`heytap.high_perf` du `power_profile.xml`), pas une mesure. ⚠️ **Le MCU a le dernier mot** :
  au démarrage, il renvoie sa valeur (`BmDataExchange.handDealMsg`, message 103) et Android
  l'écrit. Passé à 1 par adb le 23/11/2026, le réglage était revenu à 0 après le redémarrage du
  lendemain matin.
- **Sommeil profond** (`Settings.System device_deep_sleep=1`, `start_deep_sleep_time=21:00`,
  `over_deep_sleep_time=6:00`) : la montre **éteint Android** pour la nuit (`sys.boot.reason` =
  `shutdown,enter_deepin_sleep_mode`), le MCU seul tient l'écran, l'heure et la santé, comme en mode
  éco, puis Android **redémarre** à l'heure de fin. Pendant ce temps, ni cadran, ni notifications
  relayées, ni alarme du téléphone sur la montre. Tout réglage qui ne survit pas à un redémarrage
  (appops de Google Fit…) est perdu **chaque matin**.
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
- `app/src/main/java/com/defi/watchface/ReplySender.java` / `ReplyActivity.java` — Réponses rapides depuis la bulle et boutons d'alarme
- `app/src/main/java/com/defi/watchface/AlarmVibrator.java` — Vibration pendant qu'une alarme du téléphone sonne
- `app/src/main/java/com/defi/watchface/TileStore.java` — Données des tuiles HA, appuis en attente, demande d'état frais
- `app/src/main/java/com/defi/watchface/ShortcutsTileService.java`, `TemplateTileService.java`,
  `ThermostatTileService.java`, `TodoTileService.java` — Tuiles Home Assistant
- `app/src/main/java/com/defi/watchface/TodoActivity.java` — Ajout et édition d'une tâche de la tuile Liste
- `app/src/main/java/com/defi/watchface/TodoListActivity.java` — Liste entière qui défile (« Tout voir »)
- `app/src/main/java/com/defi/watchface/AssistActivity.java` — Dialogue avec Assist (appli « Assist HA »,
  icône `res/drawable/ic_assist.xml` : rond bleu HA, `mdi:microphone-message`)
- `app/src/main/AndroidManifest.xml` — Déclaration des services
- `app/build.gradle` — Dépendances (watchface, watchface-guava, guava, play-services-wearable, tiles, protolayout)

**Module `mobile` (téléphone)** :
- `mobile/src/main/java/com/defi/watchface/mobile/PhoneNotificationListenerService.java` — Capture notifs + envoi DataLayer
- `mobile/src/main/java/com/defi/watchface/mobile/HaLink.java` — WebSocket Home Assistant + envoi DataLayer
- `mobile/src/main/java/com/defi/watchface/mobile/HeartbeatReceiver.java` — Battement de cœur vers la montre
- `mobile/src/main/java/com/defi/watchface/mobile/ReplyListenerService.java` — Exécution des réponses rapides et des boutons d'alarme
- `mobile/src/main/java/com/defi/watchface/mobile/HaCommandService.java` — Commandes des tuiles et d'Assist
- `mobile/src/main/java/com/defi/watchface/mobile/MdiIcons.java` — Icônes MDI des Raccourcis
- `mobile/src/main/java/com/defi/watchface/mobile/MainActivity.java` — Activation du listener, URL/jeton HA, réglage des tuiles, batterie
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
# Build les deux modules : gradlew.bat (le script shell gradlew échoue sous Git Bash :
# « impossible de trouver la classe principale "-Xmx64m" »). Depuis PowerShell :
.\gradlew.bat :app:assembleDebug :mobile:assembleDebug

# Install montre (USB sur le socle ou adb Wi-Fi) : -r garde les permissions accordées
# (même clé de débogage du poste). Désinstaller seulement si la signature diffère.
adb -s <montre> install -r app/build/outputs/apk/debug/app-debug.apk

# Install téléphone (USB ou WiFi debug)
adb -s <phone-serial> install -r mobile/build/outputs/apk/debug/mobile-debug.apk

# Ajouter une tuile au carrousel sans passer par la montre (elle se place en tête)
adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile \
  --ecn component 'com.defi.watchface.distance/com.defi.watchface.ShortcutsTileService'
# (TemplateTileService\$Slot1…4, ThermostatTileService, TodoTileService)

# Screenshot montre
adb exec-out screencap -p > screenshot.png
```

## Commandes ADB (non disponibles dans les menus de la montre)

```bash
# --- PERMISSIONS (après une installation neuve ; install -r les garde) ---
# Notification listener (lecture des notifications pour le cadran)
adb shell cmd notification allow_listener com.defi.watchface.distance/com.defi.watchface.DefiNotificationListenerService

# Runtime permissions (complications OHealth : pas, calories, FC, etc.)
adb shell pm grant com.defi.watchface.distance android.permission.BODY_SENSORS
adb shell pm grant com.defi.watchface.distance android.permission.ACTIVITY_RECOGNITION
adb shell pm grant com.defi.watchface.distance com.google.android.wearable.permission.RECEIVE_COMPLICATION_DATA

# Vibration de l'alarme malgré le sommeil profond (AlarmVibrator réécrit vibrate_on)
adb shell appops set com.defi.watchface.distance WRITE_SETTINGS allow

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
- **Tuiles** : `androidx.wear.tiles` 1.4.1, `androidx.wear.protolayout` 1.2.1 (images inline
  ARGB_8888 / RGB_565, `onTileEnterEvent`)
- **Téléphone** : minSdk 28, OkHttp 4.12 (WebSocket HA)

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
- **Appli Home Assistant de la montre** (12/11/2026) : notifications perdues (processus coupé écran
  éteint) et Wi-Fi de la montre allumé pour chaque échange. Remplacée par les tuiles et Assist de ce
  projet : appli désinstallée de la montre et appareil « montre » supprimé de HA. Un message urgent de la DataLayer réveille la
  montre en veille Android : les tuiles n'en reçoivent que quand on les regarde.

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
