# Extraction Profile — gaime-civil-status

## Contexte

Le service d'extraction (`/api/v1/extractions`) extrait des valeurs structurées d'un document avec
le modèle de vision, à partir de profils serveur déclarés en YAML (`FieldDefinition` /
`CollectionDefinition`, schéma JSON généré dynamiquement par `ExtractionSchemaGenerator`).

Le besoin **gaime-civil-status** consiste à lire une **attestation d'état civil** : un formulaire à
7 cases à cocher (une seule case cochée normalement), signé par la personne. Le fichier soumis peut
contenir d'autres pages (p. ex. une lettre de couverture) : au plus une page est censée être
l'attestation.

Ce besoin est couvert par un **profil YAML ordinaire** du pipeline générique, sans code Java
spécifique : `src/main/resources/extraction-profiles/gaime-civil-status.yaml`.

## Description

```
POST /api/v1/extractions        (multipart: file=<pdf ou image>, context={"profile":"gaime-civil-status"})
GET  /api/v1/extractions/{jobId}
```

Le profil s'appuie sur trois options **génériques** des profils, utilisables par d'autres profils :

| Option | Effet |
|---|---|
| `perPage: true` | Chaque page est extraite seule, sans classification, et renvoyée telle quelle dans `result.pageResults` (pas de fusion). |
| `simpleOutput: true` | Le modèle ne renvoie que la valeur brute de chaque clé (pas d'`evidence` / `confidence` / `page` / `documentType` / `warnings`). |
| `nullable: false` (par champ ENUM) | Le schéma JSON interdit la réponse `null` : le modèle doit choisir une des valeurs listées. |

### `perPage`

- Chaque page (dans la limite de `zia.extraction.max-pages`) est analysée en **un seul appel** à
  `VisionFieldExtractor`, sans `PageClassifier` ni `ExtractionResultMerger`.
- `perPage` est incompatible avec `pageClassification` (profil rejeté au chargement). Les
  `pageHints` éventuels sont ignorés avec l'avertissement
  `"'pageHints' ignored: profile 'gaime-civil-status' extracts each page on its own"`.
- Le résultat est renvoyé dans `result.pageResults` (`[{page, documentType, fields, collections}]`,
  une entrée par page analysée, dans l'ordre) ; `result.fields` / `result.collections` /
  `result.pages` sont absents.
- Une page en échec est absente de `pageResults` et signalée dans `warnings`
  (`"Page <n> could not be analysed by the extraction service"`) ; si toutes les pages échouent, le
  job est en échec (`EXTRACTION_FAILED`).

### Champs du profil (identiques pour chaque page)

Tous les champs sont de type `ENUM` et déclarés `nullable: false`.

| Clé | Valeurs | Signification |
|---|---|---|
| `civil_status_detected` | `true` / `false` / `UNCLEAR` | La page est-elle l'attestation (titre + 7 cases) ? |
| `signature_present` | `true` / `false` / `UNCLEAR` | Le formulaire porte-t-il une signature manuscrite ? |
| `box_married` … `box_widowed_lpart` (7 clés) | `MARKED` / `UNCLEAR` / `UNMARKED` | État de chacune des 7 cases. |

Quand `civil_status_detected` vaut `false`, le modèle répond `signature_present = false` et toutes les cases `UNMARKED`.

### Les 7 statuts

Dans l'ordre imposé par le formulaire (ordre des cases, de haut en bas) ; la clé de champ est
`box_<clé>` :

| # | Clé | FR | DE | ES | IT | PT | EN |
|---|---|---|---|---|---|---|---|
| 1 | `married` | marié(e) | verheiratet | casado(a) | sposato(a) | casado(a) | married |
| 2 | `widowed` | veuf/veuve | verwitwet | viudo(a) | vedovo(a) | viúvo(a) | widowed |
| 3 | `divorced` | divorcé(e) | geschieden | divorciado(a) | divorziato(a) | divorciado(a) | divorced |
| 4 | `legally_separated` | séparé(e) de droit (par jugement) | gerichtlich getrennt (durch Urteil) | separado(a) legalmente (por sentencia) | separato(a) legalmente (tramite sentenza) | separado(a) por decisão judicial | legally separated (by judgment) |
| 5 | `married_lpart` | marié(e) LPart** | verheiratet/PartG** | casado(a) LPart** | sposato(a) LUD** | casado(a) LPart** | married LPart** |
| 6 | `divorced_lpart` | divorcé(e) LPart** | geschieden/PartG** | divorciado(a) LPart** | divorziato(a) LUD** | divorciado(a) LPart** | divorced LPart** |
| 7 | `widowed_lpart` | veuf/veuve LPart** | verwitwet/PartG** | viudo(a) LPart** | vedovo(a) LUD** | viúvo(a) LPart** | widowed LPart** |

### Détection du document (titre de l'attestation)

| Langue | Titre imprimé |
|---|---|
| Français | ATTESTATION D'ÉTAT CIVIL |
| Allemand | ZIVILSTANDSBESTÄTIGUNG |
| Espagnol | CERTIFICADO DE ESTADO CIVIL |
| Italien | ATTESTATO DELLO STATO CIVILE |
| Portugais | ATESTADO DE ESTADO CIVIL |
| Anglais | CIVIL STATUS CERTIFICATE |

D'autres langues ou variantes peuvent exister : les instructions demandent au modèle de reconnaître
le document par sa structure (titre + 7 cases), pas uniquement par une correspondance exacte de
texte.

## Contrat API

### Requête

```json
{
  "profile": "gaime-civil-status"
}
```

`missingFields` peut être omis ou vide : toutes les clés (`fields` + `collections`) du profil sont
alors demandées (comportement générique de `ExtractionRequestResolver` pour tout profil ; sans
profil, `missingFields` reste obligatoire). `knownFields` et `instructions` ne sont pas nécessaires.

### Réponse (`GET /api/v1/extractions/{jobId}`)

Enveloppe générique `ExtractionJobResponse` / `ExtractionResponse`. Avec `simpleOutput`, chaque
champ ne porte que `value` (ni `confidence`, ni `page`, ni `evidence`) et `documentType` est absent.

Exemple (PDF de 2 pages : page d'introduction puis attestation) :

```json
{
  "jobId": "abc123",
  "status": "SUCCEEDED",
  "result": {
    "profile": "gaime-civil-status",
    "pageResults": [
      {"page": 1, "fields": {"civil_status_detected": {"value": "false"}, "signature_present": {"value": "false"}, "box_married": {"value": "UNMARKED"}, "...": {}}},
      {"page": 2, "fields": {"civil_status_detected": {"value": "true"}, "signature_present": {"value": "true"}, "box_widowed": {"value": "MARKED"}, "box_married": {"value": "UNMARKED"}, "...": {}}}
    ],
    "warnings": []
  }
}
```

L'erreur de job (`ExtractionError` : `UNREADABLE_DOCUMENT`, `EXTRACTION_FAILED`,
`INTERNAL_ERROR`) reste l'erreur technique générique.

## Résolution métier (côté appelant)

Le service renvoie les valeurs lues page par page ; l'appelant (ex. GAIME) détermine le statut :

1. La **première** page dont `civil_status_detected = "true"` est l'attestation ; aucune → document inconnu.
2. Sur cette page, la première règle qui s'applique :

| Condition | Résultat |
|---|---|
| `signature_present = "false"` | non signé |
| `signature_present = "UNCLEAR"` ou au moins une case `UNCLEAR` | incertain |
| aucune case `MARKED` | aucune case |
| plus d'une case `MARKED` | plusieurs cases |
| exactement une case `MARKED` | statut = la clé de cette case |

### Compromis assumé

- avantage : aucun cas particulier dans le pipeline ; le profil se modifie (instructions,
  traductions) comme tout autre profil YAML, sans code Java ;
- inconvénient : la règle de priorité n'est pas garantie unique et testée côté serveur ; chaque
  appelant doit l'implémenter, avec un risque de divergence si la règle évolue. À reconsidérer si
  plusieurs clients consomment ce profil.

## Choix de conception

### Pourquoi des `fields` fixes plutôt qu'une collection

Une collection `pages` « une ligne par page d'attestation, aucune ligne sinon » a été écartée : la
règle « aucune ligne pour une page qui n'est pas une attestation » ne reposait que sur le prompt
(le schéma n'impose aucune taille au tableau), et une ligne inventée sur une page d'introduction
aurait été indiscernable d'une vraie attestation. Avec des `fields`, le schéma strict rend **toutes
les clés obligatoires** pour chaque page : le modèle doit se prononcer explicitement sur
`civil_status_detected` pour chaque page.

### `nullable: false` : le schéma interdit la réponse `null`

Par défaut (`nullable: true`), la valeur d'une clé est typée `["string", "null"]` et `null` fait
partie des valeurs ENUM : le modèle pourrait répondre `null` sans violer le schéma. Avec
`nullable: false`, `ExtractionSchemaGenerator` génère un `string` simple (sans `null` dans `type`
ni dans `enum`) : le modèle doit choisir une valeur réelle (`false` pour une page qui n'est pas
l'attestation, `UNCLEAR` pour une case illisible).

Si le modèle omet quand même une valeur, l'avertissement
`"Required value of '<clé>' missing on page <n>"` est ajouté à `result.warnings`. Si le champ est
un ENUM non nullable qui propose une valeur d'incertitude (`UNCLEAR` ou `UNKNOWN`), la clé est
conservée dans `fields` avec cette valeur de repli.

`nullable: false` n'est accepté que pour un champ **ENUM** : un profil qui le déclare sur un autre
type (`STRING`, `DATE`, `AMOUNT`…) est rejeté au démarrage. Sans `null`, seul un ENUM laisse au
modèle une réponse honnête (p. ex. `UNCLEAR`) ; pour une valeur libre, le modèle serait contraint
d'inventer une valeur plausible quand rien n'est imprimé.

### Sortie simple (`simpleOutput`) et prompt

`simpleOutput` évite de demander `evidence` / `confidence` / `page` / `documentType` / `warnings`,
inutiles pour des cases à cocher et des booléens.

Le prompt envoyé au modèle est composé de trois parties :

- un **prompt système commun** à tous les profils (`VisionFieldExtractor`) : ne renvoyer que ce qui
  est imprimé, une ligne de collection par ligne imprimée, valeurs connues en indice seulement ;
- une **section choisie selon `simpleOutput`** : en mode détaillé, les règles de `value` /
  `evidence` / `confidence` / `page` / `documentType` / `warnings` ; en mode simple (ce profil), la
  valeur seule. Le prompt système ne contredit ainsi jamais le schéma JSON généré ;
- les **`instructions` du profil**, dans le prompt utilisateur : tout ce qui est propre au document
  (titres, 7 cases et leurs traductions, définition de `MARKED` / `UNCLEAR` / `UNMARKED`, règle de
  comparaison des cases entre elles) se trouve dans `gaime-civil-status.yaml`, et non dans le
  prompt système. Les règles de cases à cocher de `sumex-invoice` sont de même dans les
  `instructions` de ce profil.

## Règles métier

- Chaque page est analysée indépendamment, en un seul appel au modèle (détection de l'attestation
  + lecture des cases combinées).
- Les 9 champs sont toujours demandés pour chaque page et ne peuvent pas valoir `null`.
- Les valeurs des cases sont exclusivement `MARKED`, `UNCLEAR` ou `UNMARKED` ; aucune note de
  confiance n'est renvoyée : l'incertitude est portée par `UNCLEAR`.
- Le service ne choisit pas la page d'attestation et ne calcule pas le statut : c'est le rôle de
  l'appelant (voir « Résolution métier »).

## Critères d'acceptation

- [x] `POST /api/v1/extractions` avec `context.profile = "gaime-civil-status"` est accepté et traité
      de façon asynchrone comme les autres profils (202 + `Location` + poll).
- [x] Le profil est chargé depuis le YAML comme un profil `perPage` + `simpleOutput` aux 9 champs
      ENUM non nullables (`ExtractionProfileRegistryTest`).
- [x] Chaque page est renvoyée dans `pageResults` sans fusion, sans classification ; une page en
      échec est signalée dans `warnings` ; les `pageHints` sont ignorés avec un avertissement
      (`ExtractionServiceTest.process_perPageProfile_returnsEachPageWithoutMerging`).
- [x] Avec `simpleOutput`, les champs ne portent que `value`, et le schéma ne demande ni
      `evidence`/`confidence`/`page`, ni `documentType`/`warnings` (`ExtractionSchemaGeneratorTest`).
- [x] `nullable: false` retire `null` du type et de l'`enum` ; une valeur manquante produit
      l'avertissement `Required value of '<clé>' missing on page <n>` et, quand l'ENUM contient
      `UNCLEAR`/`UNKNOWN`, la clé est conservée avec cette valeur.
- [x] `nullable: false` sur un champ non ENUM est rejeté au chargement
      (`ExtractionProfileRegistryTest.field_nonNullable_isOnlyAllowedForEnum`).
- [x] `perPage` combiné à `pageClassification` est rejeté au chargement.
- [x] `missingFields` omis : toutes les clés du profil sont demandées.
- [x] Le prompt système en mode simple ne mentionne pas `evidence`/`confidence`/`documentType`/
      `warnings` et ne contient aucune règle propre à un document (`VisionFieldExtractorTest`).
- [x] `mvn clean verify` passe sans erreur.

## Test manuel

En l'absence de modèle de vision réel dans les tests automatisés, valider les instructions sur de
vrais scans nécessite un test manuel :

1. Démarrer le service en local (hors profil `internal`, donc sans jeton Blue nécessaire) avec
   les variables `ZIA_VISION_BASE_URL`, `ZIA_VISION_MODEL`, `ZIA_LLM_BASE_URL`, `ZIA_LLM_MODEL`,
   `ZIA_INTERNAL_API_KEY` renseignées (le client LLM texte est instancié au démarrage même s'il
   n'est pas utilisé par ce profil).
2. Soumettre un PDF, avec le chemin de base de l'application
   (`server.servlet.context-path`, ex. `/zia-trad`) :
   ```bash
   curl -i -X POST http://localhost:8080/zia-trad/api/v1/extractions \
     -H "Idempotency-Key: $(uuidgen)" \
     -F "file=@/chemin/vers/certificate.pdf;type=application/pdf" \
     -F 'context={"profile":"gaime-civil-status"};type=application/json'
   ```
3. Récupérer `jobId` dans la réponse puis interroger jusqu'à `status = SUCCEEDED`/`FAILED` :
   ```bash
   curl -s http://localhost:8080/zia-trad/api/v1/extractions/<jobId>
   ```
4. Vérifier `result.pageResults` et `result.warnings` pour : document sans attestation,
   attestation non signée, 0/1/2+ cases cochées, case ambiguë, plusieurs pages d'attestation,
   plusieurs langues.
