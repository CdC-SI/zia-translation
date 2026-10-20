package zas.admin.zia.translation.service.invoice;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Merges the values read page by page into the response of the job.
 */
final class ExtractionResultMerger {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM-dd"),
            DateTimeFormatter.ofPattern("d.M.uuuu"),
            DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("d-M-uuuu"));

    private static final Pattern AMOUNT = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Pattern AMOUNT_NOISE = Pattern.compile("(?i)chf|fr\\.?|sfr\\.?|[\\s'’]");
    private static final Pattern IDENTIFIER_SEPARATORS = Pattern.compile("[\\s.\\-]");

    private ExtractionResultMerger() {
    }

    /**
     * Keeps, for each requested key, the value read with the highest confidence and concatenates the service lines.
     *
     * @param warnings warnings produced outside the pages (e.g. unreadable page), kept first
     */
    static ExtractionResponse merge(List<PageExtraction> pages, Set<String> requestedFields,
                                    boolean servicesRequested, List<String> warnings) {
        Map<String, ExtractedField> best = new LinkedHashMap<>();
        List<ExtractedService> services = new ArrayList<>();
        Set<String> serviceKeys = new HashSet<>();
        Set<String> allWarnings = new LinkedHashSet<>(warnings);
        String documentType = null;

        for (PageExtraction page : pages) {
            if (documentType == null && page.documentType() != null) {
                documentType = page.documentType();
            }
            page.fields().forEach((key, field) -> {
                if (!requestedFields.contains(key)) {
                    return;
                }
                ExtractedField normalized = normalize(key, field);
                if (normalized != null && isBetter(normalized, best.get(key))) {
                    best.put(key, normalized);
                }
            });
            if (servicesRequested) {
                for (ExtractedService service : page.services()) {
                    ExtractedService normalized = normalize(service);
                    if (serviceKeys.add(dedupKey(normalized))) {
                        services.add(normalized);
                    }
                }
            }
            page.warnings().forEach(warning -> allWarnings.add("Page " + page.page() + ": " + warning));
        }

        Map<String, ExtractedField> ordered = new LinkedHashMap<>();
        InvoiceFieldKeys.VALUE_KEYS.forEach(key -> {
            ExtractedField field = best.get(key);
            if (field != null) {
                ordered.put(key, field);
            }
        });
        return new ExtractionResponse(documentType, ordered, services, List.copyOf(allWarnings));
    }

    private static boolean isBetter(ExtractedField candidate, ExtractedField current) {
        return current == null || candidate.confidence() > current.confidence();
    }

    static ExtractedField normalize(String key, ExtractedField field) {
        String value = field.value() == null ? null : field.value().strip();
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (InvoiceFieldKeys.isDate(key)) {
            value = normalizeDate(value);
        } else if (InvoiceFieldKeys.isAmount(key)) {
            value = normalizeAmount(value);
        } else if (InvoiceFieldKeys.isCompactIdentifier(key)) {
            value = IDENTIFIER_SEPARATORS.matcher(value).replaceAll("").toUpperCase(Locale.ROOT);
        }
        if (value == null) {
            return null;
        }
        return new ExtractedField(value, clamp(field.confidence()), field.page());
    }

    private static ExtractedService normalize(ExtractedService service) {
        String date = service.date() == null ? null : normalizeDate(service.date().strip());
        return service.withDate(date).withConfidence(clamp(service.confidence()));
    }

    private static String dedupKey(ExtractedService service) {
        return Objects.toString(service.date()) + '|' + Objects.toString(service.code()) + '|'
                + Objects.toString(service.amount()) + '|' + Objects.toString(service.quantity());
    }

    static String normalizeDate(String value) {
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format.withResolverStyle(ResolverStyle.STRICT)).toString();
            } catch (DateTimeParseException ignored) {
                // next format
            }
        }
        return null;
    }

    static String normalizeAmount(String value) {
        String cleaned = AMOUNT_NOISE.matcher(value).replaceAll("").replace(',', '.');
        if (cleaned.endsWith(".-")) {
            cleaned = cleaned.substring(0, cleaned.length() - 2);
        }
        return AMOUNT.matcher(cleaned).matches() ? cleaned : null;
    }

    /** Missing confidences become 0 (the client ignores them), others are bounded to [0, 1]. */
    static double clamp(Double confidence) {
        if (confidence == null || confidence.isNaN()) {
            return 0.0;
        }
        return Math.clamp(confidence, 0.0, 1.0);
    }
}
