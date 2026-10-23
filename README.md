# zia-translation

Service Spring Boot (Java 25, Spring AI) de **traduction** et d'**extraction de données** de documents (PDF ou
images), basé sur les modèles internes : un modèle vision (OCR, classification, extraction) et un LLM (traduction).

## 1. Fonctionnalités

Le contrat complet de l'API est décrit en OpenAPI dans
[`src/main/resources/static/openapi.yaml`](src/main/resources/static/openapi.yaml). Le service le publie aussi à
l'adresse `/zia-trad/openapi.yaml`.

Toutes les routes sont préfixées par le contexte `/zia-trad`. L'accès est réservé aux appelants authentifiés
(JWT Blue Gateway) qui sont membres d'un des groupes de `zia.security.translator-groups`.

### Traduction (`/api/translation`)

| Route | Description |
|---|---|
| `POST /pdf` | Traduction asynchrone d'un PDF ou d'une image : renvoie un job, puis un PDF traduit. |
| `POST /md` | Traduction asynchrone : renvoie un job, puis un fichier Markdown traduit. |
| `GET /jobs/{jobId}/status`, `GET /jobs/{jobId}` | Statut du job, puis téléchargement du résultat (PDF ou Markdown). |
| `POST /text` | Traduction d'un document, pages traduites renvoyées en flux SSE. |
| `POST /plain-text` | Traduction synchrone d'un texte brut. |

Deux stratégies existent :
- `single` : le modèle vision lit et traduit en un seul appel ;
- `dual` (par défaut) : le modèle vision fait l'OCR, puis le LLM traduit.

Un pied de page signale que le document a été traduit par une IA.

### Extraction de données (`/api/v1`)

| Route | Description |
|---|---|
| `POST /extractions` | Soumission asynchrone d'une extraction (fichier + contexte JSON), idempotente via `Idempotency-Key`. |
| `GET /extractions/{jobId}` | Statut du job et, une fois terminé, son résultat (valeurs, confiance, page, preuve, classification des pages). |
| `GET /extraction-profiles` | Liste des profils d'extraction connus. |
| `GET /extraction-profiles/{profileId}` | Clés (champs et collections) d'un profil. |

Le client principal est `ole-sumex-invoice-parser`, avec le profil `sumex-invoice` (factures envoyées à l'AI/IV).

## 2. Flux d'une extraction de données

```
POST /extractions ──► validation ──► job PENDING ──► (exécuteur asynchrone) RUNNING
                                                     │
                     rendu des pages ◄───────────────┘
                           │
          profil avec pageClassification ou pageHints ?
                 │ non                              │ oui
     extraction page par page          classification des pages (hints + modèle)
                 │                                  │
                 │                     consolidation (document principal)
                 │                                  │
                 │                     extraction multi-images des pages MAIN
                 └──────────────┬───────────────────┘
                        fusion des résultats ──► job SUCCEEDED / FAILED
GET /extractions/{jobId} ◄── polling (Retry-After)
```

### 2.1 Soumission et validation (`ExtractionService.submit`)

La requête est un `multipart/form-data` avec deux parties :
- `file` : PDF, PNG, JPEG, GIF ou BMP ;
- `context` : JSON (`ExtractionContext`) avec `profile`, `documentId`, `language`, `knownFields`, `missingFields`,
  `instructions` et `pageHints`.

Le service fait les vérifications suivantes :
1. **`Idempotency-Key`** : obligatoire, 128 caractères au maximum. Une clé déjà connue renvoie le job existant,
   sans nouveau traitement.
2. **Contexte** (`ExtractionRequestResolver`) :
   - `missingFields` : entre 1 et `zia.extraction.max-fields` clés (80 par défaut) ;
   - `instructions` : `zia.extraction.max-instructions-length` caractères au maximum ;
   - `pageHints` : page ≥ 1, `documentRole` obligatoire, `formId` de 35 caractères au maximum, pas de page en double.
3. **Résolution des clés** :
   - **avec profil** : chaque clé de `missingFields` est cherchée parmi les champs et collections du profil. Une
     clé inconnue est ignorée, avec un avertissement. Si aucune clé n'est connue, la requête est refusée (400) ;
   - **sans profil** (requête ad hoc) : chaque clé est extraite comme texte libre.
4. **Document** : taille maximale `zia.translation.pdf.max-file-size`. Le type est détecté d'après la signature du
   contenu, puis d'après le content-type déclaré.

Le job est créé à l'état `PENDING` et confié à l'exécuteur asynchrone. La réponse est un `202`, avec les en-têtes
`Location` et `Retry-After`. Si l'exécuteur est saturé, la réponse est un `429`.

### 2.2 Profils d'extraction

Un profil est un fichier YAML. Les profils sont chargés au démarrage depuis `classpath:extraction-profiles/*.yaml` et
depuis le répertoire optionnel `zia.extraction.profiles-location`. Un profil invalide empêche le démarrage.

Un profil contient :
- **`instructions`** : contexte métier ajouté au prompt d'extraction (rôles des parties, identifiants, règles
  propres au domaine…) ;
