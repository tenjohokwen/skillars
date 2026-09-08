package com.softropic.skillars.platform.session.repo;

/**
 * The value set of {@code session.sessions.status}. Kept in sync with the DB CHECK constraint
 * {@code sessions_status_check} (V43 → V116: {@code CHECK (status IN ('DRAFT', 'SAVED', 'COMPLETED', 'CANCELLED'))}).
 *
 * <p>Do NOT add {@code ACTIVE}/{@code ARCHIVED} here — those are {@code session.session_templates}
 * statuses (V44), a different table.
 */
public final class SessionStatus {
    public static final String DRAFT = "DRAFT";
    public static final String SAVED = "SAVED";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    private SessionStatus() {
        throw new UnsupportedOperationException("SessionStatus is a constants class");
    }
}
