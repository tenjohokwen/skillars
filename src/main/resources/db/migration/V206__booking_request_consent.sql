-- fix/booking-request-consent: a coach can no longer accept a booking they created themselves, and two
-- concurrent accept-all calls on one batch serialise.
--
-- 1. booking.bookings.proposed_by_coach: TRUE for a booking the coach created for the family
--    ("repeat next week", BookingDuplicationService). Such a booking stays REQUESTED until the PARENT
--    confirms it (accept + charge) or declines it. BookingService.acceptAndInitiatePayment refuses the
--    coach's accept on it (booking.parentConfirmationRequired) and the parent's confirm on any other
--    booking. FALSE for every existing row: parent-made requests, and coach duplicates made before this
--    migration, which the coach could already accept (the H3 gap this closes going forward).
--
-- 2. booking_batches_status_check widened with ACCEPTING (9 chars, fits varchar(20)):
--    BookingBatchService.acceptAll claims the batch PENDING -> ACCEPTING under the batch row lock in its
--    own short transaction before the per-booking accepts, so a second, concurrent accept-all sees a
--    non-PENDING batch and is refused (booking.batchAlreadyProcessed) instead of splitting the batch's
--    bookings and publishing a second settlement event. Rebuilt as the union: V138's 4 values + this one.
--    MERGE NOTE: any other branch widening this CHECK must re-union.
--
-- Migration conventions (docs/deployment/migration-conventions.md), mirroring V192:
--  - Rule 1 (additive): a NOT NULL column with a constant default is catalog-only on Postgres 11+. Old code
--    during a rolling deploy never writes it (FALSE = the old behaviour).
--  - NOT VALID / VALIDATE skipped (migration-lint marker below): booking_batches is small with no bulk-insert
--    path, and prod is not live, so the validating scan touches dev/uat only.
--  - Rule 5 (widen one release before the first write): prod is not live, so - as at V183/V189/V192 - the
--    widening and its first write ship together.
--  - Rule 7: lock_timeout so the brief ACCESS EXCLUSIVE locks fail fast instead of queueing.
SET LOCAL lock_timeout = '5s';

ALTER TABLE booking.bookings
    ADD COLUMN IF NOT EXISTS proposed_by_coach boolean NOT NULL DEFAULT false;

ALTER TABLE booking.booking_batches
    DROP CONSTRAINT IF EXISTS booking_batches_status_check,
    -- migration-lint: allow-validating-constraint prod not live (dev/uat data only, as V192), one row per
    -- multi-session request, no bulk-insert path; a fast validating CHECK swap.
    ADD CONSTRAINT booking_batches_status_check CHECK (((status)::text = ANY ((ARRAY[
        'PENDING'::character varying,
        'ACCEPTING'::character varying,
        'FULLY_ACCEPTED'::character varying,
        'PARTIALLY_ACCEPTED'::character varying,
        'DECLINED'::character varying
    ])::text[])));
