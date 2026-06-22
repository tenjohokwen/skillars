package com.softropic.skillars.infrastructure.arachnid;

public interface ArachnidClient {
    /**
     * Submits a video for CSAM hash check.
     * @param mediaUrl a short-lived URL for the raw video (before transcoding)
     * @return scan result — never throws on clean result; throws ArachnidException if unavailable
     */
    ArachnidScanResult scan(String mediaUrl);
}
