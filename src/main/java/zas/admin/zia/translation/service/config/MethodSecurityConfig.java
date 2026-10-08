package zas.admin.zia.translation.service.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Enables {@code @PreAuthorize} checks only when the ZAS security library is present,
 * since it is the one authenticating callers (Blue token). Without it, callers are anonymous.
 */
@Configuration
@EnableMethodSecurity
@ConditionalOnClass(name = "ch.admin.zas.common.security.config.BlueGatewayJwtSecurityConfig")
class MethodSecurityConfig {
}
