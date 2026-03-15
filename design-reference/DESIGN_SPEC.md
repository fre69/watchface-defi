# Cadran "Défi Distance" — Spécifications Design V4

## Cible
- **Montre** : OnePlus Watch 2
- **OS** : Wear OS 4
- **Écran** : Rond, 450×450 pixels
- **Format** : Watch Face Format (XML déclaratif)

## Code Couleur (STRICT — chaque zone a sa couleur unique)

| Zone        | Couleur   | Hex      | Usage                          |
|-------------|-----------|----------|--------------------------------|
| Activité    | Vert      | #44FF88  | Pas (arc + texte)              |
| Calories    | Orange    | #FFAA33  | Calories (arc + texte)         |
| Santé       | Rouge/Rose| #FF4466  | Sommeil, FC, Stress            |
| Heure       | Blanc     | #FFFFFF  | HH:MM:SS central               |
| Météo       | Violet    | #AA88FF  | Température, Pluie%, UV        |
| Calendrier  | Gris clair| #AAAAAA  | Jours semaine, jour du mois    |
| Batterie    | Bleu      | #66BBFF  | Jauge + pourcentage            |
| Fond        | Noir pur  | #000000  | Background (OLED true black)   |
| Séparateurs | Gris foncé| #2A2A35  | Lignes verticales entre items  |

## Layout (de haut en bas)

### Arcs extérieurs (autour du cadran)
- **Arc externe** (rayon ~200px) : Progression PAS — vert #44FF88, épaisseur 5px
  - Track (fond) : même couleur, opacité 0.12
  - Angle : -135° à +135° (270° total)
  - Remplissage = STEP_COUNT / STEP_GOAL × 270°
- **Arc interne** (rayon ~190px) : Progression CALORIES — orange #FFAA33, épaisseur 4px
  - Même principe que l'arc pas

### Ligne 1 : PAS | CAL (y ≈ 90-130px)
- **PAS** à gauche, **CAL** à droite, séparés par un trait vertical gris
- Labels : "PAS" et "CAL" en 10px bold, lettrines espacées
- Valeurs : 22px extra-bold (police type Orbitron/monospace)
- Objectifs : "/10k" et "/800" en 9px, opacité 0.45
- Gap réduit entre PAS et CAL (rapprochés, ~10px)
- IMPORTANT : positionner SOUS les arcs, pas de superposition

### Ligne 2 : Santé (y ≈ 148px)
- 😴 Sommeil (ex: "7h12") | ❤️ FC (ex: "77") | 😊 Stress (ex: "40")
- Tout en #FF4466, 13px semi-bold
- Séparateurs verticaux gris entre chaque
- Descendue par rapport à la ligne PAS/CAL (espace visible)

### Ligne 3 : HEURE (y ≈ 172px, CENTRE du cadran)
- **HH:MM** en 56px extra-bold blanc #FFFFFF
- **:SS** en 24px normal, blanc opacité 0.35, aligné à droite
- Police de type digital/sport (Orbitron sur le mockup)
- Légère ombre (textShadow blanc opacité 0.12)

### Ligne 4 : Météo (y ≈ 240px)
- 🌙 Temp° | 🌧️ Pluie% | ☀️ UV
- Tout en #AA88FF, 12px semi-bold
- Séparateurs verticaux gris

### Ligne 5 : Calendrier (y ≈ 264px)
- 7 carrés pour D L M M J V S (16×16px, border-radius 3px)
  - Jour actif : fond #AAAAAA, texte #111
  - Jours inactifs : fond rgba(170,170,170,0.1), texte gris faible
- Jour du mois : **22px extra-bold** #AAAAAA (bien visible)
- Mois abrégé : 11px normal, opacité 0.5

### Ligne 6 : Batterie (y ≈ 295px)
- Jauge horizontale : largeur ~220px, hauteur 6px, coins arrondis
  - Fond : #66BBFF opacité 0.12
  - Remplissage : #66BBFF (passe en #FF4466 si < 20%)
- Pourcentage : 🔋 XX% en **15px bold** #66BBFF (police Orbitron)

## Mode Ambiant (always-on)
- Fond noir pur #000000
- Heure seule : HH:MM en 56px blanc opacité 0.7
- Date : "14 Mar" en 14px gris opacité 0.5
- Batterie : % en 12px bleu opacité 0.5
- Pas d'arcs, pas de couleurs vives (économie OLED)

## Complications Wear OS nécessaires
- `STEP_COUNT` + `STEP_GOAL` — pas quotidiens
- `CALORIES` — calories brûlées (+ objectif à définir manuellement : 800)
- `HEART_RATE` — fréquence cardiaque temps réel
- `SLEEP_DURATION` — heures de sommeil (dernière nuit)
- `STRESS_LEVEL` — niveau de stress (si dispo via OHealth)
- `WEATHER_TEMPERATURE` — température actuelle
- `WEATHER_RAIN_CHANCE` — probabilité de pluie (nécessite app tierce type Weather Suite)
- `UV_INDEX` — indice UV (idem, app tierce probable)
- `BATTERY_PERCENT` — niveau batterie montre
- `DAY_OF_MONTH`, `DAY_OF_WEEK`, `MONTH` — date

## Notes pour Claude Code
1. Le fichier `watchface.xml` fourni est un SQUELETTE avec des placeholders.
   Les expressions dynamiques ([STEP_COUNT], etc.) doivent être remplacées
   par la vraie syntaxe Watch Face Format (expressions WFF).
2. Le mockup JSX dans `design-reference/` montre le rendu visuel exact à atteindre.
3. Les emojis (😴❤️😊🌙🌧️☀️🔋) sont des placeholders — sur la vraie montre,
   utiliser des icônes SVG/PNG dans res/drawable pour un rendu propre.
4. Tester d'abord dans l'émulateur Wear OS d'Android Studio avant déploiement ADB.
5. La police Orbitron n'est pas dispo nativement — soit l'embarquer en TTF
   dans res/font, soit utiliser SYNC_TO_DEVICE (police système de la montre).
