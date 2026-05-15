package zas.admin.zia.translation.service.dto;

public sealed interface TranslationStreamEvent permits TranslationStreamEvent.Token, TranslationStreamEvent.PageComplete {

    record Token(int pageNumber, String token) implements TranslationStreamEvent {}

    record PageComplete(int pageNumber, String text) implements TranslationStreamEvent {}
}

