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
 */
final class ExtractionSchemaGenerator {

    static final String DOCUMENT_TYPE = "documentType";
    static final String FIELDS = "fields";
    static final String COLLECTIONS = "collections";
    static final String WARNINGS = "warnings";
    static final String VALUE = "value";
    static final String EVIDENCE = "evidence";
    static final String CONFIDENCE = "confidence";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ExtractionSchemaGenerator() {
    }

    static ObjectNode generate(List<FieldDefinition> fields, List<CollectionDefinition> collections) {
        ObjectNode properties = NODES.objectNode();
        properties.set(DOCUMENT_TYPE, nullableString("Kind of document, null if unknown"));
        if (!fields.isEmpty()) {
            ObjectNode fieldProperties = NODES.objectNode();
            fields.forEach(field -> fieldProperties.set(field.key(), fieldSchema(field)));
            properties.set(FIELDS, strictObject(fieldProperties));
        }
        if (!collections.isEmpty()) {
            ObjectNode collectionProperties = NODES.objectNode();
            collections.forEach(collection -> collectionProperties.set(collection.key(), collectionSchema(collection)));
            properties.set(COLLECTIONS, strictObject(collectionProperties));
        }
        ObjectNode warnings = NODES.objectNode();
        warnings.put("type", "array");
        warnings.put("description", "Problems met while reading the page (illegible, cut, ambiguous...)");
        warnings.putObject("items").put("type", "string");
        properties.set(WARNINGS, warnings);
        return strictObject(properties);
    }

    private static ObjectNode collectionSchema(CollectionDefinition collection) {
        ObjectNode lineProperties = NODES.objectNode();
        collection.fields().forEach(field -> lineProperties.set(field.key(), fieldSchema(field)));
        ObjectNode schema = NODES.objectNode();
        schema.put("type", "array");
        if (collection.description() != null) {
            schema.put("description", collection.description());
        }
        schema.set("items", strictObject(lineProperties));
        return schema;
    }

    private static ObjectNode fieldSchema(FieldDefinition field) {
        ObjectNode value = nullableString(null);
        if (field.type() == FieldType.ENUM) {
            ArrayNode values = value.putArray("enum");
            field.values().forEach(values::add);
            values.addNull();
        }
        ObjectNode properties = NODES.objectNode();
        properties.set(VALUE, value);
        properties.set(EVIDENCE, nullableString("Text exactly as printed in the document, null if not found"));
        ObjectNode confidence = NODES.objectNode();
        confidence.put("type", "number");
        confidence.put("description", "Confidence between 0 and 1");
        properties.set(CONFIDENCE, confidence);

        ObjectNode schema = strictObject(properties);
        schema.put("description", field.promptDescription() + ". value is null if not found");
        return schema;
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
}
