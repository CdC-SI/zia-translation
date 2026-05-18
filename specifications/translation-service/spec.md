# Translation Service

## Contexte

Le projet `zia-translation` a besoin d'un service capable de recevoir un document (PDF dans un premier temps), d'en extraire le texte via OCR, de le traduire à l'aide d'un LLM, puis de retourner soit un PDF traduit, soit le texte traduit page par page.

Ce service constitue le cœur fonctionnel de l'application. Il s'appuie sur **Spring AI** (`ChatClient`) pour communiquer avec les modèles de langage et sur **Apache PDFBox 3** pour la manipulation des fichiers PDF.

## Description

Le service expose deux endpoints REST qui acceptent chacun un fichier PDF en entrée (`byte[]`) :

1. **Traduction en PDF** — retourne un nouveau document PDF contenant le texte traduit.
2. **Extraction du texte traduit** — retourne une liste de chaînes, chaque élément correspondant au texte traduit d'une page du document source.

Le processus de traduction repose sur un ou deux appels LLM :

- **Stratégie mono-appel** : un unique appel à un modèle vision capable d'effectuer l'OCR et la traduction en une seule passe.
- **Stratégie bi-appel** : un premier appel à un modèle vision pour l'extraction OCR du texte, puis un second appel à un modèle LLM textuel pour la traduction.

La stratégie utilisée est déterminée par la configuration (`application.properties`).

### Périmètre initial

- Seul le format **PDF** est supporté en entrée.
- D'autres formats de fichier seront ajoutés ultérieurement (le design doit anticiper cette extensibilité).

## API

### `POST /api/translation/pdf`

Traduit un document et retourne le résultat sous forme de PDF.

| Élément         | Détail                                              |
|-----------------|-----------------------------------------------------|
| **Method**      | `POST`                                              |
| **Path**        | `/api/translation/pdf`                              |
| **Content-Type**| `multipart/form-data`                               |
| **Paramètres**  | `file` — le fichier PDF source (`MultipartFile`)   |
|                 | `targetLanguage` — code langue cible (ex. `fr`, `en`, `de`) (`String`, requis) |
| **Réponse OK**  | `200` — `application/pdf` (`byte[]`)               |
| **Erreurs**     | `400` — fichier manquant, vide, format non supporté ou langue cible absente |
|                 | `422` — échec de l'extraction OCR ou de la traduction |
|                 | `500` — erreur interne                              |

### `POST /api/translation/text`

Traduit un document et retourne le texte traduit page par page.

| Élément         | Détail                                              |
|-----------------|-----------------------------------------------------|
| **Method**      | `POST`                                              |
| **Path**        | `/api/translation/text`                             |
| **Content-Type**| `multipart/form-data`                               |
| **Paramètres**  | `file` — le fichier PDF source (`MultipartFile`)   |
|                 | `targetLanguage` — code langue cible (ex. `fr`, `en`, `de`) (`String`, requis) |
| **Réponse OK**  | `200` — `application/json`                         |
| **Body réponse**| `TranslationTextResponse` (voir modèle ci-dessous) |
| **Erreurs**     | `400` — fichier manquant, vide, format non supporté ou langue cible absente |
|                 | `422` — échec de l'extraction OCR ou de la traduction |
|                 | `500` — erreur interne                              |

## Fichier OpenAPI

Un fichier de description OpenAPI 3.1 doit être créé à l'emplacement :

```
src/main/resources/static/openapi.yaml
```

Ce fichier décrit les deux endpoints ci-dessus avec :

- Les schemas des requêtes (`multipart/form-data`, paramètres `file` et `targetLanguage`).
- Les schemas des réponses (`application/pdf`, `TranslationTextResponse`).
- Les codes d'erreur documentés (`400`, `422`, `500`).
- Les informations du service (titre, version, description).

## Modèle de données

### DTOs (records Java)

```java
// Réponse du endpoint /api/translation/text
record TranslationTextResponse(
    List<String> pages
) {}
```

```java
// Réponse d'erreur uniforme
record ErrorResponse(
    int status,
    String message,
    Instant timestamp
) {}
```

### Classes de service

