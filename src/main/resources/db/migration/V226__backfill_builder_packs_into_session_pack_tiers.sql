-- skillars-deferred-168 (ledger: "Deferred from: Pitchside UI tests, spec ONB-06"): turn the packs coaches
-- entered in the profile builder into the purchasable offer they always looked like.
--
-- Why: builder step 3 (and the profile's "Edit pricing" dialog) wrote marketplace.session_packs, which the
-- public profile advertised with a "Buy sessions" button, while the purchase page and the purchase itself
-- read payment.session_pack_tiers, which only the "My Session Pack Offer" page wrote. Nothing connected the
-- two, so a wizard-only coach advertised packs nobody could buy. From this release the coach's ONE active
-- payment.session_pack_tiers row is the single source of truth: step 3 writes it through
-- marketplace.contract.CoachSessionPackPort, and no code reads or writes marketplace.session_packs.
--
-- What this backfills, for each coach that has session_packs rows:
--  - only coaches with NO session_pack_tiers row at all. A coach with only inactive rows withdrew their
--    offer on purpose ("Withdraw offer"), and must not get it back; a coach with an active row already sells
--    something, and that row now also becomes what their profile shows.
--  - only coaches not erased (coach_profiles.recap_data_erased_at IS NULL, the V166 tombstone).
--  - one tier per coach, from their lowest-session_count VALID row (the cheapest entry point). Valid means
--    within the tier's own bounds, which the builder never enforced (its nested pack validation did not run
--    before this story): session_count 1..100, total_price 0.01..50000.00, and a per-session share that
--    rounds to more than 0.00 (chk_spt_price_per_session_positive).
--  - price_per_session = round(total_price / session_count, 2), as SessionPackPaymentService.createTier
--    computes it (HALF_UP; PostgreSQL's numeric round() rounds half away from zero, identical for positives).
--  - label: blank or NULL becomes 'Session pack' (SessionPackLimits.DEFAULT_LABEL, read back as "no label").
--    A label that looks like it carries contact details (an @, a URL, or a run of 7+ digits) also becomes the
--    default: the builder never ran @NoContactDetails on pack labels, and these rows are about to become a
--    public, purchasable offer. Labels longer than 200 characters cannot exist (session_packs.label is
--    varchar(100)).
-- Everything else in session_packs (a coach's other packs, invalid rows) is discarded: it was never
-- purchasable. The table itself stays for one release (migration-conventions.md rule 2: a DROP ships only
-- in a release after the one that removed its last reader); the DROP is a filed follow-up in deferred-work.md.
--
-- Size: session_packs holds at most 5 rows per coach (the old @Size(max = 5)), so this is one small
-- INSERT ... SELECT, not a chunked backfill. Prod is not live (dev/uat only, as V186-V224 record in the
-- provisioning checklist); UAT coaches can re-enter a discarded pack on their "My Session Pack Offer" page.
--
-- Migration conventions (docs/deployment/migration-conventions.md):
--  - Additive only: inserts rows, no DDL, no UPDATE/DELETE of existing rows, no DROP.
--  - Rule 7: purchases and the tier page write session_pack_tiers, so lock_timeout makes this fail fast
--    instead of queueing behind a long transaction.
SET LOCAL lock_timeout = '5s';

INSERT INTO payment.session_pack_tiers (coach_id, label, session_count, total_price, price_per_session, is_active)
SELECT DISTINCT ON (sp.coach_id)
       sp.coach_id,
       CASE
           WHEN NULLIF(trim(sp.label), '') IS NULL THEN 'Session pack'
           WHEN sp.label ~* '(@|https?://|www\.|[0-9][0-9 ()+./-]{5,}[0-9])' THEN 'Session pack'
           ELSE sp.label
       END,
       sp.session_count,
       sp.total_price,
       round(sp.total_price / sp.session_count, 2),
       true
FROM marketplace.session_packs sp
JOIN marketplace.coach_profiles cp ON cp.id = sp.coach_id
WHERE cp.recap_data_erased_at IS NULL
  AND sp.session_count BETWEEN 1 AND 100
  AND sp.total_price BETWEEN 0.01 AND 50000.00
  AND round(sp.total_price / sp.session_count, 2) > 0
  AND NOT EXISTS (SELECT 1 FROM payment.session_pack_tiers t WHERE t.coach_id = sp.coach_id)
ORDER BY sp.coach_id, sp.session_count, sp.id;
