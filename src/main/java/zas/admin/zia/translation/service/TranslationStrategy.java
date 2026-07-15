package zas.admin.zia.translation.service;

public enum TranslationStrategy {
    SINGLE,
    DUAL;

    /**
     * Résout la valeur de l'enum depuis une chaîne insensible à la casse.
     * Utilisé par le convertisseur Spring MVC pour les @RequestParam.
     */
    public static TranslationStrategy fromString(String value) {
        return switch (value.trim().toUpperCase()) {
            case "SINGLE" -> SINGLE;
            case "DUAL" -> DUAL;
            default -> throw new IllegalArgumentException(
                    "Valeur de stratégie inconnue : '%s'. Valeurs acceptées : single, dual.".formatted(value));
        };
    }
}
