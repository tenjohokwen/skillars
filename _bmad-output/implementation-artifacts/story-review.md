# Senior-dev audit — Story skillars-deferred-88

> **RESOLVED 2026-08-31 — all findings applied to the story (v0.2 in its Change Log).** Every finding
> (H1–H3, M1–M5, L1–L7) was re-verified against the actual source and held — **no false positives**.
> H1: AC2 mutation check retargeted to `entityManager.refresh`'s lock mode. H2: AC11 reframed around
> admin-lock / GDPR-erasure (`LoginAttemptsService` never persists a lock). H3: AC10 uses
> `saveAndFlush` in a `REQUIRES_NEW` boundary (the `@Tsid` id defers the INSERT to commit). M1: AC1
> `updateReview` epoch bump moved under `findByIdForUpdate`. M2: only `name unknown` added to the
> GHCR grep, `denied` left in the safe branch. M3: `workflow_dispatch` force-publish gated to
> `master`. M4: AC9 reduced to the fail-fast validator; the four `@ConditionalOnProperty` gates left
> as-is. M5: `grafana-alerts.yml` stays a committed default with a managed delimited region,
> placeholders preserved. L1–L7 folded in (2-arg `EmailTokenException`, terminal-vs-not decision,
> file-only `$0` re-exec, `.bak` inside the `if`, unresolvable-id+multi-Volume hard-fail,
> `node_exporter` underscore + `uat-hostwinds` overlay, line-number re-anchor). See the story's
> Change Log v0.2 row.

---

Reviewed: the story file against source at `d99bd19` / `5efc0f0`.
Scope: missed corner cases, false assumptions, missed flows. Every finding below was checked
against the actual code; a "considered but not a defect" list is at the end to bound false positives.

Verdict: **the story is broadly sound and well-researched, but three ACs rest on claims that do not
hold against the current code (AC2 mutation check, AC11 lock-writer premise, AC10 exception timing),
and each needs its approach adjusted before dev.**

---

## HIGH

### H1 — AC2: the prescribed mutation check does not disable locking, so the "test that fails without the lock" is not actually achieved

`MessagingService.softDeleteMessage` takes the row lock in **two** places:

```
:319  Message message = messageRepository.findByIdForUpdate(messageId)...
:325  entityManager.refresh(message, LockModeType.PESSIMISTIC_WRITE);
```

AC2's mutation check is: *"revert `softDeleteMessage`'s `findByIdForUpdate` to `findById` → the
'still blocked after a short wait' assertion fails."* That is wrong. Reverting only the repository
call leaves `entityManager.refresh(message, LockModeType.PESSIMISTIC_WRITE)` in place, which still
issues `SELECT … FOR UPDATE`. The row still locks, the concurrent `DELETE` still blocks, and the
rewritten test still passes. The mutation the story names is a no-op for causality.

Impact: AC2's entire deliverable is "a durable guard — a test that fails without the lock,
mutation-verified." As written that cannot be demonstrated.

Fix the AC: the effective mutation is on the `refresh` — change `LockModeType.PESSIMISTIC_WRITE` to
`LockModeType.NONE` (or delete the `refresh` line). Note also that with the `refresh` present the
`findByIdForUpdate` call is arguably already redundant for correctness; the test should pin the
`refresh`, not the repository method.

### H2 — AC11: false premise — `LoginAttemptsService` does not set `User.locked`

The story states (AC11 Problem, story summary, AC12 ledger note) that `User.locked` is *"set by
`LoginAttemptsService` on brute-force lockout"* and that *"an account locked mid-registration (e.g.
OTP brute-force lockout)"* is the scenario being closed.

Grep of `src/main/java` for `setLocked` / `.lock()`:

| Writer of `locked = true` | Trigger |
|---|---|
| `UserAdminService:64` `u.lock()` | admin manual action |
| `GdprErasureService:96` | GDPR erasure |
| `UserErasedEventListener:27` | user-erased event |

