package zas.admin.zia.translation.service.controller;

import org.springframework.core.convert.converter.Converter;
import zas.admin.zia.translation.service.TranslationStrategy;

class StringToTranslationStrategyConverter implements Converter<String, TranslationStrategy> {

    @Override
    public TranslationStrategy convert(String source) {
        return TranslationStrategy.fromString(source);
    }
}
