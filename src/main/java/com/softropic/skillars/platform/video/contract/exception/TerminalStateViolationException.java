package com.softropic.skillars.platform.video.contract.exception;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.platform.video.contract.VideoErrorCode;

import java.util.Map;
import java.util.UUID;

public class TerminalStateViolationException extends ApplicationException {

    public TerminalStateViolationException(UUID videoId, String currentState) {
        super("Operation not permitted in terminal state",
              Map.of("videoId", videoId.toString(), "currentState", currentState),
              VideoErrorCode.TERMINAL_STATE_VIOLATION);
    }
}
