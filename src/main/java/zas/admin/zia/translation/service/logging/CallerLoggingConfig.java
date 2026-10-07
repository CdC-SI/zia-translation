package zas.admin.zia.translation.service.logging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the global caller logging filter when {@code zia.logging.caller.enabled=true}
 * (environment variable {@code ZIA_LOGGING_CALLER_ENABLED}).
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
@ConditionalOnProperty(prefix = "zia.logging.caller", name = "enabled", havingValue = "true")
class CallerLoggingConfig {

    @Bean
    FilterRegistrationBean<CallerLoggingFilter> callerLoggingFilter(SecurityProperties securityProperties) {
        FilterRegistrationBean<CallerLoggingFilter> registration = new FilterRegistrationBean<>(new CallerLoggingFilter());
        registration.setName("callerLoggingFilter");
        registration.addUrlPatterns("/*");
        // Run right after the Spring Security filter chain so the SecurityContext is populated
        registration.setOrder(securityProperties.getFilter().getOrder() + 1);
        return registration;
    }
}
