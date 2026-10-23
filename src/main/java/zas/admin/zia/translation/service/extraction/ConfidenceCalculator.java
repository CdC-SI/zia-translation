package zas.admin.zia.translation.service.extraction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    /**
     * Signed number of the evidence: digits grouped by {@code '}, {@code ’}, no-break spaces, {@code .} or {@code ,}
     * (always followed by a digit, so that separate columns such as {@code 12, 34} are separate numbers).
     */
    private static final Pattern NUMBER_TOKEN =
            Pattern.compile("(?<![\\d.,'\\u2019\\u00A0\\u202F])([-\\u2212]?)(\\d+(?:[.,'\\u2019\\u00A0\\u202F]\\d+)*)");
    private static final Pattern DIGIT_GROUPING = Pattern.compile("['\\u2019\\u00A0\\u202F]");
    private static final Pattern DATE_TOKEN =
            Pattern.compile("\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}\\s*[./-]\\s*\\d{1,2}\\s*[./-]\\s*\\d{4}");
    /** Date printed with a 2-digit year, e.g. {@code 08.10.26} on the service lines of a Tarmed form. */
    private static final Pattern SHORT_DATE_TOKEN =
            Pattern.compile("(?<![\\d.])(\\d{1,2})\\s*[./-]\\s*(\\d{1,2})\\s*[./-]\\s*(\\d{2})(?!\\d)");
    /** Textual date, day first, e.g. {@code 12. März 2024}, {@code 1er avril 2024} (evidence folded). */
    private static final Pattern TEXTUAL_DATE_DAY_FIRST =
            Pattern.compile("(?<!\\d)(\\d{1,2})(?:\\.|er|st|nd|rd|th)?\\s*([a-z]+)\\.?\\s+(\\d{4})(?!\\d)");
    /** Textual date, month first, e.g. {@code March 12, 2024} (evidence folded). */
    private static final Pattern TEXTUAL_DATE_MONTH_FIRST =
            Pattern.compile("(?<![a-z])([a-z]+)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})(?!\\d)");
    private static final Map<String, Integer> MONTHS = months();

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
            case AMOUNT, NUMBER -> matchesNumber(value, evidence);
            case DATE -> matchesDate(value, evidence);
            case ENUM -> true;
        };
    }

    /**
     * A number of the evidence has the same signed value as the value. The numbers of the evidence are compared one by
     * one: digits of separate numbers are never joined.
     */
    private static boolean matchesNumber(String value, String evidence) {
        BigDecimal expected;
        try {
            expected = new BigDecimal(value);
        } catch (NumberFormatException e) {
            return false;
        }
        Matcher matcher = NUMBER_TOKEN.matcher(evidence);
        while (matcher.find()) {
            boolean negative = !matcher.group(1).isEmpty();
            for (BigDecimal candidate : interpretations(DIGIT_GROUPING.matcher(matcher.group(2)).replaceAll(""))) {
                if ((negative ? candidate.negate() : candidate).compareTo(expected) == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Possible values of a number whose separators are {@code .} and {@code ,}: the last separator is the decimal
     * separator when both are used; a separator used several times groups thousands; a single separator followed by 3
     * digits is ambiguous (decimal or thousands).
     */
    private static List<BigDecimal> interpretations(String number) {
        int comma = number.lastIndexOf(',');
        int dot = number.lastIndexOf('.');
        List<String> candidates = new ArrayList<>(2);
        if (comma >= 0 && dot >= 0) {
            char decimal = comma > dot ? ',' : '.';
            char grouping = comma > dot ? '.' : ',';
            String withoutGrouping = number.replace(String.valueOf(grouping), "");
            if (withoutGrouping.indexOf(decimal) == withoutGrouping.lastIndexOf(decimal)) {
                candidates.add(withoutGrouping.replace(decimal, '.'));
            }
        } else if (comma >= 0 || dot >= 0) {
            char separator = comma >= 0 ? ',' : '.';
            int last = Math.max(comma, dot);
            if (number.indexOf(separator) != last) {
                candidates.add(number.replace(String.valueOf(separator), ""));
            } else {
                candidates.add(number.replace(separator, '.'));
                if (number.length() - last - 1 == 3) {
                    candidates.add(number.replace(String.valueOf(separator), ""));
                }
            }
        } else {
            candidates.add(number);
        }
        return candidates.stream().map(BigDecimal::new).toList();
    }

    /**
     * A date of the evidence (numeric, numeric with a 2-digit year matching the last 2 digits of the year, or textual
     * with the name of the month in German, French, Italian or English) equals the value.
     */
    private static boolean matchesDate(String value, String evidence) {
        Matcher matcher = DATE_TOKEN.matcher(evidence);
        while (matcher.find()) {
            if (value.equals(FieldValueNormalizer.normalizeDate(matcher.group().replace(" ", "")))) {
                return true;
            }
        }
        Matcher shortDate = SHORT_DATE_TOKEN.matcher(evidence);
        while (shortDate.find()) {
            if (matchesShortDate(value, shortDate)) {
                return true;
            }
        }
        String folded = fold(evidence);
        return matchesTextualDate(value, TEXTUAL_DATE_DAY_FIRST.matcher(folded), 1, 2)
                || matchesTextualDate(value, TEXTUAL_DATE_MONTH_FIRST.matcher(folded), 2, 1);
    }

    private static boolean matchesTextualDate(String value, Matcher textualDate, int dayGroup, int monthGroup) {
        while (textualDate.find()) {
            Integer month = MONTHS.get(textualDate.group(monthGroup));
            if (month == null) {
                continue;
            }
            try {
                LocalDate date = LocalDate.of(Integer.parseInt(textualDate.group(3)), month,
                        Integer.parseInt(textualDate.group(dayGroup)));
                if (value.equals(date.toString())) {
                    return true;
                }
            } catch (DateTimeException e) {
                // not a valid date: next candidate
            }
        }
        return false;
    }

    /** Names of the months (folded) in German, French, Italian and English, with their usual abbreviations. */
    private static Map<String, Integer> months() {
        String[][] names = {
                {"januar", "janvier", "gennaio", "january", "jan", "janv", "genn"},
                {"februar", "fevrier", "febbraio", "february", "feb", "febr", "fev", "fevr", "febb"},
                {"marz", "mars", "marzo", "march", "mar", "mrz"},
                {"april", "avril", "aprile", "apr", "avr"},
                {"mai", "maggio", "may", "magg"},
                {"juni", "juin", "giugno", "june", "jun", "giu"},
                {"juli", "juillet", "luglio", "july", "jul", "juil", "lug"},
                {"august", "aout", "agosto", "aug", "ago"},
                {"september", "septembre", "settembre", "sep", "sept", "sett", "set"},
                {"oktober", "octobre", "ottobre", "october", "okt", "oct", "ott"},
                {"november", "novembre", "nov"},
                {"dezember", "decembre", "dicembre", "december", "dez", "dec", "dic"}};
        Map<String, Integer> months = new HashMap<>();
        for (int month = 0; month < names.length; month++) {
            for (String name : names[month]) {
                months.put(name, month + 1);
            }
        }
        return Map.copyOf(months);
    }

    private static boolean matchesShortDate(String value, Matcher shortDate) {
        try {
            LocalDate date = LocalDate.parse(value);
            return date.getDayOfMonth() == Integer.parseInt(shortDate.group(1))
                    && date.getMonthValue() == Integer.parseInt(shortDate.group(2))
                    && date.getYear() % 100 == Integer.parseInt(shortDate.group(3));
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static String fold(String text) {
        String withoutAccents = DIACRITICS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        return WHITESPACES.matcher(withoutAccents.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    private static String alphanumeric(String text) {
        return NON_ALPHANUMERIC.matcher(text).replaceAll("").toUpperCase(Locale.ROOT);
    }
}
