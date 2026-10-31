package com.softropic.skillars.platform.filestorage.repo;

/**
 * S-18: abuse-media (Project Arachnid hash match) state of an uploaded object. Only {@link #NOT_REQUIRED} and
 * {@link #CLEAN} objects are ever handed to anyone other than the uploader; everything else fails closed.
 */
public enum ScanStatus {
    /** Not scanned: a non-image, a row from before scanning existed, or scanning is switched off. Servable. */
    NOT_REQUIRED(true),
    /** Awaiting a verdict (not yet scanned, or the scanner was unreachable so far). Withheld, retried. */
    PENDING(false),
    /** Scanned, no match. Servable. */
    CLEAN(true),
    /** Hash match. Withheld; the bytes are kept for the admin / lawful-reporting process. */
    FLAGGED(false),
    /** Another object of an uploader who had a match. Withheld until an admin reviews it. */
    QUARANTINED(false);

    private final boolean servable;

    ScanStatus(boolean servable) {
        this.servable = servable;
    }

    public boolean isServable() {
        return servable;
    }
}
