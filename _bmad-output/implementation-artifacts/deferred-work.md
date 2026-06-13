## Deferred from: code review of skillars-2-3-coach-public-profile-page (2026-06-13)
- N+1 queries — `getPublicProfile` fires 8 sequential DB round-trips; acceptable for single-entity load now, but batch loading or `@EntityGraph` should be considered before Epic 3 traffic ramp [CoachProfileService.java]
- Floating-point savings math in `SessionPackTracker.vue` — `perSessionPrice * sessionCount - totalPrice` uses IEEE 754 arithmetic; add a currency library (e.g. `currency.js`) before pack discounts are prominent in UI [SessionPackTracker.vue]
- `CoachMediaItem.uploadedAt` uses field initializer `OffsetDateTime.now()` — consistent with existing `CoachProfile.createdAt` pattern but should migrate to `@CreationTimestamp` codebase-wide [CoachMediaItem.java]
- `UNIQUE (coach_id, display_order)` makes naive gallery reorder impossible without a temp value; make the constraint `DEFERRABLE INITIALLY DEFERRED` or redesign reorder API in the media management story [V28__marketplace_coach_media.sql]
- `aggregateRating`/`reviewCount` hardcoded to `0.0`/`0` — wire to reviews aggregate in Epic 9 [CoachProfileService.java]
- `long → int` cast on `strikeCount` — replace with `Math.toIntExact()` to catch overflow explicitly if strike volume ever grows large [CoachProfileService.java]
- Test `unknownId_returns404` uses `assertThatThrownBy().isInstanceOf(HttpClientErrorException.class)` — add `satisfies()` on the outer exception to verify status 404 before the inner cast, so a 5xx surfaces a cleaner failure [CoachProfileResourceIT.java]
- `VerificationBadge.vue` tooltip presence — verify the existing component already includes tier-explanation tooltip (AC 2); if not, add it in a follow-up [CoachPublicProfilePage.vue]

## Deferred from: code review of skillars-1-6-age-tier-enforcement-family-data-isolation (2026-06-12)
- Flyway V25 hardcoded IDs 112–114 — `ON CONFLICT (key) DO NOTHING` does not guard against PK collision if those IDs are already taken by different rows with different keys; spec explicitly verified the ID range is safe; established codebase Flyway seed pattern [V25__age_policy_config_seed.sql:1–6]

## Deferred from: code review of skillars-1-5-authentication-jwt-security (2026-06-12)
- Tests use raw `jdbcTemplate` inserts instead of Instancio for test data — project rule violation but tests are functionally correct [AuthResourceIT.java]
- `AuthResourceIT` lacks `@Testcontainers` annotation — may be managed via inherited `TestConfig` or `SecurityIT` base class; verify before next review [AuthResourceIT.java]
- `@Observed` at class level vs per-method on `AuthResource` — class-level is a valid Micrometer pattern; no metric data lost [AuthResource.java]
- `refresh_alreadyUsedToken` test does not assert `Set-Cookie: rtkn=; Max-Age=0` in the 401 response — minor AC2 coverage gap [AuthResourceIT.java]
- `ROLE_ROUTES` duplicated across `LoginPage.vue` and `router/index.js` — DRY violation; divergence would cause infinite redirect loop, but no current divergence
- `fr-FR` locale may be missing `auth.coach` sub-tree — investigate whether gap is pre-existing from a prior story [i18n/fr-FR/index.js]
- `hydrated` flag in router factory is closure-scoped — SSR-unsafe but app is SPA only [src/frontend/src/router/index.js]
- Client-side `skp` clear in `auth.store.logout()` is redundant — server `logout()` already sends `Set-Cookie: skp=; Max-Age=0`; the `document.cookie` write is belt-and-suspenders [auth.store.js]

