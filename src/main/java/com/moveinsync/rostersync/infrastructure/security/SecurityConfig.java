package com.moveinsync.rostersync.infrastructure.security;

import com.moveinsync.rostersync.domain.model.Role;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.web.OAuth2ProtectedResourceMetadataFilter;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(10); }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokenService tokens, JsonMapper mapper) throws Exception {
        var unauthorized = new RestAuthenticationEntryPoint(mapper);
        var forbidden = new RestAccessDeniedHandler(mapper);
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of(new SimpleGrantedAuthority(
                "ROLE_" + Role.valueOf(jwt.getClaimAsString("role")).name())));

        // Bearer tokens only: no session, cookies, saved requests, form login or Basic authentication.
        var chain = http.csrf(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(access -> access
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // No form/Basic/logout filters exist. Let MVC return 404 for their old paths.
                        .requestMatchers("/login", "/logout").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.decoder(tokens.decoder()).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                // Construct here, rather than as a servlet Filter bean, to prevent double registration.
                .addFilterAfter(new TenantContextFilter(), BearerTokenAuthenticationFilter.class)
                .build();
        // Security 7.1 adds public metadata automatically and exposes no disable switch.
        // Our public-route contract is only login and health, so omit that filter from the chain.
        return new DefaultSecurityFilterChain(chain.getRequestMatcher(), chain.getFilters().stream()
                .filter(filter -> !(filter instanceof OAuth2ProtectedResourceMetadataFilter)).toList());
    }
}
