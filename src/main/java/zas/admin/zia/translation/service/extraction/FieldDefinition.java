package zas.admin.zia.translation.service.extraction;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Definition of a value to extract.
 *
 * @param values allowed values of an {@link FieldType#ENUM} field
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record FieldDefinition(String key, FieldType type, String description, List<String> values) {

    public FieldDefinition {
        type = type == null ? FieldType.STRING : type;
        values = values == null ? List.of() : List.copyOf(values);
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
}
