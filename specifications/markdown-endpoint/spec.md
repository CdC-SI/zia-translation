# Markdown Endpoint

## Contexte

Le service `zia-translation` expose aujourd'hui un endpoint de traduction asynchrone au format PDF (`POST /api/translation/pdf`, avec suivi de statut et téléchargement via `GET /api/translation/pdf/{jobId}/status` et `GET /api/translation/pdf/{jobId}`) ainsi qu'un endpoint de streaming texte (`POST /api/translation/text`). Pour faciliter l'évaluation qualitative du service (relecture rapide du contenu traduit, diff textuel, intégration dans des outils de revue), il est utile de pouvoir récupérer directement le résultat de la traduction sous forme d'un unique fichier **Markdown**, plus simple à inspecter, versionner et comparer qu'un PDF binaire.

Cette spec ajoute un nouvel endpoint de soumission `POST /api/translation/md`, produisant un fichier `.md` au lieu d'un `.pdf`. Plutôt que de dupliquer les endpoints de suivi de statut et de téléchargement pour chaque format (`/pdf/{jobId}/...`, `/md/{jobId}/...`), ces endpoints sont **mutualisés** sous une nouvelle ressource générique `/api/translation/jobs/{jobId}`, commune à tous les formats de sortie (PDF et Markdown, et extensible à de futurs formats). Les anciens endpoints `GET /api/translation/pdf/{jobId}/status` et `GET /api/translation/pdf/{jobId}` sont conservés pour compatibilité ascendante mais marqués **dépréciés**.

## Description

Le service doit :

1. Accepter un document source (même contrat d'entrée que `/api/translation/pdf` : `file` + `targetLanguage`, validation identique, réutilisation de `DocumentParser` pour l'extraction des pages).
2. Traduire chaque page du document (même pipeline OCR/traduction — stratégies `single`/`dual` — que les endpoints existants), en demandant un rendu du texte traduit au format **Markdown** par page (`renderAsMarkdown = true`, déjà supporté par `TextTranslationService`).
3. Fusionner le Markdown de chaque page traduite en un seul document, en insérant entre chaque page le séparateur HTML suivant :

   ```html
   <div style="page-break-after: always;"></div>
   ```

4. Traiter la génération de façon asynchrone (job en arrière-plan), à l'instar de `POST /api/translation/pdf` : soumission → `202 Accepted` + `jobId`.
5. Exposer le suivi de statut et le téléchargement du résultat via des endpoints **génériques**, communs aux jobs PDF et Markdown :
   - `GET /api/translation/jobs/{jobId}/status`
   - `GET /api/translation/jobs/{jobId}`
6. Stocker le fichier Markdown généré sur le même volume que les PDF (`zia.translation.pdf.storage-path`), sous le nom `{jobId}.md`, avec le même mécanisme de nettoyage par TTL (`JobCleanupScheduler`).
7. Conserver, en mode **déprécié**, les anciens endpoints `GET /api/translation/pdf/{jobId}/status` et `GET /api/translation/pdf/{jobId}`, qui continuent de fonctionner à l'identique (délégation vers la même logique que les endpoints génériques) afin de ne pas casser les clients existants.

## API

### `POST /api/translation/md`

Soumet un job de traduction Markdown. La génération s'effectue en arrière-plan.

| Élément          | Détail                                                            |
|------------------|--------------------------------------------------------------------|
| **Method**       | `POST`                                                             |
| **Path**         | `/api/translation/md`                                              |
| **Content-Type** | `multipart/form-data`                                              |
| **Paramètres**   | `file` — fichier source (`MultipartFile`)                          |
|                  | `targetLanguage` — code langue cible (ex. `fr`, `en`, `de`) (`String`, requis) |
| **Réponse OK**   | `202 Accepted` — `application/json`                                |
| **Body réponse** | `TranslationJobResponse`                                           |
| **Erreurs**      | `400` — fichier manquant, vide, format non supporté ou langue cible absente |

### `GET /api/translation/jobs/{jobId}/status` *(nouveau, générique)*

Retourne le statut courant d'un job de traduction, quel que soit son format de sortie (PDF ou Markdown).

