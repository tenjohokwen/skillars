package com.softropic.skillars.platform.security.repo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-172 AC1: sign-in lowercases the typed id and matches {@code login} exactly, and V231's
 * {@code lower()} unique indexes treat case variants as one address, so every write of an email-shaped
 * identity must store it lowercased. The entity is the one place every registration and change path passes.
 */
class UserIdentityCaseTest {

    @Test
    void setEmail_storesLowercase() {
        User user = new User();
        user.setEmail("Anna.Smith@Example.COM");
        assertThat(user.getEmail()).isEqualTo("anna.smith@example.com");
    }

    @Test
    void setLogin_storesLowercase() {
        User user = new User();
        user.setLogin("Anna.Smith@Example.COM");
        assertThat(user.getLogin()).isEqualTo("anna.smith@example.com");
    }

    @Test
    void nullsStayNull() {
        User user = new User();
        user.setEmail(null);
        user.setLogin(null);
        assertThat(user.getEmail()).isNull();
        assertThat(user.getLogin()).isNull();
    }
}
