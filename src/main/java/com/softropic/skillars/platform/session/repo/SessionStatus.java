package com.softropic.skillars.platform.session.repo;

public final class SessionStatus {
    public static final String ACTIVE = "ACTIVE";
    public static final String DRAFT = "DRAFT";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String ARCHIVED = "ARCHIVED";

    private SessionStatus() {
        throw new UnsupportedOperationException("SessionStatus is a constants class");
    }
}