| Élément          | Détail                                                 |
|------------------|------------------------------------------------------------|
| **Method**       | `GET`                                                       |
| **Path**         | `/api/translation/jobs/{jobId}/status`                      |
| **Réponse OK**   | `200` — `application/json` — `TranslationJobResponse`      |
| **Erreurs**      | `404` — jobId inconnu                                       |

### `GET /api/translation/jobs/{jobId}` *(nouveau, générique)*

Télécharge le fichier traduit une fois le job terminé. Le `Content-Type` et l'extension du fichier retourné dépendent du format de sortie associé au job (`application/pdf` + `.pdf` pour un job PDF, `text/markdown` + `.md` pour un job Markdown).

| Élément          | Détail                                                           |
|------------------|---------------------------------------------------------------------|
| **Method**       | `GET`                                                                |
| **Path**         | `/api/translation/jobs/{jobId}`                                      |
| **Réponse OK**   | `200` — `application/pdf` ou `text/markdown` selon le format du job  |
| **Erreurs**      | `404` — jobId inconnu                                                 |
|                  | `409 Conflict` — job pas encore terminé (`PENDING`/`PROCESSING`)      |
|                  | `410 Gone` — fichier expiré (nettoyé après TTL)                       |
|                  | `422` — job en erreur (`FAILED`)                                      |

### `GET /api/translation/pdf/{jobId}/status` *(déprécié)*

> **Deprecated** : conservé pour compatibilité ascendante. Utiliser `GET /api/translation/jobs/{jobId}/status`. Comportement inchangé par rapport à l'existant (délègue en interne à la même logique que l'endpoint générique).

### `GET /api/translation/pdf/{jobId}` *(déprécié)*

> **Deprecated** : conservé pour compatibilité ascendante. Utiliser `GET /api/translation/jobs/{jobId}`. Comportement inchangé par rapport à l'existant (délègue en interne à la même logique que l'endpoint générique).

## Modèle de données

Aucun nouveau DTO n'est nécessaire, ni de champ additionnel sur les DTOs existants : le endpoint réutilise les records existants tels quels :

- `TranslationJobResponse(String jobId, JobStatus status)` — réponse de soumission et de suivi de statut, inchangée.
- `JobStatus` (`PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`) — statut du job, inchangé, partagé entre tous les formats.

Le format de sortie d'un job (PDF ou Markdown) n'a pas besoin d'être exposé dans les DTOs publics : il reste un détail d'implémentation interne. Pour le résoudre de façon fiable au moment du téléchargement, `TranslationJob` (record interne de `TranslationJobStore`, non exposé au client) est enrichi d'un champ `outputFormat` :

```java
// Record interne de TranslationJobStore — jamais sérialisé côté client
record TranslationJob(
    String jobId,
    JobStatus status,
    Instant createdAt,
    Instant completedAt,
    String errorMessage,
    JobOutputFormat outputFormat
) {}
```

```java
// Format de sortie d'un job, usage interne uniquement (non exposé dans TranslationJobResponse)
enum JobOutputFormat {
    PDF,
    MARKDOWN
}
```

`TranslationJobStore.createPendingJob()` est conservée (par défaut `PDF`, pour compatibilité) et complétée par une surcharge `createPendingJob(JobOutputFormat)` utilisée par `submitMarkdownTranslation`. Toutes les méthodes de transition (`markProcessing`, `markCompleted`, `markFailed`) doivent préserver `outputFormat` lors de la reconstruction du record.

> **Pourquoi un champ interne plutôt qu'une résolution par sondage du disque ?** Interroger successivement les services de stockage (« essayer `.pdf`, sinon `.md` ») fonctionne mais introduit des risques : condition de course si le fichier est encore en cours d'écriture, ambiguïté si les deux fichiers existent (bug), et absence de traçabilité en cas de diagnostic. Stocker `outputFormat` dans `TranslationJob` dès la soumission élimine ces risques, sans impacter les DTOs exposés ni casser la compatibilité API.

### Évolutions des classes de service existantes

