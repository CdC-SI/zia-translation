package zas.admin.zia.translation.service.language;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Target languages supported by the translation service.
 */
public enum TargetLanguage {

    FR("fr", "French", "français",
            Set.of("fr", "fra", "fre", "french", "francais", "franzosisch", "francese")),
    DE("de", "German", "Deutsch",
            Set.of("de", "deu", "ger", "german", "deutsch", "allemand", "tedesco")),
    IT("it", "Italian", "italiano",
            Set.of("it", "ita", "italian", "italiano", "italien", "italienisch")),
    EN("en", "English", "English",
            Set.of("en", "eng", "english", "anglais", "englisch", "inglese"));

    private final String code;
    private final String englishName;
    private final String nativeName;
    private final Set<String> aliases;

    TargetLanguage(String code, String englishName, String nativeName, Set<String> aliases) {
        this.code = code;
        this.englishName = englishName;
        this.nativeName = nativeName;
        this.aliases = aliases;
    }

    public String code() {
        return code;
    }

    public String englishName() {
        return englishName;
    }

    /**
     * Unambiguous label used in LLM prompts, e.g. {@code German (Deutsch, ISO 639-1 code "de")}.
     */
    public String promptLabel() {
        String isoCode = "ISO 639-1 code \"" + code + "\"";
        if (englishName.equals(nativeName)) {
            return englishName + " (" + isoCode + ")";
        }
        return englishName + " (" + nativeName + ", " + isoCode + ")";
    }

    /**
     * Resolves a language from an ISO code (2 or 3 letters), a regional variant (e.g. {@code de-CH})
     * or a common language name in fr/de/it/en. Matching is case- and accent-insensitive.
     */
    public static Optional<TargetLanguage> fromString(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(value);
        String withoutRegion = normalized.split("[-_]", 2)[0];
        return Arrays.stream(values())
                .filter(language -> language.aliases.contains(normalized) || language.aliases.contains(withoutRegion))
                .findFirst();
    }

    private static String normalize(String value) {
        String decomposed = Normalizer.normalize(value.strip(), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
