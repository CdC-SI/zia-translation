# Copilot Coding Agent — Workspace Instructions

## Avant de coder

- Consulte `specifications/` pour trouver la spec liée à l'issue assignée.
- Lis entièrement la spec avant de commencer l'implémentation.
- Vérifie la structure actuelle du projet avec le `pom.xml` et les packages existants.
- Identifie les dépendances nécessaires : si une nouvelle dépendance Maven est requise, ajoute-la au `pom.xml`.

## Règles d'implémentation

- Crée les classes dans le package `zas.admin.zia.translation.<module>` approprié.
- Chaque endpoint REST doit avoir :
  - Un Controller (`*Controller.java`)
  - Un Service (`*Service.java`) si logique métier
  - Un DTO (Java record) si échange de données
  - Des tests (unitaires + intégration)
- Utilise la visibilité package-private par défaut (pas de `public` sauf nécessité).
- Préfère les records Java pour les DTOs et les objets de valeur.
- Ne modifie PAS `ZiaTranslationApplication.java` sauf si explicitement demandé dans la spec.
- Ne modifie PAS les fichiers dans `specifications/`.
- Ne modifie PAS les fichiers dans `.github/` ou `.copilot/`.

## Validation

- Exécute `mvn clean verify` pour valider la compilation et les tests.
- Assure-toi qu'aucun warning de compilation n'est introduit.
- Vérifie que tous les tests existants passent toujours.

## Pull Request

- Titre : `feat: <description courte>`
- Corps de la PR :
  - Résumé de l'implémentation
  - Lien vers `specifications/<feature>/spec.md`
  - Liste des fichiers créés/modifiés
- Labels : `feature`, `agent-implemented`

