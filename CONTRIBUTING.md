# Contributing — zia-translation

## Workflow Gitflow

### Branches permanentes

| Branche   | Rôle                                      |
|-----------|-------------------------------------------|
| `main`    | Code en production, déploiements          |
| `develop` | Intégration des features                  |
| `specs`   | Réception des spécifications (.md)        |

### Cycle de vie d'une feature

```
spec/<name>  ──merge──▶  specs  ──PR──▶  develop  ◀──PR──  feature/<name>
                                                                │
                                                          (agent Copilot)
                                                                │
                                         develop  ──PR──▶  release/<v>  ──PR──▶  main
```

#### Étape 1 — Rédaction de la spec

1. Se placer sur la branche `specs`
2. Créer une branche `spec/<feature-name>`
3. Ajouter le fichier `specifications/<feature-name>/spec.md`
4. Commits itératifs pour affiner la spec
5. Merge dans `specs`

#### Étape 2 — Propagation vers develop

1. Créer une PR de `specs` → `develop`
2. Le dossier `specifications/<feature-name>/` arrive dans develop
3. Merger la PR

#### Étape 3 — Implémentation par l'agent Copilot

1. Créer une issue GitHub décrivant la feature à implémenter :
   ```
   Titre : Implement <feature-name>
   Body  : Implement the feature described in specifications/<feature-name>/spec.md
   ```
2. Assigner l'issue à **@copilot**
3. L'agent :
   - Lit les instructions (`.github/copilot-instructions.md` + `.copilot/instructions.md`)
   - Lit la spec dans `specifications/<feature-name>/spec.md`
   - Crée une branche `feature/<feature-name>` depuis `develop`
   - Implémente et teste
   - Ouvre une PR vers `develop`
4. Review humaine puis merge

#### Étape 4 — Release

1. Créer une branche `release/<version>` depuis `develop`
2. PR de `release/<version>` → `main`
3. Tag de version après merge

---

## Structure des spécifications

```
specifications/
├── <feature-name>/
│   ├── spec.md          # Spécification détaillée
│   └── (diagrammes, exemples, etc.)
```

## Template de spec

Chaque fichier `spec.md` doit suivre cette structure :

```markdown
# <Feature Name>

## Contexte
Pourquoi cette feature est nécessaire.

## Description
Ce que la feature doit faire.

## API (si applicable)
- `POST /api/...` — description
- Request body : ...
- Response : ...

## Modèle de données (si applicable)
Description des entités/DTOs.

## Règles métier
- Règle 1
- Règle 2

## Critères d'acceptation
- [ ] Critère 1
- [ ] Critère 2
- [ ] Tests unitaires
- [ ] Tests d'intégration
```

## Conventions de code

- **Java 25** — utiliser les fonctionnalités modernes (records, pattern matching, sealed classes)
- **Pas de Lombok**
- **Visibilité package-private** par défaut (pas de `public` sauf nécessité)
- **Records** pour les DTOs et objets de valeur
- **Tests obligatoires** pour toute nouvelle feature
- **`mvn clean verify`** doit passer avant toute PR

