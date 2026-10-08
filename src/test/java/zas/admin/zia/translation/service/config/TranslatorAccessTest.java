package zas.admin.zia.translation.service.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TranslatorAccessTest {

    private static final String TRANSLATOR =
            "cn=TRANSLATOR,ou=DEV,ou=MyApp,ou=Applications,ou=Groups,dc=example,dc=org";
    private static final String ADMIN =
            "cn=ADMIN,ou=DEV,ou=MyApp,ou=Applications,ou=Groups,dc=example,dc=org";

    private final TranslatorAccess access = new TranslatorAccess(TRANSLATOR + " ; " + ADMIN + ";");

    @Test
    void grantsCallerHoldingAnyConfiguredGroup() {
        assertThat(access.isGranted(authenticated("ROLE_USER", TRANSLATOR))).isTrue();
        assertThat(access.isGranted(authenticated(ADMIN))).isTrue();
    }

    @Test
    void comparesDnsCaseAndWhitespaceInsensitively() {
        String variant = "CN=translator, OU=dev, ou=MyApp, ou=Applications, ou=Groups, dc=EXAMPLE, dc=org";

        assertThat(access.isGranted(authenticated(variant))).isTrue();
    }

    @Test
    void deniesSameCnInAnotherBranch() {
        assertThat(access.isGranted(authenticated(TRANSLATOR.replace("ou=DEV", "ou=TEST")))).isFalse();
        assertThat(access.isGranted(authenticated("cn=TRANSLATOR"))).isFalse();
    }

    @Test
    void ignoresNonDnAuthorities() {
        assertThat(access.isGranted(authenticated("ROLE_TRANSLATOR", "TRANSLATOR", "not a dn"))).isFalse();
    }

    @Test
    void deniesMissingOrAnonymousOrUnauthenticatedCaller() {
        Authentication unauthenticated = new TestingAuthenticationToken("jdoe", "n/a", AuthorityUtils.createAuthorityList(TRANSLATOR));
        unauthenticated.setAuthenticated(false);

        assertThat(access.isGranted(null)).isFalse();
        assertThat(access.isGranted(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")))).isFalse();
        assertThat(access.isGranted(unauthenticated)).isFalse();
    }

    @Test
    void emptyConfigurationDeniesEveryone() {
        assertThat(new TranslatorAccess("").isGranted(authenticated(TRANSLATOR))).isFalse();
        assertThat(new TranslatorAccess(" ; ").isGranted(authenticated(TRANSLATOR))).isFalse();
        assertThat(new TranslatorAccess(null).isGranted(authenticated(TRANSLATOR))).isFalse();
    }

    @Test
    void invalidConfiguredDnFailsFast() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TranslatorAccess(TRANSLATOR + ";TRANSLATOR"))
                .withMessageContaining("TRANSLATOR");
    }

    private static Authentication authenticated(String... authorities) {
        return new TestingAuthenticationToken("jdoe", "n/a", authorities);
    }
}
