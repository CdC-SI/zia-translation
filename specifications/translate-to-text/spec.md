# Translate To Text

## Contexte

Le service `zia-translation` ne traduit aujourd'hui que des **fichiers** (PDF, images) vers différents formats de sortie :

- `POST /api/translation/pdf` — job asynchrone produisant un PDF ;
- `POST /api/translation/md` — job asynchrone produisant un fichier Markdown ;
- `POST /api/translation/text` — streaming SSE (`text/event-stream`) du texte traduit, page par page.

Plusieurs clients ont besoin de traduire un **simple texte** (phrase, paragraphe, contenu saisi par un utilisateur) sans passer par un document, sans gérer de job asynchrone et sans consommer un flux SSE. Cette spec ajoute un endpoint **synchrone**, **non streamé**, qui reçoit un texte et une langue cible et retourne directement le texte traduit, en **texte brut** (aucun formatage Markdown ou autre).

## Description

Le service doit :

1. Accepter un texte source et une langue cible dans un body JSON.
2. Traduire ce texte via le LLM textuel (`llmChatClient`) en une seule requête, avec un prompt demandant un résultat **en texte brut** (pas de Markdown, pas de commentaire).
3. Retourner le texte traduit **en une seule réponse HTTP** (`200 OK`), de façon **synchrone** : pas de job, pas de `jobId`, pas de stockage, pas de SSE.

Hors périmètre :

- Aucune extraction OCR ni utilisation du modèle Vision : l'entrée est déjà du texte.
- Le paramètre `strategy` (`single`/`dual`) ne s'applique pas à cet endpoint.
- Aucune détection/retour de la langue source.
- Aucun découpage (chunking) d'un texte long en plusieurs appels LLM.

## API

### `POST /api/translation/plain-text`

| Élément          | Détail                                                                 |
|------------------|-------------------------------------------------------------------------|
| **Method**       | `POST`                                                                  |
| **Path**         | `/api/translation/plain-text` (préfixé par le context-path `/zia-trad`) |
| **Content-Type** | `application/json`                                                      |
| **Body**         | `PlainTextTranslationRequest`                                           |
| **Réponse OK**   | `200 OK` — `application/json` — `PlainTextTranslationResponse`          |
| **Erreurs**      | `400` — body absent/illisible, `text` ou `targetLanguage` absent/vide, `text` trop long |
|                  | `415` — `Content-Type` différent de `application/json`                  |
|                  | `422` — échec de la traduction côté LLM (`TranslationProcessingException`) |
|                  | `500` — erreur inattendue                                               |

Les erreurs sont retournées au format `ErrorResponse` existant (`status`, `message`, `timestamp`) via `GlobalExceptionHandler`.

> **Pourquoi pas `POST /api/translation/text` ?** Ce chemin est déjà utilisé par l'endpoint SSE multipart. Le body JSON n'étant pas exposé en `@RequestParam`, une distinction par paramètres (`params = ...`) n'est pas possible. Spring permettrait de distinguer les deux mappings via `consumes` (`multipart/form-data` vs `application/json`), mais cela imposerait de modifier le mapping existant, rendrait le routage dépendant du `Content-Type` (source d'erreurs `415` peu lisibles pour les clients) et compliquerait la documentation OpenAPI (une seule opération par couple chemin/méthode, avec des réponses de types différents). Un chemin dédié est plus explicite.

#### Exemple

Requête :

```http
POST /zia-trad/api/translation/plain-text
Content-Type: application/json

{
  "text": "Bonjour, comment allez-vous ?",
  "targetLanguage": "de"
}
```

