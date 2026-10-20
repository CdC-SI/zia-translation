package zas.admin.zia.translation.service.invoice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A service line of the invoice.
 *
 * @param date       ISO {@code yyyy-MM-dd}
 * @param tariffType tariff type code of the XSD (e.g. 007, 590, 999)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
record ExtractedService(
        String date,
        String tariffType,
        String code,
        String name,
        Double quantity,
        Double unitPrice,
        Double amount,
        Double vatRate,
        Double confidence) {

    ExtractedService withDate(String newDate) {
        return new ExtractedService(newDate, tariffType, code, name, quantity, unitPrice, amount, vatRate, confidence);
    }

    ExtractedService withConfidence(Double newConfidence) {
        return new ExtractedService(date, tariffType, code, name, quantity, unitPrice, amount, vatRate, newConfidence);
    }
}
