package com.softropic.skillars.platform.video.contract.exception;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.platform.video.contract.VideoErrorCode;

import java.util.Map;
import java.util.UUID;

/**
 * The video is not in the operational state a requested transition requires.
 *
 * <p>skillars-deferred-91 code review, decision D11. AC17 mapped {@code IllegalStateException} to
 * {@code 500} on the premise that "an audit of every request-reachable
 * {@code throw new IllegalStateException} confirmed none legitimately means 409". Sites such as
 * {@code VideoLifecycleService.markPurged} and {@code VideoService.retryUpload}'s FAILED claim falsify
 * that: they are pure client-side races — the client needs a 409 so it can re-fetch and retry, and 5xx
 * alerting should not see them. (The parental-approval sites that originally motivated this class
 * were removed with the approval flow — ADR "Minors never upload", 2026-10-28.)
 */
public class VideoStateConflictException extends ApplicationException {

    public VideoStateConflictException(UUID videoId, String expectedState, String actualState) {
        super("Video is not in the state this operation requires",
              Map.of("videoId", String.valueOf(videoId),
                     "expectedState", expectedState,
                     "actualState", String.valueOf(actualState)),
              VideoErrorCode.VIDEO_STATE_CONFLICT);
    }
}
