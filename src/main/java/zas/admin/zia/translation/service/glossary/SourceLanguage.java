package zas.admin.zia.translation.service.glossary;

/**
 * Language of the source text, chosen by the model when it calls lookup_acronyms. Spring AI lists the constant
 * names as a JSON Schema {@code enum} in the tool definition, and converts the model's value back with
 * {@code Enum.valueOf} (exact, case-sensitive).
 */
public enum SourceLanguage {
    DE("de"),
    FR("fr"),
    IT("it"),
    EN("en");

    private final String code;

    SourceLanguage(String code) {
        this.code = code;
    }

    /** Glossary language code ("de"…), as used in the taxonomy file and in {@link Glossary}'s index. */
    public String code() {
        return code;
    }
}
