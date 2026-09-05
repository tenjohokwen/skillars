-- skillars-deferred-92 AC29.5: un-stamp the 2-hour reminders that were recorded as sent but never were.
--
-- Until AC29, BookingEmailListener.onBookingReminder carried no annotation at all — not
-- @EventListener, not @TransactionalEventListener — so Spring never dispatched
-- BookingReminderEvent to it and EmailTemplate.BOOKING_REMINDER had no other sender anywhere in
-- src/main. No booking reminder has ever been delivered. BookingReminderScheduler nonetheless
-- stamped both *_reminder_sent_at columns inside its own transaction, so the database records
-- sends that did not happen.
--
-- Why only the SECONDARY column is reset:
--   * BookingRepository.findUpcomingWithin2hWindow filters `secondary_reminder_sent_at IS NULL`, so
--     a stamped row is permanently excluded. Without this reset, every booking already stamped when
--     the fix deploys would silently never get its 2-hour reminder either — the bug would outlive
--     its own fix for exactly the bookings closest to starting.
--   * primary_reminder_sent_at gates nothing. findConfirmedForUpcomingTransition selects on
--     status = 'CONFIRMED' and the transition moves the row to 'UPCOMING', so the primary reminder
--     is already once-only by state, not by stamp. Clearing it would change no behaviour and would
--     destroy the only record of which bookings were affected.
--
-- Bounded and idempotent by construction: only rows still in the future and still UPCOMING are
-- touched. Past bookings keep their (inaccurate) stamp — re-reminding someone about a session that
-- has already happened is worse than the missing reminder. Worst case for an affected row is one
-- reminder arriving slightly late; there is no double-send, because nothing was sent.
--
-- Row count is tiny by nature (the secondary window is 2 hours wide) and this project has no
-- production deployment yet, so a single bounded UPDATE is correct here — no chunking needed. See
-- docs/deployment/migration-conventions.md § "Full-table DML" for when chunking IS required.
--
-- Lock profile: UPDATE takes ROW EXCLUSIVE, which does not block concurrent readers or writers of
-- other rows, so this is not the ACCESS-EXCLUSIVE hazard the lock_timeout convention binds (that
-- convention is about lock-taking DDL, per docs/deployment/migration-conventions.md rule 7). A row
-- lock held by a concurrent writer on one of these specific rows could still make this UPDATE wait,
-- so SET lock_timeout is added anyway, defensively, rather than argued out of (code review,
-- skillars-deferred-92): the row set is tiny and the wait should never be long if it happens at all.
SET lock_timeout = '5s';

UPDATE booking.bookings
   SET secondary_reminder_sent_at = NULL
 WHERE secondary_reminder_sent_at IS NOT NULL
   AND status = 'UPCOMING'
   AND requested_start_time > NOW();
