package zas.admin.zia.translation.service.extraction;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import zas.admin.zia.translation.service.InvalidDocumentException;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtractionRequestResolverTest {

    private static ExtractionRequestResolver resolver;

    @BeforeAll
    static void setUp() throws IOException {
        resolver = new ExtractionRequestResolver(new ExtractionProfileRegistry(JsonMapper.builder().build(), ""), 5, 20);
    }

    @Test
    void withProfile_resolvesFieldsAndCollections_andWarnsAboutUnknownKeys() {
        ExtractionRequest request = resolver.resolve(new ExtractionContext("sumex-invoice", "D_1", "de",
                Map.of("patient.ssn", "7568250251199"), List.of("biller.gln", "services", "not.a.key"), "hint"));

        assertThat(request.profile()).isEqualTo("sumex-invoice");
        assertThat(request.profileInstructions()).isNotBlank();
        assertThat(request.fields()).extracting(FieldDefinition::key).containsExactly("biller.gln");
        assertThat(request.fields().getFirst().type()).isEqualTo(FieldType.IDENTIFIER);
        assertThat(request.collections()).extracting(CollectionDefinition::key).containsExactly("services");
        assertThat(request.knownFields()).containsEntry("patient.ssn", "7568250251199");
        assertThat(request.instructions()).isEqualTo("hint");
        assertThat(request.warnings()).containsExactly("Keys unknown to profile 'sumex-invoice' ignored: not.a.key");
    }

    @Test
    void withoutProfile_requestsStringFields() {
        ExtractionRequest request = resolver.resolve(new ExtractionContext(null, null, null, null,
                List.of("contract.number", "customer_name"), null));

        assertThat(request.profile()).isNull();
        assertThat(request.fields()).extracting(FieldDefinition::key).containsExactly("contract.number", "customer_name");
        assertThat(request.fields()).allMatch(field -> field.type() == FieldType.STRING);
        assertThat(request.collections()).isEmpty();
        assertThat(request.warnings()).isEmpty();
    }

    @Test
    void invalidContexts_areRejected() {
        assertInvalid(null, "Part 'context' is missing.");
        assertInvalid(new ExtractionContext(null, null, null, null, null, null), "at least one key");
        assertInvalid(new ExtractionContext(null, null, null, null, List.of("a", "b", "c", "d", "e", "f"), null),
                "exceeds 5 keys");
        assertInvalid(new ExtractionContext(null, null, null, null, List.of("a"), "x".repeat(21)),
                "exceeds 20 characters");
        assertInvalid(new ExtractionContext(null, null, null, null, List.of("bad key"), null), "Invalid key");
        assertInvalid(new ExtractionContext("unknown", null, null, null, List.of("a"), null),
                "Unknown extraction profile");
        assertInvalid(new ExtractionContext("sumex-invoice", null, null, null, List.of("a", "b"), null),
                "No key of 'missingFields'");
    }

    @Test
    void withProfile_classifiesPages_andKeepsPageHints() {
        PageClassification hint = new PageClassification(1, DocumentRole.MAIN, DocumentType.FORM, "300.001", null, null);
        ExtractionRequest request = resolver.resolve(new ExtractionContext("sumex-invoice", "D_1", "de", null,
                List.of("biller.gln"), null, List.of(hint)));

        assertThat(request.pageHints()).containsExactly(hint);
        assertThat(request.pageClassification().mainDocument()).contains("IV office");
        assertThat(request.pageClassification().instructions()).contains("300.001");
        assertThat(request.classifiesPages()).isTrue();
    }

    @Test
    void withoutProfile_classifiesPagesOnlyWithHints() {
        ExtractionRequest withoutHints = resolver.resolve(new ExtractionContext(null, null, null, null,
                List.of("name"), null));
        ExtractionRequest withHints = resolver.resolve(new ExtractionContext(null, null, null, null,
                List.of("name"), null,
                List.of(new PageClassification(2, DocumentRole.SUPPORTING, null, null, null, null))));

        assertThat(withoutHints.classifiesPages()).isFalse();
        assertThat(withHints.classifiesPages()).isTrue();
        assertThat(withHints.pageClassification()).isNull();
        assertThat(withHints.pageClassificationSettings()).isEqualTo(PageClassificationSettings.DEFAULT);
    }

    @Test
    void invalidPageHints_areRejected() {
        assertInvalid(hints(new PageClassification(0, DocumentRole.MAIN, null, null, null, null)), "at least 1");
        assertInvalid(hints(new PageClassification(1, null, DocumentType.FORM, null, null, null)),
                "'documentRole' is missing on page 1");
        assertInvalid(hints(new PageClassification(1, DocumentRole.MAIN, null, "x".repeat(36), null, null)),
                "'formId' exceeds 35 characters");
        assertInvalid(hints(new PageClassification(2, DocumentRole.MAIN, null, null, null, null),
                new PageClassification(2, DocumentRole.OTHER, null, null, null, null)), "page 2 is given twice");
    }

    private static ExtractionContext hints(PageClassification... hints) {
        return new ExtractionContext(null, null, null, null, List.of("a"), null, java.util.Arrays.asList(hints));
    }

    @Test
    void context_dropsNullsAndDuplicates() {
        ExtractionContext context = new ExtractionContext(" ", null, null, null,
                java.util.Arrays.asList(" a ", null, "a"), null);

        assertThat(context.profile()).isNull();
        assertThat(context.missingFields()).containsExactly("a");
        assertThat(context.knownFields()).isEqualTo(Collections.emptyMap());
    }

    private static void assertInvalid(ExtractionContext context, String message) {
        assertThatThrownBy(() -> resolver.resolve(context))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining(message);
    }
}
