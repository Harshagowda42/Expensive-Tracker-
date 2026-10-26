package com.example.springbootdemo;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserAccountService {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public UserAccountService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    public RegistrationResult register(String username, String password) {
        String validationError = validate(username, password);
        if (validationError != null) {
            return new RegistrationResult(null, validationError, false);
        }

        String normalizedUsername = normalize(username);
        try {
            jdbcTemplate.update(
                    "INSERT INTO app_users (username, password_hash, enabled) VALUES (?, ?, TRUE)",
                    normalizedUsername,
                    passwordEncoder.encode(password));
            return new RegistrationResult(normalizedUsername, null, false);
        } catch (DuplicateKeyException exception) {
            return new RegistrationResult(null, "That username is already taken.", true);
        }
    }

    public void createConfiguredAccount(String username, String password) {
        if (username.isBlank() && password.isBlank()) {
            return;
        }
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException(
                    "Set both APP_AUTH_USERNAME and APP_AUTH_PASSWORD, or leave both unset.");
        }

        String validationError = validate(username, password);
        if (validationError != null) {
            throw new IllegalStateException("Configured initial account is invalid: " + validationError);
        }

        try {
            jdbcTemplate.update(
                    "INSERT INTO app_users (username, password_hash, enabled) VALUES (?, ?, TRUE)",
                    normalize(username),
                    passwordEncoder.encode(password));
        } catch (DuplicateKeyException exception) {
            // Preserve an existing account and its password when restarting.
        }
    }

    private String validate(String username, String password) {
        if (username == null || !username.trim().matches("[A-Za-z0-9._-]{3,32}")) {
            return "Username must be 3 to 32 characters and use only letters, numbers, dots, underscores, or hyphens.";
        }
        if (password == null) {
            return "Password must be at least 10 characters.";
        }
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (password.length() < 10 || passwordBytes > 72) {
            return "Password must be at least 10 characters and no more than 72 UTF-8 bytes.";
        }
        return null;
    }

    private String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    public record RegistrationResult(String username, String error, boolean usernameTaken) {
        public boolean succeeded() {
            return error == null;
        }
    }
}
