package com.softropic.skillars.platform.development.contract;

public record CoachBrandingResponse(
    String logoSignedUrl,    // signed URL for logo preview; null if no logo
    String brandColour       // hex colour; null if not set
) {}
