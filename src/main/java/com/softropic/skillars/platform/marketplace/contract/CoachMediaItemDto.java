package com.softropic.skillars.platform.marketplace.contract;

import java.math.BigDecimal;
import java.util.UUID;

public record CoachMediaItemDto(UUID id, String fileUrl, String mediaType, int displayOrder) {}
