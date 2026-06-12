package com.softropic.skillars.platform.marketplace.contract;

import java.util.UUID;

public record ProfileBuilderStepResponse(UUID coachId, int stepSaved, int nextStep) {}
