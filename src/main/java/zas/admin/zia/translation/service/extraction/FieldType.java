package zas.admin.zia.translation.service.extraction;

/**
 * Type of an extracted value: drives the format asked to the model, the normalization and the evidence check.
 */
public enum FieldType {
    STRING("text as printed"),
    DATE("date, formatted yyyy-MM-dd"),
    AMOUNT("amount with '.' as decimal separator, without currency nor thousands separator"),
    NUMBER("number with '.' as decimal separator"),
    IDENTIFIER("identifier, without spaces nor separators"),
    ENUM("one of the allowed values");

    private final String formatHint;

    FieldType(String formatHint) {
        this.formatHint = formatHint;
    }

    String formatHint() {
        return formatHint;
    }
}
