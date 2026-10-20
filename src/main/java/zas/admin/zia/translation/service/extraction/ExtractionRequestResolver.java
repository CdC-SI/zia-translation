package zas.admin.zia.translation.service.extraction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import zas.admin.zia.translation.service.InvalidDocumentException;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates an {@link ExtractionContext} and resolves the definitions of the requested values.
 * <ul>
 *     <li>with a profile, the {@code missingFields} are looked up in the profile; unknown keys are ignored with a
 *     warning;</li>
 *     <li>without a profile, every key is extracted as a {@link FieldType#STRING} value.</li>
 * </ul>
 */
@Component
class ExtractionRequestResolver {

    private final ExtractionProfileRegistry registry;
    private final int maxFields;
    private final int maxInstructionsLength;

    ExtractionRequestResolver(ExtractionProfileRegistry registry,
                              @Value("${zia.extraction.max-fields:80}") int maxFields,
                              @Value("${zia.extraction.max-instructions-length:2000}") int maxInstructionsLength) {
        this.registry = registry;
        this.maxFields = maxFields;
        this.maxInstructionsLength = maxInstructionsLength;
    }

    /**
     * @throws InvalidDocumentException invalid context, unknown profile or no extractable key
     */
    ExtractionRequest resolve(ExtractionContext context) {
        if (context == null) {
            throw new InvalidDocumentException("Part 'context' is missing.");
        }
        List<String> keys = context.missingFields();
        if (keys.isEmpty()) {
            throw new InvalidDocumentException("'missingFields' must contain at least one key.");
        }
        if (keys.size() > maxFields) {
            throw new InvalidDocumentException("'missingFields' exceeds %d keys.".formatted(maxFields));
        }
        if (context.instructions() != null && context.instructions().length() > maxInstructionsLength) {
            throw new InvalidDocumentException("'instructions' exceeds %d characters.".formatted(maxInstructionsLength));
        }
        return context.profile() == null ? resolveAdHoc(context) : resolveWithProfile(context);
    }

    private static ExtractionRequest resolveAdHoc(ExtractionContext context) {
        List<FieldDefinition> fields = new ArrayList<>();
        for (String key : context.missingFields()) {
            if (!ExtractionProfileRegistry.isValidKey(key)) {
                throw new InvalidDocumentException(
                        "Invalid key in 'missingFields' (expected %s).".formatted(ExtractionProfileRegistry.KEY_PATTERN));
            }
            fields.add(FieldDefinition.adHoc(key));
        }
        return new ExtractionRequest(null, null, context.documentId(), context.language(), context.knownFields(),
                context.instructions(), fields, List.of(), List.of());
    }

    private ExtractionRequest resolveWithProfile(ExtractionContext context) {
        ExtractionProfile profile = registry.find(context.profile())
                .orElseThrow(() -> new InvalidDocumentException("Unknown extraction profile."));
        List<FieldDefinition> fields = new ArrayList<>();
        List<CollectionDefinition> collections = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        for (String key : context.missingFields()) {
            FieldDefinition field = profile.fields().get(key);
            CollectionDefinition collection = profile.collections().get(key);
            if (field != null) {
                fields.add(field);
            } else if (collection != null) {
                collections.add(collection);
            } else {
                ignored.add(key);
            }
        }
        if (fields.isEmpty() && collections.isEmpty()) {
            throw new InvalidDocumentException("No key of 'missingFields' is defined in the extraction profile.");
        }
        List<String> warnings = ignored.isEmpty()
                ? List.of()
                : List.of("Keys unknown to profile '%s' ignored: %s".formatted(profile.id(), abbreviate(ignored)));
        return new ExtractionRequest(profile.id(), profile.instructions(), context.documentId(), context.language(),
                context.knownFields(), context.instructions(), fields, collections, warnings);
    }

    private static String abbreviate(List<String> keys) {
        String joined = String.join(", ", keys);
        return joined.length() <= 500 ? joined : joined.substring(0, 500) + "...";
    }
}
