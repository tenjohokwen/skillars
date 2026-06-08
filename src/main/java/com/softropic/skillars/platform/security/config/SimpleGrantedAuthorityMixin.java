package com.softropic.skillars.platform.security.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Adapter for deserializing {@link org.springframework.security.core.authority.SimpleGrantedAuthority}
 */
@SuppressWarnings("PMD")
public abstract class SimpleGrantedAuthorityMixin {

    private final String role;

    @JsonCreator
    public SimpleGrantedAuthorityMixin(@JsonProperty("role") String role) {
        this.role = role;
    }

    public String getRole() {
        return role;
    }

}
