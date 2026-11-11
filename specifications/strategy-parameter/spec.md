# Feature Name: Paramètre de stratégie pour les endpoints de traduction

## Contexte

Actuellement, la stratégie de traduction du service `zia-translation` est définie **globalement** via la variable de configuration `ZIA_TRANSLATION_STRATEGY` (propriété `zia.translation.strategy`, valeur par défaut : `dual`). Cette stratégie s'applique de façon uniforme à toutes les requêtes, sans possibilité de surcharge à la demande.

Ce modèle présente une rigidité opérationnelle : selon la nature du document (complexité typographique, besoin de précision OCR vs. vitesse), il peut être souhaitable de choisir une stratégie différente pour un appel spécifique, sans modifier la configuration globale du service.

Cette feature ajoute un paramètre **optionnel** `strategy` sur les trois endpoints de soumission de traduction (`POST /api/translation/pdf`, `POST /api/translation/text`, `POST /api/translation/md`), permettant à l'appelant de forcer une stratégie précise pour une requête donnée, tout en conservant la stratégie globale configurée comme valeur de repli.

Par ailleurs, la stratégie — aujourd'hui modélisée comme une `String` brute dans `TranslationService` — est formalisée en **enum Java** pour garantir la cohérence des valeurs acceptées à la compilation et à l'exécution.

## Description

La feature doit :

1. Introduire l'enum `TranslationStrategy` avec les valeurs `SINGLE` et `DUAL`, alignées sur les valeurs actuelles de `ZIA_TRANSLATION_STRATEGY` (`single` / `dual`).
2. Accepter `strategy` comme paramètre de formulaire **optionnel** sur les trois endpoints `POST` de traduction.
3. Résoudre la stratégie effective pour chaque requête selon la règle de priorité : stratégie fournie dans la requête > stratégie configurée globalement (`zia.translation.strategy`).
4. Supprimer l'usage des constantes `String` `"single"` / `"dual"` dans `TranslationService` au profit de comparaisons sur l'enum `TranslationStrategy`.
5. Mettre à jour la lecture de `zia.translation.strategy` dans `TranslationService` pour produire un `TranslationStrategy` plutôt qu'une `String` brute.
6. Ne pas modifier le comportement observable des endpoints lorsque le paramètre `strategy` est absent (compatibilité ascendante totale).

## API

### `POST /api/translation/pdf`

Soumet un job de traduction PDF. Comportement inchangé ; le paramètre `strategy` est ajouté en option.

| Élément          | Détail                                                                                   |
|------------------|------------------------------------------------------------------------------------------|
| **Method**       | `POST`                                                                                   |
| **Path**         | `/api/translation/pdf`                                                                   |
| **Content-Type** | `multipart/form-data`                                                                    |
| **Paramètres**   | `file` — fichier source (`MultipartFile`, requis)                                        |
|                  | `targetLanguage` — code langue cible (`String`, requis)                                  |
|                  | `strategy` — stratégie de traduction (`TranslationStrategy`, **optionnel**, valeurs acceptées : `single`, `dual`) |
| **Réponse OK**   | `202 Accepted` — `application/json` — `TranslationJobResponse` (inchangé)                |
| **Erreurs**      | `400` — fichier manquant, vide, format non supporté, langue absente, **ou valeur `strategy` inconnue** |

### `POST /api/translation/text`

Traduit un document et streame le texte traduit page par page. Le paramètre `strategy` est ajouté en option.

| Élément          | Détail                                                                                   |
|------------------|------------------------------------------------------------------------------------------|
| **Method**       | `POST`                                                                                   |
| **Path**         | `/api/translation/text`                                                                  |
| **Content-Type** | `multipart/form-data`                                                                    |
| **Paramètres**   | `file` — fichier source (`MultipartFile`, requis)                                        |
|                  | `targetLanguage` — code langue cible (`String`, requis)                                  |
|                  | `strategy` — stratégie de traduction (`TranslationStrategy`, **optionnel**, valeurs acceptées : `single`, `dual`) |
| **Réponse OK**   | `200` — `text/event-stream` (flux SSE inchangé)                                          |
| **Erreurs**      | `400` — fichier manquant, vide, format non supporté, langue absente, ou valeur `strategy` inconnue |
|                  | `422` — erreur OCR/traduction                                                            |

### `POST /api/translation/md`

Soumet un job de traduction Markdown. Le paramètre `strategy` est ajouté en option.

