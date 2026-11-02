package zas.admin.zia.translation.service.extraction;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Definition of a value to extract.
 *
 * @param values   allowed values of an {@link FieldType#ENUM} field
 * @param nullable whether the model is allowed to answer {@code null} (value not found/unclear). When {@code
 *                 false}, the generated schema forces the model to pick an actual value (e.g. one of the ENUM
 *                 {@code values}) instead of silently answering {@code null}.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record FieldDefinition(String key, FieldType type, String description, List<String> values,
                              boolean nullable) {

    public FieldDefinition {
        type = type == null ? FieldType.STRING : type;
        values = values == null ? List.of() : List.copyOf(values);
    }

    public FieldDefinition(String key, FieldType type, String description, List<String> values) {
        this(key, type, description, values, true);
    }

    static FieldDefinition adHoc(String key) {
        return new FieldDefinition(key, FieldType.STRING, null, List.of());
    }

    /** Description given to the model: profile description, format and allowed values. */
    String promptDescription() {
        StringBuilder text = new StringBuilder();
        if (description != null && !description.isBlank()) {
            text.append(description.strip()).append(". ");
        }
        text.append("Format: ").append(type.formatHint());
        if (type == FieldType.ENUM) {
            text.append(" ").append(values);
        }
        return text.toString();
    }

    /** Description given in the JSON Schema: the prompt description and whether {@code null} is accepted. */
    String schemaDescription() {
        return promptDescription() + (nullable
                ? ". value is null if not found"
                : ". value is mandatory: you must pick one of the listed values, null is not accepted");
    }
}