- **`fields`** et **`fieldGroups`** : définition des valeurs à extraire (`key`, `type`, `description`, `values`).
  Un `fieldGroup` produit une clé `<préfixe>.<nom>` pour chaque préfixe (ex. `biller.gln`, `patient.gln`…).
  Les types sont `STRING`, `DATE`, `AMOUNT`, `NUMBER`, `IDENTIFIER` et `ENUM` ;
- **`collections`** : lignes répétées (ex. `services` : date, code, quantité, montant…) ;
- **`pageClassification`** (optionnel) : active la classification des pages et décrit le domaine.
  - `mainDocument` est une phrase courte qui décrit le document principal. Elle est utilisée par les prompts de
    classification et d'extraction.
  - `instructions` donne le contexte métier pour distinguer le document principal des justificatifs (formulaires
    connus, types de pièces jointes…).

```yaml
pageClassification:
  mainDocument: the invoice addressed to the Swiss disability insurance (IV office) or to the insured person
  instructions: |
    The files are invoices sent to the Swiss disability insurance (IV/AI, ...), in German, French or Italian.
    - Main document: an IV invoice form (FORM, e.g. form 300.001 "Rechnung für Hörgeräteversorgung" ...) ...
    - Supporting documents: copies of invoices of the seller or of the manufacturer, quotes, ...
```

Les prompts du code restent **génériques** : sens des clés renvoyées, rôles `MAIN`/`SUPPORTING`/`OTHER` et règles
de lecture. Tout le vocabulaire métier est dans le profil. Pour traiter un nouveau type de document, il suffit donc
d'ajouter un profil, sans modifier le code.

### 2.3 Rendu des pages

Le traitement asynchrone commence par le rendu des pages : les PDF sont rendus en images à 150 DPI, les images sont
prises telles quelles. Seules les `zia.extraction.max-pages` premières pages (20 par défaut) sont analysées, avec un
avertissement si le document en a davantage. Un document illisible ou sans page met le job en échec
(`UNREADABLE_DOCUMENT`).

### 2.4 Classification des pages

La classification n'a lieu que si le profil déclare une section `pageClassification` (cas de `sumex-invoice`) ou si
la requête contient des `pageHints`. Hypothèse du contrat : **un fichier contient un seul document principal**,
éventuellement accompagné de justificatifs.

1. **Hints du client** : une page avec un `pageHint` est reprise telle quelle et n'est pas reclassée. Un hint qui
   porte sur une page non analysée est ignoré, avec un avertissement.
2. **Classification par le modèle** (`PageClassifier`) : chaque page sans hint fait l'objet d'un appel léger au
   modèle vision. L'image fait 1024 px au maximum et la réponse est contrainte par un JSON Schema strict.
   - Le modèle renvoie `title`, `recipient`, `formId`, `printedPageNumber`/`printedPageCount` (« Seite 2 von 2 »),
     `continuesPreviousPage`, `documentRole` (`MAIN`, `SUPPORTING`, `OTHER`), `documentType` (`FORM`, `INVOICE`,
     `QUOTE`, `POWER_OF_ATTORNEY`…), `evidence` et `confidence`.
   - Le prompt contient la description `mainDocument` et les `instructions` du profil. Il contient aussi la
     classification des pages déjà connues (hints et pages précédentes), comme contexte.
   - En cas d'échec, la page reste non classée, avec l'avertissement « Page n could not be classified ».
3. **Consolidation** (`PageClassificationResolver`, logique déterministe) :
   - les hints ne sont jamais modifiés ;
   - une page classée `SUPPORTING`/`OTHER` par le modèle devient `MAIN` si elle suit une page `MAIN` **et** si l'une
     des conditions suivantes est remplie : même `formId`, numérotation imprimée qui se suit, ou continuation
     indiquée par le modèle. Un avertissement le signale ;
   - **pages à extraire** : les pages `MAIN` et les pages non classées, qui sont traitées comme des pages du document
     principal ;
   - **sans page `MAIN`** : toutes les pages sauf les pages `OTHER` sont analysées, avec un avertissement.
     Si toutes les pages sont `OTHER`, elles sont toutes analysées.

La classification finale est renvoyée dans `result.pages`. Les pages non classées en sont absentes.

### 2.5 Extraction des valeurs (`VisionFieldExtractor`)

