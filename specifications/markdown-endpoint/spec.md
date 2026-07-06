# Markdown Endpoint

## Contexte

Le service `zia-translation` expose aujourd'hui un endpoint de traduction asynchrone au format PDF (`POST /api/translation/pdf`) ainsi qu'un endpoint de streaming texte (`POST /api/translation/text`). Pour faciliter l'évaluation qualitative du service (relecture rapide du contenu traduit, diff textuel, intégration dans des outils de revue), il est utile de pouvoir récupérer directement le résultat de la traduction sous forme d'un unique fichier **Markdown**, plus simple à inspecter, versionner et comparer qu'un PDF binaire.

Cette spec ajoute un nouvel endpoint `POST /api/translation/md`, calqué sur le fonctionnement asynchrone de `POST /api/translation/pdf` (soumission de job, suivi de statut, téléchargement du résultat), mais produisant un fichier `.md` au lieu d'un `.pdf`.

## Description

Le service doit :

1. Accepter un document source (même contrat d'entrée que `/api/translation/pdf` : `file` + `targetLanguage`, validation identique, réutilisation de `DocumentParser` pour l'extraction des pages).
2. Traduire chaque page du document (même pipeline OCR/traduction — stratégies `single`/`dual` — que les endpoints existants), en demandant un rendu du texte traduit au format **Markdown** par page (`renderAsMarkdown = true`, déjà supporté par `TextTranslationService`).
3. Fusionner le Markdown de chaque page traduite en un seul document, en insérant entre chaque page le séparateur HTML suivant :

   ```html
   <div style="page-break-after: always;"></div>
   ```

4. Traiter la génération de façon asynchrone (job en arrière-plan), à l'instar de `POST /api/translation/pdf` : soumission → `202 Accepted` + `jobId`, suivi du statut via un endpoint dédié, puis téléchargement du fichier `.md` une fois le job `COMPLETED`.
5. Stocker le fichier Markdown généré sur le même volume que les PDF (`zia.translation.pdf.storage-path`), sous le nom `{jobId}.md`, avec le même mécanisme de nettoyage par TTL (`JobCleanupScheduler`).

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

### `GET /api/translation/md/{jobId}/status`

Retourne le statut courant du job de génération Markdown.

| Élément          | Détail                                                 |
|------------------|------------------------------------------------------------|
| **Method**       | `GET`                                                       |
| **Path**         | `/api/translation/md/{jobId}/status`                        |
| **Réponse OK**   | `200` — `application/json` — `TranslationJobResponse`      |
| **Erreurs**      | `404` — jobId inconnu                                       |

### `GET /api/translation/md/{jobId}`

Télécharge le fichier Markdown traduit une fois le job terminé.

| Élément          | Détail                                                           |
|------------------|---------------------------------------------------------------------|
| **Method**       | `GET`                                                                |
| **Path**         | `/api/translation/md/{jobId}`                                        |
| **Réponse OK**   | `200` — `text/markdown` (stream du fichier `.md`)                    |
| **Erreurs**      | `404` — jobId inconnu                                                 |
|                  | `409 Conflict` — job pas encore terminé (`PENDING`/`PROCESSING`)      |
|                  | `410 Gone` — fichier expiré (nettoyé après TTL)                       |
|                  | `422` — job en erreur (`FAILED`)                                      |

## Modèle de données

Aucun nouveau DTO n'est nécessaire : le endpoint réutilise les records existants :

- `TranslationJobResponse(String jobId, JobStatus status)` — réponse de soumission et de suivi de statut.
- `JobStatus` (`PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`) — statut du job, partagé avec le flux PDF.

### Évolutions des classes de service existantes

| Classe                    | Évolution                                                                                     |
|---------------------------|-------------------------------------------------------------------------------------------------|
| `TranslationController`  | Ajout des trois méthodes `translateToMarkdown`, `getMarkdownJobStatus`, `downloadMarkdown`      |
| `TranslationService`     | Ajout de `submitMarkdownTranslation(MultipartFile, String)` et de la logique de fusion des pages Markdown |
| `PdfStorageService`      | Généralisée (ou dupliquée dans une classe dédiée `MarkdownStorageService`) pour stocker/charger/supprimer des fichiers `{jobId}.md` en plus des `{jobId}.pdf` |
| `TranslationJobStore`    | Réutilisée telle quelle — un job Markdown suit le même cycle de vie qu'un job PDF               |
| `JobCleanupScheduler`    | Étendu pour nettoyer également les fichiers `.md` expirés                                        |