`LoginAttemptsService`, `FraudAwareAuthenticationManager` and `AuthenticationFailureListener`
throttle via **in-memory Guava `LoadingCache`s** (`expireAfterWrite(4, HOURS)`) and never touch
`User.locked` or the DB. There is no OTP- or login-brute-force code path that persists a lock on a
mid-registration user.

Impact: the guard is still defensible defense-in-depth (an admin-locked or GDPR-erased user who
still holds a valid pre-lock email/OTP token could self-advance `verificationStatus` to
`EMAIL_VERIFIED` / `BASIC_VERIFIED`), but the AC's rationale, the "mid-registration account that
gets locked" framing, and any test comment citing brute-force lockout are inaccurate and should be
rewritten around the real writers (admin lock, erasure).

### H3 — AC10: `DataIntegrityViolationException` won't surface at `save()`; the prescribed catch can't catch it, and the surrounding transaction is poisoned

`PhoneOtpToken extends BaseEntity`, which uses `@Id @Tsid` — an **application-assigned** identifier.
Hibernate does not need an immediate INSERT to obtain the id, so the INSERT is deferred to
flush/commit. The three registration services are class-level `@Transactional`, and the OTP insert
is the last DB write in `verifyEmail`. Therefore:

1. The unique-index violation throws at **transaction commit** — after `verifyEmail` returns — as an
   unhandled 500, which is exactly the outcome AC10 says it is preventing. A `try/catch` around
   `otpTokenRepository.save(otpToken)` as literally described never fires. Catching it requires
   `saveAndFlush()` or an explicit `entityManager.flush()`.
2. Even with a forced flush, a constraint violation inside the outer `@Transactional` marks the
   transaction rollback-only. Catching and re-throwing a "friendly retry" exception still rolls back
   the `verificationStatus → EMAIL_VERIFIED` transition and `evt.setUsed(true)` performed earlier in
   the same method, and the commit throws `UnexpectedRollbackException`. To get the "an OTP is
   already being sent, retry" semantics the AC wants, the OTP delete+insert must run in a
   `REQUIRES_NEW` sub-transaction or behind a savepoint.

The AC treats this as "wrap the `save`." It is not that simple.

Secondary (reachability): `email_verification_tokens` already has `@Version` (`version BIGINT`), so
two concurrent `verifyEmail` calls **with the same token** are already serialized at
`emailTokenRepository.save(evt)` (optimistic-lock → `security.emailTokenUsed`) before either reaches
the OTP insert. The genuinely reachable double-insert requires concurrent *resend* calls creating
two email tokens (no unique index there) plus no `@Version` on `User`. The index is still worth
adding, but the AC's "two concurrent OTP requests … both commit" description skips the existing
guard and overstates how easy the race is.

---

## MEDIUM

### M1 — AC1: the epoch is incremented on a non-locked read, so it does not defend the concurrent-edit case the AC exists to future-proof

`updateReview` loads the row via `findByReviewIdAndAuthorId` (no lock), then does
`setModerationEpoch(getModerationEpoch() + 1)`. Two near-simultaneous edits both read epoch *N*,
both write *N+1*, both publish an event carrying *N+1*; the row ends at *N+1* and **both** in-flight
Gemini verdicts pass the `event.moderationEpoch() == review.getModerationEpoch()` guard.

The sequential superseded-edit case is fixed. The concurrent case is not. The AC's own stated
justification is "reachable the moment the 365-day edit rule is relaxed" — and a relaxed rule is
precisely what makes rapid successive / concurrent edits reachable. Increment atomically
(`UPDATE reviews.coach_reviews SET moderation_epoch = moderation_epoch + 1 WHERE …`) or perform the
`updateReview` read under `findByIdForUpdate`.

### M2 — AC6: reclassifying GHCR `denied` as ":latest absent" weakens the fail-safe

