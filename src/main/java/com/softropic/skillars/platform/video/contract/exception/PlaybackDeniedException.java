package com.softropic.skillars.platform.video.contract.exception;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.VideoErrorCode;

import java.util.Map;
import java.util.UUID;

public class PlaybackDeniedException extends ApplicationException {

    public PlaybackDeniedException(UUID videoId, String viewerId) {
        super("Playback access denied",
              Map.of("videoId", videoId.toString(), "viewerId", viewerId),
              VideoErrorCode.PLAYBACK_DENIED);
    }

    public PlaybackDeniedException(UUID videoId, String viewerId,
                                   OperationalState operationalState,
                                   AccessState accessState) {
        super("Playback access denied",
              Map.of("videoId", videoId.toString(),
                     "viewerId", viewerId,
                     "operationalState", operationalState.name(),
                     "accessState", accessState.name()),
              VideoErrorCode.PLAYBACK_DENIED);
    }
}
