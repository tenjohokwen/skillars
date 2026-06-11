package com.softropic.skillars.platform.security.contract;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CoachRegistrationRequest(
    @NotBlank @Size(max = 100) String firstName,
    @NotBlank @Size(max = 100) String lastName,
    @Email @NotBlank String email,
    @NotBlank @Size(min = 8) String password,
    @NotBlank @Size(min = 7, max = 20) @Pattern(regexp = "\\+?[\\d\\s\\-().]{7,20}") String phone,
    @Size(min = 2, max = 5) String langKey
) {}
