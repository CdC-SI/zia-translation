# Image Preprocessing

## Contexte

Avant l'OCR ou la traduction, toutes les pages sont envoyées au modèle vision sous forme de PNG pleine résolution :

- les PDF sont rendus à 150 DPI par `PdfDocumentParser` ;
- les images sont converties en PNG par `ImageDocumentParser`, sans redimensionnement.

Spring AI (`OpenAiChatModel`) convertit ensuite chaque `Media` en data URL `data:image/png;base64,...`. Comme le base64 ajoute environ 33 % de volume, les requêtes deviennent très lourdes. Une page A4 à 150 DPI fait environ 1240×1754 px, et une photo de smartphone peut dépasser 4000 px.

Cela entraîne :

- une latence réseau et un temps d'encodage et de décodage élevés ;
- un nombre élevé de tokens image côté modèle vision, donc un temps d'inférence plus long ;
- un risque de dépasser les limites de taille de requête du modèle.

La plupart des modèles vision découpent l'image en patches de 14, 16 ou 28 px, regroupés en blocs de 32 px. Une image dont les dimensions sont multiples de 32 évite le padding et le redimensionnement interne côté modèle.

## Description

Ajouter une étape de **preprocessing** appliquée à chaque image juste avant son envoi au modèle vision. Elle s'applique à tous les endpoints et toutes les stratégies qui sollicitent le modèle vision :

| Endpoint | Stratégie | Appel vision concerné |
|---|---|---|
| `POST /api/translation/pdf` | `dual` | `OcrExtractionService.extractText` |
| `POST /api/translation/pdf` | `single` | `TextTranslationService.translatePagesSingleStrategy` |
| `POST /api/translation/md` | `dual` | `OcrExtractionService.extractText` |
| `POST /api/translation/md` | `single` | `TextTranslationService.translatePagesSingleStrategy` |
| `POST /api/translation/text` (SSE) | `dual` | `OcrExtractionService.extractText` |
| `POST /api/translation/text` (SSE) | `single` | `TextTranslationService.translatePageSingleStrategyStream` |

Le preprocessing applique, dans l'ordre :

1. **Calcul du facteur d'échelle** en conservant le ratio d'aspect :
   `scale = Math.min(1.0, Math.min(MAX_EDGE / width, MAX_EDGE / height))`.
   Le facteur est plafonné à `1.0` : on réduit les grandes images, on n'agrandit jamais les petites.
2. **Arrondi des dimensions cibles au multiple de 32 inférieur** :
   `targetWidth = max(32, floor(width * scale / 32) * 32)`, et de même pour la hauteur.
3. **Redimensionnement** de l'image aux dimensions cibles, avec une interpolation de qualité (bicubique).
4. **Compression PNG → JPEG** avec une qualité de `0.75f` (75 %).

Les bytes JPEG obtenus sont passés à Spring AI dans un `Media` de type `image/jpeg`. **L'encodage base64 reste à la charge de Spring AI**, aucun encodage manuel n'est ajouté.

## Changements requis

### 1. Création de `VisionImagePreprocessor`

**Package** : `zas.admin.zia.translation.service.image`
**Visibilité** : `public`, car le composant est injecté dans les packages `ocr` et `llm`
**Annotation** : `@Component`

#### Configuration

Injectée par `@Value` dans le constructeur :

| Propriété | Défaut | Description |
|---|---|---|
| `zia.translation.vision.preprocessing.max-edge` | `1024` | Taille max (px) du plus grand côté (`MAX_EDGE`) |
| `zia.translation.vision.preprocessing.jpeg-quality` | `0.75` | Qualité de compression JPEG (`0.0`–`1.0`) |

Ajouter dans `application.properties` :

```properties
# --- Preprocessing des images avant envoi au modèle vision ---
zia.translation.vision.preprocessing.max-edge=${ZIA_VISION_PREPROCESSING_MAX_EDGE:1024}
zia.translation.vision.preprocessing.jpeg-quality=${ZIA_VISION_PREPROCESSING_JPEG_QUALITY:0.75}
```

Validation au démarrage, avec une `IllegalArgumentException` en cas d'échec :

- `max-edge` doit être `>= 32` ;
- `jpeg-quality` doit être compris dans `]0.0, 1.0]`.

#### API

```java
public record PreprocessedImage(byte[] bytes, MimeType mimeType) {}

public PreprocessedImage preprocess(byte[] imageBytes);
public Media toMedia(byte[] imageBytes); // preprocess + new Media(mimeType, new ByteArrayResource(bytes))
```

`toMedia` est le point d'entrée utilisé par les services appelant le modèle vision.