## Deferred from: code review of skillars-1-4-parent-registration-player-profiles-shadow-accounts Group 1 (2026-06-12)
- OTP hash uses `SHA-256(otp+userId)` no separator — same pre-existing pattern as CoachRegistrationService (already tracked Story 1.3 Group B D3); rate limiting is primary mitigation [ParentRegistrationService.java — hashOtp]
- `verifyEmail` saves `activated=true` before optimistic-lock check — correctly rolled back by `@Transactional`; same pattern as CoachRegistrationService; would break if called inside `REQUIRES_NEW` propagation [ParentRegistrationService.java:129–137]
- `PhoneNumber("XX")` hardcoded country placeholder — intentional per Dev Notes; same as coach flow (Story 1.3 Dev Notes) [ParentRegistrationService.java:98]
- Migration IDs 100–102 in `platform_config` — different table from V21's authority rows; `ON CONFLICT (key) DO NOTHING` is correct idempotency guard [V22__parent_player_shadow_accounts.sql]
- `dateOfBirth = LocalDate.of(1900, 1, 1)` parent user placeholder — intentional per Dev Notes; same pattern as coach (Story 1.3 Dev Notes) [ParentRegistrationService.java:102]
- Age tier snapshotted at creation, never recomputed as child ages — by design per spec; explicit consent-escalation update deferred to Story 1.6 [PlayerProfile.java; ShadowAccountService.java]
- `@Past` constraint allows 1-day-old player DOB; no minimum player age enforced — not in scope per spec; no AC addresses minimum player age [CreatePlayerProfileRequest.java:12]
- OTP rate-limit key is `userId` only — expired-OTP resubmissions drain legitimate user's budget; same pre-existing pattern as CoachRegistrationService (Story 1.3 Group B D2) [ParentRegistrationService.java:154]
- Phone-collision detection via `msg.contains("phone")` — DB-dialect fragile; same pre-existing pattern as CoachRegistrationService [ParentRegistrationService.java:100–104]

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification Group D (2026-06-11)
- D1: `/verify-email` endpoint not rate-limited — large UUID space; already tracked Group A D6; acceptable risk [CoachRegistrationResource.java]
- D2: Rate limit consumed before user table lookup in `verifyPhone` — targeted bucket exhaustion possible; design limitation of public OTP endpoints; mitigated by per-userId keying [CoachRegistrationService.java:145–147]
- D3: `SUSPENDED` user in `EMAIL_VERIFIED` state can complete phone OTP — no suspension code exists yet; guard should be updated when suspension story is implemented [CoachRegistrationService.java]
- D4: SES failure during `/resend-verification` creates valid DB token with no email delivery — logged at ERROR; resend button available [CoachRegistrationEmailListener.java]
- D5: Frontend 60s cooldown resets on page refresh — UI-only throttle; server-side rate limit is authoritative [CoachEmailPendingPage.vue]
- D6: `ContactDetailSanitizer` double-redaction edge case — phone pattern can match trailing digits in already-redacted string; benign, no exploitable effect [ContactDetailSanitizer.java]
- D7: `ON CONFLICT (name) DO NOTHING` in authority seed does not protect against PK collision on `id` — already tracked Group A D4; id=100/101 safe for this project [V21__skillars_security_extension.sql]
- D8: `verifyEmail` response leaks internal userId as URL query param — already tracked Group C D1; spec-mandated (AC4); mitigated by per-userId rate limiting [CoachRegistrationService.java:142, CoachEmailVerifyPage.vue:72]

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification Group C (2026-06-11)
- D1: userId in URL query param as tamper vector — spec-mandated design (AC4); mitigated by per-userId rate limiting (Group B P4) [CoachEmailVerifyPage.vue, CoachPhoneVerifyPage.vue]
- D2: GET with token in query string exposes token to server logs/Referer — spec-mandated endpoint design (AC4); single-use token mitigates
- D3: sessionStorage fragility / cross-device flow — architectural limitation of spec-prescribed flow; out of scope for story 1.3
- D4: useContactDetector PHONE_RE may false-positive on numeric strings in name fields — low practical risk in practice
- D5: OTP handlers reimplemented instead of reused from OtpPage.vue per spec Dev Notes — functionally equivalent; refactor candidate
- D6: useContactDetector not applied to phone field — less relevant; spec doesn't require it here
- D7: canResend read directly from err.response.data bypassing parseApiError — works correctly; architectural cleanup is future work
- D8: resendSuccess banner implies email was always sent — intentional anti-enumeration security design
- D9: auth.firstName/validation.* absent from en/index.js — false positive: app default is en-US; en falls back to en-US for these keys
- D10: --accent-warning CSS token confirmed present at _colors.scss lines 31 and 88

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification Group B (2026-06-11)
- D1: verifyPhone caller-supplied userId with no ownership binding — spec-required design; rate limiting is primary mitigation [VerifyPhoneRequest.java]
- D2: IP-keyed rate limiting timing oracle on /resend-verification — pre-existing RateLimitingService limitation [CoachRegistrationService.java]
- D3: OTP hash SHA-256(otp+userId) no random salt — spec-prescribed; already tracked as W1 [CoachRegistrationService.java:hashOtp]
- D4: Hardcoded DOB(1900,1,1) and Gender.OTHER placeholders persisted to DB — spec-acknowledged; cleaned up in Story 2.1 [CoachRegistrationService.java]
- D5: registerCoach returns void not CoachRegistrationResult — intentional simplification; void sufficient for current ACs [CoachRegistrationService.java]
- D6: resendVerificationEmail deletes unused tokens instead of marking used=true — deletion achieves invalidation intent [CoachRegistrationService.java:168]
- D7: Hardcoded BIGINT test fixture IDs risk TSID collision — low probability, acceptable in test-only code [CoachRegistrationResourceIT.java]
- D8: SecureRandom re-instantiated per generateOtp() call — low severity performance concern [CoachRegistrationService.java:generateOtp]

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification Group A (2026-06-11)
- D1: BIGINT PK with no DB sequence — pre-existing @Tsid pattern; direct SQL inserts require manual TSID generation [V21__skillars_security_extension.sql]
- D2: verification_status unconstrained VARCHAR(20) — no CHECK constraint; pre-existing pattern for enum-backed columns [V21__skillars_security_extension.sql]
- D3: SES region hardcoded eu-west-1 in SesProperties, not overridden in application-prod.yaml — deployment config concern [SesProperties.java, application-prod.yaml]
- D4: Authority id 100/101 magic numbers — PK collision if authority sequence reaches these values; ON CONFLICT (name) DO NOTHING does not protect against PK clash with different name [V21__skillars_security_extension.sql]
- D5: phone_otp_tokens no partial unique index on active OTPs — multiple valid OTPs possible if service doesn't invalidate old tokens first; verify in Group B [V21__skillars_security_extension.sql]
- D6: verifyEmail endpoint not @RateLimited — brute-force UUID token space; Group B code [CoachRegistrationService.java]
- D7: resendVerificationEmail accepts EMAIL_VERIFIED users and re-triggers email verification instead of OTP step — flow regression; Group B code [CoachRegistrationService.java]

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification (2026-06-11)
- W1: OTP hash uses `SHA-256(otp+userId)` — 6-digit OTP space vulnerable to offline pre-computation if DB is breached; hash scheme is spec-prescribed; rate limiting on `/verify-phone` is primary mitigation [CoachRegistrationService.java:hashOtp]
- W2: `verifyPhone` accepts caller-supplied `userId` with no ownership binding — spec-required field; risk mitigated by rate limiting [VerifyPhoneRequest.java]
- W3: SES conditional bean: unrecognized value for `app.ses.enabled` (e.g. `enabled: yes`) leaves `SesEmailService` unwired at startup [SesConfig.java, SesEmailServiceImpl.java]
- W4: `BaseEntity` TSID + V21 `BIGINT PRIMARY KEY` with no sequence — direct SQL inserts in future migrations or test fixtures require manual TSID generation [V21__skillars_security_extension.sql]
- W5: `ContactDetailSanitizer.PHONE_PATTERN` may redact digit-heavy name segments (e.g. "Type 2 Analyst") — pattern is spec-prescribed; refine when real-world false positives are observed [ContactDetailSanitizer.java]
- W6: `RateLimitingService` uses in-process `ConcurrentHashMap` — not cluster-safe, no eviction; pre-existing infrastructure issue not introduced by this story
- W7: `TokenErrorResponse.errorKey` field alignment with `useErrorHandler` composable — confirm when applying patches; likely aligned by naming convention [ApiAdvice.java]
- W8: `EMAIL_VERIFIED` users have no path to re-request phone OTP via `/resend-verification` — resend endpoint intentionally scoped to email verification only; add dedicated `/resend-otp` endpoint in a later story

