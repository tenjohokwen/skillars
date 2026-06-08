package com.softropic.skillars.platform.security.api.dto;

import com.softropic.skillars.infrastructure.validation.CamPhone;
import jakarta.validation.constraints.NotNull;

/**
 * DTO for phone number update requests.
 * Validates phone number format using Cameroon mobile validation.
 */
public class ChangePhoneDto {

    @NotNull
    @CamPhone
    private String phone;

    public ChangePhoneDto() {}

    public ChangePhoneDto(String phone) {
        this.phone = phone;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }
}
