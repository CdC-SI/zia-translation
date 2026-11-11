package zas.admin.zia.translation.service;

public enum TranslationStrategy {
    SINGLE,
    DUAL;

    /**
     * Resolves the enum value from a case-insensitive string.
     * Used by the Spring MVC converter for @RequestParam binding.
     */
    public static TranslationStrategy fromString(String value) {
        return switch (value.trim().toUpperCase()) {
            case "SINGLE" -> SINGLE;
            case "DUAL" -> DUAL;
            default -> throw new IllegalArgumentException(
                    "Unknown strategy value: '%s'. Accepted values: single, dual.".formatted(value));
        };
    }
}
