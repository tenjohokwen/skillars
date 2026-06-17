package com.softropic.skillars.platform.session.contract;

import java.util.UUID;

public record VideoPhysicalDeletionEvent(UUID videoId, UUID drillId) {}
