package com.softropic.skillars.platform.reviews.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BlockReviewRequest(@NotBlank @Size(max = 500) String reason) {}
