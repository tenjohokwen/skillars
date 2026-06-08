package com.softropic.skillars.platform.security.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DTO for password change requests.
 * Requires current password for verification and new password to set.
 */
public class ChangePasswordRequestDto {

    @NotNull
    @Size(min = 5, max = 100)
    private String currentPassword;

    @NotNull
    @Size(min = 5, max = 100)
    private String newPassword;

    public ChangePasswordRequestDto() {}

    public ChangePasswordRequestDto(String currentPassword, String newPassword) {
        this.currentPassword = currentPassword;
        this.newPassword = newPassword;
    }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