## Deferred from: code review of skillars-1-2-skillars-design-system-foundation (2026-06-11)
- W1: `.glass-card` still uses `transition: all` — inconsistent with `.hover-lift` narrowed to `transform + box-shadow` in this story; pre-existing in glass.scss, out of story scope [src/frontend/src/css/glass.scss]
- W2: `auth`, `profile`, `session` keys missing from `en`/`de` locale stubs — pre-existing template strings not added by this story; `en-US` fallback handles them at runtime [src/frontend/src/i18n/en/index.js]
- W3: `app-bg` class has no boot-failure fallback in `App.vue` — boot file is the canonical owner per spec design; fallback in App.vue would duplicate logic; acceptable exceptional-case gap
- W4: `onSessionExpired` in MainLayout clears username but does not redirect to `/login` — pre-existing behaviour not introduced by this story
- W5: `variables.scss` dual import path — `app.scss` imports `tokens/colors` directly AND `variables.scss` also forwards to `tokens/colors`; any file that @imports `variables.scss` picks up colour tokens twice; latent build-warning risk
- W6: Rapid double-click theme toggle can briefly desync DOM attribute and `darkMode` ref — `toggleTheme` is synchronous so window is negligible in practice; acceptable
- W7: No CSP header coverage for `fonts.googleapis.com` — infrastructure/deployment concern outside story scope

