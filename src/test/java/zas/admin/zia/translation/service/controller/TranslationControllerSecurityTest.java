package zas.admin.zia.translation.service.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;
import zas.admin.zia.translation.service.TranslationService;
import zas.admin.zia.translation.service.dto.TranslationJobResponse;
import zas.admin.zia.translation.service.job.JobStatus;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {TranslationController.class, GlobalExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
@Import(TranslationControllerSecurityTest.MethodSecurityTestConfig.class)
class TranslationControllerSecurityTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TranslationService translationService;

    @Test
    @WithMockUser(authorities = {"cn=OTHER", "cn=TRANSLATOR"})
    void callerWithTranslatorRole_isAllowed() throws Exception {
        when(translationService.getJobStatusResponse("job-1"))
                .thenReturn(Optional.of(new TranslationJobResponse("job-1", JobStatus.PROCESSING)));

        mockMvc.perform(get("/api/translation/jobs/job-1/status"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = {"cn=OTHER", "ROLE_TRANSLATOR", "TRANSLATOR"})
    void callerWithoutTranslatorRole_isForbidden() throws Exception {
        mockMvc.perform(get("/api/translation/jobs/job-1/status"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(translationService);
    }

    @Test
    @WithAnonymousUser
    void anonymousCaller_isForbidden() throws Exception {
        mockMvc.perform(get("/api/translation/jobs/job-1/status"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(translationService);
    }

    @Test
    void allRestControllers_requireTranslatorRole() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<BeanDefinition> controllers = scanner.findCandidateComponents("zas.admin.zia.translation");

        assertThat(controllers).isNotEmpty();
        for (BeanDefinition controller : controllers) {
            PreAuthorize preAuthorize = Class.forName(controller.getBeanClassName()).getAnnotation(PreAuthorize.class);
            assertThat(preAuthorize)
                    .as("%s must be annotated with @PreAuthorize", controller.getBeanClassName())
                    .isNotNull();
            assertThat(preAuthorize.value()).isEqualTo(TranslationController.TRANSLATOR_AUTHORITY_CHECK);
        }
    }
}
