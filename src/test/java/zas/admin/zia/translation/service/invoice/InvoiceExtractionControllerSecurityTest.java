package zas.admin.zia.translation.service.invoice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import zas.admin.zia.translation.service.config.TranslatorAccess;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InvoiceExtractionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({InvoiceExtractionControllerSecurityTest.MethodSecurityTestConfig.class, TranslatorAccess.class})
@TestPropertySource(properties = "zia.security.translator-groups=" + InvoiceExtractionControllerSecurityTest.TRANSLATOR_GROUP)
class InvoiceExtractionControllerSecurityTest {

    static final String TRANSLATOR_GROUP =
            "cn=TRANSLATOR,ou=DEV,ou=MyApp,ou=Applications,ou=Groups,dc=example,dc=org";
    private static final String OTHER_GROUP =
            "cn=READER,ou=DEV,ou=MyApp,ou=Applications,ou=Groups,dc=example,dc=org";

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InvoiceExtractionService extractionService;

    @Test
    @WithMockUser(authorities = TRANSLATOR_GROUP)
    void callerWithTranslatorGroup_isAllowed() throws Exception {
        when(extractionService.find("job-1")).thenReturn(Optional.of(
                new ExtractionJobResponse("job-1", ExtractionStatus.PENDING, Instant.now(), null, null)));

        mockMvc.perform(get(InvoiceExtractionController.BASE_PATH + "/job-1"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = OTHER_GROUP)
    void callerWithoutTranslatorGroup_isForbidden() throws Exception {
        mockMvc.perform(get(InvoiceExtractionController.BASE_PATH + "/job-1"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(extractionService);
    }

    @Test
    @WithAnonymousUser
    void anonymousCaller_isForbidden() throws Exception {
        mockMvc.perform(get(InvoiceExtractionController.BASE_PATH + "/job-1"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(extractionService);
    }
}