Réponse :

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "translatedText": "Hallo, wie geht es Ihnen?"
}
```

## Modèle de données

Nouveaux records dans `zas.admin.zia.translation.service.dto` :

```java
public record PlainTextTranslationRequest(
        String text,
        String targetLanguage
) {}
```

```java
public record PlainTextTranslationResponse(
        String translatedText
) {}
```

`ErrorResponse` est réutilisé tel quel pour les erreurs.

### Évolutions des classes existantes

| Classe                    | Évolution |
|---------------------------|-----------|
| `TranslationController`   | Ajout de `translatePlainText` : `@PostMapping(value = "/plain-text", consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)`, `@RequestBody PlainTextTranslationRequest`, retourne `ResponseEntity<PlainTextTranslationResponse>` (`200`). Log de la requête **sans** le contenu du texte (seulement la langue cible et la longueur). |
| `TranslationService`      | Ajout de `translatePlainText(String text, String targetLanguage)` : réutilise `validateTargetLanguage`, valide `text` (non-null, non-blank, longueur max) en levant `InvalidDocumentException` (→ `400`), délègue à `TextTranslationService`, encapsule toute exception du LLM dans `TranslationProcessingException`. |
| `TextTranslationService`  | Ajout de `translateText(String text, String targetLanguage)` : appel **non streamé** (`.call().content()`) de `llmChatClient` avec le prompt existant `TRANSLATE_PLAIN_PROMPT_TEMPLATE` ; retourne `""` si le contenu LLM est `null`. |
| `GlobalExceptionHandler`  | Ajout d'un handler `HttpMessageNotReadableException` → `400` (« Malformed or missing request body. ») et `HttpMediaTypeNotSupportedException` → `415`, afin de ne pas tomber dans le handler générique `500`. |
| `openapi.yaml`            | Documentation du nouvel endpoint et des schemas `PlainTextTranslationRequest` / `PlainTextTranslationResponse`. |
| `http/translation.http`   | Ajout d'un exemple de requête. |

### Configuration

Nouvelle propriété dans `application.properties` :

```properties
zia.translation.text.max-length=${ZIA_TRANSLATION_TEXT_MAX_LENGTH:20000}
```

Nombre maximal de caractères acceptés dans `text`, afin de rester dans la fenêtre de contexte du LLM et de borner le temps de réponse d'un appel synchrone.

### Package

```
zas.admin.zia.translation.service.controller   → TranslationController, GlobalExceptionHandler (méthodes additionnelles)
zas.admin.zia.translation.service              → TranslationService (méthode additionnelle)
zas.admin.zia.translation.service.llm          → TextTranslationService (méthode additionnelle)
zas.admin.zia.translation.service.dto          → PlainTextTranslationRequest, PlainTextTranslationResponse (nouveaux records)
```

## Règles métier

1. **Validation** (synchrone, avant tout appel LLM) — toute violation retourne `400` avec un `ErrorResponse` explicite :
   - body absent ou JSON invalide ;
   - `targetLanguage` null ou blank (même règle que les endpoints existants, via `validateTargetLanguage`) ;
   - `text` null ou blank ;
   - `text.length()` > `zia.translation.text.max-length`.
2. **Texte brut** — le prompt utilisé est `TRANSLATE_PLAIN_PROMPT_TEMPLATE` (« Return only the translated text, without any commentary, formatting, or additional explanation »). Aucun post-traitement Markdown, HTML ou autre n'est appliqué à la réponse ; le texte est retourné tel que produit par le LLM, uniquement débarrassé des espaces/sauts de ligne en début et fin (`strip()`).
3. **Préservation du contenu** — les sauts de ligne internes du texte source sont transmis tels quels au LLM ; aucune normalisation n'est appliquée au texte d'entrée.
4. **Synchrone et non streamé** — un seul appel LLM bloquant (`call()`, jamais `stream()`), sur le thread de la requête HTTP. Aucun job n'est créé dans `TranslationJobStore`, aucun fichier n'est écrit sur le stockage, l'executor asynchrone n'est pas utilisé.
5. **Modèle utilisé** — uniquement le LLM textuel (`llmChatClient`), indépendamment de `zia.translation.strategy` ; le modèle Vision n'est jamais sollicité.
6. **Langue cible** — `targetLanguage` est transmis tel quel au prompt (même comportement que les endpoints existants, ex. `fr`, `de`, `it`, `en`).
7. **Gestion d'erreurs LLM** — toute exception levée lors de l'appel au LLM est encapsulée dans une `TranslationProcessingException` et retourne `422` ; le message d'erreur exposé au client ne contient ni le texte source ni de détail technique interne.
8. **Confidentialité** — le contenu de `text` et de `translatedText` n'est jamais logué (seuls la langue cible et la longueur du texte peuvent l'être).
9. **Non-régression** — les endpoints existants (`/pdf`, `/md`, `/text`, `/jobs/...`, endpoints dépréciés) restent inchangés.

## Critères d'acceptation

- [ ] `POST /api/translation/plain-text` avec un body valide retourne `200` et un `PlainTextTranslationResponse` contenant le texte traduit.
- [ ] La réponse est une réponse JSON unique (pas de `text/event-stream`), aucun `jobId` n'est retourné et aucun job n'est créé dans `TranslationJobStore`.
- [ ] Le prompt envoyé au LLM est le prompt « plain » (sans instruction Markdown) et l'appel est non streamé (`call()`).
- [ ] Le modèle Vision n'est jamais appelé, quelle que soit la valeur de `zia.translation.strategy`.
- [ ] `400` si le body est absent ou mal formé, si `text` ou `targetLanguage` est absent/vide, ou si `text` dépasse `zia.translation.text.max-length`.
- [ ] `415` si le `Content-Type` n'est pas `application/json`.
- [ ] `422` si le LLM lève une exception.
- [ ] Les espaces de début/fin de la réponse LLM sont supprimés ; une réponse LLM `null` donne `translatedText = ""`.
- [ ] Le texte source/traduit n'apparaît pas dans les logs.
- [ ] `openapi.yaml` et `http/translation.http` mis à jour.
- [ ] Tests unitaires : `TextTranslationService.translateText` (prompt plain, appel non streamé, gestion `null`), `TranslationService.translatePlainText` (validations, longueur max, encapsulation des erreurs LLM).
- [ ] Tests d'intégration (`TranslationControllerTest`) : cas nominal `200`, cas `400` (body absent, JSON invalide, `text` vide, `targetLanguage` vide, texte trop long), `415`, `422`, et non-régression de `POST /api/translation/text` (SSE multipart).
- [ ] `mvn clean verify` passe sans erreur.
