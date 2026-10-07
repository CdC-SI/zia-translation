package zas.admin.zia.translation.service.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Logs the identity of the caller (principal username and authorities) for each incoming request.
 * Must run after the Spring Security filter chain so that the security context is populated.
 */
class CallerLoggingFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(CallerLoggingFilter.class);
    private static final String ACTUATOR_PATH = "/actuator";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith(ACTUATOR_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        logCaller(request, SecurityContextHolder.getContext().getAuthentication());
        chain.doFilter(request, response);
    }

    private static void logCaller(HttpServletRequest request, Authentication authentication) {
        if (!LOG.isInfoEnabled()) {
            return;
        }
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            LOG.info("Caller [method={}, uri={}, remoteAddr={}] anonymous",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            return;
        }
        LOG.info("Caller [method={}, uri={}, remoteAddr={}] username={}, authorities={}",
                request.getMethod(), request.getRequestURI(), request.getRemoteAddr(),
                username(authentication), authorities(authentication));
    }

    private static String username(Authentication authentication) {
        if (authentication.getPrincipal() instanceof UserDetails userDetails) {
            return userDetails.getUsername();
        }
        return authentication.getName();
    }

    private static List<String> authorities(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
    }
}
