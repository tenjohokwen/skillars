package com.softropic.skillars.platform.reviews.contract;

/**
 * Why a review is sitting in {@code UNDER_REVIEW}. Stored as a string in {@code held_reason}
 * (varchar(20), no CHECK constraint), so a new constant needs no migration — keep names at most
 * 20 characters.
 *
 * <p>{@code MODERATION_TIMEOUT}: the review stayed {@code PENDING} past the grace window because
 * its async moderation verdict never landed; {@code ReviewModerationSweeper} held it for a human.
 * (A {@code MANUAL} constant used to exist here but no code path ever wrote it; removed.)
 */
public enum HeldReason {
    GEMINI_UNCERTAIN, GEMINI_FAILURE, FLAG_THRESHOLD, MODERATION_TIMEOUT
}
