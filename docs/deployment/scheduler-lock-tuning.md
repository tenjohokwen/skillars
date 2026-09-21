# Scheduler Lock Floor Tuning

`skillars-deferred-123` AC4. `@SchedulerLock`'s `lockAtLeastFor` (and, for cron-scheduled jobs,
`lockAtMostFor`) is how ShedLock keeps a scheduler from re-firing faster than its own worst-case
runtime — a floor sized against that scheduler's *cadence* at the time the code was written. When an
operator lowers a scheduler's poll-delay/interval property without also raising its lock floor, the
mismatch fails silently: dropped ticks, no error, no warning, no metric. ShedLock resolves `${...}`
Spring property placeholders inside `@SchedulerLock` attributes (verified by decompiling the resolved
`shedlock-spring` artifact — `SpringLockConfigurationExtractor` routes both attributes through
`StringValueResolver.resolveStringValue` before converting to a `Duration`), so every scheduler whose
cadence is itself a tunable property now has a matching, independently-settable lock-floor property.
Defaults are unchanged from the pre-existing hardcoded literals — set one of these only when you are
deliberately changing the paired cadence property below it.

| Scheduler.method | Cadence property | Lock-floor propert(y/ies) |
|---|---|---|
| `OutboxService.sweep` | `app.outbox.sweep-ms` | `app.outbox.lock-at-least` (default `PT1M`) |
| `QuotaReservationTimeoutService.expireStaleReservations` | `app.video.reservation-check-interval-ms` | `app.video.reservation-lock-at-least` (default `PT1M`) |
| `VideoDeletionOutboxProcessor.process` | `platform.video.deletion.outbox_poll_delay_ms` | `platform.video.deletion.outbox_lock_at_least` (default `PT30S`) |
| `RadarCompositeDlqProcessor.process` | `platform.development.radar_composite_dlq.poll_delay_ms` | `platform.development.radar_composite_dlq.lock_at_least` (default `PT30S`) |
| `EmailRetryScheduler.retryFailedEmails` | `email.retry.interval-ms` | `email.retry.lock-at-least` (default `PT10S`) |
| `OutboxPollerScheduler.pollAndProcess` | `app.storage.poller.fixed-delay-ms` | `app.storage.poller.lock-at-least` (default `PT2S`) |
| `DeletionSchedulerService.processDeletions` | `app.storage.poller.fixed-delay-ms` (same property as the row above — a different scheduler/lock) | `app.storage.deletion.lock-at-least` (default `PT2S`) |
| `VideoLifecycleScheduler.runLifecycleJob` | `app.video.lifecycle.cron` | `app.video.lifecycle.lock-at-most` (default `PT12H`), `app.video.lifecycle.lock-at-least` (default `PT30S`) |
| `SluSnapshotAppliedRetentionService.prune` | `app.slu.snapshot-applied.prune-cron` | `app.slu.snapshot-applied.lock-at-most` (default `PT15M`), `app.slu.snapshot-applied.lock-at-least` (default `PT1M`) |
| `NeglectedSkillDetectionService.detect` | `app.development.neglected-detection-cron` | `app.development.neglected-detection.lock-at-most` (default `PT30M`), `app.development.neglected-detection.lock-at-least` (default `PT5M`) |

