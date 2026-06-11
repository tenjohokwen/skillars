package com.softropic.skillars.platform.config.contract;

import jakarta.validation.constraints.NotBlank;

public record UpdateConfigRequest(@NotBlank String value) {
}
