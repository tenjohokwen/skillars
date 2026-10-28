package com.softropic.skillars.platform.video.contract.exception;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.platform.video.contract.VideoErrorCode;

import java.util.Map;

/**
 * Minors never upload (ADR 2026-10-28): a player upload (initiate or retry) whose owning PlayerProfile
 * is a minor, a parent-managed profile, or has no date of birth is refused before any quota is reserved
 * or provider asset created. Reaching this is an invariant break — a minor holding a self-registered
 * player account — so the refusal also raises an admin alert.
 */
public class MinorUploadRefusedException extends ApplicationException {

    public MinorUploadRefusedException(String ownerId, String reason) {
        super("Video uploads are not available for this player profile",
              Map.of("ownerId", ownerId, "reason", reason),
              VideoErrorCode.VIDEO_MINOR_UPLOAD_REFUSED);
    }
}
