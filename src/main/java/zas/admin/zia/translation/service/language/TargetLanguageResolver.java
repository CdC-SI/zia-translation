package zas.admin.zia.translation.service.language;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the requested target language to one of the supported {@link TargetLanguage}s,
 * falling back to the configured default language when the value is missing or unsupported.
 */
@Component
public class TargetLanguageResolver {

    private static final Logger log = LoggerFactory.getLogger(TargetLanguageResolver.class);

    private final TargetLanguage defaultLanguage;

    public TargetLanguageResolver(@Value("${zia.translation.default-language:fr}") String defaultLanguage) {
        this.defaultLanguage = TargetLanguage.fromString(defaultLanguage)
                .orElseThrow(() -> new IllegalStateException(
                        "Invalid default target language '%s'. Supported values: fr, de, it, en."
                                .formatted(defaultLanguage)));
    }

    public TargetLanguage defaultLanguage() {
        return defaultLanguage;
    }

    public TargetLanguage resolve(String requestedLanguage) {
        return TargetLanguage.fromString(requestedLanguage).orElseGet(() -> {
            log.warn("Target language '{}' is missing or unsupported, falling back to default language '{}'",
                    sanitizeForLog(requestedLanguage), defaultLanguage.code());
            return defaultLanguage;
        });
    }

    private static String sanitizeForLog(String value) {
        return value == null ? null : value.replace("\r", "\\r").replace("\n", "\\n");
    }
}
