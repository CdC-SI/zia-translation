# Async PDF Generation & Text Streaming

## Contexte

Les endpoints actuels (`POST /api/translation/pdf` et `POST /api/translation/text`) sont synchrones. Sur les documents volumineux, le temps de traitement (OCR + traduction + génération PDF) peut dépasser les timeouts clients (30–60s typiques).

Cette spec introduit deux évolutions :

1. **PDF asynchrone** : le client soumet un job, poll le statut, puis télécharge le résultat.
2. **Texte en streaming SSE** : le client reçoit les pages traduites au fil de l'eau via Server-Sent Events.

L'application **reste en Spring MVC** (`spring-boot-starter-web`). La dépendance `spring-boot-starter-webflux` est ajoutée uniquement pour disposer de Reactor (`Flux`, `Mono`) dans le classpath — Spring Boot démarre toujours en mode Servlet quand les deux starters sont présents.

## Dépendances Maven

Ajouter dans le `pom.xml` :

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

> **Note** : `spring-boot-starter-web` reste présent. L'application démarre en mode Servlet (Tomcat).

## API

### PDF — Soumission asynchrone

#### `POST /api/translation/pdf`

Soumet un job de traduction PDF. La génération s'effectue en arrière-plan.

| Élément          | Détail                                                            |
|------------------|-------------------------------------------------------------------|
| **Method**       | `POST`                                                            |
| **Path**         | `/api/translation/pdf`                                            |
| **Content-Type** | `multipart/form-data`                                             |
| **Paramètres**   | `file` — fichier PDF source (`MultipartFile`)                     |
|                  | `targetLanguage` — code langue cible (`String`, requis)           |
| **Réponse OK**   | `202 Accepted` — `application/json`                               |
| **Body réponse** | `TranslationJobResponse`                                          |
| **Erreurs**      | `400` — fichier manquant, vide, format non supporté, langue absente |

#### `GET /api/translation/pdf/{jobId}/status`

Retourne le statut courant du job.

| Élément          | Détail                                                 |
|------------------|--------------------------------------------------------|
| **Method**       | `GET`                                                  |
| **Path**         | `/api/translation/pdf/{jobId}/status`                  |
| **Réponse OK**   | `200` — `application/json` — `TranslationJobResponse` |
| **Erreurs**      | `404` — jobId inconnu                                  |

#### `GET /api/translation/pdf/{jobId}`

Télécharge le PDF traduit une fois le job terminé.

| Élément          | Détail                                                  |
|------------------|---------------------------------------------------------|
| **Method**       | `GET`                                                   |
| **Path**         | `/api/translation/pdf/{jobId}`                          |
| **Réponse OK**   | `200` — `application/pdf` (stream du fichier)           |
| **Erreurs**      | `404` — jobId inconnu                                   |
|                  | `409 Conflict` — job pas encore terminé (PENDING/PROCESSING) |
|                  | `410 Gone` — fichier expiré (nettoyé après TTL)         |
|                  | `422` — job en erreur (FAILED)                          |

### Texte — Streaming SSE

#### `POST /api/translation/text`

Traduit un document et streame le texte traduit page par page via Server-Sent Events.

| Élément          | Détail                                                            |
|------------------|-------------------------------------------------------------------|
| **Method**       | `POST`                                                            |
| **Path**         | `/api/translation/text`                                           |
| **Content-Type** | `multipart/form-data`                                             |
| **Paramètres**   | `file` — fichier PDF source (`MultipartFile`)                     |
|                  | `targetLanguage` — code langue cible (`String`, requis)           |
| **Réponse OK**   | `200` — `text/event-stream`                                       |
| **Body réponse** | Flux SSE de `TranslationPageEvent` (un événement par page)        |
| **Erreurs**      | `400` — fichier manquant, vide, format non supporté, langue absente |
|                  | `422` — erreur OCR/traduction (envoyée comme événement `error`)   |

Chaque événement SSE a la forme :

```
event: page
data: {"pageNumber": 1, "text": "Texte traduit de la page 1..."}

event: page
data: {"pageNumber": 2, "text": "Texte traduit de la page 2..."}

event: complete
data: {"totalPages": 2}
```

En cas d'erreur pendant le streaming :

```
event: error
data: {"message": "OCR extraction failed on page 3"}
```

## Modèle de données

### Nouveaux DTOs (records Java)

```java
// Réponse pour les endpoints PDF async
record TranslationJobResponse(
    String jobId,
    JobStatus status
) {}
```

```java
// Événement SSE pour le streaming texte
record TranslationPageEvent(
    int pageNumber,
    String text
) {}
```

```java
// Événement SSE de fin de stream
record TranslationCompleteEvent(
    int totalPages
) {}
```

```java
// Statut d'un job de traduction PDF
enum JobStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
```

### Nouvelles classes de service

| Classe                    | Rôle                                                                 |
|---------------------------|----------------------------------------------------------------------|
| `TranslationJobStore`     | Stockage en mémoire des jobs (ConcurrentHashMap<String, TranslationJob>) |
| `TranslationJob`          | Record interne : jobId, status, createdAt, completedAt, errorMessage |
| `PdfStorageService`       | Gestion du stockage/lecture/suppression des PDF générés sur le volume |
| `JobCleanupScheduler`     | `@Scheduled` — supprime les jobs et fichiers expirés (TTL dépassé)   |

### Package

Nouvelles classes dans :

```
zas.admin.zia.translation.service.job        → TranslationJobStore, TranslationJob, JobStatus, JobCleanupScheduler
zas.admin.zia.translation.service.storage    → PdfStorageService
zas.admin.zia.translation.service.dto        → TranslationJobResponse, TranslationPageEvent, TranslationCompleteEvent
```

