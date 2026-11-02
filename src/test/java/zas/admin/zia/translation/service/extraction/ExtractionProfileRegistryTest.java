package zas.admin.zia.translation.service.extraction;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtractionProfileRegistryTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final ObjectReader READER = MAPPER.readerFor(ExtractionProfileRegistry.ProfileDocument.class)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void loadsSumexInvoiceProfileFromClasspath() throws IOException {
        ExtractionProfileRegistry registry = new ExtractionProfileRegistry(MAPPER, "");

        ExtractionProfile profile = registry.find("sumex-invoice").orElseThrow();

        assertThat(profile.fields()).hasSize(7 * 21 + 33);
        assertThat(profile.fields().get("biller.gln").type()).isEqualTo(FieldType.IDENTIFIER);
        assertThat(profile.fields().get("patient.birthdate").type()).isEqualTo(FieldType.DATE);
        assertThat(profile.fields().get("patient.sex").values()).containsExactly("male", "female");
        assertThat(profile.fields().get("balance.amount").type()).isEqualTo(FieldType.AMOUNT);
        assertThat(profile.fields()).containsKeys("request.language", "esr.iban", "treatment.diagnosis.text");
        assertThat(profile.collections()).containsOnlyKeys("services");
        assertThat(profile.collections().get("services").fields())
                .extracting(FieldDefinition::key)
                .containsExactly("date", "tariffType", "code", "name", "quantity", "unitPrice", "amount", "vatRate",
                        "unitMt", "scaleFactorMt", "unitFactorMt", "unitTt", "scaleFactorTt", "unitFactorTt", "rawText");
        assertThat(profile.fields().get("body.role").values()).contains("physician", "other");
        assertThat(profile.fields().get("body.place").values()).containsExactly("practice", "hospital", "lab",
                "association", "company");
        assertThat(profile.fields().get("treatment.reason").values()).contains("prevention", "birthdefect");
        assertThat(profile.fields().get("law.type").values()).contains("IVG");
        assertThat(profile.instructions()).contains("identification line", "rawText");
        assertThat(profile.instructions())
                .contains("only the items that are checked", "never return an item that is only printed");
        assertThat(profile.instructions()).contains("biller");
        assertThat(profile.pageClassification().mainDocument()).contains("invoice", "IV office");
        assertThat(profile.pageClassification().instructions()).contains("300.001", "Rechnungskopie des Herstellers");
    }

    @Test
    void pageClassification_isOptional() {
        ExtractionProfile without = parse("id: p\nfields:\n  - {key: a}");
        ExtractionProfile withoutInstructions = parse("id: p\nfields:\n  - {key: a}\npageClassification: {}");
        ExtractionProfile with = parse("id: p\nfields:\n  - {key: a}\npageClassification:\n"
                + "  mainDocument: ' the medical report '\n  instructions: ' Files. '");

        assertThat(without.pageClassification()).isNull();
        assertThat(withoutInstructions.pageClassification())
                .isEqualTo(new PageClassificationSettings("the main document of the file", ""));
        assertThat(with.pageClassification()).isEqualTo(new PageClassificationSettings("the medical report", "Files."));
        assertThatThrownBy(() -> parse("id: p\nfields:\n  - {key: a}\npageClassification:\n  unknown: 1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void loadsCivilStatusProfileFromClasspath_asAPerPageProfileWithFixedFields() throws IOException {
        ExtractionProfile profile = new ExtractionProfileRegistry(MAPPER, "").find("gaime-civil-status").orElseThrow();

        assertThat(profile.perPage()).isTrue();
        assertThat(profile.simpleOutput()).isTrue();
        assertThat(profile.pageClassification()).isNull();
        assertThat(profile.collections()).isEmpty();
        assertThat(profile.fields()).containsOnlyKeys("civil_status_detected", "signature_present", "box_married", "box_widowed",
                "box_divorced", "box_legally_separated", "box_married_lpart", "box_divorced_lpart",
                "box_widowed_lpart");
        assertThat(profile.fields().get("civil_status_detected").values()).containsExactly("true", "false", "UNCLEAR");
        assertThat(profile.fields().get("signature_present").values()).containsExactly("true", "false", "UNCLEAR");
        assertThat(profile.fields().get("box_widowed_lpart").values()).containsExactly("MARKED", "UNCLEAR", "UNMARKED");
        assertThat(profile.fields().values()).allMatch(field -> !field.nullable(),
                "every field is non-nullable so the model must commit to a value");
        assertThat(profile.instructions()).contains("ZIVILSTANDSBESTÄTIGUNG", "\"civil_status_detected\" is \"true\"");
    }

    @Test
    void field_nullable_defaultsTrue_andCanBeDisabledFromYaml() {
        ExtractionProfile nullableByDefault = parse("id: p\nfields:\n  - {key: a}");
        ExtractionProfile nonNullable = parse("id: p\nfields:\n  - {key: a, type: ENUM, values: [x, y], nullable: false}");

        assertThat(nullableByDefault.fields().get("a").nullable()).isTrue();
        assertThat(nonNullable.fields().get("a").nullable()).isFalse();
    }

    @Test
    void field_nonNullable_isOnlyAllowedForEnum() {
        for (String type : List.of("STRING", "DATE", "AMOUNT", "NUMBER", "IDENTIFIER")) {
            assertThatThrownBy(() -> parse("id: p\nfields:\n  - {key: a, type: %s, nullable: false}".formatted(type)))
                    .as(type)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("field 'a': 'nullable: false' is only allowed for ENUM fields");
        }
        assertThatThrownBy(() -> parse("id: p\nfields:\n  - {key: a, nullable: false}"))
                .as("type defaults to STRING")
                .hasMessageContaining("only allowed for ENUM fields");
        assertThatThrownBy(() -> parse("id: p\ncollections:\n  - key: lines\n    fields: [{name: a, nullable: false}]"))
                .as("collection field")
                .hasMessageContaining("only allowed for ENUM fields");
        assertThat(parse("id: p\nfields:\n  - {key: a, type: DATE, nullable: true}").fields().get("a").nullable())
                .isTrue();
    }

    @Test
    void simpleOutput_isOptional_andDefaultsFalse() {
        assertThat(parse("id: p\nfields:\n  - {key: a}").simpleOutput()).isFalse();
        assertThat(parse("id: p\nsimpleOutput: true\nfields:\n  - {key: a}").simpleOutput()).isTrue();
    }

    @Test
    void perPage_isOptional_andCannotBeCombinedWithPageClassification() {
        assertThat(parse("id: p\nfields:\n  - {key: a}").perPage()).isFalse();
        assertThat(parse("id: p\nperPage: true\nfields:\n  - {key: a}").perPage()).isTrue();
        assertThatThrownBy(() -> parse("id: p\nperPage: true\nfields:\n  - {key: a}\npageClassification: {}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'perPage' and 'pageClassification' cannot be combined");
    }

    @Test
    void loadsProfilesFromExternalLocation() throws IOException {
        ExtractionProfileRegistry registry = new ExtractionProfileRegistry(MAPPER, "classpath:extraction-profiles-test");

        assertThat(registry.all()).extracting(ExtractionProfile::id)
                .containsExactly("gaime-civil-status", "sumex-invoice", "test-extra");
        assertThat(registry.find("test-extra").orElseThrow().fields().get("name").type()).isEqualTo(FieldType.STRING);
        assertThat(registry.find("unknown")).isEmpty();
    }

    @Test
    void invalidProfiles_areRejected() {
        List<String> invalid = List.of(
                "description: no id\nfields:\n  - {key: a}",
                "id: p\nfields:\n  - {key: a}\n  - {key: a}",
                "id: p\nfields:\n  - {key: 'bad key'}",
                "id: p\nfields:\n  - {key: a, type: UNKNOWN}",
                "id: p\nfields:\n  - {key: a, type: ENUM}",
                "id: p\nfields:\n  - {key: a, unknownProperty: 1}",
                "id: p\ncollections:\n  - {key: lines}",
                "id: p\nfieldGroups:\n  - fields: [{name: a}]",
                "id: p",
                "id: p\nfields: [unclosed");
        for (String yaml : invalid) {
            assertThatThrownBy(() -> parse(yaml))
                    .as(yaml)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invalid extraction profile test");
        }
    }

    @Test
    void fieldGroups_areExpandedPerPrefix() {
        ExtractionProfile profile = parse("""
                id: p
                fieldGroups:
                  - prefixes: [a, b]
                    fields:
                      - {name: x, type: NUMBER}
                fields:
                  - {key: c}
                collections:
                  - key: lines
                    fields:
                      - {name: amount, type: AMOUNT}
                """);

        assertThat(profile.fields()).containsOnlyKeys("a.x", "b.x", "c");
        assertThat(profile.fields().get("b.x").type()).isEqualTo(FieldType.NUMBER);
        assertThat(profile.collections().get("lines").fields().getFirst().type()).isEqualTo(FieldType.AMOUNT);
    }

    private static ExtractionProfile parse(String yaml) {
        return ExtractionProfileRegistry.parse(MAPPER, READER,
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test");
    }
}
