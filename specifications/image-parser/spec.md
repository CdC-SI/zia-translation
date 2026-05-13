# Image Document Parser

## Contexte

Le service de traduction ne supporte actuellement que les documents PDF en entrée. L'interface `DocumentParser` a été conçue pour être extensible, mais seule l'implémentation `PdfDocumentParser` existe. Il est nécessaire d'ajouter le support des fichiers image (PNG, JPEG, GIF, BMP, WebP, TIFF) afin de permettre la traduction de documents scannés ou photographiés transmis directement sous forme d'image.

## Description

Ajouter un `ImageDocumentParser` implémentant l'interface `DocumentParser`, capable de :

1. Accepter un fichier image dans l'un des formats supportés.
2. Convertir l'image en PNG (format attendu par le pipeline OCR/vision).
3. Retourner une unique « page » contenant les bytes PNG.
4. Extraire les dimensions de l'image pour produire un `PageLayout`.

Cette feature implique également de faire évoluer l'interface `DocumentParser` pour supporter **plusieurs MIME types** par parser, et d'adapter `TranslationService` en conséquence.

## Changements requis

### 1. Modification de l'interface `DocumentParser`

Remplacer la méthode :

```java
String supportedMimeType();
```

par :

```java
List<String> supportedMimeTypes();
```