| Classe                    | Évolution                                                                                     |
|---------------------------|-------------------------------------------------------------------------------------------------|
| `TranslationController`  | Ajout de `translateToMarkdown` (`POST /md`), `getJobStatus` et `downloadJob` génériques (`GET /jobs/{jobId}/status`, `GET /jobs/{jobId}`) ; les méthodes existantes `GET /pdf/{jobId}/status` et `GET /pdf/{jobId}` sont conservées, annotées `@Deprecated`, et délèguent aux mêmes méthodes de service que les endpoints génériques |
| `TranslationService`     | Ajout de `submitMarkdownTranslation(MultipartFile, String)`, de la logique de fusion des pages Markdown, et généralisation de `getJobStatusResponse`/`getTranslatedFile` : cette dernière lit `job.outputFormat()` pour déléguer au bon service de stockage et déterminer le `Content-Type`/nom de fichier à retourner |
| `PdfStorageService`      | Généralisée (ou dupliquée dans une classe dédiée `MarkdownStorageService`) pour stocker/charger/supprimer des fichiers `{jobId}.md` en plus des `{jobId}.pdf` |
| `TranslationJobStore`    | `TranslationJob` enrichi du champ interne `outputFormat` (`JobOutputFormat`) ; ajout de `createPendingJob(JobOutputFormat)` (surcharge, `createPendingJob()` existante conservée avec `PDF` par défaut) ; les méthodes `markProcessing`/`markCompleted`/`markFailed` préservent ce champ |
| `JobCleanupScheduler`    | Étendu pour nettoyer également les fichiers `.md` expirés, en choisissant le service de stockage à partir de `job.outputFormat()` |

> **Note d'implémentation** : si `PdfStorageService` est spécialisée sur l'extension `.pdf`, il est recommandé d'introduire une classe `MarkdownStorageService` (même contrat, extension `.md`) plutôt que de coupler artificiellement le stockage Markdown au nommage PDF. Le choix final (généralisation vs. duplication) est laissé à l'implémentation, du moment que la séparation des responsabilités reste claire. Le stockage Markdown suit le même mode d'écriture que `PdfStorageService.store()` aujourd'hui (écriture directe du fichier) — aucun changement du mécanisme d'écriture existant n'est demandé par cette spec.

### Package

Les nouvelles classes/méthodes s'intègrent dans les packages existants :

```
zas.admin.zia.translation.service.controller   → TranslationController (méthodes additionnelles + endpoints dépréciés)
zas.admin.zia.translation.service              → TranslationService (méthodes additionnelles, logique générique par format)
zas.admin.zia.translation.service.storage      → MarkdownStorageService (nouvelle classe, si duplication retenue)
zas.admin.zia.translation.service.job          → JobOutputFormat (nouvel enum interne), TranslationJob (champ additionnel interne)
```

## Règles métier

1. **Validation** — identique aux endpoints existants : fichier non-null, non-vide, format supporté (vérifié via `DocumentParser`), taille ≤ `zia.translation.pdf.max-file-size`, `targetLanguage` non-null/non-vide. Toute violation retourne `400`.
2. **Traduction en Markdown** — chaque page est traduite avec `renderAsMarkdown = true`, produisant un contenu Markdown par page (mise en forme conservée : titres, listes, tableaux, etc. si présents dans la traduction).
3. **Fusion des pages** — les pages Markdown traduites sont concaténées dans l'ordre du document source. Chaque page est séparée de la suivante par le séparateur `<div style="page-break-after: always;"></div>` (sur sa propre ligne, entourée de sauts de ligne). Aucun séparateur n'est ajouté après la dernière page.
4. **Traitement asynchrone** — la soumission (`POST /api/translation/md`) retourne immédiatement un `jobId` (`202 Accepted`) ; le traitement (extraction + traduction + fusion Markdown) s'exécute en arrière-plan sur l'executor dédié (`zia.translation.async.pool-size`), à l'identique du flux PDF.
5. **Stockage** — le fichier `.md` généré est écrit sur `zia.translation.pdf.storage-path` sous le nom `{jobId}.md`.
6. **Cycle de vie du job** — un job Markdown suit les mêmes statuts (`PENDING` → `PROCESSING` → `COMPLETED`/`FAILED`) que les jobs PDF, stockés dans le même `TranslationJobStore`, avec un champ interne `outputFormat` (`PDF`/`MARKDOWN`) fixé à la création du job et invariant sur toute sa durée de vie (jamais exposé dans `TranslationJobResponse`).
7. **Endpoints génériques** — `GET /api/translation/jobs/{jobId}/status` et `GET /api/translation/jobs/{jobId}` fonctionnent indifféremment pour un job PDF ou Markdown ; lors du téléchargement, le `Content-Type` et le nom de fichier sont déterminés à partir de `job.outputFormat()`, qui indique sans ambiguïté quel service de stockage interroger.
8. **Compatibilité ascendante** — `GET /api/translation/pdf/{jobId}/status` et `GET /api/translation/pdf/{jobId}` restent fonctionnels et se comportent exactement comme avant cette évolution (aucun changement de contrat, de code de statut ou de payload) ; ils sont marqués `@Deprecated` dans le code et documentés comme dépréciés dans l'OpenAPI, avec une recommandation de migrer vers les endpoints génériques `/api/translation/jobs/{jobId}...`.
9. **Nettoyage** — le `JobCleanupScheduler` supprime également les fichiers `.md` expirés (TTL identique à `zia.translation.pdf.job-ttl`), en sélectionnant le service de stockage approprié via `job.outputFormat()`.
10. **Gestion d'erreurs** — en cas d'échec pendant le traitement (OCR/traduction), le job passe à `FAILED` avec un message d'erreur consultable via l'endpoint status ; le téléchargement retourne alors `422`.
11. **Content-Type de téléchargement** — la réponse de téléchargement utilise `application/pdf` (`Content-Disposition: attachment; filename="{jobId}.pdf"`) pour un job `outputFormat=PDF`, et `text/markdown` (`Content-Disposition: attachment; filename="{jobId}.md"`) pour un job `outputFormat=MARKDOWN`.

