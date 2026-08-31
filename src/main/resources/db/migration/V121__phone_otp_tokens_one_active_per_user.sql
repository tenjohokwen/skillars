-- Story skillars-deferred-88 AC10: DB backstop for the "one live phone OTP per user" invariant.
--
-- CoachRegistrationService / PlayerRegistrationService / ParentRegistrationService all call
-- otpTokenRepository.deleteByUserIdAndUsedFalse(userId) immediately before inserting a new
-- PhoneOtpToken, so the invariant holds in application code. Nothing enforced it at the DB, and two
-- concurrent resend-OTP calls for the same user (ParentRegistrationService.resendPhoneOtp is the
-- reachable path — no email-token @Version to serialise them) can each delete-then-insert under
-- READ COMMITTED and both commit two active rows.
--
-- Partial predicate is `used = false` only — NOT `expires_at > now()` (now() is not IMMUTABLE and is
-- rejected in an index predicate). An expired-but-unused row still counts as "the one active row";
-- the services' delete-before-insert clears it first, so this is correct.
--
-- Non-CONCURRENTLY: Flyway runs migrations in a transaction and the table is tiny — same accepted
-- class as every other index in this codebase's history (the skillars-deferred-84 online-migration
-- convention item stays open and is not in scope here).

-- Defensive dedup so CREATE UNIQUE INDEX cannot fail on legacy / already-violating data. @Tsid ids
-- are time-sorted, so keeping the highest id per user keeps the newest unused row (matches
-- skillars-deferred-57's guidance that a migration has no remediation path for pre-existing
-- violations).
DELETE FROM main.phone_otp_tokens a
USING main.phone_otp_tokens b
WHERE a.user_id = b.user_id
  AND a.used = false
  AND b.used = false
  AND a.id < b.id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_pot_one_active_per_user
    ON main.phone_otp_tokens (user_id)
    WHERE used = false;
