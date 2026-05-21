package com.mobmoney.transfer.service;

import com.mobmoney.transfer.domain.AppUser;
import com.mobmoney.transfer.dto.Dtos.LoginRequest;
import com.mobmoney.transfer.dto.Dtos.LoginResponse;
import com.mobmoney.transfer.exception.ApiExceptions.ApiException;
import com.mobmoney.transfer.repository.AppUserRepository;
import com.mobmoney.transfer.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(AppUserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(LoginRequest request) {
        AppUser user = users.findByUsername(request.username())
                // Same generic error whether the user is missing or the password is wrong,
                // so the endpoint does not reveal which usernames exist.
                .orElseThrow(AuthService::invalidCredentials);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }

        List<String> roles = Arrays.stream(user.getRoles().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        String token = jwtService.issueToken(user.getUsername(), roles);
        return new LoginResponse(token, "Bearer", jwtService.getTtlSeconds(), user.getUsername());
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }
}