| Élément          | Détail                                                                                   |
|------------------|------------------------------------------------------------------------------------------|
| **Method**       | `POST`                                                                                   |
| **Path**         | `/api/translation/md`                                                                    |
| **Content-Type** | `multipart/form-data`                                                                    |
| **Paramètres**   | `file` — fichier source (`MultipartFile`, requis)                                        |
|                  | `targetLanguage` — code langue cible (`String`, requis)                                  |
|                  | `strategy` — stratégie de traduction (`TranslationStrategy`, **optionnel**, valeurs acceptées : `single`, `dual`) |
| **Réponse OK**   | `202 Accepted` — `application/json` — `TranslationJobResponse` (inchangé)                |
| **Erreurs**      | `400` — fichier manquant, vide, format non supporté, langue absente, ou valeur `strategy` inconnue |

> **Note** : les endpoints `GET` (statut, téléchargement) ne sont pas modifiés par cette feature.

## Modèle de données

### Enum `TranslationStrategy`

Modélise de façon exhaustive et type-safe les stratégies de traduction acceptées par le service. La désérialisation depuis les paramètres HTTP (valeurs en minuscules `single` / `dual`) est assurée par un `@JsonCreator` ou une conversion Spring MVC.

```java
// Package : zas.admin.zia.translation.service
enum TranslationStrategy {
    SINGLE,
    DUAL;

    /**
     * Résout la valeur de l'enum depuis une chaîne insensible à la casse.
     * Utilisé par le convertisseur Spring MVC pour les @RequestParam.
     */
    static TranslationStrategy fromString(String value) {
        return switch (value.trim().toUpperCase()) {
            case "SINGLE" -> SINGLE;
            case "DUAL"   -> DUAL;
            default -> throw new IllegalArgumentException(
                    "Valeur de stratégie inconnue : '%s'. Valeurs acceptées : single, dual.".formatted(value));
        };
    }
}
```

> **Visibilité** : l'enum est package-private (pas de modificateur `public`) sauf contrainte Spring MVC. Si Spring MVC requiert une visibilité publique pour le `Converter<String, TranslationStrategy>` ou le `@RequestParam`, seul le minimum nécessaire est rendu public.

### Converter Spring MVC

Un `Converter<String, TranslationStrategy>` (ou un `ConverterFactory`) est enregistré via `WebMvcConfigurer` pour désérialiser la valeur du paramètre `strategy` de façon insensible à la casse. En cas de valeur inconnue, il lève une exception mappée en `400`.

```java
// Package : zas.admin.zia.translation.service.controller (ou config)
// Enregistré via WebMvcConfigurer.addFormatters()
class StringToTranslationStrategyConverter implements Converter<String, TranslationStrategy> {
    @Override
    public TranslationStrategy convert(String source) {
        return TranslationStrategy.fromString(source);
    }
}
```

### Évolutions de `TranslationService`

Le champ `strategy` de type `String` est remplacé par un champ de type `TranslationStrategy`. La méthode `resolveStrategy` centralise la logique de sélection de la stratégie effective pour une requête donnée :

```java
// Résolution de la stratégie effective : paramètre de requête prioritaire,
// sinon repli sur la stratégie globale configurée.
private TranslationStrategy resolveStrategy(TranslationStrategy requestStrategy) {
    return requestStrategy != null ? requestStrategy : this.defaultStrategy;
}
```

Les méthodes publiques de `TranslationService` qui déclenchent une traduction reçoivent un paramètre supplémentaire `TranslationStrategy strategy` (nullable). Les appels internes à `translatePages` et `streamPageTranslation` utilisent la stratégie résolue via `resolveStrategy(strategy)`.

### Évolutions de `TranslationController`

Les trois méthodes POST reçoivent un `@RequestParam(name = "strategy", required = false) TranslationStrategy strategy` et le transmettent à `TranslationService`. Spring MVC invoquera automatiquement le `Converter` enregistré ; si la conversion échoue (valeur inconnue), Spring MVC lève une `MethodArgumentTypeMismatchException` ou similaire, que le `GlobalExceptionHandler` mappe en `400`.

### Package

Aucun nouveau package n'est nécessaire. Les modifications s'effectuent dans les packages existants :

```
zas.admin.zia.translation.service            → TranslationStrategy (nouvel enum), TranslationService (refactoring)
zas.admin.zia.translation.service.controller → TranslationController (paramètre additionnel), StringToTranslationStrategyConverter
```

## Règles métier

