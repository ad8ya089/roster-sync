package com.moveinsync.rostersync.infrastructure.security;

import com.moveinsync.rostersync.domain.model.Role;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

public class TenantContextFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            // Only verified JWT claims reach this filter; no request header can select a tenant.
            if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken authentication) {
                var jwt = authentication.getToken();
                var user = new CurrentUser(jwt.getSubject(), UUID.fromString(jwt.getClaimAsString("tid")),
                        jwt.getClaimAsString("tcode"), Role.valueOf(jwt.getClaimAsString("role")), jwt.getClaimAsString("emp"));
                TenantContext.set(user);
                MDC.put("tenantId", user.tenantId().toString());
            }
            chain.doFilter(request, response);
        } finally {
            // Servlet threads are reused, including after failures and denied requests.
            TenantContext.clear();
            MDC.remove("tenantId");
        }
    }
}
