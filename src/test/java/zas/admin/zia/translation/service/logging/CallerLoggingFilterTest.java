package zas.admin.zia.translation.service.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class CallerLoggingFilterTest {

    private final CallerLoggingFilter filter = new CallerLoggingFilter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void logsUsernameAndAuthoritiesOfUserDetailsPrincipal(CapturedOutput output) throws Exception {
        var user = User.withUsername("jdoe").password("n/a").authorities("ROLE_USER", "ROLE_ADMIN").build();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/zia-trad/api/translation/pdf"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(output).contains("uri=/zia-trad/api/translation/pdf")
                .contains("username=jdoe")
                .contains("authorities=[ROLE_ADMIN, ROLE_USER]");
    }

    @Test
    void logsNameOfNonUserDetailsPrincipal(CapturedOutput output) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "service-account", null, AuthorityUtils.createAuthorityList("SCOPE_translate")));

        filter.doFilter(request("/zia-trad/api/translation/text"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(output).contains("username=service-account").contains("authorities=[SCOPE_translate]");
    }

    @Test
    void logsAnonymousWhenNoAuthentication(CapturedOutput output) throws Exception {
        filter.doFilter(request("/zia-trad/api/translation/text"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(output).contains("Caller [").contains("anonymous").doesNotContain("username=");
    }

    @Test
    void logsAnonymousForAnonymousToken(CapturedOutput output) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        filter.doFilter(request("/zia-trad/api/translation/text"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(output).contains("anonymous").doesNotContain("username=");
    }

    @Test
    void skipsActuatorEndpoints(CapturedOutput output) throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/zia-trad/actuator/health"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(output).doesNotContain("Caller [");
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContextPath("/zia-trad");
        return request;
    }
}
