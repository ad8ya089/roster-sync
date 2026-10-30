package com.moveinsync.rostersync.application.auth;

import com.moveinsync.rostersync.api.error.ApiException;
import com.moveinsync.rostersync.api.error.ErrorCode;
import com.moveinsync.rostersync.infrastructure.persistence.AppUserRepository;
import com.moveinsync.rostersync.infrastructure.persistence.TenantRepository;
import com.moveinsync.rostersync.infrastructure.security.CurrentUser;
import com.moveinsync.rostersync.infrastructure.security.JwtTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    // Fixed BCrypt strength-10 hash: unknown accounts still perform exactly one expensive match.
    static final String DUMMY_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);
    private final AppUserRepository users;
    private final TenantRepository tenants;
    private final PasswordEncoder passwords;
    private final JwtTokenService tokens;

    public AuthService(AppUserRepository users, TenantRepository tenants, PasswordEncoder passwords, JwtTokenService tokens) {
        this.users = users;
        this.tenants = tenants;
        this.passwords = passwords;
        this.tokens = tokens;
    }

    @Transactional(readOnly = true)
    public LoginResult login(String username, String password) {
        var account = users.findByUsername(username);
        boolean matches = passwords.matches(password, account.map(user -> user.getPasswordHash()).orElse(DUMMY_HASH));
        if (account.isEmpty() || !matches || !account.get().isEnabled()) throw denied();
        var user = account.get();
        if (!user.getRole().hasRequiredEmployeeId(user.getEmployeeExternalId())) throw denied();
        var tenant = tenants.findByTenantId(user.getTenantId()).orElseThrow(AuthService::denied);
        var identity = new CurrentUser(user.getUsername(), user.getTenantId(), tenant.getCode(), user.getRole(), user.getEmployeeExternalId());
        return new LoginResult(tokens.issue(identity), "Bearer", tokens.expiresInSeconds(), user.getRole().name(), tenant.getCode());
    }

    private static ApiException denied() {
        // Supplied usernames can themselves contain PII; do not log untrusted credentials.
        LOG.warn("auth.login.failed");
        return new ApiException(ErrorCode.UNAUTHENTICATED) { };
    }

    public record LoginResult(String accessToken, String tokenType, long expiresIn, String role, String tenant) {
        @Override public String toString() { return "LoginResult[role=" + role + ", tenant=" + tenant + "]"; }
    }
}
