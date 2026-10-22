package com.softropic.skillars.platform.development.contract;

public enum PlayerTimelineEventType {
    SESSION_COMPLETED,   // source: BookingCompletedEvent; referenceId = bookingId; referenceModule = "booking"
    RADAR_ASSESSMENT,    // source: RadarEntrySubmittedEvent; referenceId = null; referenceModule = "development"
    PERFORMANCE_REPORT,  // source: ReportGenerationService; referenceId = reportId; referenceModule = "development"
    MILESTONE_REACHED,   // OUT OF SCOPE (Epic 12 decision D4, 2026-10-22, skillars-12-8): no producer. Enum value and UI stub are kept so a future story can add one without a migration; no milestone concept exists in platform/
    HOMEWORK_ASSIGNED,   // source: HomeworkAssignedEvent (session module); referenceId = bookingId; referenceModule = "booking"; coach_id column stays NULL (never feeds the access clock) -- skillars-12-8
    VIDEO_UPLOADED,      // OUT OF SCOPE (Epic 12 decision D4, 2026-10-22, skillars-12-8): no producer. Enum value and UI stub are kept so a future story can add one without a migration; VideoUploadedEvent carries no player or video type
    PAYMENT_RECEIVED,    // OUT OF SCOPE (Epic 12 decision D4, 2026-10-22, skillars-12-8): no producer. Enum value and UI stub are kept so a future story can add one without a migration; would expose a parent's payments to coaches
    REVIEW_LEFT          // OUT OF SCOPE (Epic 12 decision D4, 2026-10-22, skillars-12-8): no producer. Enum value and UI stub are kept so a future story can add one without a migration; a parent's review of one coach does not belong on a shared timeline
}