## Deferred from: code review of skillars-1-1-feature-gate-configuration-layer (2026-06-11)
- `IllegalStateException` → HTTP 409 semantically wrong for missing config keys (should be 500 for misconfiguration); pre-existing ApiAdvice mapping not introduced by this story [ApiAdvice.java:existing illegalStateExceptionHandler]
- `refreshCache()` failure after `invalidate()` causes all subsequent config gets to throw 409 instead of serving stale data during DB outage; acceptable design choice for this scope [ConfigService.java]
- Scheduled refresh + lazy TTL `ensureFresh()` can both fire near-simultaneously, causing ~2x DB polls per TTL period; minor efficiency concern, spec-designed dual-refresh pattern [ConfigService.java]
- IT test fixture hardcodes bcrypt hash for test user seed SQL; follows existing project IT test pattern [ConfigResourceIT.java:setUp]

## Deferred from: code review of deploy-3-4-operational-documentation-suite (2026-06-05)
- Integrity check (table count ≥ 1) is trivially weak — a partially-loaded dump that created only one table passes; pre-existing restore-from-dump.sh limitation [docs/deployment/backup-restore.md]
- DROP DATABASE may fail if services other than `app` hold open DB connections — script stops only `app` before drop; pre-existing script limitation [docs/deployment/backup-restore.md]
- Hardcoded container UIDs (65534/10001/472) not tied to Docker image versions — upstream UID changes (historically seen with Grafana) would silently break subdirectory ownership after snapshot restore [docs/deployment/backup-restore.md]
- /tmp space check in restore-from-dump.sh validates compressed dump size only — decompressed SQL is typically 5-10x larger; mid-restore /tmp exhaustion possible; pre-existing script gap [docs/deployment/backup-restore.md]
- APP_CID capture races container registration immediately after `docker compose start app` — health-wait loop can time out on a healthy app; pre-existing script race condition [docs/deployment/backup-restore.md]
- WebhookPermanentFailure Admin API re-trigger has no endpoint or auth reference — Admin API not defined in this story's scope; needs dedicated API documentation [docs/deployment/monitoring.md]
- CallbackRateZero public callback endpoint undocumented — application-specific URL not defined in deployment docs; needs a secrets-reference or application guide entry [docs/deployment/monitoring.md]

## Deferred from: code review of deploy-3-3-external-uptime-monitoring-alert-rules (2026-06-05)
- Double notification risk if Alertmanager added later — Prometheus rules and Grafana alerting both evaluate the same infra alerts; currently no Alertmanager so only Grafana notifies, but future Alertmanager addition would cause duplicate ops notifications for every infra alert
- CallbackFailureRatioHigh divide-by-zero on zero callback traffic — pre-existing rule divides rate by rate with no zero-denominator guard; fires spuriously during quiet periods [deploy/lgtm/alerts.yml]
- node_exporter network isolation — shares `skillars-internal` network with app containers; port 9100 reachable by any compromised container; FR-9 required this topology, changing it is out of scope
- Empty notification vars cause silent delivery failure — if `GF_ALERT_NOTIFY_EMAIL` or `GF_SLACK_WEBHOOK_URL` are empty (compose defaults), Grafana provisions the contact point but notifications silently fail; intentional spec design tradeoff (`${VAR:-}`)
- DiskDataVolumeHigh requires Hetzner Volume mounted at `/opt/skillars/data` — if volume not provisioned, no metrics series exists and alert never fires; infrastructure provisioning dependency

