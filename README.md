# Programme Thierry — Android App

Appli callisthénie avec widget écran d'accueil.

## Compilation

### Via GitHub Actions (recommandé)
1. Push ce dossier sur GitHub
2. GitHub Actions compile automatiquement
3. Télécharger l'APK dans **Actions → Build APK → Artifacts**

### Structure
- `app/src/main/assets/programme.html` — le programme complet
- `MainActivity.java` — WebView plein écran
- `ProgrammeWidget.java` — widget 4×2 écran d'accueil

## Widget
Affiche la séance du jour avec couleur dynamique. Se met à jour automatiquement toutes les 30 minutes.
