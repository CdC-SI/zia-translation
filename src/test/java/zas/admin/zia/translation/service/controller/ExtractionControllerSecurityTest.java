package zas.admin.zia.translation.service.controller;

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
import zas.admin.zia.translation.service.extraction.ExtractionJobResponse;
import zas.admin.zia.translation.service.extraction.ExtractionProfileRegistry;
import zas.admin.zia.translation.service.extraction.ExtractionService;
import zas.admin.zia.translation.service.extraction.ExtractionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {ExtractionController.class, ExtractionProfileController.class})
@AutoConfigureMockMvc(addFilters = false)
@Import({ExtractionControllerSecurityTest.MethodSecurityTestConfig.class, TranslatorAccess.class})
@TestPropertySource(properties = "zia.security.translator-groups=" + ExtractionControllerSecurityTest.TRANSLATOR_GROUP)
class ExtractionControllerSecurityTest {

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
    private ExtractionService extractionService;

    @MockitoBean
    private ExtractionProfileRegistry registry;

    @Test
    @WithMockUser(authorities = TRANSLATOR_GROUP)
    void callerWithTranslatorGroup_isAllowed() throws Exception {
        when(extractionService.find("job-1")).thenReturn(Optional.of(
                new ExtractionJobResponse("job-1", ExtractionStatus.PENDING, Instant.now(), null, null)));
        when(registry.all()).thenReturn(List.of());

        mockMvc.perform(get(ExtractionController.BASE_PATH + "/job-1"))
                .andExpect(status().isOk());
        mockMvc.perform(get(ExtractionProfileController.BASE_PATH))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = OTHER_GROUP)
    void callerWithoutTranslatorGroup_isForbidden() throws Exception {
        mockMvc.perform(get(ExtractionController.BASE_PATH + "/job-1"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(ExtractionProfileController.BASE_PATH))
                .andExpect(status().isForbidden());

        verifyNoInteractions(extractionService, registry);
    }

    @Test
    @WithAnonymousUser
    void anonymousCaller_isForbidden() throws Exception {
        mockMvc.perform(get(ExtractionController.BASE_PATH + "/job-1"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(ExtractionProfileController.BASE_PATH))
                .andExpect(status().isForbidden());

        verifyNoInteractions(extractionService, registry);
    }
}
