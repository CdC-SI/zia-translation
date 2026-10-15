package zas.admin.zia.translation.service.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.ClassUtils;
import zas.admin.zia.translation.service.TranslationService;
import zas.admin.zia.translation.service.dto.TranslationJobResponse;
import zas.admin.zia.translation.service.job.JobStatus;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real Blue token security chain provided by zas-common-security-starter
 * (only on the classpath with {@code -Pinternal}).
 */
@SpringBootTest(properties = {
        "zas.security.blue-token.header-name=Blue",
        "zia.security.translator-groups=" + InternalSecurityIntegrationTest.TRANSLATOR_GROUP
})
@AutoConfigureMockMvc
@EnabledIf("zasSecurityLibraryPresent")
class InternalSecurityIntegrationTest {

    static final String TRANSLATOR_GROUP =
            "cn=TRANSLATOR,ou=DEV,ou=MyApp,ou=Applications,ou=Groups,dc=example,dc=org";
    private static final String OTHER_GROUP =
            "cn=READER,ou=DEV,ou=MyApp,ou=Applications,ou=Groups,dc=example,dc=org";
    private static final String STATUS_URL = "/api/translation/jobs/job-1/status";
    private static final String BLUE_HEADER = "Blue";

    private static final KeyPair GATEWAY_KEYS = generateRsaKeyPair();
    private static final KeyPair FOREIGN_KEYS = generateRsaKeyPair();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TranslationService translationService;

    static boolean zasSecurityLibraryPresent() {
        return ClassUtils.isPresent("ch.admin.zas.common.security.config.BlueGatewayJwtSecurityConfig",
                InternalSecurityIntegrationTest.class.getClassLoader());
    }

    @DynamicPropertySource
    static void gatewayPublicKey(DynamicPropertyRegistry registry) {
        registry.add("zas.security.blue-token.public-key",
                () -> Base64.getEncoder().encodeToString(GATEWAY_KEYS.getPublic().getEncoded()));
    }

    @Test
    void actuatorHealth_isReachableWithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void apiCallWithoutToken_isUnauthorized() throws Exception {
        mockMvc.perform(get(STATUS_URL))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(translationService);
    }

    @Test
    void apiCallWithTokenSignedByUnknownKey_isUnauthorized() throws Exception {
        String token = blueToken(FOREIGN_KEYS.getPrivate(), List.of(TRANSLATOR_GROUP), Instant.now().plusSeconds(300));

        mockMvc.perform(get(STATUS_URL).header(BLUE_HEADER, "Bearer " + token))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(translationService);
    }

    @Test
    void apiCallWithExpiredToken_isUnauthorized() throws Exception {
        String token = blueToken(GATEWAY_KEYS.getPrivate(), List.of(TRANSLATOR_GROUP), Instant.now().minusSeconds(60));

        mockMvc.perform(get(STATUS_URL).header(BLUE_HEADER, "Bearer " + token))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(translationService);
    }

    @Test
    void apiCallWithTokenWithoutRoles_isForbidden() throws Exception {
        String token = blueToken(GATEWAY_KEYS.getPrivate(), List.of(), Instant.now().plusSeconds(300));

        mockMvc.perform(get(STATUS_URL).header(BLUE_HEADER, "Bearer " + token))
                .andExpect(status().isForbidden());

        verifyNoInteractions(translationService);
    }

    @Test
    void apiCallWithoutTranslatorGroup_isForbidden() throws Exception {
        String token = blueToken(GATEWAY_KEYS.getPrivate(), List.of(OTHER_GROUP), Instant.now().plusSeconds(300));

        mockMvc.perform(get(STATUS_URL).header(BLUE_HEADER, "Bearer " + token))
                .andExpect(status().isForbidden());

        verifyNoInteractions(translationService);
    }

    @Test
    void apiCallWithTranslatorGroup_isAllowed() throws Exception {
        when(translationService.getJobStatusResponse("job-1"))
                .thenReturn(Optional.of(new TranslationJobResponse("job-1", JobStatus.PROCESSING)));
        String token = blueToken(GATEWAY_KEYS.getPrivate(), List.of(OTHER_GROUP, TRANSLATOR_GROUP),
                Instant.now().plusSeconds(300));

        mockMvc.perform(get(STATUS_URL).header(BLUE_HEADER, "Bearer " + token))
                .andExpect(status().isOk());
    }

    private static String blueToken(PrivateKey signingKey, List<String> roles, Instant expiration)
            throws GeneralSecurityException {
        String rolesJson = roles.stream().map(role -> "\"" + role + "\"").collect(Collectors.joining(",", "[", "]"));
        String subject = """
                {"userUID":"test-user","visa":"TST","firstname":"Test","lastname":"User","roles":%s}"""
                .formatted(rolesJson);
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String payload = "{\"sub\":\"%s\",\"exp\":%d}"
                .formatted(subject.replace("\"", "\\\""), expiration.getEpochSecond());

        String signingInput = base64Url(header.getBytes(StandardCharsets.UTF_8)) + "."
                + base64Url(payload.getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signingKey);
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + base64Url(signature.sign());
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
