# Downgrade vers Java 21

## Contexte
Pour des raisons internes de compatibilité et de support, le projet doit passer de Java 25 à Java 21 (LTS).

## Description
Modifier l'ensemble de la configuration du projet pour cibler Java 21 au lieu de Java 25.
S'assurer qu'aucune fonctionnalité spécifique à Java 22+ n'est utilisée dans le code source.

## Fichiers impactés

### 1. `pom.xml`
- Modifier la propriété `<java.version>` de `25` à `21`.

### 2. `.github/workflows/ci.yml`
- Modifier `java-version` de `'25'` à `'21'` dans le step `Set up JDK`.

### 3. `.github/copilot-instructions.md`
- Mettre à jour les références à Java 25 vers Java 21.
- Adapter la mention des fonctionnalités modernes du langage : records, pattern matching et sealed classes restent disponibles en Java 21.

### 4. `README.md`
- Mettre à jour toute référence à la version Java si présente.

### 5. Code source
- Vérifier qu'aucune API ou syntaxe spécifique à Java 22+ n'est utilisée.
- Le code actuel (records, sealed classes, pattern matching for instanceof) est compatible Java 21 — aucune modification attendue.

## Règles métier
- La version Java cible doit être 21 (LTS).
- Le projet doit continuer à compiler et passer tous les tests avec `mvn clean verify`.
- La CI GitHub Actions doit utiliser le JDK 21 Temurin.

## Critères d'acceptation
- [ ] `pom.xml` : `<java.version>21</java.version>`
- [ ] `ci.yml` : `java-version: '21'`
- [ ] `copilot-instructions.md` : références mises à jour vers Java 21
- [ ] `README.md` : références mises à jour si applicable
- [ ] Aucune utilisation de fonctionnalités Java 22+ dans le code source
- [ ] `mvn clean verify` passe sans erreur
- [ ] Tests unitaires et d'intégration passent