## Critères d'acceptation

- [ ] `POST /api/translation/md` retourne `202` avec un `jobId` (job créé en interne avec `outputFormat=MARKDOWN`, non exposé dans la réponse).
- [ ] `GET /api/translation/jobs/{jobId}/status` retourne le statut correct, pour un job PDF comme pour un job Markdown.
- [ ] `GET /api/translation/jobs/{jobId}` retourne le fichier traduit avec le bon `Content-Type` (`application/pdf` ou `text/markdown`) selon `job.outputFormat()`, quand celui-ci est `COMPLETED`.
- [ ] `GET /api/translation/jobs/{jobId}` retourne `409` si le job n'est pas terminé, `410` si le fichier a été nettoyé, `422` si le job a échoué, `404` si le jobId est inconnu — pour les deux formats.
- [ ] Le contenu Markdown généré fusionne toutes les pages traduites, séparées par `<div style="page-break-after: always;"></div>`, sans séparateur final après la dernière page.
- [ ] La validation d'entrée (fichier manquant/vide/format non supporté, langue absente) retourne `400` de façon synchrone, comme pour `/pdf`.
- [ ] Les jobs Markdown expirés sont automatiquement nettoyés (fichier `.md` + métadonnées du job) par `JobCleanupScheduler`, en résolvant le bon service de stockage via `job.outputFormat()`.
- [ ] `GET /api/translation/pdf/{jobId}/status` et `GET /api/translation/pdf/{jobId}` continuent de fonctionner exactement comme avant (non-régression), sont annotés `@Deprecated` dans le code, et documentés comme dépréciés dans l'OpenAPI.
- [ ] Fichier `openapi.yaml` mis à jour : nouvel endpoint `POST /md`, nouveaux endpoints génériques `GET /jobs/{jobId}/status` et `GET /jobs/{jobId}`, endpoints `/pdf/{jobId}/status` et `/pdf/{jobId}` marqués `deprecated: true` (le schema `TranslationJobResponse` reste inchangé, `outputFormat` n'étant pas exposé).
- [ ] Tests unitaires : logique de fusion Markdown dans `TranslationService` (fusion multi-pages, cas une seule page, cas zéro page), résolution du `Content-Type`/service de stockage via `job.outputFormat()`, préservation du champ `outputFormat` dans `TranslationJobStore` à travers les transitions (`markProcessing`/`markCompleted`/`markFailed`), et stockage/lecture/suppression des fichiers `.md`.
- [ ] Tests d'intégration : `POST /md`, `GET /jobs/{jobId}/status`, `GET /jobs/{jobId}` (pour un job PDF et un job Markdown), ainsi que la non-régression des endpoints dépréciés `/pdf/{jobId}/status` et `/pdf/{jobId}`, couvrant les codes d'erreur (`400`, `404`, `409`, `410`, `422`).
- [ ] `mvn clean verify` passe sans erreur.
