package zas.admin.zia.translation.service.logging;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;

class CallerLoggingConfigTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(CallerLoggingConfig.class);

    @Test
    void filterNotRegisteredByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(FilterRegistrationBean.class));
    }

    @Test
    void filterNotRegisteredWhenDisabled() {
        runner.withPropertyValues("zia.logging.caller.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(FilterRegistrationBean.class));
    }

    @Test
    void filterRegisteredAfterSecurityChainWhenEnabled() {
        runner.withPropertyValues("zia.logging.caller.enabled=true", "spring.security.filter.order=42")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(FilterRegistrationBean.class);
                    FilterRegistrationBean<?> registration = ctx.getBean(FilterRegistrationBean.class);
                    assertThat(registration.getFilter()).isInstanceOf(CallerLoggingFilter.class);
                    assertThat(registration.getOrder()).isEqualTo(43);
                });
    }
}
