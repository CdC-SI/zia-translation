package zas.admin.zia.translation.service.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;

import static org.assertj.core.api.Assertions.assertThat;

class MethodSecurityConfigTest {

    @Test
    void methodSecurityDisabledWithoutZasSecurityLibrary() {
        new ApplicationContextRunner()
                .withUserConfiguration(MethodSecurityConfig.class)
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(MethodSecurityConfig.class);
                    assertThat(ctx).doesNotHaveBean(AuthorizationManagerBeforeMethodInterceptor.class);
                });
    }
}
