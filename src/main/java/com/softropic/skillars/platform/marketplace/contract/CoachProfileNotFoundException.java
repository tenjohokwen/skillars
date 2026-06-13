package com.softropic.skillars.platform.marketplace.contract;

import java.util.UUID;

public class CoachProfileNotFoundException extends RuntimeException {
    public CoachProfileNotFoundException(UUID coachId) {
        super("Coach profile not found or not published: " + coachId);
    }
}
