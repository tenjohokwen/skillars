package com.softropic.skillars.platform.security.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DTO for 2FA toggle requests.
 * Requires password verification for security.
 */
public class Toggle2faDto {

    @NotNull
    private Boolean enabled;

    @NotNull
    @Size(min = 5, max = 100)
    private String password;

    public Toggle2faDto() {}

    public Toggle2faDto(Boolean enabled, String password) {
        this.enabled = enabled;
        this.password = password;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
