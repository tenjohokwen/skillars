package com.softropic.skillars.platform.video.contract;

import java.util.UUID;

public record ConfirmUploadResponse(UUID videoId, OperationalState operationalState) {}
