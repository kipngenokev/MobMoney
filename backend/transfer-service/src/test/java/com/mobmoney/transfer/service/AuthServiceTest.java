package com.mobmoney.transfer.service;

import com.mobmoney.transfer.domain.AppUser;
import com.mobmoney.transfer.dto.Dtos.LoginRequest;
import com.mobmoney.transfer.dto.Dtos.LoginResponse;
import com.mobmoney.transfer.exception.ApiExceptions.ApiException;
import com.mobmoney.transfer.repository.AppUserRepository;
import com.mobmoney.transfer.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock AppUserRepository users;
    @Mock JwtService jwtService;

    PasswordEncoder encoder = new BCryptPasswordEncoder();
    AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(users, encoder, jwtService);
    }

    @Test
    void issuesTokenForValidCredentials() {
        AppUser user = AppUser.builder().username("alice")
                .passwordHash(encoder.encode("secret")).roles("USER").build();
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        when(jwtService.issueToken(eq("alice"), any())).thenReturn("signed.jwt.token");
        when(jwtService.getTtlSeconds()).thenReturn(3600L);

        LoginResponse response = authService.login(new LoginRequest("alice", "secret"));

        assertThat(response.accessToken()).isEqualTo("signed.jwt.token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.username()).isEqualTo("alice");
    }

    @Test
    void rejectsWrongPassword() {
        AppUser user = AppUser.builder().username("alice")
                .passwordHash(encoder.encode("secret")).roles("USER").build();
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("alice", "wrong")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid username or password");
    }

    @Test
    void rejectsUnknownUserWithSameGenericError() {
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost", "secret")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid username or password");
    }

    @Test
    void parsesMultipleRoles() {
        AppUser user = AppUser.builder().username("admin")
                .passwordHash(encoder.encode("secret")).roles("USER, ADMIN").build();
        when(users.findByUsername("admin")).thenReturn(Optional.of(user));
        when(jwtService.issueToken(eq("admin"), eq(List.of("USER", "ADMIN")))).thenReturn("t");
        when(jwtService.getTtlSeconds()).thenReturn(3600L);

        LoginResponse response = authService.login(new LoginRequest("admin", "secret"));
        assertThat(response.accessToken()).isEqualTo("t");
    }
}