- **Avec classification** : les pages du document principal sont envoyées **ensemble, dans un seul appel**. Il y a
  plusieurs images par message, `zia.extraction.max-images-per-call` au maximum (4 par défaut, à aligner sur
  `--limit-mm-per-prompt` du vLLM). Au-delà, les pages sont réparties en lots.
  - Le prompt indique quelles pages du fichier sont envoyées.
  - Il précise que les autres pages sont des justificatifs : elles ne sont pas montrées, et aucune valeur ne doit en
    provenir.
  - Il précise aussi que la numérotation du fichier est technique et ne doit pas être signalée.
  - Chaque valeur renvoie la `page` où elle a été lue. Une page absente ou invalide est remplacée par la première page
    du lot.
- **Sans classification** : chaque page est extraite séparément.
- **Images** : elles sont préparées à une résolution propre à l'extraction (`zia.extraction.vision.max-edge`,
  1792 px par défaut, JPEG `zia.extraction.vision.jpeg-quality` = 0.9). Cette résolution plus haute permet de lire
  les cases à cocher et les montants.
- **Sortie structurée** : un JSON Schema strict est généré à partir des clés demandées (`ExtractionSchemaGenerator`).
  Chaque valeur prend la forme `{value, evidence, confidence[, page]}`. Avec
  `zia.extraction.response-format=json_object`, le schéma est inclus dans le prompt.
- **Prompt** :
  - règles génériques : ne renvoyer que des valeurs imprimées, preuve copiée telle qu'imprimée ;
  - pour un formulaire, seules les cases **cochées ou remplies** donnent des lignes ;
  - contenu propre à la requête : instructions du profil, description de chaque clé et de son format, valeurs déjà
    connues (`knownFields`, en indice seulement), instructions du client.

Un appel en échec donne un avertissement. Si **aucun** appel ne réussit, le job échoue (`EXTRACTION_FAILED`).

### 2.6 Normalisation et confiance

- **Normalisation** (`FieldValueNormalizer`) :
  - les dates sont mises au format `yyyy-MM-dd` ;
  - les montants et nombres utilisent `.` comme séparateur décimal, sans devise ni séparateur de milliers ;
  - les identifiants sont écrits sans espaces ni séparateurs ;
  - les `ENUM` doivent faire partie des valeurs autorisées.

  Une valeur non normalisable est rejetée, avec un avertissement.
- **Confiance** (`ConfidenceCalculator`) : la confiance déclarée par le modèle est plafonnée dans deux cas :
  - à `zia.extraction.confidence.no-evidence-cap` (0.3) si aucune preuve n'est donnée ;
  - à `zia.extraction.confidence.unmatched-evidence-cap` (0.5) si la valeur ne se retrouve pas dans sa preuve.

  Les sommes de contrôle (GLN, AVS, IBAN…) restent vérifiées côté client.

### 2.7 Fusion et résultat (`ExtractionResultMerger`)

- **Champs** : quand une clé est trouvée par plusieurs appels, la valeur la plus confiante est retenue. En cas
  d'égalité, c'est la première page qui l'emporte.
- **Collections** : les lignes sont concaténées dans l'ordre des pages. Une ligne identique à une ligne d'une autre
  page (page répétée) est supprimée.
- **Avertissements** : ceux de la résolution et du traitement sont suivis de ceux du modèle, préfixés par les pages
  concernées (`Page 2: …`, `Pages 1, 2: …`).

Le job passe à `SUCCEEDED` avec le résultat `{profile, documentType, pages, fields, collections, warnings}`, ou à
`FAILED` avec une erreur `{code, message}` : `UNREADABLE_DOCUMENT`, `EXTRACTION_FAILED` ou `INTERNAL_ERROR`. Les jobs
terminés sont conservés pendant `zia.extraction.job-ttl` (24 h par défaut), puis supprimés. Un job inconnu ou expiré
répond `404`.

### 2.8 Paramètres de l'extraction

| Propriété | Défaut | Rôle |
|---|---|---|
| `zia.extraction.max-pages` | 20 | Pages analysées au maximum |
| `zia.extraction.max-images-per-call` | 4 | Images par appel au modèle vision (≤ `--limit-mm-per-prompt`) |
| `zia.extraction.vision.max-edge` / `jpeg-quality` | 1792 / 0.9 | Résolution des images d'extraction |
| `zia.extraction.response-format` | `json_schema` | `json_schema` (décodage guidé) ou `json_object` |
| `zia.extraction.max-fields` / `max-instructions-length` | 80 / 2000 | Limites du contexte |
| `zia.extraction.confidence.no-evidence-cap` / `unmatched-evidence-cap` | 0.3 / 0.5 | Plafonds de confiance |
| `zia.extraction.profiles-location` | — | Répertoire de profils supplémentaires (`file:/config/extraction-profiles/`) |
| `zia.extraction.job-ttl` / `cleanup-interval` / `retry-after` | 24h / 10m / 5 | Cycle de vie des jobs et polling |