## Deferred from: code review of deploy-3-2-scripted-restore-process (2026-06-04)
- fstab not updated after snapshot restore — new volume mounted with `mount /dev/sdb ...` but no fstab update; volume won't auto-mount on reboot if volume UUID changed. Beyond Task 2 scope [restore-from-snapshot.sh:62]
- DOMAIN sourced from .env in restore-from-snapshot.sh but never used — spec says "source .env for DOMAIN" but no reference to DOMAIN in script [restore-from-snapshot.sh:19]
- App and DB left in partial state on mid-restore failure — no recovery trap by design; operator must manually restart the app service and investigate [restore-from-dump.sh:90]
- /dev/sdb hardcoded, no filesystem UUID verification — per spec; operator confirms correct attachment at the Hetzner Console ENTER prompt [restore-from-snapshot.sh:23]

## Deferred from: code review of deploy-3-1-postgresql-backup-automation (2026-06-04)
- PGPASSWORD exposed via docker exec `-e` flag (visible in `ps aux` for duration of call) — spec-prescribed pattern; would require Docker secrets or a wrapper script to fix [deploy/backup/pg-backup.sh:22]
- Credentials visible in `/proc/<pid>/environ` when `.env` is sourced — project-wide pattern, not introduced by this story
- No retention policy — S3 dumps and Hetzner snapshots accumulate unbounded; add lifecycle rules or a rotation script in a future backup hardening story
- install-crons.sh installs cron for the invoking user with no enforcement — typically root; document the expected user or add a guard in a future hardening pass
- No upload integrity check (checksum / ETag verification after aws s3 cp) — out of scope for this story
- No handling for Hetzner API HTTP 409 (action in progress) or 422 (quota exhausted) in volume-snapshot.sh — out of scope for this story
- awscli v1 from Ubuntu apt may have `--endpoint-url` edge cases with Hetzner Object Storage — spec-approved as sufficient; revisit if upload failures occur in production

## Deferred from: code review of deploy-2-3-deployment-rollback-documentation (2026-06-04)
- No pre-deploy GHCR image existence check — no step to verify the image tag exists in GHCR before triggering deploy; typo causes mid-run failure after 2–5 min wait.
- No GHCR auth failure handling — no guidance if `docker login` fails (expired PAT, wrong token scope) before `docker compose pull`.
- Step 5 health check retry loop is manual — "retry after 10 seconds" gives no command to re-run; a simple loop would be deterministic [rollback.md:139–142].
- Partial pull failure leaves .env inconsistent — if `docker compose pull` times out, .env holds new tag but image not available; no recovery path documented [rollback.md:106].
- 60s smoke test window vs. `start_period: 60s` — docker-compose.yml sets start_period to 60s; slow JVM startup can exhaust all 12 smoke test retries during startup grace period, triggering false Auto-Revert [deploy.yml + docker-compose.yml].
- Auto-Revert fails if previous image deleted from GHCR — GHCR retention policies can evict old images; Auto-Revert pull then fails with `outcome=failed` and production may be in unknown state.
- `SSH_KNOWN_HOST` empty or multi-line edge cases — empty secret bypasses known-host verification; multi-line `ssh-keyscan` output is valid but undocumented [deploy.yml:27].
- Container name `skillars-app-1` hardcoded in expected output without explaining Docker Compose naming convention (project-service-index) [rollback.md:113].
- No explicit guidance if `docker compose pull app` fails mid-execution — the `&&` chain halts correctly, but no next-step is documented for auth errors, network timeout, or image-not-found.

