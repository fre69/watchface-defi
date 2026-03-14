# Défi Distance — Cadran Wear OS 4

Cadran personnalisé pour OnePlus Watch 2 utilisant le Watch Face Format (XML déclaratif).

## Structure du projet

```
watchface-defi/
├── app/
│   ├── build.gradle                  ← dépendances
│   └── src/main/
│       ├── AndroidManifest.xml       ← déclaration du service
│       └── res/
│           ├── raw/watchface.xml     ← DÉFINITION DU CADRAN (fichier principal)
│           ├── xml/watch_face_info.xml
│           ├── values/strings.xml
│           └── drawable/             ← icônes et preview (à ajouter)
├── design-reference/
│   ├── DESIGN_SPEC.md               ← SPÉCIFICATIONS COMPLÈTES (couleurs, layout, tailles)
│   └── watchface-v4-mockup.jsx      ← Mockup React du rendu visuel cible
├── build.gradle
├── settings.gradle
├── gradle.properties
└── README.md
```

## Prérequis

- Android Studio Hedgehog (2023.1.1) ou plus récent
- SDK Android 34 + Wear OS system image
- Java 17+

## Compilation

```bash
# Depuis la racine du projet
./gradlew :app:assembleDebug
```

L'APK sera dans `app/build/outputs/apk/debug/`.

## Déploiement sur la montre

```bash
# 1. Activer mode développeur sur la montre :
#    Paramètres → À propos → taper 7x sur "Numéro de build"
#    Puis Paramètres → Options développeur → Débogage ADB → ON
#    Activer le débogage WiFi → noter l'IP

# 2. Connecter via ADB
adb connect <IP_MONTRE>:5555

# 3. Installer
adb install app/build/outputs/apk/debug/app-debug.apk

# 4. Sélectionner le cadran sur la montre
```

## Pour Claude Code

Si le XML ne compile pas ou ne respecte pas le design :
1. Lire `design-reference/DESIGN_SPEC.md` pour toutes les specs exactes
2. Ouvrir `design-reference/watchface-v4-mockup.jsx` pour le rendu visuel cible
3. Le `watchface.xml` fourni est un squelette — les expressions dynamiques
   doivent être converties en vraie syntaxe WFF (Watch Face Format expressions)
4. Documentation officielle : https://developer.android.com/training/wearables/wff
