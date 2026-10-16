package zas.admin.zia.translation.service.pdf;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import zas.admin.zia.translation.service.language.TargetLanguage;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;


/**
 * AI-translation disclaimer printed at the bottom of every generated PDF page, one text per target language.
 * A blank text disables the footer for that language.
 */
@Component
public class PdfFooterTexts {

    private final Map<TargetLanguage, String> texts;

    public PdfFooterTexts(
            @Value("${zia.translation.pdf.footer.fr:}") String fr,
            @Value("${zia.translation.pdf.footer.de:}") String de,
            @Value("${zia.translation.pdf.footer.it:}") String it,
            @Value("${zia.translation.pdf.footer.en:}") String en) {

        this.texts = new EnumMap<>(TargetLanguage.class);
        this.texts.put(TargetLanguage.FR, nullToEmpty(fr));
        this.texts.put(TargetLanguage.DE, nullToEmpty(de));
        this.texts.put(TargetLanguage.IT, nullToEmpty(it));
        this.texts.put(TargetLanguage.EN, nullToEmpty(en));
    }

    public Optional<String> forLanguage(TargetLanguage language) {
        if (language == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(texts.get(language))
                .map(String::strip)
                .filter(text -> !text.isEmpty());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