`denied` is GHCR's response **both** for a package that does not exist yet **and** for a genuine
permission / org-SSO failure on an existing package. The current `else` branch (any unrecognised
read failure → publish `sha-` only, leave `:latest` untouched) is deliberately the safe direction.
Moving `denied` into the "absent → publish both tags" branch means a transient or permission
`denied` on an existing `:latest` now triggers an unconditional `:latest` publish — the
older-overwrites-newer regression the ordering guard exists to prevent.

`name unknown` is unambiguous and safe to add. `denied` is not — either leave it in the generic
branch, or have the AC explicitly acknowledge the residual risk (mitigated only by the fact that
`build-and-push` has just authenticated with `packages: write`).

### M3 — AC6: the `workflow_dispatch` force-publish path has no branch guard

The AC short-circuits to "both tags, no ancestor check" on `force_publish_latest=true` but never
requires `github.ref == 'refs/heads/master'`. A dispatch from any feature branch would then publish
`:latest` from non-master code. Add an explicit ref check that fails the step (or refuses to append
`latest`) when not on `master`. Also note `org.opencontainers.image.created` is fed from
`github.event.head_commit.timestamp`, which is null on a `workflow_dispatch` payload — the label
will be empty on the recovery path (cosmetic, but worth a fallback).

### M4 — AC9: overstated premise, and "align the defaults" is incomplete and risky

**Premise:** with `app.ses.enabled=yes` the context does **not** "silently" leave SES unwired.
`SesEmailServiceImpl` (`havingValue="true"`), `DevSesEmailService` (`havingValue="true"`) and
`NoOpSesEmailService` (`havingValue="false"`, `matchIfMissing=true` — applies only when the property
is *absent*) all fail to match, so `SesEmailService` has **zero** implementations and the three
`*RegistrationEmailListener` constructor injections fail startup with
`NoSuchBeanDefinitionException`. Loud, not silent. Also, prod runs with
`SPRING_PROFILES_ACTIVE=prod` (docker-compose `app` env), not "no active profile." The fail-fast
validator is still worth adding for a *clear* message — but the story's justification needs
correcting so the dev doesn't write a test asserting silent-noop behaviour that doesn't exist.

**"Align the defaults":** the same property gate lives on four artifacts — `SesConfig`,
`SesEmailServiceImpl`, `DevSesEmailService` (`matchIfMissing=false`) and `NoOpSesEmailService`
(`matchIfMissing=true`). "Files expected to change (AC9)" lists only `SesConfig.java` /
`SesProperties.java`. Changing `SesConfig`'s `matchIfMissing` (or the `SesProperties.enabled`
default) without touching the other three re-introduces an inconsistency — e.g. absent property →
`SesV2Client` bean created but no `SesEmailServiceImpl`. Recommendation: leave every `havingValue`
gate exactly as-is (they are already mutually consistent: true/false-only for the real beans, a
`matchIfMissing` fallback for the no-op) and add **only** the fail-fast validator. Do not touch
`matchIfMissing` or the field default.

### M5 — AC7: bootstrapping gap — the generated file may not exist when Docker binds it

`provision.sh` today never writes `grafana-alerts.yml`; it runs **before** `docker compose up -d`
and its section 8 only executes `if [ -f "${ENV_FILE}" ]`. Under option 1 (remove
`grafana-alerts.yml` from the repo, generate it), any run where `.env` is absent — **including the
documented first provision** ("place `.env`, then re-run") — leaves the bind-mount source
`./deploy/lgtm/grafana-alerts.yml` missing, and Docker will silently create a **directory** at
`/etc/grafana/provisioning/alerting/alerts.yml`, breaking alert provisioning.

The AC must either keep a committed valid default file (option 2, delimited region) or generate
unconditionally with safe empty-channel handling, and it must call out the `.gitignore` +
`git rm --cached` step for the now-generated path.

