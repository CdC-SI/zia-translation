package zas.admin.zia.translation.service.extraction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import zas.admin.zia.translation.service.InvalidDocumentException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates an {@link ExtractionContext} and resolves the definitions of the requested values.
 * <ul>
 *     <li>with a profile, the {@code missingFields} are looked up in the profile; unknown keys are ignored with a
 *     warning; when {@code missingFields} is empty, every field and collection of the profile is requested;</li>
 *     <li>without a profile, every key is extracted as a {@link FieldType#STRING} value; {@code missingFields}
 *     must then contain at least one key, since there is no profile to default to.</li>
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
        if (keys.size() > maxFields) {
            throw new InvalidDocumentException("'missingFields' exceeds %d keys.".formatted(maxFields));
        }
        if (context.instructions() != null && context.instructions().length() > maxInstructionsLength) {
            throw new InvalidDocumentException("'instructions' exceeds %d characters.".formatted(maxInstructionsLength));
        }
        validatePageHints(context.pageHints());
        if (context.profile() == null) {
            if (keys.isEmpty()) {
                throw new InvalidDocumentException("'missingFields' must contain at least one key.");
            }
            return resolveAdHoc(context);
        }
        return resolveWithProfile(context);
    }

    private static void validatePageHints(List<PageClassification> hints) {
        Set<Integer> pages = new HashSet<>();
        for (PageClassification hint : hints) {
            if (hint.page() < 1) {
                throw new InvalidDocumentException("'pageHints': 'page' must be at least 1.");
            }
            if (hint.documentRole() == null) {
                throw new InvalidDocumentException("'pageHints': 'documentRole' is missing on page %d."
                        .formatted(hint.page()));
            }
            if (hint.formId() != null && hint.formId().length() > PageClassification.MAX_FORM_ID_LENGTH) {
                throw new InvalidDocumentException("'pageHints': 'formId' exceeds %d characters on page %d."
                        .formatted(PageClassification.MAX_FORM_ID_LENGTH, hint.page()));
            }
            if (!pages.add(hint.page())) {
                throw new InvalidDocumentException("'pageHints': page %d is given twice.".formatted(hint.page()));
            }
        }
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
                context.instructions(), fields, List.of(), List.of(), context.pageHints(), null);
    }

    private ExtractionRequest resolveWithProfile(ExtractionContext context) {
        ExtractionProfile profile = registry.find(context.profile())
                .orElseThrow(() -> new InvalidDocumentException("Unknown extraction profile."));
        List<String> requestedKeys = context.missingFields();
        if (requestedKeys.isEmpty()) {
            // default to every field/collection of the profile rather than forcing the client to repeat them all.
            requestedKeys = new ArrayList<>(profile.fields().keySet());
            requestedKeys.addAll(profile.collections().keySet());
        }
        List<FieldDefinition> fields = new ArrayList<>();
        List<CollectionDefinition> collections = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        for (String key : requestedKeys) {
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
        if (profile.perPage() && !context.pageHints().isEmpty()) {
            warnings = new ArrayList<>(warnings);
            warnings.add("'pageHints' ignored: profile '%s' extracts each page on its own".formatted(profile.id()));
        }
        return new ExtractionRequest(profile.id(), profile.instructions(), context.documentId(), context.language(),
                context.knownFields(), context.instructions(), fields, collections, warnings, context.pageHints(),
                profile.pageClassification(), profile.perPage(), profile.simpleOutput());
    }

    private static String abbreviate(List<String> keys) {
        String joined = String.join(", ", keys);
        return joined.length() <= 500 ? joined : joined.substring(0, 500) + "...";
    }
}
