package com.softropic.skillars.platform.security.api;


import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record KeyAndPasswordDto(@NotNull @Size(min = 2, max = 25) String key,
                                @NotNull @Size(min = 5, max = 50) String password) {
}
