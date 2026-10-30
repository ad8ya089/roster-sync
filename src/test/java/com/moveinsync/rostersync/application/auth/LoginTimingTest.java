package com.moveinsync.rostersync.application.auth;

import com.moveinsync.rostersync.api.error.ApiException;
import com.moveinsync.rostersync.api.error.ErrorCode;
import com.moveinsync.rostersync.domain.model.Role;
import com.moveinsync.rostersync.infrastructure.persistence.*;
import com.moveinsync.rostersync.infrastructure.security.JwtTokenService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginTimingTest {
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final BCryptPasswordEncoder passwords = spy(new BCryptPasswordEncoder(10));
    private final JwtTokenService tokens = mock(JwtTokenService.class);
    private final AuthService auth = new AuthService(users, tenants, passwords, tokens);

    @Test void unknownAndWrongPasswordEachPerformOneRealBcryptMatch() {
        var user = account(true, Role.TRANSPORT_ADMIN, null);
        when(users.findByUsername("unknown")).thenReturn(Optional.empty());
        when(users.findByUsername("known")).thenReturn(Optional.of(user));
        denied("unknown", "wrong");
        verify(passwords).matches("wrong", AuthService.DUMMY_HASH);
        denied("known", "wrong");
        verify(passwords).matches("wrong", user.getPasswordHash());
        verify(passwords, times(2)).matches(anyString(), anyString());
        verifyNoInteractions(tenants, tokens);
    }

    @Test void disabledAccountAndEmployeeWithoutIdFailGenericallyEvenWithCorrectPassword() {
        var disabled = account(false, Role.TRANSPORT_ADMIN, null);
        var missingId = account(true, Role.EMPLOYEE, null);
        when(users.findByUsername("disabled")).thenReturn(Optional.of(disabled));
        when(users.findByUsername("missing-id")).thenReturn(Optional.of(missingId));
        denied("disabled", "correct");
        denied("missing-id", "correct");
        verify(passwords, times(2)).matches(eq("correct"), anyString());
        verifyNoInteractions(tenants, tokens);
    }

    private AppUserEntity account(boolean enabled, Role role, String employeeId) {
        var user = mock(AppUserEntity.class);
        String hash = passwords.encode("correct");
        when(user.getPasswordHash()).thenReturn(hash);
        when(user.getRole()).thenReturn(role);
        when(user.getEmployeeExternalId()).thenReturn(employeeId);
        when(user.isEnabled()).thenReturn(enabled);
        return user;
    }
    private void denied(String username, String password) {
        assertThatThrownBy(() -> auth.login(username, password)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }
}