#### Algorithme de référence

```java
static final int DIMENSION_MULTIPLE = 32;

PreprocessedImage preprocess(byte[] imageBytes) {
    BufferedImage source = readImage(imageBytes);            // ImageIO.read, null -> exception
    int width = source.getWidth();
    int height = source.getHeight();

    double scale = Math.min(1.0, Math.min((double) maxEdge / width, (double) maxEdge / height));
    int targetWidth  = roundDownToMultiple(width * scale);
    int targetHeight = roundDownToMultiple(height * scale);

    BufferedImage resized = resizeToRgb(source, targetWidth, targetHeight);
    return new PreprocessedImage(encodeJpeg(resized, jpegQuality), MimeTypeUtils.IMAGE_JPEG);
}

static final double FLOATING_POINT_TOLERANCE = 1e-9;

static int roundDownToMultiple(double value) {
    int floored = (int) Math.floor(value + FLOATING_POINT_TOLERANCE);
    int rounded = (floored / DIMENSION_MULTIPLE) * DIMENSION_MULTIPLE;
    return Math.max(DIMENSION_MULTIPLE, rounded);
}
```

> La tolérance `FLOATING_POINT_TOLERANCE` évite qu'une erreur d'arrondi flottant (ex. `1023.9999999` au lieu de `1024`) ne fasse chuter la dimension au multiple de 32 inférieur (`992`).

- `resizeToRgb` crée une `BufferedImage` de type `TYPE_INT_RGB`, remplit le fond en **blanc**, puis dessine la source avec `Graphics2D.drawImage(source, 0, 0, targetWidth, targetHeight, null)` et les hints suivants :
  - `KEY_INTERPOLATION = VALUE_INTERPOLATION_BICUBIC`
  - `KEY_RENDERING = VALUE_RENDER_QUALITY`
  - `KEY_ANTIALIASING = VALUE_ANTIALIAS_ON`

  Le `Graphics2D` doit être libéré (`dispose()`) dans un `finally`. Le fond blanc est nécessaire car le JPEG ne supporte pas l'alpha et qu'une zone transparente deviendrait noire.
- `encodeJpeg` utilise `ImageIO.getImageWritersByFormatName("jpeg")` et un `ImageWriteParam` avec :
  - `setCompressionMode(ImageWriteParam.MODE_EXPLICIT)`
  - `setCompressionQuality(jpegQuality)`

  Le writer doit être libéré (`dispose()`) dans un `finally`. Si aucun writer JPEG n'est disponible, lever une exception explicite.
- Les dimensions étant arrondies indépendamment, le ratio d'aspect peut varier légèrement (au plus 31 px par côté). C'est accepté.

#### Gestion des erreurs

- Si les bytes ne sont pas lisibles (`ImageIO.read` retourne `null`) ou en cas d'`IOException` à l'encodage, lever une `ImagePreprocessingException` (nouvelle `RuntimeException` dans le même package) avec un message explicite et la cause.
- Comme c'est une `RuntimeException`, elle est déjà convertie en `TranslationProcessingException` par `TranslationService.translatePages`, et le job passe en `FAILED` pour les traitements asynchrones. Aucun changement n'est requis dans `TranslationService`.

### 2. Adaptation de `OcrExtractionService`

- Injecter `VisionImagePreprocessor` dans le constructeur.
- Remplacer :

  ```java
  Media media = new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(imageBytes));
  ```

  par :

  ```java
  Media media = imagePreprocessor.toMedia(imageBytes);
  ```

### 3. Adaptation de `TextTranslationService`

- Injecter `VisionImagePreprocessor` dans le constructeur.
- Faire le même remplacement dans `translatePagesSingleStrategy` et `translatePageSingleStrategyStream`.
- Les méthodes purement textuelles (`translatePages`, `translatePageStream`) ne changent pas.

### 4. Hors périmètre

- `PdfDocumentParser` et `ImageDocumentParser` ne changent pas : ils continuent à produire du PNG.
- `extractPageLayouts` et la génération PDF (`PdfGenerationService`) continuent d'utiliser les dimensions **originales** du document. Le preprocessing ne concerne que la charge utile envoyée au modèle vision.
- Aucun changement d'API REST, de DTO ni de contrat OpenAPI.

## API

Aucun changement d'API. Le comportement des endpoints `POST /api/translation/pdf`, `POST /api/translation/md` et `POST /api/translation/text` est inchangé du point de vue client.

## Modèle de données

- `PreprocessedImage(byte[] bytes, MimeType mimeType)` : record représentant l'image prête pour le modèle vision.
- `ImagePreprocessingException extends RuntimeException`.

