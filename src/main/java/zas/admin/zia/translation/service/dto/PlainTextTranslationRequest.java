package zas.admin.zia.translation.service.dto;

public record PlainTextTranslationRequest(
        String text,
        String targetLanguage
) {}
