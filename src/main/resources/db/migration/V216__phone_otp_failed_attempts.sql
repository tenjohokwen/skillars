-- S-14: hard cap on wrong phone-OTP guesses per token. Persisted on the token row so a restart (which
-- empties the in-memory rate-limit buckets) cannot reset it; the token is invalidated at the cap and a
-- resend issues a fresh row. Existing rows start at 0.
ALTER TABLE main.phone_otp_tokens
    ADD COLUMN failed_attempts integer NOT NULL DEFAULT 0;
