-- skillars-deferred-106 AC11 cutover: switching from Stripe destination charges (coach paid at
-- capture, net via transfer_data.destination + application_fee_amount on the PaymentIntent) to
-- separate charges & transfers (coach paid on completion via a platform-initiated Transfer, this
-- story). Bookings CAPTURED before this deploy already paid the coach at capture, so inserting a
-- payment.coach_payouts row in RELEASED for every currently-CAPTURED payment.booking_payments row
-- makes AC7.1's booking_id PK + PENDING_RELEASE-only status gate suppress a second transfer for
-- them: CoachPayoutTransferHandler / the completion-enqueue listener both no-op on an existing
-- non-PENDING_RELEASE row.
--
-- Skillars has NO production system (docs/deployment/migration-conventions.md Grandfathering), so a
-- one-shot backfill is acceptable here. A live deployment would instead ship a payout_model marker
-- column one release ahead and enqueue payouts only for post-cutover bookings. This project runs
-- Flyway migrations inside a single transaction, so a genuine per-chunk-commit ctid loop is not
-- available in a plain .sql migration; the backfill is a single INSERT ... SELECT. It touches only
-- already-terminal (CAPTURED) rows on a table that is not hot for writes, and re-runs are a no-op
-- via ON CONFLICT (booking_id) DO NOTHING. This is an INSERT ... SELECT (not UPDATE/DELETE/TRUNCATE),
-- so MigrationLint.Rule.UNBATCHED_DML does not bind; the "no production system" fact is what makes
-- the un-chunked form acceptable rather than the lint's silence.
--
-- released_at / release_after are set to captured_at: under the old destination charge the coach was
-- paid at capture, so that is the honest "released" instant for revenue reporting (AC12 dates coach
-- revenue by coach_payouts.released_at). commission_rate is NULL on pre-V133 booking_payments rows,
-- so the net falls back to the live platform.commission.rate for these legacy rows only — the same
-- value that produced their original application_fee_amount.

SET lock_timeout = '5s';

INSERT INTO payment.coach_payouts (
    booking_id, coach_id, coach_stripe_account_id,
    gross_amount, commission_amount, net_amount, currency,
    status, stripe_transfer_id, stripe_transfer_reversal_id,
    release_after, released_at, reversed_at,
    attempts, last_error, created_at, updated_at)
SELECT
    bp.booking_id,
    b.coach_id,
    NULL,
    (bp.stripe_charged + bp.credit_debited) AS gross_amount,
    round((bp.stripe_charged + bp.credit_debited)
        * COALESCE(bp.commission_rate,
            (SELECT value::numeric FROM main.platform_config WHERE key = 'platform.commission.rate')), 2)
        AS commission_amount,
    (bp.stripe_charged + bp.credit_debited)
        - round((bp.stripe_charged + bp.credit_debited)
            * COALESCE(bp.commission_rate,
                (SELECT value::numeric FROM main.platform_config WHERE key = 'platform.commission.rate')), 2)
        AS net_amount,
    COALESCE((SELECT value FROM main.platform_config WHERE key = 'platform.payment.currency'), 'eur') AS currency,
    'RELEASED',
    NULL,
    NULL,
    COALESCE(bp.captured_at, bp.reserved_at, now()) AS release_after,
    COALESCE(bp.captured_at, bp.reserved_at, now()) AS released_at,
    NULL,
    0,
    NULL,
    now(),
    now()
FROM payment.booking_payments bp
JOIN booking.bookings b ON b.id = bp.booking_id
WHERE bp.status = 'CAPTURED'
ON CONFLICT (booking_id) DO NOTHING;