**Not in this table, for two different reasons — corrected 2026-09-18 code review, this paragraph
previously read as exhaustive and was self-contradictory (it listed a scheduler that does carry
`@SchedulerLock` as an example of one that doesn't):**
- **Structurally safe, no floor to tune:** `ReconciliationWorkerScheduler.sweepOrphanedProviderAssets`
  *does* carry `@SchedulerLock` (`lockAtLeastFor = "PT0S"`), but a zero floor cannot be undercut by any
  cadence value, so there is nothing to property-ize.
- **No `@SchedulerLock` at all:** `ReconciliationWorkerScheduler.reconcile`,
  `UploadSessionExpiryScheduler`, `WebhookEventProcessorScheduler`, `ModerationSlaMonitorService`,
  `ConfigService.scheduledRefresh`, `AlertRuleCache.refresh`, `AlertEvaluationService.evaluate` — no
  lock floor exists to fall out of sync with their cadence.

Separately, and not listed above because the set is large: many other schedulers in this codebase
*do* carry `@SchedulerLock` with a hardcoded floor (`BandwidthResetService`, `AuthCleanupService`'s two
jobs, `UserAdminService`, `VideoSubscriptionLifecycleListener`, `QuickCompleteTimeoutService`,
`BookingExpiryScheduler`, `BookingReminderScheduler`, `SubscriptionGracePeriodChecker`,
`SubscriptionChangeApplicator`, `PaymentPendingSweeper`, `SessionPackForfeitureScheduler`,
`SessionPackExpiryNotifier`, `MessageModerationSweeper`, `MessageRetentionScheduler`). These are
correctly absent from the table above for a different reason than either bullet: their *cadence* is
itself a hardcoded `cron`/`fixedDelay` literal, not a Spring property — so there is no cadence knob an
operator could change that would leave the lock floor behind. If a scheduler's cadence is ever made
property-tunable, its lock floor should be property-ized in the same change, following this doc's
pattern.

Values accept both ISO-8601 (`PT90S`) and Spring's shorthand (`90s`) duration syntax.

## Hard constraint: the floor must not exceed the ceiling

`lockAtLeastFor` must be **less than or equal to** `lockAtMostFor`. ShedLock enforces this in
`LockConfiguration`'s constructor, but it builds that object *per invocation*, not at startup — so
historically an inverted pair let the application boot normally and then threw
`IllegalArgumentException` on every single tick, before the job's method body ran. Spring's scheduler
error handler swallows it and reschedules, so the job silently never ran again: no metric, no health
signal, one log line per tick.

Since `skillars-deferred-123`'s code review this is caught at boot instead. `ConfigStartupAssertion`
reads the `@SchedulerLock` annotations off the live beans, resolves their `${...}` expressions through
the same `Environment` ShedLock uses, and refuses to start (outside the `dev` profile) if any pair is
inverted, negative, or unparseable — logging the scheduler name and both resolved values, and
incrementing `config.value.misconfigured{key=scheduler.lock.<name>}`. Under `dev` it logs the error but
does not block.

Most rows above expose only the floor; their ceiling stays a code literal, so it is the value you must
stay under:

| Scheduler.method | Ceiling (`lockAtMostFor`) | Settable? |
|---|---|---|
| `OutboxService.sweep` | `PT10M` | no — code literal |
| `QuotaReservationTimeoutService.expireStaleReservations` | `PT10M` | no — code literal |
| `VideoDeletionOutboxProcessor.process` | `PT15M` | no — code literal, and load-bearing (must stay strictly under `STALE_CLAIM_WINDOW`) |
| `RadarCompositeDlqProcessor.process` | `PT10M` | no — code literal, and load-bearing (must stay strictly under `STALE_CLAIM_WINDOW`, restored to a real margin by `skillars-deferred-125` AC2 — `STALE_CLAIM_WINDOW` widened 10m → 15m, a 5-minute buffer over this 10-minute lock) |
| `EmailRetryScheduler.retryFailedEmails` | `PT10M` | no — code literal |
| `OutboxPollerScheduler.pollAndProcess` | `PT10M` | no — code literal |
| `DeletionSchedulerService.processDeletions` | `PT5M` | no — code literal |
| `VideoLifecycleScheduler.runLifecycleJob` | `PT12H` | yes — `app.video.lifecycle.lock-at-most` |
| `SluSnapshotAppliedRetentionService.prune` | `PT15M` | yes — `app.slu.snapshot-applied.lock-at-most` |
| `NeglectedSkillDetectionService.detectNeglectedSkills` | `PT30M` | yes — `app.development.neglected-detection.lock-at-most` |

A note on the three settable ceilings: unlike the floors, `lockAtMostFor` is **not** a cadence knob. It
is a crash-recovery ceiling sized against the job's worst-case *runtime* — how long another instance
must wait before assuming a dead holder. `VideoLifecycleScheduler`'s in particular is sized off the
`platform.video.lifecycle.batch_size` ceiling rather than its default, because the failure mode if it
is set too tight is a genuine double-archive/double-delete against the storage provider, not just a
missed tick. Lower these only with that arithmetic in hand.
