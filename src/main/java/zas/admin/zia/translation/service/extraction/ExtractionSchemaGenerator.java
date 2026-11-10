package zas.admin.zia.translation.service.extraction;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Generates the JSON Schema of the structured output of the vision model, restricted to the requested keys.
 * <p>
 * The schema is compatible with the strict mode of the OpenAI API (and the guided decoding of vLLM): every object
 * lists all its properties as {@code required} and forbids additional properties. A value that is not found must
 * be {@code null}. No {@code format}/{@code pattern} constraint is used, the values are normalized by the service.
 * <p>
 * By default, each field is {@code {value, evidence, confidence[, page]}} ({@code page}: the page of the file the
 * value is read on, included only when several pages are sent in the call), with {@code documentType} and
 * {@code warnings}. With {@code simpleOutput}, each field is a bare value, without {@code documentType}/
 * {@code warnings}, still compliant with strict mode (a non-object property has no {@code required}/
 * {@code additionalProperties} constraint to satisfy).
 */
final class ExtractionSchemaGenerator {

    static final String DOCUMENT_TYPE = "documentType";
    static final String FIELDS = "fields";
    static final String COLLECTIONS = "collections";
    static final String WARNINGS = "warnings";
    static final String VALUE = "value";
    static final String EVIDENCE = "evidence";
    static final String CONFIDENCE = "confidence";
    static final String PAGE = "page";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ExtractionSchemaGenerator() {
    }

    static ObjectNode generate(List<FieldDefinition> fields, List<CollectionDefinition> collections,
                               boolean simpleOutput, boolean withPage) {
        ObjectNode properties = NODES.objectNode();
        if (!simpleOutput) {
            properties.set(DOCUMENT_TYPE, nullableString("Kind of document, null if unknown"));
        }
        if (!fields.isEmpty()) {
            ObjectNode fieldProperties = NODES.objectNode();
            fields.forEach(field -> fieldProperties.set(field.key(), fieldSchema(field, simpleOutput, withPage)));
            properties.set(FIELDS, strictObject(fieldProperties));
        }
        if (!collections.isEmpty()) {
            ObjectNode collectionProperties = NODES.objectNode();
            collections.forEach(collection ->
                    collectionProperties.set(collection.key(), collectionSchema(collection, simpleOutput, withPage)));
            properties.set(COLLECTIONS, strictObject(collectionProperties));
        }
        if (!simpleOutput) {
            ObjectNode warnings = NODES.objectNode();
            warnings.put("type", "array");
            warnings.put("description", "Problems met while reading the document (illegible, cut, ambiguous...)");
            warnings.putObject("items").put("type", "string");
            properties.set(WARNINGS, warnings);
        }
        return strictObject(properties);
    }

    private static ObjectNode collectionSchema(CollectionDefinition collection, boolean simpleOutput,
                                               boolean withPage) {
        ObjectNode lineProperties = NODES.objectNode();
        collection.fields().forEach(field -> lineProperties.set(field.key(), fieldSchema(field, simpleOutput, withPage)));
        ObjectNode schema = NODES.objectNode();
        schema.put("type", "array");
        if (collection.description() != null) {
            schema.put("description", collection.description());
        }
        schema.set("items", strictObject(lineProperties));
        return schema;
    }

    /** Schema of the value itself: a string, restricted to the ENUM values, {@code null} only when nullable. */
    private static ObjectNode valueSchema(FieldDefinition field) {
        ObjectNode value = field.nullable() ? nullableString(null) : requiredString();
        if (field.type() == FieldType.ENUM) {
            ArrayNode values = value.putArray("enum");
            field.values().forEach(values::add);
            if (field.nullable()) {
                values.addNull();
            }
        }
        return value;
    }

    /** With {@code simpleOutput}, the bare value (no wrapper object): the minimum needed to answer this field. */
    private static ObjectNode fieldSchema(FieldDefinition field, boolean simpleOutput, boolean withPage) {
        if (simpleOutput) {
            return valueSchema(field).put("description", field.schemaDescription());
        }
        ObjectNode properties = NODES.objectNode();
        properties.set(VALUE, valueSchema(field));
        properties.set(EVIDENCE, nullableString("Text exactly as printed in the document, null if not found"));
        ObjectNode confidence = NODES.objectNode();
        confidence.put("type", "number");
        confidence.put("description", "Confidence between 0 and 1");
        properties.set(CONFIDENCE, confidence);
        if (withPage) {
            ObjectNode page = NODES.objectNode();
            page.putArray("type").add("integer").add("null");
            page.put("description", "Page of the file the value is read on, null if not found");
            properties.set(PAGE, page);
        }
        return strictObject(properties).put("description", field.schemaDescription());
    }

    private static ObjectNode strictObject(ObjectNode properties) {
        ObjectNode schema = NODES.objectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        ArrayNode required = schema.putArray("required");
        properties.propertyNames().forEach(required::add);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode nullableString(String description) {
        ObjectNode schema = NODES.objectNode();
        schema.putArray("type").add("string").add("null");
        if (description != null) {
            schema.put("description", description);
        }
        return schema;
    }

    /** Same as {@link #nullableString}, without allowing {@code null}: the model must answer a real value. */
    private static ObjectNode requiredString() {
        ObjectNode schema = NODES.objectNode();
        schema.put("type", "string");
        return schema;
    }
}
