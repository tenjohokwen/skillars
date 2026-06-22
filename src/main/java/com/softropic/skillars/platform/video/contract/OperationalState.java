package com.softropic.skillars.platform.video.contract;

public enum OperationalState {
    UPLOADING,
    PROCESSING,   // backward compat — upload success received, moderation not yet started
    SCANNING,     // under content moderation (Arachnid + VideoIntel + minor gate)
    TRANSCODING,  // moderation passed, Bunny encoding in progress
    READY,        // transcoding complete, playable
    LOCKED,       // content violation or lifecycle lock
    HIDDEN,       // minor safety gate: awaiting parent approval (Story 6.6)
    FAILED,
    DELETED
}