| Classe / Interface              | Rôle                                                             |
|---------------------------------|------------------------------------------------------------------|
| `TranslationController`        | Contrôleur REST exposant les deux endpoints                      |
| `TranslationService`           | Orchestration : validation, appel OCR/traduction, génération PDF |
| `OcrExtractionService`         | Extraction du texte par page via un modèle vision (Spring AI)    |
| `TextTranslationService`       | Traduction du texte extrait via un modèle LLM (Spring AI)       |
| `PdfGenerationService`         | Génération du PDF traduit avec Apache PDFBox 3                   |
| `DocumentParser`               | Interface pour le parsing de documents (extensibilité formats)   |
| `PdfDocumentParser`            | Implémentation `DocumentParser` pour les fichiers PDF            |

### Package

Toutes les classes seront dans le package :

```
zas.admin.zia.translation.service
```

Sous-packages possibles si nécessaire :

```
zas.admin.zia.translation.service.controller
zas.admin.zia.translation.service.dto
zas.admin.zia.translation.service.ocr
zas.admin.zia.translation.service.llm
zas.admin.zia.translation.service.pdf
zas.admin.zia.translation.service.parser
```

## Configuration (`application.properties`)

```properties
# --- Stratégie de traduction : single (vision) | dual (vision + llm) ---
zia.translation.strategy=dual

# --- Modèle Vision interne (OCR + traduction si stratégie single) ---
zia.internal.vision.model=${ZIA_VISION_MODEL}
zia.internal.vision.base-url=${ZIA_VISION_BASE_URL}

# --- Modèle LLM textuel interne (traduction si stratégie dual) ---
zia.internal.llm.model=${ZIA_LLM_MODEL}
zia.internal.llm.base-url=${ZIA_LLM_BASE_URL}

# --- PDF ---
zia.translation.pdf.max-file-size=10MB
```

> **Note** : les modèles sont internes (pas de clé d'API). Toutes les valeurs sont injectées via variables d'environnement — aucune valeur par défaut n'est définie dans le fichier properties.

## Dépendances Maven à ajouter

```xml
<!-- Spring AI — starter OpenAI (inclut ChatClient) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>

<!-- Apache PDFBox 3 -->
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
    <version>3.0.4</version>
</dependency>
```

Un BOM Spring AI doit être ajouté dans la section `<dependencyManagement>` du `pom.xml` :

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Avec la propriété :

```xml
<spring-ai.version>1.1.4</spring-ai.version>
```

## Règles métier

1. **Validation du fichier** — le fichier reçu doit être non-null, non-vide, et de type PDF (vérification du content-type et/ou des magic bytes `%PDF`).
2. **Taille maximale** — le fichier ne doit pas dépasser la taille configurée (`zia.translation.pdf.max-file-size`).
3. **Extraction OCR** — chaque page du PDF est envoyée au modèle vision sous forme d'image ; le texte extrait est retourné page par page.
4. **Traduction** — le texte extrait est traduit vers la langue cible fournie dans la requête (`targetLanguage`). En stratégie `single`, l'extraction et la traduction sont faites en un seul appel au modèle vision. En stratégie `dual`, la traduction est effectuée par un second appel à un modèle LLM textuel.
5. **Génération PDF** — le PDF de sortie est généré avec Apache PDFBox 3 en recréant un document avec le texte traduit (mise en page simple, une page par page source).
6. **Extensibilité** — le design via `DocumentParser` permet d'ajouter facilement le support de nouveaux formats de fichier (DOCX, images, etc.) sans modifier le cœur du service.
7. **Gestion d'erreurs** — toutes les erreurs sont retournées sous forme de `ErrorResponse` avec un code HTTP approprié.

## Critères d'acceptation

- [ ] Endpoint `POST /api/translation/pdf` opérationnel — accepte un PDF, retourne un PDF traduit.
- [ ] Endpoint `POST /api/translation/text` opérationnel — accepte un PDF, retourne le texte traduit page par page en JSON.
- [ ] Validation du fichier en entrée (null, vide, format non supporté, taille excessive) avec retour `400`.
- [ ] Gestion des erreurs OCR/traduction avec retour `422`.
- [ ] Configuration des modèles LLM via `application.properties` (stratégie `single` et `dual`).
- [ ] Fichier OpenAPI (`openapi.yaml`) décrivant les deux endpoints.
- [ ] Dépendances Maven ajoutées : Spring AI (starter + BOM) et Apache PDFBox 3.
- [ ] Design extensible via `DocumentParser` pour les futurs formats.
- [ ] Tests unitaires pour chaque service (`OcrExtractionService`, `TextTranslationService`, `PdfGenerationService`, `PdfDocumentParser`).
- [ ] Tests d'intégration pour les deux endpoints du contrôleur (`TranslationController`).
- [ ] `mvn clean verify` passe sans erreur.

