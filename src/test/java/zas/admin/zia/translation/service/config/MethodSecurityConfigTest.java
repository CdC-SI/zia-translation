package zas.admin.zia.translation.service.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MethodSecurityConfigTest {
    private static final String ZAS_SECURITY_CLASS =
            "ch.admin.zas.common.security.config.BlueGatewayJwtSecurityConfig";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MethodSecurityConfig.class);

    @Test
    void methodSecurityDisabledWithoutZasSecurityLibrary() {
        runner.withClassLoader(new FilteredClassLoader(ZAS_SECURITY_CLASS))
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(MethodSecurityConfig.class);
                    assertThat(ctx).doesNotHaveBean(AuthorizationManagerBeforeMethodInterceptor.class);
                });
    }

    @Test
    void methodSecurityEnabledWithZasSecurityLibrary() {
        assumeTrue(ClassUtils.isPresent(ZAS_SECURITY_CLASS, getClass().getClassLoader()),
                "zas-security not on classpath (run with -Pinternal)");

        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(MethodSecurityConfig.class);
            assertThat(ctx).hasBean("preAuthorizeAuthorizationMethodInterceptor");
        });
    }
}
