# myPersonnalCadran — cadran et tuiles Home Assistant pour OnePlus Watch 2

Cadran personnel pour **OnePlus Watch 2** (Wear OS 5), avec son appli compagnon Android. Le téléphone
fait tout le travail réseau ; la montre ne parle jamais à Home Assistant ni à Internet par elle-même.

## Ce que fait le projet

**Sur la montre (module `app`)**
- **Cadran** dessiné en Java (`ListenableWatchFaceService` + `CanvasRenderer`) : heure, date, pas,
  calories, fréquence cardiaque, batterie (complications OHealth), météo de la maison, rendez-vous du
  jour, vignette de la dernière voiture passée sur le chemin (photo en grand à l'appui).
- **Notifications du téléphone** dans une bulle du cadran, avec réponses rapides (Oui / Non / OK /
  dictée) et allumage de l'écran à l'arrivée.
- **Tuiles Home Assistant** : Raccourcis (9 entités avec leur état réel et leur icône, + micro),
  Modèle 1 à 4 (texte d'un modèle HA mis en forme : horaires d'école et bus des enfants, imprimante
  3D), Thermostat (consigne − / +).
- **Assist** : dialogue vocal avec Home Assistant, choisissable comme assistant de la montre.

**Sur le téléphone (module `mobile`)**
- Relais des notifications vers la montre.
- Lien permanent avec Home Assistant (WebSocket, jeton longue durée) : il pousse à la montre ce qui
  change, exécute les appuis des tuiles et pose les questions à Assist.
- Écran de réglage : adresse et jeton HA, entités et modèles des tuiles.

Les deux appareils communiquent par la **Wearable Data Layer** (Bluetooth, ou cloud de Google quand
le Bluetooth manque).

## Structure

```
watchface-defi/
├── app/                      montre (Wear OS)
│   └── src/main/java/com/defi/watchface/
│       ├── DefiWatchFaceService.java        cadran
│       ├── DataLayerListenerService.java    réception depuis le téléphone
│       ├── NotificationHolder.java, ScreenWaker.java, Reply*.java   notifications
│       ├── HomeData.java, PhoneLink.java    données HA du cadran, témoin de lien
│       ├── TileStore.java, *TileService.java   tuiles Home Assistant
│       └── AssistActivity.java              Assist
├── mobile/                   téléphone
│   └── src/main/
│       ├── java/com/defi/watchface/mobile/
│       │   ├── PhoneNotificationListenerService.java, ReplyListenerService.java
│       │   ├── HaLink.java, HaCommandService.java, MdiIcons.java
│       │   └── MainActivity.java
│       └── assets/mdi.ttf, mdi.txt  police Material Design Icons
├── design-reference/         spécifications et maquette du cadran
└── CLAUDE.md                 documentation technique détaillée (à lire avant toute modification)
```

## Compiler

Prérequis : JDK 17, SDK Android 34 (le wrapper fournit Gradle 8.4).

```powershell
.\gradlew.bat :app:assembleDebug :mobile:assembleDebug
```

Le script `gradlew` (shell) ne fonctionne pas sous Git Bash : passer par `gradlew.bat`.

## Installer

```bash
adb -s <montre> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <téléphone> install -r mobile/build/outputs/apk/debug/mobile-debug.apk
```

`-r` met à jour en gardant les permissions. Sur une installation neuve, accorder celles de la montre
(commandes dans `CLAUDE.md`, « Commandes ADB »), puis, dans l'appli du téléphone :
1. activer l'accès aux notifications ;
2. saisir l'adresse de Home Assistant et un jeton d'accès longue durée ;
3. remplir « Tuiles de la montre » et enregistrer ;
4. ne pas restreindre la batterie de l'appli (sinon le lien avec HA se coupe).

Enfin, choisir le cadran sur la montre et ajouter les tuiles au carrousel (appui long sur une tuile).

## Données personnelles

Le dépôt est public : l'adresse de Home Assistant, le jeton et les entités des tuiles restent dans
les préférences privées de l'appli du téléphone, jamais dans le code. Seuls les identifiants des
entités affichées par le cadran (météo, voiture, agendas) sont écrits dans `HaLink`.

## Licences tierces

Police Material Design Icons 7.4.47 (Pictogrammers), sous licence Apache 2.0 :
`mobile/src/main/assets/mdi-LICENSE.txt`.
