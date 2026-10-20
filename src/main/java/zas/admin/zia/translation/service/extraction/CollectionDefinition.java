package zas.admin.zia.translation.service.extraction;

import java.util.List;

/**
 * Definition of a repeated structure of the document (e.g. the service lines of an invoice).
 *
 * @param fields fields of one line, {@link FieldDefinition#key()} being the name of the field in the line
 */
record CollectionDefinition(String key, String description, List<FieldDefinition> fields) {

    CollectionDefinition {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
