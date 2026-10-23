package zas.admin.zia.translation.service.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void maxUploadSize_onTranslation_returns400() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/zia-trad/api/translation/pdf");

        var response = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(1), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void maxUploadSize_onExtraction_returns413() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/zia-trad/api/v1/extractions");

        var response = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(1), request);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
    }
}
