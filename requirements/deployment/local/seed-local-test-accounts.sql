-- ============================================================================
-- seed-local-test-accounts.sql
--
-- Promotes already-registered LOCAL accounts to a "paid" state without ever
-- calling Stripe. See local-manual-testing.md in this directory for the full
-- walkthrough and for why each statement is safe.
--
-- PREREQUISITES — this script only *upgrades* accounts, it does not create them:
--   1. Coach registered, email verified, and profile PUBLISHED via the
--      profile builder (publish is what creates marketplace.coach_subscriptions
--      and is what gives the coach a marketplace.coach_profiles row worth
--      seeding against).
--   2. Parent registered and email verified.
--   3. At least one player profile created (parent-created shadow account, or
--      an adult player's self-owned profile).
--
-- USAGE:
--   dcl exec -T postgres psql -U postgres -d skillars \
--     -v coach_email=coach@example.com \
--     -v owner_email=parent@example.com \
--     < requirements/deployment/local/seed-local-test-accounts.sql
--
-- Or edit the \set defaults below and pipe the file in with no -v flags.
-- NEVER run this against UAT or production — it fabricates payment state.
-- ============================================================================

\set ON_ERROR_STOP on

-- Defaults. A -v flag on the psql command line takes precedence over these.
\if :{?coach_email}
\else
  \set coach_email coach@example.com
\endif
\if :{?owner_email}
\else
  \set owner_email parent@example.com
\endif
\if :{?credit_amount}
\else
  \set credit_amount 5000.00
\endif
\if :{?coach_tier}
\else
  \set coach_tier ACADEMY
\endif
\if :{?player_tier}
\else
  \set player_tier PRO
\endif

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Coach passes the booking payment-readiness gate.
--
-- BookingService.createBookingRequest calls paymentGateway.isCoachPaymentReady(),
-- which in StripePaymentGateway is a pure read of this table — no Stripe call.
-- A row with COMPLETE + charges_enabled is all the gate looks at.
--
-- stripe_account_id has a UNIQUE index, so it is derived from the coach id
-- rather than being a fixed literal, to stay unique across several seeded coaches.
-- ---------------------------------------------------------------------------
INSERT INTO payment.coach_stripe_accounts
    (coach_id, stripe_account_id, onboarding_status, charges_enabled, payouts_enabled)
SELECT cp.id,
       'acct_local_' || left(replace(cp.id::text, '-', ''), 16),
       'COMPLETE', true, true
FROM marketplace.coach_profiles cp
JOIN main."user" u ON u.id = cp.user_id
WHERE u.email = :'coach_email'
ON CONFLICT (coach_id) DO UPDATE
    SET onboarding_status = 'COMPLETE',
        charges_enabled   = true,
        payouts_enabled   = true,
        updated_at        = now();

-- ---------------------------------------------------------------------------
-- 2. Coach feature tier.
--
-- Feature gating (skills radar, performance reports, ACADEMY branding, drill
-- video upload, storage/bandwidth quotas) reads marketplace.coach_subscriptions
-- via CoachProfileService.getCoachSubscriptionTier — NOT the payment schema.
-- This is the row that actually unlocks features.
-- ---------------------------------------------------------------------------
INSERT INTO marketplace.coach_subscriptions (coach_id, tier)
SELECT cp.id, :'coach_tier'
FROM marketplace.coach_profiles cp
JOIN main."user" u ON u.id = cp.user_id
WHERE u.email = :'coach_email'
ON CONFLICT (coach_id) DO UPDATE SET tier = EXCLUDED.tier;

-- ---------------------------------------------------------------------------
-- 3. Coach billing row, so the coach subscription page reflects the same tier.
--
-- Purely cosmetic relative to step 2 — this table drives
-- /api/payment/subscriptions/coach/me, not the feature gates. stripe_subscription_id
-- is deliberately left NULL: changeCoachTier/cancelCoachSubscription refuse to
-- run without it, which is what keeps this seed from wandering into Stripe calls.
-- ---------------------------------------------------------------------------
INSERT INTO payment.coach_subscriptions (coach_id, tier, status, current_period_end)
SELECT cp.id, :'coach_tier', 'ACTIVE', now() + interval '1 year'
FROM marketplace.coach_profiles cp
JOIN main."user" u ON u.id = cp.user_id
WHERE u.email = :'coach_email'
ON CONFLICT (coach_id) DO UPDATE
    SET tier               = EXCLUDED.tier,
        status             = 'ACTIVE',
        current_period_end = EXCLUDED.current_period_end,
        updated_at         = now();

-- ---------------------------------------------------------------------------
-- 4. Parent credit wallet.
--
-- THIS IS THE STATEMENT THAT KEEPS BOOKINGS OFF STRIPE ENTIRELY.
-- PaymentLifecycleService.handleCreditBasedBooking computes
--   stripeAmount = max(0, sessionPrice - creditBalance)
-- and only enters the charge branch when stripeAmount > 0. With enough credit,
-- the booking settles to CONFIRMED with no gateway call at all.
--
-- The chk_ledger_amount_sign CHECK only permits a positive amount for
-- BOOKING_REFUND / BOOKING_DEDUCTION_REVERSAL / CASH_OUT_REVERSAL, hence the type.
--
-- The table is append-only (V79 triggers reject UPDATE and DELETE), so there is
-- no ON CONFLICT form here and no way to correct a row. Re-running this file
-- ADDS another credit row rather than replacing the previous one.
-- ---------------------------------------------------------------------------
INSERT INTO payment.parent_credit_ledger (parent_id, amount, type, description)
SELECT u.id, :credit_amount, 'BOOKING_REFUND', 'local manual-test seed'
FROM main."user" u
WHERE u.email = :'owner_email';

-- ---------------------------------------------------------------------------
-- 5. Paid player subscription for every player owned by :owner_email.
--
-- player_profiles.parent_id holds the OWNING user: the parent for a shadow
-- account, or the player themselves for a self-registered adult player. Both
-- cases are covered by this join.
--
-- chk_pps_pro_yearly / chk_pps_semi_pro_yearly require billing_interval =
-- 'YEARLY' for the PRO and SEMI_PRO tiers, so YEARLY is hardcoded. Only ATHLETE
-- may be MONTHLY or QUARTERLY.
--
-- Effect is narrow by design: PlayerSubscriptionQueryAdapter is the only reader,
-- and it drives video retention. It is not a prerequisite for booking.
-- ---------------------------------------------------------------------------
INSERT INTO payment.player_subscriptions
    (player_id, tier, billing_interval, status, current_period_end)
SELECT pp.id, :'player_tier', 'YEARLY', 'ACTIVE', now() + interval '1 year'
FROM main.player_profiles pp
JOIN main."user" u ON u.id = pp.parent_id
WHERE u.email = :'owner_email'
ON CONFLICT (player_id) DO UPDATE
    SET tier               = EXCLUDED.tier,
        billing_interval   = 'YEARLY',
        status             = 'ACTIVE',
        current_period_end = EXCLUDED.current_period_end,
        updated_at         = now();

COMMIT;

-- ---------------------------------------------------------------------------
-- Verification — read back what was seeded.
-- ---------------------------------------------------------------------------
\echo ''
\echo '--- Coach payment readiness + tier ---'
SELECT u.email,
       cp.id                AS coach_id,
       cp.status            AS profile_status,
       mcs.tier             AS feature_tier,
       csa.onboarding_status,
       csa.charges_enabled
FROM main."user" u
JOIN marketplace.coach_profiles cp             ON cp.user_id  = u.id
LEFT JOIN marketplace.coach_subscriptions mcs  ON mcs.coach_id = cp.id
LEFT JOIN payment.coach_stripe_accounts csa    ON csa.coach_id = cp.id
WHERE u.email = :'coach_email';

\echo ''
\echo '--- Parent credit balance (must exceed the coach per-session price) ---'
SELECT u.email,
       COALESCE(SUM(l.amount), 0) AS balance_eur
FROM main."user" u
LEFT JOIN payment.parent_credit_ledger l ON l.parent_id = u.id
WHERE u.email = :'owner_email'
GROUP BY u.email;

\echo ''
\echo '--- Player subscriptions ---'
SELECT pp.id AS player_id, pp.name, ps.tier, ps.billing_interval, ps.status, ps.current_period_end
FROM main.player_profiles pp
JOIN main."user" u                        ON u.id = pp.parent_id
LEFT JOIN payment.player_subscriptions ps ON ps.player_id = pp.id
WHERE u.email = :'owner_email';
