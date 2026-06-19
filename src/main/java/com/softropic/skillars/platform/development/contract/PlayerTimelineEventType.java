package com.softropic.skillars.platform.development.contract;

public enum PlayerTimelineEventType {
    SESSION_COMPLETED,   // source: BookingCompletedEvent; referenceId = bookingId; referenceModule = "booking"
    RADAR_ASSESSMENT,    // source: RadarEntrySubmittedEvent; referenceId = null; referenceModule = "development"
    PERFORMANCE_REPORT,  // source: ReportGenerationService; referenceId = reportId; referenceModule = "development"
    MILESTONE_REACHED,   // deferred — Story 5.6+; placeholder per Epic 5 AC5
    HOMEWORK_ASSIGNED,   // deferred — placeholder for Story 6+
    VIDEO_UPLOADED,      // deferred — Story 6
    PAYMENT_RECEIVED,    // deferred — Story 7
    REVIEW_LEFT          // deferred — Story 9
}