Also unspecified: whether the rendered `contactPoints` block **inlines the resolved secret values**
(`GF_SLACK_WEBHOOK_URL`, `GF_ALERT_NOTIFY_EMAIL`) or preserves the `${...}` placeholders that
Grafana currently expands at load. Inlining would write the Slack webhook and ops email into an
on-disk file that today holds only placeholders — a secrets-hygiene regression. It must preserve the
placeholders, and the Slack `text:` Go-template (`{{ range .Alerts.Firing }}`) must be emitted from
a **quoted** heredoc so the shell doesn't mangle `{{ }}`.

---

## LOW / NITS

### L1 — AC11: the AC's code sketch does not compile for `verifyEmail`
`EmailTokenException` is only ever constructed as `(String key, boolean terminal)`. The sketch
`throw new EmailTokenException("security.accountLocked")` is 1-arg. Dev must write
`new EmailTokenException("security.accountLocked", true)`. (`OtpVerificationException("…")` in
`verifyPhone` is fine as a 1-arg.)

### L2 — AC11: abuse trade-off not considered
Since `locked` is now also reachable via **admin action** (`UserAdminService.lock()`), a terminal
exception in `verifyEmail`/`verifyPhone` means an admin lock (or a GDPR erasure) on a half-registered
account permanently wedges that registration with no self-service path. Probably acceptable — but
call it out, and consider a non-terminal error so a later unlock lets the user resume.

### L3 — AC3: the `flock` self-re-exec idiom does not handle the piped case it claims to
`exec env _PROVISION_LOCKED=1 flock … "$0" "$@"` re-executes `$0`. Under `curl … | bash`, `$0` is
`bash`/`-bash` with no script path, so the re-exec runs an empty shell. The AC says the idiom "works
when the script is piped or run directly" — it only works when run from a file, which is how the docs
invoke it (`bash /opt/skillars/deploy/provision.sh`). Drop the piped claim.

