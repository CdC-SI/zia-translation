package zas.admin.zia.translation.service.glossary;

import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.execution.ToolCallResultConverter;

import java.lang.reflect.Type;
import java.util.List;

/**
 * Sends the lookup results to the model with the next step spelled out. A bare list left the model unsure whether
 * the lookup was over, and it repeated the same call round after round (seen on 04.11): the result now says that
 * the lookup is done and that the translation is what comes next.
 */
public final class AcronymLookupResultConverter implements ToolCallResultConverter {

    static final String NEXT_STEP = "The acronym lookup is complete. Do not call lookup_acronyms again: "
            + "write the translation now, using these results.";

    private final DefaultToolCallResultConverter delegate = new DefaultToolCallResultConverter();

    @Override
    public String convert(Object result, Type returnType) {
        if (result instanceof List<?> results) {
            return delegate.convert(new LookupResponse(results, NEXT_STEP), LookupResponse.class);
        }
        return delegate.convert(result, returnType);
    }

    /** What the model receives: the results, then what to do with them. */
    public record LookupResponse(List<?> results, String nextStep) { }
}
