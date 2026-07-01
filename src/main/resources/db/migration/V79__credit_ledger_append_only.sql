-- Story deferred-3: enforce append-only invariant on payment.parent_credit_ledger at the DB layer.
-- NOTE: authoritative schema is "payment" (payment.parent_credit_ledger, created in
-- V62__session_payment_credit_wallet.sql), not "booking".
-- A trigger is used instead of a RULE ... DO INSTEAD NOTHING because the trigger raises a visible
-- exception, surfacing accidental UPDATE/DELETE attempts rather than silently discarding them.
-- GDPR erasure (Story 10.4) intentionally retains ledger rows, so this does not conflict with that flow.
CREATE OR REPLACE FUNCTION payment.enforce_ledger_append_only()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'parent_credit_ledger is append-only; UPDATE/DELETE is not permitted';
END;
$$;

CREATE TRIGGER trg_ledger_no_update
    BEFORE UPDATE ON payment.parent_credit_ledger
    FOR EACH ROW EXECUTE FUNCTION payment.enforce_ledger_append_only();

CREATE TRIGGER trg_ledger_no_delete
    BEFORE DELETE ON payment.parent_credit_ledger
    FOR EACH ROW EXECUTE FUNCTION payment.enforce_ledger_append_only();
