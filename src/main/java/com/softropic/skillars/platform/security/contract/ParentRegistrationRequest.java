package com.softropic.skillars.platform.security.contract;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ParentRegistrationRequest(
    @NotBlank @Size(max = 50) String firstName,
    @NotBlank @Size(max = 50) String lastName,
    @Email @NotBlank String email,
    @NotBlank @Size(min = 8) String password,
    @NotBlank @Pattern(regexp = "^\\+?[\\d][\\d\\s\\-().]{6,18}[\\d]$") String phone,
    @Size(min = 2, max = 5) String langKey
) {}
