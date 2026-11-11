package zas.admin.zia.translation.service.extraction;

/** Role of a page in the submitted file, which concerns one invoice. */
public enum DocumentRole {
    /** Page of the invoice addressed to the insurance or to the insured person: source of the values. */
    MAIN,
    /** Supporting document (copy of an invoice of a seller or a manufacturer, quote, power of attorney...). */
    SUPPORTING,
    /** Blank page, separator, unreadable page. */
    OTHER
}
