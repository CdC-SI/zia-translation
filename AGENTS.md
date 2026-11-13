# AGENTS.md — zia-translation

Service Spring Boot de **traduction** de documents/textes et d'**extraction structurée** de données
(OCR, vision, profils d'extraction avec score de confiance) pour l'écosystème ZIA.
Voir `README.md` pour les endpoints et le flux d'extraction détaillé.

## Stack

- **Java 25**, **Spring Boot 4.1.x** (`spring-boot-starter-webmvc`), **Spring AI 2.0.x**, **Maven**.
- Package racine : `zas.admin.zia.translation` ; le code métier est sous `service/<module>`
  (`controller`, `dto`, `config`, `llm`, `extraction`, `glossary`, `image`, `job`, `language`,
  `ocr`, `parser`, `pdf`, `storage`, `logging`).
- Ressources : `extraction-profiles/`, `glossary/`, `templates/`, `fonts/`, `application.properties`.

## Build & tests

- Validation de référence : `mvn clean verify` (doit passer avant toute PR).
- Un seul test : `mvn -o test -Dtest=NomDeLaClasseTest`.
- Profil Maven `internal` : ajoute `zas-common-security-starter` (jeton bleu). Utilisé par le
  `Jenkinsfile` (`mvn -B clean verify -Pinternal`), indisponible sur GitHub. Sans ce profil, la
  sécurité est ouverte (dev local, CI GitHub Actions).

## Conventions de code

- **Pas de Lombok.**
- Records pour les DTOs et objets de valeur ; fonctionnalités Java modernes (pattern matching, sealed…).
- Visibilité **package-private** par défaut (pas de `public` sauf nécessité).
- Classes utilitaires avec constructeur privé.
- Ne pas modifier `ZiaTranslationApplication` (constructeur privé) sauf demande explicite.
- Endpoint REST : `*Controller` + `*Service` si logique métier + DTO record + tests.
- **Tests obligatoires** pour toute nouvelle fonctionnalité (`spring-boot-starter-test`) ;
  ne pas introduire de warning de compilation.

## Git

- Base d'intégration : `develop` ; production : `main`. Jamais de commit direct sur ces branches.
- Branches de travail : `feature/<nom>` (ou `chore/…`, `bugfix/…`) depuis `develop`, PR vers `develop`.
- Release : `release/<version>` depuis `develop` vers `main`, tag après merge.
- PR : titre au format Conventional Commits (`feat: …`, `fix: …`, `chore: …`), résumé et fichiers modifiés.

## Points d'attention

- Les job stores sont en mémoire et le stockage sur le système de fichiers : le service n'est pas
  multi-replica (voir `ROADMAP` du workspace).
- Ne pas committer de secrets ni de fichiers HTTP privés (`*.private.env.json`).