Cela permet à un même parser de gérer plusieurs MIME types (ex. toutes les variantes d'images).

### 2. Adaptation de `PdfDocumentParser`

Remplacer le champ et la méthode existants :

```java
// Avant
private static final String SUPPORTED_MIME_TYPE = "application/pdf";

@Override
public String supportedMimeType() {
    return SUPPORTED_MIME_TYPE;
}

// Après
private static final List<String> SUPPORTED_MIME_TYPES = List.of("application/pdf");

@Override
public List<String> supportedMimeTypes() {
    return SUPPORTED_MIME_TYPES;
}
```

Le reste de la classe (`renderPages`, `extractPageLayouts`, `RENDER_DPI`) ne change pas.

### 3. Création de `ImageDocumentParser`

**Package** : `zas.admin.zia.translation.service.parser`
**Visibilité** : package-private (pas de `public`)
**Annotation** : `@Component`

#### MIME types supportés

```java
private static final List<String> SUPPORTED_MIME_TYPES = List.of(
    "image/png",
    "image/jpeg",
    "image/jpg",
    "image/gif",
    "image/bmp",
    "image/webp",
    "image/tiff"
);
```

> Note : `image/jpg` n'est pas un MIME type officiel IANA (le standard est `image/jpeg`), mais il est conservé comme alias car certains clients l'envoient.

#### Méthode `renderPages`

- Lire les bytes d'entrée via `ImageIO.read()`.
- Si l'image ne peut pas être lue (`null`), lever une `IOException`.
- Convertir en PNG via `ImageIO.write(image, "png", outputStream)`.
- Retourner une liste contenant un unique élément : les bytes PNG.

```java
@Override
public List<byte[]> renderPages(byte[] documentBytes) throws IOException {
    return List.of(convertToPng(documentBytes));
}

private byte[] convertToPng(byte[] imageBytes) throws IOException {
    BufferedImage image = readImage(imageBytes);
    try (var baos = new ByteArrayOutputStream()) {
        boolean written = ImageIO.write(image, "png", baos);
        if (!written) {
            throw new IOException("No suitable ImageIO writer found for PNG format.");
        }
        return baos.toByteArray();
    }
}

private BufferedImage readImage(byte[] imageBytes) throws IOException {
    try (var inputStream = new ByteArrayInputStream(imageBytes)) {
        BufferedImage image = ImageIO.read(inputStream);
        if (image == null) {
            throw new IOException("Unable to read image: format not recognized or data is corrupted.");
        }
        return image;
    }
}
```

#### Méthode `extractPageLayouts`

- Lire l'image via `ImageIO.read()` pour obtenir largeur et hauteur en pixels.
- Convertir les pixels en points PDF en utilisant **72 DPI** comme référence (1 pixel = 1 point).
- Retourner une liste contenant un unique `PageLayout(width, height)`.

```java
@Override
public List<PageLayout> extractPageLayouts(byte[] documentBytes) throws IOException {
    BufferedImage image = readImage(documentBytes);
    return List.of(new PageLayout(image.getWidth(), image.getHeight()));
}
```

#### Méthode `supportedMimeTypes`

```java
@Override
public List<String> supportedMimeTypes() {
    return SUPPORTED_MIME_TYPES;
}
```

### 4. Adaptation de `TranslationService`

#### Construction de `parsersByMimeType`

Remplacer :

```java
this.parsersByMimeType = parsers.stream()
    .collect(Collectors.toMap(DocumentParser::supportedMimeType, Function.identity()));
```

Par :

```java
this.parsersByMimeType = parsers.stream()
    .flatMap(parser -> parser.supportedMimeTypes().stream()
        .map(mimeType -> Map.entry(mimeType, parser)))
    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
```

#### Méthode `resolveParser`

Mettre à jour le message d'erreur pour ne plus mentionner « Only PDF » mais lister dynamiquement les formats supportés :

```java
throw new InvalidDocumentException(
    "Unsupported file format: '%s'. Supported formats: %s."
        .formatted(contentType, parsersByMimeType.keySet()));
```

Le fallback par magic bytes PDF (`isPdf(bytes)`) reste en place. Pas de fallback magic bytes pour les images ; le `contentType` du `MultipartFile` suffit.

### 5. Tests

#### `ImageDocumentParserTest`

- `supportedMimeTypes_returnsAllImageTypes` — vérifie la liste complète des 7 MIME types.
- `renderPages_validPng_returnsOnePngPage` — fournir un PNG valide, vérifier qu'on reçoit une liste de taille 1 avec des magic bytes PNG (`89 50 4E 47`).
- `renderPages_validJpeg_convertsToPng` — fournir un JPEG valide, vérifier la conversion en PNG.
- `renderPages_invalidBytes_throwsIOException` — fournir des bytes aléatoires, vérifier l'`IOException`.
- `extractPageLayouts_returnsCorrectDimensions` — créer une image de dimensions connues (ex. 200×100), vérifier `PageLayout(200f, 100f)`.
- `extractPageLayouts_invalidBytes_throwsIOException`.

#### Adaptation de `TranslationServiceTest`

- Mettre à jour le `setUp()` : remplacer `when(pdfParser.supportedMimeType())` par `when(pdfParser.supportedMimeTypes()).thenReturn(List.of("application/pdf"))`.
- Ajouter un test `resolveParser_imageContentType_resolvesImageParser` : injecter un second mock `imageParser` avec `supportedMimeTypes()` retournant les MIME types image, vérifier que le service résout le bon parser pour `image/png`.
- Vérifier que le message d'erreur pour un format non supporté ne mentionne plus « Only PDF ».

## Règles métier

- Une image produit toujours **une seule page** en sortie (un seul élément dans la liste de `renderPages` et `extractPageLayouts`).
- La conversion en PNG est obligatoire quel que soit le format d'entrée, car le pipeline en aval attend du PNG.
- Si `ImageIO.read()` retourne `null` (format non reconnu ou bytes corrompus), une `IOException` doit être levée avec un message explicite.
- Les dimensions en points utilisent un ratio de **72 DPI** (1 pixel = 1 point PDF).

## Critères d'acceptation

- [ ] L'interface `DocumentParser` expose `List<String> supportedMimeTypes()` au lieu de `String supportedMimeType()`
- [ ] `PdfDocumentParser` compile et fonctionne avec la nouvelle signature
- [ ] `ImageDocumentParser` est créé et supporte les 7 MIME types image listés
- [ ] `ImageDocumentParser.renderPages()` convertit correctement une image en PNG
- [ ] `ImageDocumentParser.extractPageLayouts()` retourne les bonnes dimensions
- [ ] `ImageDocumentParser` lève une `IOException` sur des bytes invalides
- [ ] `TranslationService` résout correctement le parser pour un MIME type image
- [ ] `TranslationService` résout toujours correctement le parser PDF
- [ ] Le message d'erreur de `resolveParser` est mis à jour
- [ ] Tests unitaires pour `ImageDocumentParser` (conversion PNG, MIME types, layout, erreur)
- [ ] Tests unitaires adaptés pour `TranslationService` (injection des deux parsers, résolution par MIME type)
- [ ] `mvn clean verify` passe sans erreur

