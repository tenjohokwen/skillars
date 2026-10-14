package com.softropic.skillars.platform.development.contract;

public record CoachBrandingResponse(
    String logoSignedUrl,    // signed URL for logo preview; null if no logo
    // skillars-deferred-151 AC3: logoSignedUrl is derived and time-limited, with no way for the
    // frontend to resend it as an upload key — a colour-only save with no raw key to resubmit
    // silently cleared any existing logo. Exposing the raw key (a storage path, not a secret — this
    // project already round-trips one through ProfileBuilderStep5Request/CoachProfileSelfResponse's
    // confusingly-named photoUrl field) lets the dialog resend it unchanged.
    String logoKey,          // raw storage key; null if no logo
    String brandColour       // hex colour; null if not set
) {}
