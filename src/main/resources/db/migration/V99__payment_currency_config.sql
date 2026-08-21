-- Story Deferred-53 (AC3): StripePaymentGateway.chargeAndCapture's hardcoded "eur" currency
-- literal becomes a configuration value, mirroring the platform.commission.rate pattern.
--
-- 604 is the next free id: 603 is V93's booking.session.defaultDurationMinutes.
--
-- The id MUST be free. main.platform_config.id is PRIMARY KEY with no sequence (V20:8) — ids are
-- hand-assigned — and the ON CONFLICT target below is `key`, a DIFFERENT unique constraint
-- (uq_platform_config_key, V20:9). An id collision therefore raises a PK violation the
-- ON CONFLICT (key) clause never sees, failing Flyway on every database that has run a later
-- migration reusing this id.
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
VALUES (604, 'platform.payment.currency', 'eur', 'STRING',
        'ISO 4217 currency code (lowercase, as Stripe''s API expects) used for all Stripe charges. '
        || 'Single-currency platform today; extracted from a hardcoded literal so a future '
        || 'multi-currency change does not require a code deploy for this value alone.', NOW())
ON CONFLICT (key) DO NOTHING;