### L4 — AC4: spec vs. verification mismatch on when the `.bak` is written
The AC says place `cp -p /etc/fstab …bak` "before the `sed -i` purge," but that `sed -i` is inside
`if grep … ; then`. Verification case (d) ("no `/opt/skillars/data` line at all → backup still
written") only holds if `cp` is placed *outside* the `if`. As written they conflict. And placing it
outside means every idempotent steady-state re-run drops a fresh `/etc/fstab.bak.<ts>` forever
(retention explicitly out of scope). Cleanest: back up only immediately before actually running
`sed -i` (inside the `if`), and drop verification case (d)'s "backup still written" expectation.

### L5 — AC5: the multi-Volume hard-fail leaves the more dangerous case open
The AC hard-fails only when `>1` `scsi-0HC_Volume_*` symlink exists **and** `HETZNER_VOLUME_ID` is
unset. When `HETZNER_VOLUME_ID` is *set but unresolvable* (typo / stale id) **and** multiple Volumes
are attached, the current code only `err`-warns, then falls through to "first matching symlink" →
`readlink -f` → and if that Volume is unformatted, `mkfs.ext4` on it. That is the higher-risk path
(operator believes they pinned the device). The hard-fail should also cover "id set, unresolvable,
and `>1` Volume present."

### L6 — AC8: wrong service name, and a third overlay is unlisted
The compose service key is `node_exporter` (underscore); the AC body, Tasks and AC12 ledger text all
write `node-exporter` (that hyphenated string is only the Prometheus job label; the scrape target is
`node_exporter:9100`). Also `docker-compose.uat-hostwinds.yml` exists and is never mentioned in the
"check they still merge" step (only `.local` and `.uat` are). The `.local`/`.uat` overlays add only
`minio`/`minio-init` on `skillars-internal` and do not redefine base service network lists, so the
merge risk there is low — but confirm `uat-hostwinds` too.

### L7 — line-number drift throughout Dev Notes / References
`provision.sh` is 484 lines; citations `:193-202`, `:297-298`, `:333-350`, `:423-475` and Task refs
`:341` / `:348` are all shifted (staging `rsync` ≈ `:344`, fstab block ≈ `:330-355`, section 8 ≈
`:421-475`). `SoftDeleteIT` test cited `:246-289` is ≈ `:263-292`. `ReviewSubmissionService` publish
sites cited `:63,:72,:98,:103` are `:63` / `:72-73` and `:98` / `:103-104`. Not blocking, but a
re-anchor pass is cheap and will save the dev time.

---

## Considered, but NOT a defect (guarding against false positives)

- **Migration numbering** — `V119` is the current max; `V120`/`V121` are correct and unused.
- **AC1 event arity / types** — `ReviewSubmittedEvent` is a 5-field record (`UUID, UUID, Long, int,
  String`); the story's 6-arg examples are type-consistent. All 8 `new ReviewSubmittedEvent(`
  call-sites (6 in `ReviewModerationServiceTest`, 2 in `ReviewSubmissionService`) are correctly
  identified; `ReviewModerationServiceTest` and `ReviewModerationIT` both exist as the story assumes.
- **AC1 `ReviewModerationService` structure** — the `REQUIRES_NEW` / `AFTER_COMMIT` /
  `findByIdForUpdate` / swallowing-catch description matches source exactly; the single added guard
  clause and the "check epoch before the `PENDING` guard" ordering are correct.
- **AC1 `submitCoachResponse`** — also calls `findByIdForUpdate` + `save` but never changes
  `moderationStatus` or publishes an event, so it correctly needs no epoch change.
- **AC2 409 / error-key mapping** — the existing test already asserts `HttpStatus.CONFLICT`, so the
  409 mapping for `MessagingErrorCode.ALREADY_DELETED` exists; asserting the `messaging.alreadyDeleted`
  body key is reasonable.
- **AC2 no `@Version` on `Message`** — confirmed by the in-source comment at
  `MessagingService.java:315-317`; the story correctly forbids adding it.
- **AC7 file split point** — `grafana-alerts.yml` cleanly separates `groups:` (rules) from
  `# ── Contact Points ──` / `contactPoints:` / `policies:`; the proposed split is structurally sound.
- **AC8 Prometheus scrape targets** — `prometheus.yml` scrapes only `app:8367` and
  `node_exporter:9100`; both are covered once `app` and `node_exporter` share the observability
  network with `prometheus`. No hidden `postgres_exporter` / `cadvisor` / `traefik` scrape jobs that
  would be stranded.
- **AC8 Grafana datasources** — `prometheus:9090`, `loki:3100`, `tempo:3200`; all reachable with
  `grafana` on both networks. `app` env URLs (`LOKI_URL`, `MANAGEMENT_OTLP_TRACING_ENDPOINT`,
  `SPRING_DATA_REDIS_HOST`) are all satisfied by `app` joining `skillars-observability`.
- **AC8 `internal: true` semantics** — a network with no gateway blocks egress for
  observability-only members while still allowing intra-network DNS/routing and host-side image
  pulls; the topology (5 restricted services observability-only, `app`/`grafana` dual-homed) is
  correct.
- **AC10 defensive dedup `DELETE … WHERE a.id < b.id`** — `@Tsid` ids are time-sorted, so
  "highest id = newest unused row" holds; leaving exactly one row is the goal regardless.
- **AC10 partial-index predicate** — `WHERE used = false` only (not `expires_at > now()`); correct,
  `now()` is not `IMMUTABLE` and would be rejected in an index predicate.
- **AC9 `SesEmailServiceImpl` does inject `SesV2Client`** — confirmed; and `SesEmailService` has
  three impls (`SesEmailServiceImpl` `!dev`, `DevSesEmailService` `dev`, `NoOpSesEmailService`),
  which is why the non-boolean value produces a hard startup failure (see M4).