## Règles métier

- Toute image envoyée au modèle vision passe **obligatoirement** par `VisionImagePreprocessor`. Aucun `new Media(MimeTypeUtils.IMAGE_PNG, ...)` ne doit subsister pour les appels vision.
- Le facteur d'échelle est `min(1.0, MAX_EDGE / width, MAX_EDGE / height)`. Le ratio d'aspect est conservé et il n'y a jamais d'agrandissement, sauf le plancher de 32 px décrit plus bas.
- Les dimensions cibles sont arrondies **vers le bas** au multiple de 32, avec un minimum de 32 px par côté.
- Après preprocessing, aucun côté ne dépasse `MAX_EDGE`.
- Une image déjà dans les limites (côtés ≤ `MAX_EDGE`) est quand même ramenée aux multiples de 32 et recompressée en JPEG.
- Le format de sortie est toujours JPEG (`image/jpeg`), avec la qualité configurée (défaut `0.75f`).
- Les zones transparentes sont aplaties sur fond blanc.
- L'encodage base64 est délégué à Spring AI.

### Exemples (MAX_EDGE = 1024)

| Entrée (L×H) | scale | L×H mis à l'échelle | Sortie (L×H) |
|---|---|---|---|
| 1240×1754 (A4 @150 DPI) | 0.5838 | 723.9×1024 | **704×1024** |
| 4032×3024 (photo) | 0.2540 | 1024×768 | **1024×768** |
| 2000×500 | 0.512 | 1024×256 | **1024×256** |
| 800×600 | 1.0 | 800×600 | **800×576** |
| 1024×1024 | 1.0 | 1024×1024 | **1024×1024** |
| 20×10 | 1.0 | 20×10 | **32×32** (plancher) |

## Critères d'acceptation

- [ ] `VisionImagePreprocessor` existe dans `zas.admin.zia.translation.service.image` et est un `@Component`
- [ ] Les propriétés `max-edge` (défaut `1024`) et `jpeg-quality` (défaut `0.75`) sont configurables et validées au démarrage
- [ ] Le facteur d'échelle conserve le ratio d'aspect et n'agrandit jamais l'image (plafonné à `1.0`)
- [ ] Les dimensions de sortie sont des multiples de 32, au minimum 32, et ne dépassent pas `MAX_EDGE`
- [ ] La sortie est un JPEG valide (magic bytes `FF D8 FF`) avec le MIME type `image/jpeg`
- [ ] Les images avec canal alpha sont aplaties sur fond blanc, sans zone noire
- [ ] Des bytes invalides lèvent une `ImagePreprocessingException`
- [ ] `OcrExtractionService` envoie au modèle vision un `Media` prétraité de type `image/jpeg`
- [ ] `TextTranslationService.translatePagesSingleStrategy` et `translatePageSingleStrategyStream` envoient un `Media` prétraité de type `image/jpeg`
- [ ] Aucun changement d'API REST ; `PageLayout` et la génération PDF utilisent toujours les dimensions originales
- [ ] Tests unitaires `VisionImagePreprocessorTest` :
  - [ ] `roundDownToMultiple` : 723.9 → 704, 1024 → 1024, 1023.9999999999 → 1024, 600 → 576, 20 → 32
  - [ ] chaque ligne du tableau d'exemples est vérifiée en relisant le JPEG produit (`ImageIO.read`)
  - [ ] image plus petite que `MAX_EDGE` : pas d'agrandissement
  - [ ] image avec alpha (`TYPE_INT_ARGB` entièrement transparente) : pixel de sortie blanc (à une tolérance JPEG près)
  - [ ] sortie JPEG : magic bytes `FF D8 FF` et `mimeType() == IMAGE_JPEG`
  - [ ] la qualité influe sur la taille : un JPEG à `0.75` est plus petit qu'à `1.0` pour la même image
  - [ ] bytes invalides : `ImagePreprocessingException`
  - [ ] configuration invalide (`max-edge < 32`, `jpeg-quality` hors `]0, 1]`) : `IllegalArgumentException`
- [ ] Tests unitaires adaptés :
  - [ ] `OcrExtractionServiceTest` : le preprocessor est appelé pour chaque page et le `Media` envoyé est de type `image/jpeg`
  - [ ] tests de `TextTranslationService` (stratégie single, sync et stream) : même vérification
  - [ ] `TranslationServiceTest` reste vert, et une `ImagePreprocessingException` est convertie en `TranslationProcessingException`
- [ ] Tests d'intégration : le contexte Spring démarre avec les valeurs par défaut des nouvelles propriétés
- [ ] `mvn clean verify` passe sans erreur
