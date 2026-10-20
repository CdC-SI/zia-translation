package zas.admin.zia.translation.service.extraction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes the confidence of an extracted value. The confidence declared by the model is not reliable on its own:
 * it is capped when the model gives no evidence (text read in the document) or when the value cannot be found in
 * its evidence.
 */
@Component
class ConfidenceCalculator {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern WHITESPACES = Pattern.compile("\\s+");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^A-Za-z0-9]");
    private static final Pattern NON_DIGIT = Pattern.compile("\\D");
    private static final Pattern DATE_TOKEN =
            Pattern.compile("\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}\\s*[./-]\\s*\\d{1,2}\\s*[./-]\\s*\\d{4}");

    private final double noEvidenceCap;
    private final double unmatchedEvidenceCap;

    ConfidenceCalculator(@Value("${zia.extraction.confidence.no-evidence-cap:0.3}") double noEvidenceCap,
                         @Value("${zia.extraction.confidence.unmatched-evidence-cap:0.5}") double unmatchedEvidenceCap) {
        this.noEvidenceCap = noEvidenceCap;
        this.unmatchedEvidenceCap = unmatchedEvidenceCap;
    }

    /**
     * @param value           normalized value
     * @param evidence        text read in the document according to the model
     * @param modelConfidence confidence declared by the model
     */
    double compute(FieldDefinition field, String value, String evidence, Double modelConfidence) {
        double confidence = modelConfidence == null || modelConfidence.isNaN()
                ? 0.0
                : Math.clamp(modelConfidence, 0.0, 1.0);
        if (evidence == null || evidence.isBlank()) {
            return Math.min(confidence, noEvidenceCap);
        }
        if (!matchesEvidence(field, value, evidence)) {
            return Math.min(confidence, unmatchedEvidenceCap);
        }
        return confidence;
    }

    static boolean matchesEvidence(FieldDefinition field, String value, String evidence) {
        return switch (field.type()) {
            case STRING -> fold(evidence).contains(fold(value));
            case IDENTIFIER -> alphanumeric(evidence).contains(alphanumeric(value));
            case AMOUNT, NUMBER -> {
                String digits = NON_DIGIT.matcher(value).replaceAll("");
                yield !digits.isEmpty() && NON_DIGIT.matcher(evidence).replaceAll("").contains(digits);
            }
            case DATE -> matchesDate(value, evidence);
            case ENUM -> true;
        };
    }

    /** A numeric date of the evidence equals the value, or the evidence has a textual date of the same year. */
    private static boolean matchesDate(String value, String evidence) {
        Matcher matcher = DATE_TOKEN.matcher(evidence);
        boolean numericDate = false;
        while (matcher.find()) {
            numericDate = true;
            if (value.equals(FieldValueNormalizer.normalizeDate(matcher.group().replace(" ", "")))) {
                return true;
            }
        }
        return !numericDate && value.length() >= 4 && evidence.contains(value.substring(0, 4));
    }

    private static String fold(String text) {
        String withoutAccents = DIACRITICS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        return WHITESPACES.matcher(withoutAccents.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    private static String alphanumeric(String text) {
        return NON_ALPHANUMERIC.matcher(text).replaceAll("").toUpperCase(Locale.ROOT);
    }
}