1. **Repli sur la stratégie globale** — si le paramètre `strategy` est absent de la requête (valeur `null` côté Spring MVC), la stratégie effective est celle définie par `zia.translation.strategy` (variable d'environnement `ZIA_TRANSLATION_STRATEGY`, valeur par défaut `dual`). Le comportement du service est ainsi identique à l'état actuel lorsque le paramètre est omis.

2. **Priorité au paramètre de requête** — si `strategy` est fourni et valide, il prime sur la configuration globale pour la durée de cette seule requête. La configuration globale n'est pas modifiée.

3. **Validation des valeurs de l'enum** — seules les valeurs `single` et `dual` (insensibles à la casse) sont acceptées. Toute autre valeur (ex. `fast`, `auto`, chaîne vide) entraîne une réponse `400 Bad Request` avec un message d'erreur explicite, via la gestion d'exception existante (`GlobalExceptionHandler` ou équivalent).

4. **Type-safety** — les comparaisons sur la stratégie sont effectuées via l'enum (`switch` sur `TranslationStrategy`) et non via des comparaisons de chaînes. Les constantes `STRATEGY_SINGLE` et `STRATEGY_DUAL` de type `String` présentes dans `TranslationService` sont supprimées.

5. **Compatibilité ascendante** — aucun client existant n'est impacté : le paramètre `strategy` étant optionnel (`required = false`), les requêtes actuelles sans ce paramètre continuent de fonctionner sans modification ni dégradation de comportement.

6. **Propagation de la stratégie** — la stratégie résolue est propagée à toutes les méthodes internes de traduction (`translatePages`, `streamPageTranslation`). Elle ne modifie pas l'état du job (non stockée dans `TranslationJob`, non exposée dans `TranslationJobResponse`).

7. **Lecture de la configuration** — la propriété `zia.translation.strategy` est lue comme une `String` via `@Value` puis convertie en `TranslationStrategy` dans le constructeur de `TranslationService`, avec un message d'erreur clair si la valeur configurée est invalide (fail-fast au démarrage).

## Critères d'acceptation

- [ ] L'enum `TranslationStrategy` existe avec les valeurs `SINGLE` et `DUAL`, et la méthode `fromString` gère la casse de façon insensible.
- [ ] Un `Converter<String, TranslationStrategy>` est enregistré dans le contexte Spring MVC et utilisé pour désérialiser le paramètre `strategy`.
- [ ] `POST /api/translation/pdf` accepte le paramètre optionnel `strategy` et applique la stratégie fournie si présente.
- [ ] `POST /api/translation/text` accepte le paramètre optionnel `strategy` et applique la stratégie fournie si présente.
- [ ] `POST /api/translation/md` accepte le paramètre optionnel `strategy` et applique la stratégie fournie si présente.
- [ ] En l'absence du paramètre `strategy`, le comportement est strictement identique à l'état actuel (repli sur `zia.translation.strategy`).
- [ ] Une valeur `strategy` inconnue (ex. `fast`) retourne `400` avec un message d'erreur explicite.
- [ ] Le champ `String strategy` de `TranslationService` est remplacé par `TranslationStrategy defaultStrategy`.
- [ ] Les constantes `STRATEGY_SINGLE` et `STRATEGY_DUAL` de type `String` sont supprimées de `TranslationService`.
- [ ] La valeur de `zia.translation.strategy` invalide au démarrage provoque un échec explicite de l'application (fail-fast).
- [ ] Le fichier `openapi.yaml` est mis à jour pour documenter le paramètre `strategy` (optionnel, enum `single`/`dual`) sur les trois endpoints `POST`.
- [ ] Tests unitaires :
  - [ ] `TranslationStrategy.fromString` : valeurs valides en différentes casses, valeur inconnue (exception attendue).
  - [ ] `StringToTranslationStrategyConverter.convert` : valeurs valides, valeur inconnue.
  - [ ] `TranslationService` : stratégie fournie en paramètre prioritaire sur la globale, absence de paramètre → repli sur la globale, les deux valeurs d'enum (`SINGLE`, `DUAL`) produisent le bon branchement de traitement (mock des services OCR et LLM).
- [ ] Tests d'intégration :
  - [ ] Chaque endpoint `POST` avec `strategy=single` explicite.
  - [ ] Chaque endpoint `POST` avec `strategy=dual` explicite.
  - [ ] Chaque endpoint `POST` sans paramètre `strategy` (comportement identique à l'existant).
  - [ ] Chaque endpoint `POST` avec une valeur `strategy` invalide → `400`.
- [ ] `mvn clean verify` passe sans erreur.
