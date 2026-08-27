-- Story Deferred-75 AC7: session.homework_assignments.pack_id has always pointed at a real, live
-- payment.session_pack_purchases.purchase_id (via HomeworkAssignmentService.resolvePackId ->
-- PackSessionService.getActivePackId) but never had the FK declared (V45 predates the pattern this
-- migration now applies, mirroring the existing booking.bookings -> payment.session_pack_purchases
-- cross-schema FK from V62). ON DELETE SET NULL because the column is already nullable and no code
-- path deletes a session_pack_purchases row today.

-- Defensive: clear any orphaned pack_id that doesn't match a live purchase before adding the FK, so
-- this migration cannot fail on unexpected existing data in any already-deployed environment.
UPDATE session.homework_assignments ha
SET pack_id = NULL
WHERE ha.pack_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM payment.session_pack_purchases p WHERE p.purchase_id = ha.pack_id
  );

ALTER TABLE session.homework_assignments
    ADD CONSTRAINT fk_homework_assignments_pack
    FOREIGN KEY (pack_id) REFERENCES payment.session_pack_purchases(purchase_id)
    ON DELETE SET NULL;