## Deferred from: code review of deploy-2-2-manual-production-deploy-workflow-with-smoke-test-auto-revert (2026-06-04)
- `Fail workflow` step is unreachable if a notification step throws — job still fails (attributed to the notification step instead), same end outcome, low severity diagnostic issue [`.github/workflows/deploy.yml`:139-143].

## Deferred from: code review of deploy-2-1-automated-ci-build-pipeline (2026-06-04)
- No PR trigger — a broken `Dockerfile` or workflow is only discovered after merge to `main`; no pre-merge build validation. Add a `pull_request:` trigger (build-only, no push) in a future CI hardening pass.
- Actions pinned by floating tag not SHA digest — `checkout@v4`, `login-action@v3`, `build-push-action@v6` are mutable tags; a force-push to any tag is a supply-chain attack vector. Pin to immutable commit SHAs.
- ~~No `HEALTHCHECK` in `Dockerfile`~~ — **RESOLVED 2026-06-04**: added `HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3` using the actuator health endpoint.
- ~~No `--platform` flag on builder stage~~ — **RESOLVED 2026-06-04**: added `--platform=linux/amd64` to both `FROM` stages in `Dockerfile`.
- No `SPRING_PROFILES_ACTIVE` in `ENTRYPOINT` — the container boots on the base profile; prod-specific beans and any config not overridden by environment variables silently use dev defaults. Recommend documenting the required env var in the Compose service definition.
- No stable/latest symbolic tag alongside the SHA tag — downstream scripts and Helm charts must be updated on every push or they silently run stale images; a `main` or `latest` tag would provide a stable pointer.

## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-04)
- Repo cloned to `/opt/skillars` before Hetzner Volume mounted — volume mount overlays `/opt/skillars/data`; benign today since repo has no `data/` content, but fragile if repo structure changes.
- `acme.json` lives on root disk — server rebuild loses all TLS certificates; Let's Encrypt rate limits make reissuance slow; no backup or restore guidance exists.
- `ufw` installed by `provision.sh` but never enabled — Hetzner-level firewall is the sole protection layer with no host-level fallback [deploy/provision.sh].
- Redis data on named Docker volume (root disk), not Hetzner persistent Volume — session/cache data lost on server rebuild [docker-compose.yml].
- No outbound firewall rules — observability containers (Prometheus, Loki, Tempo, Redis) have unrestricted internet egress; security hardening enhancement.
- Docker Hub unauthenticated pull rate limits not documented — shared Hetzner egress IPs can hit the 100/6h limit; rare but unmitigated.
- Partial `provision.sh` failure recovery undocumented — `set -euo pipefail` exits on first error; re-run may silently skip a broken install block [deploy/provision.sh].
- No rollback procedure documented for a bad `APP_IMAGE` deploy when Flyway migrations have already run — operational concern for Epic 3.
- Loki (720h), Tempo (336h), Prometheus (15d) retention periods inconsistent and undocumented — no disk sizing or tuning guidance [deploy/lgtm/].
- `docker-compose-lgtm.yaml` in repo root has anonymous Grafana auth enabled and ports exposed — not warned against production use; dev-only artifact.
- No secret rotation procedure documented (PostgreSQL password, JWT secret, Grafana admin password) — ongoing operational maintenance concern.
- JWT_SECRET minimum length stated (64+) but Spring algorithm and actual enforcement not documented — application implementation detail.
- Grafana admin initial login not explicitly verified as part of Step 7 deployment completion check.
- `provision.sh` re-run while stack is live runs `chown -R` over live data mounts — safe with current UIDs but fragile on container image UID changes.

## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-03)
- Firewall applied after provisioning — SSH port 22 is open to all internet IPs during the provisioning window. Deliberate ordering constraint (Hetzner firewall requires local hcloud CLI run, user may not have local clone yet). Consider documenting the exposure window or restructuring for users who already have a local clone.
- `/dev/sdb` hardcoded device path unreliable on multi-volume servers — if Hetzner changes device assignment order the mount silently fails. The doc accuracy fix is a patch (see F2); fixing the script is Story 1.1 territory [deploy/provision.sh:145].
- Repo cloned as root into `/opt/skillars` — `.git` directory sits alongside runtime data and secrets. Pre-existing architectural decision; would require a deploy-user or sparse-checkout approach to change.
- `bantime=3600s` in fail2ban is a minimal starter value — inadequate for production. 1-hour bans are bypassed by slow-rate botnets. Pre-existing Story 1.1 config [deploy/provision.sh].
- No rollback / disaster-recovery documentation — explicitly out of scope for Story 1.5; belongs to Epic 3 (Stories 3.2 and 3.4).
- git clone root (`/opt/skillars`) contains the volume data subdirectory (`/opt/skillars/data`) — `git clean` could interact with data dirs if `.gitignore` coverage lapses. Pre-existing architecture.
- `apply-firewall.sh` accumulates old SSH allowlist rules when re-run with a different `SSH_ALLOWLIST_IP` — delete step targets `0.0.0.0/0` source, not the previously-set specific CIDR. Pre-existing script bug from Story 1.1 [deploy/firewall/apply-firewall.sh].

## Deferred from: code review of deploy-1-4-security-hardening (2026-06-03)
- `err()` writes to stderr — lost in stdout-only log capture; if callers redirect stdout to a log file, error messages won't appear in it [deploy/provision.sh].
- `touch` will error without parent dir if sections are reordered — parent dir (`${DEPLOY_ROOT}/traefik`) is created in section 5 which runs first; only an issue if the script structure is modified [deploy/provision.sh].

## Deferred from: code review of deploy-1-3-lgtm-observability-stack Round 2 (2026-06-03)
- `chown` calls in provision.sh run unconditionally on every execution — safe for first provision, but re-running against a live system can interrupt in-progress container writes; document script as "first provision only" [deploy/provision.sh].
- `${MOUNT_POINT}/postgres` has no `chown` after `mkdir -p` — Postgres (UID 999) will fail to write on a fresh volume. Pre-existing from Story 1.2; fix in Story 1.4 or a dedicated housekeeping ticket [deploy/provision.sh:126].
- Duplicate logical alert definitions for PaymentFailureRateHigh, OrangeCircuitBreakerOpen, MtnCircuitBreakerOpen exist in both `alerts.yml` (Prometheus rules) and `grafana-alerts.yml` (Grafana unified alerts) — different notification paths with no Alertmanager wired; revisit when Alertmanager is added to avoid double-paging.

## Deferred from: code review of deploy-1-3-lgtm-observability-stack (2026-06-03)
- Alert rule divide-by-zero guards (CallbackFailureRatioHigh, FraudBlockRateHigh, PaymentFailureRateHigh) in `deploy/lgtm/alerts.yml` — pre-existing in root `alerts.yml`; copied per spec. Guards like `and (...) > 0` needed on all ratio denominators.
- `DbConnectionPoolHigh` alert has no label selector — pre-existing in root `alerts.yml`. Add `by (pool)` clause or label filter.
- TraceID regex `[a-f0-9]{32}` only matches lowercase hex; OTel SDKs may emit uppercase. Pre-existing in root `grafana-datasources.yml`.
- `spanStartTimeShift`/`spanEndTimeShift` of 1h creates extremely wide Loki query windows on trace drill-down. Pre-existing in root `grafana-datasources.yml`. Reduce to 1m/1m.
- Prometheus has no `depends_on: app` in compose — cold-start scrape failures on first `docker compose up`. Acceptable gap; scrapes recover once app is healthy.
- LGTM data `mkdir -p` calls gated inside Hetzner Volume device `if [ -b ]` check — consistent with existing postgres pattern. If volume is absent at provision time, Docker auto-creates dirs as root (further compounds the permission issue once it's resolved).

## Deferred from: code review (2026-05-26)
- ~~Potential Path Traversal [S3StorageService.java]~~ — **RESOLVED 2026-05-28**: `StorageKeyGenerator` strips `/` from `entity` and `entityId` inputs via `[^a-zA-Z0-9_-]` sanitization. The `/` chars in the composed S3 key come exclusively from hardcoded format-string separators, not user input.
- ~~Unbounded Thread Pool [BlobstoreConfig.java]~~ — **RESOLVED 2026-05-28**: Replaced `Executors.newFixedThreadPool` with `ThreadPoolExecutor` backed by `ArrayBlockingQueue(100)` and `CallerRunsPolicy`. Pool size and queue capacity configurable via `app.storage.executor.*`.
