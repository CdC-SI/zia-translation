package zas.admin.zia.translation.service.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Extraction profiles loaded at startup from {@code classpath*:extraction-profiles/*.yaml} and from the optional
 * directory {@code zia.extraction.profiles-location}. An invalid profile prevents the application from starting.
 */
@Component
public class ExtractionProfileRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExtractionProfileRegistry.class);

    static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.\\-]{0,99}");
    private static final String CLASSPATH_LOCATION = "classpath*:extraction-profiles/";
    private static final List<String> EXTENSIONS = List.of("*.yaml", "*.yml");

    private final Map<String, ExtractionProfile> profiles;

    ExtractionProfileRegistry(ObjectMapper objectMapper,
                              @Value("${zia.extraction.profiles-location:}") String externalLocation) throws IOException {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Resource> resources = new ArrayList<>(findResources(resolver, CLASSPATH_LOCATION));
        if (externalLocation != null && !externalLocation.isBlank()) {
            String location = externalLocation.endsWith("/") ? externalLocation : externalLocation + "/";
            resources.addAll(findResources(resolver, location));
        }
        ObjectReader reader = objectMapper.readerFor(ProfileDocument.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        Map<String, ExtractionProfile> loaded = new LinkedHashMap<>();
        for (Resource resource : resources) {
            ExtractionProfile profile;
            try (InputStream input = resource.getInputStream()) {
                profile = parse(objectMapper, reader, input, resource.getDescription());
            }
            if (loaded.putIfAbsent(profile.id(), profile) != null) {
                throw new IllegalStateException("Duplicate extraction profile '%s' (%s)"
                        .formatted(profile.id(), resource.getDescription()));
            }
        }
        this.profiles = Map.copyOf(loaded);
        log.info("Extraction profiles loaded: {}", loaded.keySet());
    }

    public Optional<ExtractionProfile> find(String id) {
        return Optional.ofNullable(profiles.get(id));
    }

    public List<ExtractionProfile> all() {
        return profiles.values().stream().sorted(Comparator.comparing(ExtractionProfile::id)).toList();
    }

    private static List<Resource> findResources(ResourcePatternResolver resolver, String location) throws IOException {
        List<Resource> found = new ArrayList<>();
        for (String extension : EXTENSIONS) {
            for (Resource resource : resolver.getResources(location + extension)) {
                if (resource.exists() && resource.isReadable()) {
                    found.add(resource);
                }
            }
        }
        return found;
    }

    static ExtractionProfile parse(ObjectMapper objectMapper, ObjectReader reader, InputStream input, String source) {
        ProfileDocument document;
        try {
            Object yaml = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            document = reader.readValue(objectMapper.writeValueAsString(yaml));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Invalid extraction profile " + source + ": " + ex.getMessage(), ex);
        }
        try {
            return toProfile(document);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid extraction profile " + source + ": " + ex.getMessage(), ex);
        }
    }

    private static ExtractionProfile toProfile(ProfileDocument document) {
        if (document == null || !isValidKey(document.id())) {
            throw new IllegalArgumentException("missing or invalid 'id'");
        }
        Set<String> keys = new HashSet<>();
        Map<String, FieldDefinition> fields = new LinkedHashMap<>();
        for (FieldGroupDocument group : nullToEmpty(document.fieldGroups())) {
            if (group.prefixes() == null || group.prefixes().isEmpty()) {
                throw new IllegalArgumentException("a field group has no 'prefixes'");
            }
            for (String prefix : group.prefixes()) {
                for (FieldDocument field : nullToEmpty(group.fields())) {
                    addField(fields, keys, toField(prefix + "." + field.name(), field));
                }
            }
        }
        for (FieldDocument field : nullToEmpty(document.fields())) {
            addField(fields, keys, toField(field.key(), field));
        }
        Map<String, CollectionDefinition> collections = new LinkedHashMap<>();
        for (CollectionDocument collection : nullToEmpty(document.collections())) {
            if (!isValidKey(collection.key()) || !keys.add(collection.key())) {
                throw new IllegalArgumentException("invalid or duplicate collection key '%s'".formatted(collection.key()));
            }
            if (collection.fields() == null || collection.fields().isEmpty()) {
                throw new IllegalArgumentException("collection '%s' has no field".formatted(collection.key()));
            }
            Set<String> names = new HashSet<>();
            List<FieldDefinition> lineFields = new ArrayList<>();
            for (FieldDocument field : collection.fields()) {
                FieldDefinition definition = toField(field.name(), field);
                if (!names.add(definition.key())) {
                    throw new IllegalArgumentException("duplicate field '%s' in collection '%s'"
                            .formatted(definition.key(), collection.key()));
                }
                lineFields.add(definition);
            }
            collections.put(collection.key(), new CollectionDefinition(collection.key(), collection.description(), lineFields));
        }
        if (fields.isEmpty() && collections.isEmpty()) {
            throw new IllegalArgumentException("profile '%s' defines no field".formatted(document.id()));
        }
        PageClassificationSettings pageClassification = null;
        if (document.pageClassification() != null) {
            pageClassification = new PageClassificationSettings(document.pageClassification().mainDocument(),
                    document.pageClassification().instructions());
        }
        return new ExtractionProfile(document.id(), document.description(), document.instructions(), fields,
                collections, pageClassification);
    }

    private static void addField(Map<String, FieldDefinition> fields, Set<String> keys, FieldDefinition field) {
        if (!keys.add(field.key())) {
            throw new IllegalArgumentException("duplicate key '%s'".formatted(field.key()));
        }
        fields.put(field.key(), field);
    }

    private static FieldDefinition toField(String key, FieldDocument field) {
        if (!isValidKey(key)) {
            throw new IllegalArgumentException("invalid key '%s'".formatted(key));
        }
        FieldType type = field.type() == null ? FieldType.STRING : field.type();
        List<String> values = nullToEmpty(field.values());
        if (type == FieldType.ENUM && values.isEmpty()) {
            throw new IllegalArgumentException("ENUM field '%s' has no 'values'".formatted(key));
        }
        return new FieldDefinition(key, type, field.description(), values);
    }

    static boolean isValidKey(String key) {
        return key != null && KEY_PATTERN.matcher(key).matches();
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    record ProfileDocument(String id, String description, String instructions, List<FieldGroupDocument> fieldGroups,
                           List<FieldDocument> fields, List<CollectionDocument> collections,
                           PageClassificationDocument pageClassification) {
    }

    record PageClassificationDocument(String mainDocument, String instructions) {
    }

    record FieldGroupDocument(List<String> prefixes, List<FieldDocument> fields) {
    }

    record FieldDocument(String key, String name, FieldType type, String description, List<String> values) {
    }

    record CollectionDocument(String key, String description, List<FieldDocument> fields) {
    }
}
