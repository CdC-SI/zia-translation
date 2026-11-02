package zas.admin.zia.translation.service.extraction;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionSchemaGeneratorTest {

    private static final FieldDefinition GLN = new FieldDefinition("biller.gln", FieldType.IDENTIFIER, "GLN", null);
    private static final FieldDefinition SEX =
            new FieldDefinition("patient.sex", FieldType.ENUM, "Sex", List.of("male", "female"));
    private static final CollectionDefinition SERVICES = new CollectionDefinition("services", "Service lines",
            List.of(new FieldDefinition("amount", FieldType.AMOUNT, "Amount", null)));

    @Test
    void generate_listsOnlyRequestedKeys_inStrictObjects() {
        ObjectNode schema = ExtractionSchemaGenerator.generate(List.of(GLN, SEX), List.of(SERVICES), false);

        assertStrict(schema, "documentType", "fields", "collections", "warnings");
        JsonNode fields = schema.get("properties").get("fields");
        assertStrict(fields, "biller.gln", "patient.sex");
        JsonNode gln = fields.get("properties").get("biller.gln");
        assertStrict(gln, "value", "evidence", "confidence", "page");
        assertThat(gln.get("description").asString()).contains("GLN").contains("value is null if not found");
        assertThat(gln.get("properties").get("value").get("type").toString()).isEqualTo("[\"string\",\"null\"]");
        assertThat(gln.get("properties").get("evidence").get("type").toString()).isEqualTo("[\"string\",\"null\"]");
        assertThat(gln.get("properties").get("confidence").get("type").asString()).isEqualTo("number");
        assertThat(fields.get("properties").get("patient.sex").get("properties").get("value").get("enum").toString())
                .isEqualTo("[\"male\",\"female\",null]");

        JsonNode services = schema.get("properties").get("collections").get("properties").get("services");
        assertThat(services.get("type").asString()).isEqualTo("array");
        assertStrict(services.get("items"), "amount");
        assertThat(schema.toString()).doesNotContain("\"format\"").doesNotContain("\"pattern\"");
    }

    @Test
    void generate_omitsEmptySections() {
        ObjectNode onlyFields = ExtractionSchemaGenerator.generate(List.of(GLN), List.of(), false);
        ObjectNode onlyCollections = ExtractionSchemaGenerator.generate(List.of(), List.of(SERVICES), false);

        assertStrict(onlyFields, "documentType", "fields", "warnings");
        assertStrict(onlyCollections, "documentType", "collections", "warnings");
    }

    @Test
    void generate_detailed_addsPageToEachValue() {
        ObjectNode schema = ExtractionSchemaGenerator.generate(List.of(GLN), List.of(SERVICES), false);

        JsonNode gln = schema.get("properties").get("fields").get("properties").get("biller.gln");
        assertStrict(gln, "value", "evidence", "confidence", "page");
        assertThat(gln.get("properties").get("page").get("type").toString()).isEqualTo("[\"integer\",\"null\"]");
        JsonNode amount = schema.get("properties").get("collections").get("properties").get("services")
                .get("items").get("properties").get("amount");
        assertStrict(amount, "value", "evidence", "confidence", "page");
    }

    @Test
    void generate_withNonNullableField_forbidsNullValue() {
        FieldDefinition required = new FieldDefinition("civil_status_detected", FieldType.ENUM, "Attestation?",
                List.of("true", "false"), false);

        ObjectNode schema = ExtractionSchemaGenerator.generate(List.of(required), List.of(), false);

        JsonNode civilStatus = schema.get("properties").get("fields").get("properties").get("civil_status_detected");
        assertThat(civilStatus.get("properties").get("value").get("type").asString()).isEqualTo("string");
        assertThat(civilStatus.get("properties").get("value").get("enum").toString())
                .isEqualTo("[\"true\",\"false\"]");
        assertThat(civilStatus.get("description").asString())
                .contains("value is mandatory").doesNotContain("value is null if not found");
    }

    @Test
    void generate_simple_asksOnlyTheBareValue_noDocumentTypeOrWarnings() {
        FieldDefinition civilStatus = new FieldDefinition("civil_status_detected", FieldType.ENUM, "Attestation?",
                List.of("true", "false"), false);

        ObjectNode schema = ExtractionSchemaGenerator.generate(List.of(civilStatus), List.of(), true);

        assertStrict(schema, "fields");
        JsonNode fields = schema.get("properties").get("fields");
        assertStrict(fields, "civil_status_detected");
        JsonNode value = fields.get("properties").get("civil_status_detected");
        assertThat(value.get("type").asString()).isEqualTo("string");
        assertThat(value.get("enum").toString()).isEqualTo("[\"true\",\"false\"]");
        assertThat(value.has("properties")).isFalse();
        assertThat(value.get("description").asString()).contains("value is mandatory");
    }

    @Test
    void generate_simple_nullableField_stillBareValue() {
        FieldDefinition name = new FieldDefinition("name", FieldType.STRING, "Name", null);

        ObjectNode schema = ExtractionSchemaGenerator.generate(List.of(name), List.of(), true);

        JsonNode value = schema.get("properties").get("fields").get("properties").get("name");
        assertThat(value.get("type").toString()).isEqualTo("[\"string\",\"null\"]");
        assertThat(value.has("properties")).isFalse();
    }

    private static void assertStrict(JsonNode schema, String... properties) {
        assertThat(schema.get("type").asString()).isEqualTo("object");
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
        List<String> names = new ArrayList<>(schema.get("properties").propertyNames());
        assertThat(names).containsExactly(properties);
        List<String> required = new ArrayList<>();
        schema.get("required").forEach(node -> required.add(node.asString()));
        assertThat(required).containsExactly(properties);
    }
}
