package zas.admin.zia.translation.service.invoice;

import java.util.List;
import java.util.Map;

/**
 * Raw values read on one page by the vision model, before the merge of the pages.
 */
record PageExtraction(
        int page,
        String documentType,
        Map<String, ExtractedField> fields,
        List<ExtractedService> services,
        List<String> warnings) {

    PageExtraction {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        services = services == null ? List.of() : List.copyOf(services);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
