package com.softropic.skillars.platform.security.api.dto;

/**
 * S-27: what the SPA needs to render the age-verification card.
 *
 * @param applicable             the caller is a self-registered player (anyone else never sees the flow)
 * @param enabled                Stripe Identity verification is switched on and configured; when false only the
 *                               fallback (chat after a completed session) applies and there is nothing to start
 * @param state                  NOT_STARTED, PENDING (submitted / being checked), FAILED (try again), VERIFIED or
 *                               BLOCKED (verified under 18)
 * @param attemptsRemainingToday how many more sessions the daily cap allows
 */
public record AgeVerificationStatusResponse(boolean applicable, boolean enabled, String state,
                                            int attemptsRemainingToday) {
}
