package com.softropic.skillars.platform.reviews.contract;

import com.softropic.skillars.infrastructure.exception.ErrorCode;

public enum ReviewErrorCode implements ErrorCode {
    NO_RECENT_SESSION("reviews.noRecentSession"),
    ALREADY_SUBMITTED("reviews.alreadySubmitted"),
    BODY_TOO_LONG("reviews.bodyTooLong"),
    RESPONSE_TOO_LONG("reviews.responseTooLong"),
    UPDATE_TOO_SOON("reviews.updateTooSoon"),
    EDIT_NOT_PERMITTED("reviews.editNotPermitted"),
    REVIEW_NOT_APPROVED("reviews.reviewNotApproved"),
    RESPONSE_ALREADY_SUBMITTED("reviews.responseAlreadySubmitted"),
    COACH_MISMATCH("reviews.coachMismatch"),
    AUTHOR_MISMATCH("reviews.authorMismatch"),
    AUTHOR_ROLE_NOT_ALLOWED("reviews.authorRoleNotAllowed"),
    CANNOT_FLAG_OWN_REVIEW("reviews.cannotFlagOwnReview"),
    CANNOT_FLAG_OWN_COACHED_REVIEW("reviews.cannotFlagOwnCoachedReview"),
    ALREADY_FLAGGED("reviews.alreadyFlagged"),
    REVIEW_NOT_FOUND("reviews.reviewNotFound");

    private final String code;

    ReviewErrorCode(String code) { this.code = code; }

    @Override
    public String getErrorCode() { return code; }
}
