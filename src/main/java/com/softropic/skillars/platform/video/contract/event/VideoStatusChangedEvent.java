package com.softropic.skillars.platform.video.contract.event;

import com.softropic.skillars.platform.video.contract.OperationalState;
import java.util.UUID;

public record VideoStatusChangedEvent(UUID videoId, OperationalState newState) {}
