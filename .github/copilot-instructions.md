# Copilot Instructions — zia-translation

## Contexte projet

Ce projet est un service de traduction Spring Boot 3 (Java 21, Maven).
Package racine : `zas.admin.zia.translation`

## Architecture & Conventions

- **Framework** : Spring Boot 3.5.x avec spring-boot-starter-web
- **Build** : Maven (pas Gradle)
- **Java** : 21 (utiliser les fonctionnalités modernes du langage quand pertinent : records, pattern matching, sealed classes, etc.)
- **Package convention** : `zas.admin.zia.translation.<module>`
- **Tests** : Obligatoires pour toute nouvelle feature. Utiliser `spring-boot-starter-test`.
- **Style** :
  - Pas de Lombok
  - Classes utilitaires avec constructeur privé
  - Préférer les records pour les DTOs
  - Pas de `public` sur les classes sauf nécessité (visibilité package par défaut)
  - Constructeur de `ZiaTranslationApplication` est privé — ne pas modifier cette classe sauf demande explicite

## Workflow Git (Gitflow)

- `main` : production/déploiement uniquement — jamais de commit direct
- `develop` : intégration des features — jamais de commit direct
- `specs` : branche recevant les spécifications (fichiers .md dans `specifications/`)
- Feature branches : `feature/<nom>` depuis `develop`
- Release branches : `release/<version>` depuis `develop` vers `main`
- Spec branches : `spec/<nom>` depuis `specs`

## Spécifications

Les spécifications se trouvent dans le dossier `specifications/`.
Chaque feature a son propre sous-dossier : `specifications/<feature-name>/spec.md`
Le fichier spec.md décrit la feature de façon détaillée (contexte, API, modèle, règles métier, critères d'acceptation).

## Consignes pour l'implémentation par l'agent

1. Lire attentivement le fichier de spec dans `specifications/<feature>/spec.md`
2. Créer une feature branch `feature/<feature-name>` depuis `develop`
3. Implémenter la feature en respectant l'architecture existante
4. Écrire les tests unitaires et d'intégration
5. S'assurer que `mvn clean verify` passe sans erreur
6. Créer une PR vers `develop` avec un résumé de l'implémentation
7. Référencer le fichier de spec dans la description de la PR