> **Note d'implémentation** : si `PdfStorageService` est spécialisée sur l'extension `.pdf`, il est recommandé d'introduire une classe `MarkdownStorageService` (même contrat, extension `.md`) plutôt que de coupler artificiellement le stockage Markdown au nommage PDF. Le choix final (généralisation vs. duplication) est laissé à l'implémentation, du moment que la séparation des responsabilités reste claire.

### Package

Les nouvelles classes/méthodes s'intègrent dans les packages existants :

```
zas.admin.zia.translation.service.controller   → TranslationController (méthodes additionnelles)
zas.admin.zia.translation.service              → TranslationService (méthodes additionnelles)
zas.admin.zia.translation.service.storage      → MarkdownStorageService (nouvelle classe, si duplication retenue)
```

## Règles métier

1. **Validation** — identique aux endpoints existants : fichier non-null, non-vide, format supporté (vérifié via `DocumentParser`), taille ≤ `zia.translation.pdf.max-file-size`, `targetLanguage` non-null/non-vide. Toute violation retourne `400`.
2. **Traduction en Markdown** — chaque page est traduite avec `renderAsMarkdown = true`, produisant un contenu Markdown par page (mise en forme conservée : titres, listes, tableaux, etc. si présents dans la traduction).
3. **Fusion des pages** — les pages Markdown traduites sont concaténées dans l'ordre du document source. Chaque page est séparée de la suivante par le séparateur `<div style="page-break-after: always;"></div>` (sur sa propre ligne, entourée de sauts de ligne). Aucun séparateur n'est ajouté après la dernière page.
4. **Traitement asynchrone** — la soumission (`POST /api/translation/md`) retourne immédiatement un `jobId` (`202 Accepted`) ; le traitement (extraction + traduction + fusion Markdown) s'exécute en arrière-plan sur l'executor dédié (`zia.translation.async.pool-size`), à l'identique du flux PDF.
5. **Stockage** — le fichier `.md` généré est écrit sur `zia.translation.pdf.storage-path` sous le nom `{jobId}.md`.
6. **Cycle de vie du job** — un job Markdown suit les mêmes statuts (`PENDING` → `PROCESSING` → `COMPLETED`/`FAILED`) que les jobs PDF, stockés dans le même `TranslationJobStore`.
7. **Nettoyage** — le `JobCleanupScheduler` supprime également les fichiers `.md` expirés (TTL identique à `zia.translation.pdf.job-ttl`).
8. **Gestion d'erreurs** — en cas d'échec pendant le traitement (OCR/traduction), le job passe à `FAILED` avec un message d'erreur consultable via l'endpoint status ; le téléchargement retourne alors `422`.
9. **Content-Type de téléchargement** — la réponse de téléchargement utilise `text/markdown` (avec en-tête `Content-Disposition: attachment; filename="{jobId}.md"`).

## Critères d'acceptation

- [ ] `POST /api/translation/md` retourne `202` avec un `jobId`.
- [ ] `GET /api/translation/md/{jobId}/status` retourne le statut correct du job.
- [ ] `GET /api/translation/md/{jobId}` retourne le fichier Markdown traduit (`text/markdown`) quand le job est `COMPLETED`.
- [ ] `GET /api/translation/md/{jobId}` retourne `409` si le job n'est pas terminé.
- [ ] `GET /api/translation/md/{jobId}` retourne `410` si le fichier a été nettoyé.
- [ ] `GET /api/translation/md/{jobId}` retourne `422` si le job a échoué.
- [ ] Le contenu Markdown généré fusionne toutes les pages traduites, séparées par `<div style="page-break-after: always;"></div>`, sans séparateur final après la dernière page.
- [ ] La validation d'entrée (fichier manquant/vide/format non supporté, langue absente) retourne `400` de façon synchrone, comme pour `/pdf`.
- [ ] Les jobs Markdown expirés sont automatiquement nettoyés (fichier `.md` + métadonnées du job) par `JobCleanupScheduler`.
- [ ] Fichier `openapi.yaml` mis à jour avec le nouvel endpoint et ses schemas (réutilisation de `TranslationJobResponse`/`JobStatus`).
- [ ] Tests unitaires : logique de fusion Markdown dans `TranslationService` (fusion multi-pages, cas une seule page, cas zéro page), et stockage/lecture/suppression des fichiers `.md`.
- [ ] Tests d'intégration : les 3 endpoints (`POST /md`, `GET /md/{jobId}/status`, `GET /md/{jobId}`), couvrant les cas nominaux et les codes d'erreur (`400`, `404`, `409`, `410`, `422`).
- [ ] `mvn clean verify` passe sans erreur.
