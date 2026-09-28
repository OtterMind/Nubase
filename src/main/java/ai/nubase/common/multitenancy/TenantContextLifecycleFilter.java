package ai.nubase.common.multitenancy;

import ai.nubase.common.context.MultiTenancyContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Owns tenant context cleanup outside both the gateway and tenant authentication chains. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantContextLifecycleFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        MultiTenancyContext.clear();
        try {
            filterChain.doFilter(request, response);
        } finally {
            MultiTenancyContext.clear();
        }
    }
}
