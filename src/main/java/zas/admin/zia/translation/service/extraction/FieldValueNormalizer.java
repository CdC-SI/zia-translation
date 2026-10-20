package zas.admin.zia.translation.service.extraction;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalizes a value read by the model according to its {@link FieldType}. A value that cannot be normalized is
 * rejected ({@code null}): the service never returns a value in an unexpected format.
 */
final class FieldValueNormalizer {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT));
    private static final Pattern ISO_DATE_TIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T.*");
    private static final Pattern CURRENCY = Pattern.compile("(?i)(s?fr\\.?|chf|eur|\\u20AC)");
    private static final Pattern NUMBER_NOISE = Pattern.compile("[\\s\\u00A0\\u202F'\\u2019`%]");
    private static final Pattern DASH_DECIMALS = Pattern.compile("[.,]-+$");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Pattern IDENTIFIER_SEPARATORS = Pattern.compile("[\\s\\u00A0\\u202F.\\-]");

    private FieldValueNormalizer() {
    }

    static String normalize(FieldDefinition field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        return switch (field.type()) {
            case STRING -> trimmed;
            case DATE -> normalizeDate(trimmed);
            case AMOUNT, NUMBER -> normalizeNumber(trimmed);
            case IDENTIFIER -> normalizeIdentifier(trimmed);
            case ENUM -> field.values().stream().filter(trimmed::equalsIgnoreCase).findFirst().orElse(null);
        };
    }

    static String normalizeDate(String value) {
        String candidate = ISO_DATE_TIME.matcher(value).matches() ? value.substring(0, 10) : value.replace(" ", "");
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(candidate, format).toString();
            } catch (DateTimeParseException ignored) {
                // next format
            }
        }
        return null;
    }

    static String normalizeNumber(String value) {
        String number = CURRENCY.matcher(value).replaceAll("");
        number = NUMBER_NOISE.matcher(number).replaceAll("");
        number = DASH_DECIMALS.matcher(number).replaceAll("");
        int comma = number.lastIndexOf(',');
        int dot = number.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            number = comma < dot ? number.replace(",", "") : number.replace(".", "").replace(',', '.');
        } else if (comma >= 0) {
            number = number.replace(',', '.');
        }
        return NUMBER.matcher(number).matches() ? number : null;
    }

    static String normalizeIdentifier(String value) {
        String identifier = IDENTIFIER_SEPARATORS.matcher(value).replaceAll("").toUpperCase(Locale.ROOT);
        return identifier.isEmpty() ? null : identifier;
    }
}
