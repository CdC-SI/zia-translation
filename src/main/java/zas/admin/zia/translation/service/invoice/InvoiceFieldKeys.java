package zas.admin.zia.translation.service.invoice;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Field keys ({@code FieldKey}) of the invoice extraction contract.
 */
final class InvoiceFieldKeys {

    static final String SERVICES = "services";

    static final List<String> PARTY_ROLES = List.of(
            "biller", "provider", "debitor", "insurance", "patient", "guarantor", "creditor");

    static final List<String> PARTY_FIELDS = List.of(
            "gln", "gln_location", "zsr", "uid", "companyname", "department", "salutation", "title",
            "familyname", "givenname", "pobox", "street", "house_no", "zip", "city", "country", "phone", "email",
            "birthdate", "sex", "ssn");

    static final List<String> OTHER_KEYS = List.of(
            "request.language",
            "request.guid",
            "payload.request_subtype",
            "invoice.request_id",
            "invoice.request_date",
            "invoice.request_timestamp",
            "body.role",
            "body.place",
            "body.remark",
            "tiers.mode",
            "tiers.allowModification",
            "law.type",
            "law.case_id",
            "law.case_date",
            "law.insured_id",
            "law.card_id",
            "treatment.date_begin",
            "treatment.date_end",
            "treatment.canton",
            "treatment.treatment",
            "treatment.reason",
            "treatment.diagnosis.type",
            "treatment.diagnosis.code",
            "treatment.diagnosis.text",
            "esr.iban",
            "esr.reference_type",
            "esr.reference_number",
            "esr.payment_period_days",
            "balance.amount",
            "balance.amount_due",
            "balance.amount_prepaid",
            "balance.vat.vat_number",
            "balance.vat.vat");

    private static final Set<String> DATE_KEYS = Set.of(
            "invoice.request_date", "law.case_date", "treatment.date_begin", "treatment.date_end");

    private static final Set<String> AMOUNT_KEYS = Set.of(
            "balance.amount", "balance.amount_due", "balance.amount_prepaid", "balance.vat.vat");

    /** Every value key (i.e. all keys except {@link #SERVICES}), in a stable order. */
    static final Set<String> VALUE_KEYS = buildValueKeys();

    private InvoiceFieldKeys() {
    }

    static boolean isKnown(String key) {
        return SERVICES.equals(key) || VALUE_KEYS.contains(key);
    }

    static boolean isDate(String key) {
        return DATE_KEYS.contains(key) || key.endsWith(".birthdate");
    }

    static boolean isAmount(String key) {
        return AMOUNT_KEYS.contains(key);
    }

    /** Identifiers commonly printed with spaces or dots that must be returned without separators. */
    static boolean isCompactIdentifier(String key) {
        return key.endsWith(".gln") || key.endsWith(".gln_location") || key.endsWith(".ssn")
                || "esr.iban".equals(key);
    }

    private static Set<String> buildValueKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (String role : PARTY_ROLES) {
            for (String field : PARTY_FIELDS) {
                keys.add(role + "." + field);
            }
        }
        keys.addAll(OTHER_KEYS);
        return Collections.unmodifiableSet(keys);
    }
}