## Changements dans le contrôleur

Le `TranslationController` existant est modifié :

```java
// POST /pdf → 202 Accepted + jobId
@PostMapping("/pdf")
ResponseEntity<TranslationJobResponse> translateToPdf(
        @RequestParam("file") MultipartFile file,
        @RequestParam("targetLanguage") String targetLanguage) {
    // Validation synchrone (400 si invalide)
    // Création du job (PENDING)
    // Lancement async du traitement
    // Retour 202 + jobId
}

// GET /pdf/{jobId}/status
@GetMapping("/pdf/{jobId}/status")
ResponseEntity<TranslationJobResponse> getJobStatus(@PathVariable String jobId) { }

// GET /pdf/{jobId}
@GetMapping("/pdf/{jobId}")
ResponseEntity<Resource> downloadPdf(@PathVariable String jobId) { }

// POST /text → SSE stream
@PostMapping("/text")
Flux<ServerSentEvent<String>> translateToText(
        @RequestParam("file") MultipartFile file,
        @RequestParam("targetLanguage") String targetLanguage) {
    // Validation synchrone
    // Retour Flux qui émet un SSE par page traduite
}
```

## Changements dans TranslationService

- `translateToPdf(MultipartFile, String)` → lance le traitement dans un thread (`@Async` ou `CompletableFuture.supplyAsync` sur un executor dédié), stocke le résultat via `PdfStorageService`, met à jour le `TranslationJobStore`.
- Nouvelle méthode `Flux<String> translateToTextStream(MultipartFile, String)` → émet les pages une par une au fur et à mesure de la traduction.

## Configuration (`application.properties`)

```properties
# --- Stockage des PDF générés ---
zia.translation.pdf.storage-path=${ZIA_PDF_STORAGE_PATH:./data/translated-pdfs}

# --- TTL des jobs (durée avant nettoyage) ---
zia.translation.pdf.job-ttl=1h

# --- Pool de threads pour la génération async ---
zia.translation.async.pool-size=4
```

## Mise à jour du fichier OpenAPI (`openapi.yaml`)

Le fichier `src/main/resources/static/openapi.yaml` doit être mis à jour pour refléter :

1. **`POST /pdf`** : réponse `202` avec schema `TranslationJobResponse` (au lieu de `200 application/pdf`).
2. **Nouvel endpoint `GET /pdf/{jobId}/status`** : réponse `200` avec `TranslationJobResponse`, erreur `404`.
3. **Nouvel endpoint `GET /pdf/{jobId}`** : réponse `200 application/pdf`, erreurs `404`, `409`, `410`, `422`.
4. **`POST /text`** : réponse `200 text/event-stream` avec description du format SSE.
5. **Nouveaux schemas** : `TranslationJobResponse`, `JobStatus`, `TranslationPageEvent`, `TranslationCompleteEvent`.

## Règles métier

1. **Validation** — identique à la spec existante (fichier non-null, non-vide, PDF, taille max). La validation est toujours synchrone et retourne `400` immédiatement.
2. **Unicité des jobs** — chaque job reçoit un UUID v4 comme identifiant.
3. **Stockage** — les PDF générés sont écrits sur le volume configuré (`zia.translation.pdf.storage-path`) avec le nom `{jobId}.pdf`.
4. **Nettoyage** — un scheduler (`@Scheduled`, fixedDelay = 10 min) supprime les jobs dont `completedAt` dépasse le TTL configuré. Les fichiers associés sont supprimés du disque.
5. **Erreur pendant le traitement** — si le job échoue (exception OCR/traduction/PDF), le statut passe à `FAILED` avec un message d'erreur consultable via l'endpoint status.
6. **Streaming texte** — les pages sont émises dès qu'elles sont traduites (pas d'attente de la fin du document complet). En cas d'erreur sur une page, un événement `error` est émis et le flux se termine.
7. **Concurrence** — le nombre de jobs en parallèle est limité par la taille du pool de threads (`zia.translation.async.pool-size`).

## Critères d'acceptation

- [ ] `POST /api/translation/pdf` retourne `202` avec un `jobId`.
- [ ] `GET /api/translation/pdf/{jobId}/status` retourne le statut correct du job.
- [ ] `GET /api/translation/pdf/{jobId}` retourne le PDF traduit quand le job est `COMPLETED`.
- [ ] `GET /api/translation/pdf/{jobId}` retourne `409` si le job n'est pas terminé.
- [ ] `GET /api/translation/pdf/{jobId}` retourne `410` si le fichier a été nettoyé.
- [ ] `POST /api/translation/text` retourne un flux SSE avec un événement par page.
- [ ] Le flux SSE se termine par un événement `complete`.
- [ ] En cas d'erreur, un événement SSE `error` est émis.
- [ ] La validation d'entrée retourne toujours `400` de façon synchrone.
- [ ] Les jobs expirés sont automatiquement nettoyés (fichiers + métadonnées).
- [ ] Dépendance `spring-boot-starter-webflux` ajoutée, `spring-boot-starter-web` conservé.
- [ ] Fichier `openapi.yaml` mis à jour avec les nouveaux endpoints et schemas.
- [ ] Tests unitaires : `TranslationJobStore`, `PdfStorageService`, `JobCleanupScheduler`.
- [ ] Tests d'intégration : les 4 endpoints (POST pdf, GET status, GET download, POST text SSE).
- [ ] `mvn clean verify` passe sans erreur.

