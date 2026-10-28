package com.softropic.skillars.platform.marketplace.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** feat/coach-workflow-gaps D9: links an already-confirmed {@code coach_gallery/...} storage key to the gallery. */
public record CoachGalleryItemRequest(@NotBlank @Size(max = 512) String key) {}
