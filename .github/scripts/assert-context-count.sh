#!/usr/bin/env bash
# Fails the build when the integration suite builds more Spring contexts than the agreed
# ceiling. Story deferred-19, AC3/AC10.3.
#
#   assert-context-count.sh <build-log> [ceiling]
#
# Spring's DefaultContextCache logs, at DEBUG under org.springframework.test.context.cache:
#
#   Spring test ApplicationContext cache statistics: [DefaultContextCache@1b2c3d4
#   size = 7, maxSize = 32, parentContextCount = 0, hitCount = 122, missCount = 7,
#   failureCount = 0]
#
# missCount is the number of contexts actually BUILT. Because it also counts rebuilds after
# LRU eviction it is an upper bound on the number of distinct contexts, which is the right
# thing to gate on: it cannot under-report a regression.
#
# The category is enabled in src/test/resources/logback-test.xml. If that is removed this
# script exits non-zero rather than silently passing -- a gate that cannot find its input is
# a gate that never fires, which is worse than no gate at all.

set -uo pipefail

LOG="${1:?usage: assert-context-count.sh <build-log> [ceiling]}"

# Ceiling, and why it is not AC3's original 10.
#
# The story targeted <= 10 on the assumption that each test family could share one
# @MockitoBean set. That assumption does not hold: the video family CONTAINS the real
# integration tests for QuotaService, VideoLifecycleService and ModerationOrchestrationService
# (QuotaServiceConcurrencyIT, VideoRetryUploadIT, WebhookPipelineIT, VideoLifecycleLogIT,
# MinorSafetyGateIT -- all extending BaseVideoIT). Hoisting those mocks onto the family base to
# reach 10 would replace the system under test in those classes and they would keep passing
# while asserting nothing.
#
# Two further reasons the number here is larger than the offline analysis reports (20):
#   - ~11 @WebMvcTest slice classes build their own cut-down contexts. They start no container
#     and are cheap, but they still count towards missCount.
#   - @DirtiesContext forces rebuilds. RateLimitingAspectIT still uses AFTER_CLASS.
#     ConfigResourceIT used AFTER_EACH_TEST_METHOD and contributed several on its own until
#     it was removed; that single change took CI from 37 to 34.
#
# CEILING = 36, tightened from 42.
#
# Measured, all on the same tree (721b3e1):
#   CI, pr-build   34   (PR #33, run 31263728801)
#   CI, master     34   (run 31264226119)
#   local          32   (full mvn verify -DskipFrontend)
#
# 36 gives +2 over the reproducible CI figure. 42 was set when the steady state was 37 and is
# now loose enough to ABSORB a regression rather than catch one, which matters more than it
# used to: both runs above report the cache at `size = 32, maxSize = 32`, i.e. Spring's default
# cache is exactly full. At capacity, one new context configuration does not cost +1 -- it also
# evicts something still in use, which is then rebuilt. That is the thrashing this story
# existed to remove, so the gate should fire early rather than late.
#
# Honest caveat: the local 32 vs CI 34 gap is NOT fully explained. Both run the same 905 tests
# with the same 53 skipped, so it is most likely execution order interacting with eviction and
# with RateLimitingAspectIT's AFTER_CLASS dirtying. The gate runs in CI, so it is set from the
# CI number.
#
# If this starts failing intermittently at 35-36, do NOT just raise the number: that variance
# would mean the cache is thrashing on ordering alone, and the fix is to remove a context
# configuration (or the remaining @DirtiesContext) so the suite sits below 32 distinct keys
# with room to spare.
#
# CEILING = 37, deliberate +1 (skillars-deferred-83 AC1). AccountDeletionCascadeIT's
# outboxRepository field moved from @Autowired to @MockitoSpyBean (mirrors CaptureReservationIT/
# MessageModerationSweeperIT's own established "spy on a real repository to prove commit/
# rollback" precedent). That changes the class's ContextCustomizer override set from
# {VideoProviderAdapter} to {VideoProviderAdapter, VideoDeletionOutboxRepository}, which no
# longer matches whichever other class it previously shared a context with -- forking a new one.
# This is the deterministic, one-time cost the @MockitoBean trap describes (see
# docs/testing/why-inheritance-over-import.md), not ordering-dependent thrashing: every local and
# CI run of this branch reproduces exactly 37, not a range.
#
# CEILING = 39, deliberate +2 (story ses-1.2, SmtpTransportBootIT). This class's own javadoc
# already names the +1 it expects and accepts: it is the only place the shipped
# `transport=smtp` path is verified end-to-end (real component scan, real YAML binding, a real
# socket conversation with a fake SMTP server) via @DynamicPropertySource -- which is
# structurally invisible to IntegrationTestConventionTest's static checks, so nothing catches it
# before this gate does. Measured on PR #179 (run 34675738460): missCount went 37 -> 39, not 38,
# because the suite runs at cache capacity (`size = 32, maxSize = 32`) by this point -- exactly
# the "one new config also evicts and later re-forks something else" dynamic this file's own
# CEILING=36 section above already documents, not a second, separate regression. Reproduced
# twice at 39 on the same tree; not a 37-39 range, so this is not the ordering-thrashing case the
# warning below guards against.
#
# CEILING = 40, deliberate +1 (story ses-1.4, RegistrationEmailDurabilityIT). AC6's own story
# text names and accepts this cost explicitly: BookingReminderEmailWiringIT (the obvious model)
# runs with `enable.test.mail=true`, which swaps the whole MailManager bean for TestMailManager
# and never produces an EnvelopeEntity row -- structurally unable to assert this story's
# FAILED/DEADLINE_EXPIRED re-drive behaviour. RegistrationEmailDurabilityIT instead runs with
# `enable.test.mail=false` so the real MailManager bean is active, which is a distinct
# ContextCustomizer set from every other `enable.test.mail=true` IT and therefore forks its own
# context, the same one-new-config-forks-one-context shape as SmtpTransportBootIT above.
# Measured on PR #183 (run 34808851693): missCount went 39 -> 40.
#
# CEILING = 41, deliberate +1 (story skillars-deferred-114, SesCutoverPreflightResourceIT).
# A @WebMvcTest slice for the new admin-only SES cutover preflight endpoint
# (POST /v1/admin/ses/preflight), forced onto `app.email.transport=ses` via
# @TestPropertySource (the shared integration context runs `transport=log`) plus its own
# hand-rolled TestSecurityConfig and a five-@MockitoBean set unique to this class
# (SesAccountChecker, MailManager, EnvelopeEntityRepository, plus VideoMetrics/JwtSecretService
# for the global VideoApiAdvice/SecurityAdviceFilter beans every @WebMvcTest slice pulls in) --
# the same one-new-config-forks-one-context shape SmtpTransportBootIT and
# RegistrationEmailDurabilityIT above already document, not ordering-dependent thrashing: this
# is the first and only @PreAuthorize-enforcement test in this codebase (no existing slice to
# extend instead), so a new fork was unavoidable. Measured on PR #193 (run 35073092775):
# missCount went 40 -> 41.
#
# CEILING = 42 at pr-build.yml's call site by skillars-deferred-121 (PR #209, new
# AdminCoachEnforcementConcurrencyIT), but that bump was never appended here -- caught in
# passing while adding the entry below; not re-investigated further since it is not this
# story's regression to fix.
#
# CEILING = 43, deliberate +1 (story skillars-deferred-123, AdminCoachEnforcementIsolationRuntimeIT).
# The first (and only) class in the suite to @MockitoSpyBean CoachProfileRepository -- needed to
# read the *effective* Postgres isolation level from inside a real getEnforcementProfile/
# getCoachesUnderEnforcement call (the story's own correction of an originally-specified
# two-JDBC-connection design that could not have observed Spring's `validateExistingTransaction
# = false` hazard at all), replacing what AdminCoachEnforcementServiceIsolationTest could only
# prove by reflection. Same one-new-config-forks-one-context shape as
# AccountDeletionCascadeIT/SmtpTransportBootIT/SesCutoverPreflightResourceIT above, not
# ordering-dependent thrashing. Measured in CI: missCount went 42 -> 43.
# CEILING = 44, deliberate +1 (story skillars-deferred-124 AC2, VideoDeletionOutboxProcessorIT).
# The new isolation test proving the outer-loop per-row exception guard (process()'s new try/catch
# around processRow) needs a failure OUTSIDE the class's only existing seam (a @MockitoBean on
# VideoProviderAdapter, which only covers the deleteAsset path) -- so the class gained a second
# override, @MockitoSpyBean DrillVideoRefRepository, to force findByVideoId to throw for one row.
# Same one-new-config-forks-one-context shape as AccountDeletionCascadeIT/SmtpTransportBootIT/
# AdminCoachEnforcementIsolationRuntimeIT above, not ordering-dependent thrashing -- no other test
# class in the suite combines these two overrides. Expected +1 from local reasoning (no full local
# `mvn test` run per this project's own no-mvn-verify-locally convention); confirm the exact
# pre->post missCount against this PR's actual CI run and correct this note if it differs.
#
# CEILING = 45, deliberate +1 (story skillars-deferred-128, /bmad-code-review response).
# GdprErasureIT's atomicity test originally raced a 1ms budget; the review replaced that with a
# @MockitoSpyBean on RefreshTokenRepository (alongside the class's pre-existing @MockitoBean
# FileStorageService) so it can force a deterministic failure from markAllUsedByUserId, the first
# step after the PARENT loop, instead of racing AC2's own deadline. No other class in the suite
# combines these two overrides, so this forks its own context -- same one-new-config-forks-
# one-context shape as every entry above, not ordering-dependent thrashing. Confirmed against this
# PR's own CI run (35770394422): missCount went 44 -> 45.
#
# CEILING = 47 (story skillars-deferred-159, PR #296). Measured in CI: 47 on three consecutive runs of
# the same commit vs 44 on master's last PR build (#295), with ZERO context-affecting test changes in
# the diff (no @MockitoBean/@MockitoSpyBean/@TestPropertySource/@DynamicPropertySource/@Import/
# @DirtiesContext added; the only IT touched, SessionCompletionResourceIT, gained an @Autowired field).
# The failsafe class order differed wholesale between the master run and this PR's runs, and the cache
# is at size = maxSize = 32, so this is the order-vs-eviction thrashing described above rather than a
# new fork. Raised to the measured number per this file's precedent; the header's advice to remove a
# context configuration instead still stands as the real fix.
# CEILING = 17 (story skillars-deferred-160) -- PREDICTED, not yet measured in CI: confirm it against
# the story's first green pr-build and correct it to the measured missCount if it differs.
# The real fix this header kept asking for: the failsafe JVM had 47 live distinct context
# configurations (cache permanently at size = maxSize = 32, so LRU evicted and rebuilt contexts and
# missCount depended on class order). The story's changes bring that to a predicted 17:
#   - 14 @WebMvcTest slices renamed *IT -> *WebMvcTest, so surefire runs them in its own JVM;
#   - 4 per-class event-capture configs replaced by one shared RecordedEvents bean (TestConfig);
#   - redundant @Import(E2ESecurityConfig) dropped from 2 classes (their @Sql already seeds main.sec);
#   - @AutoConfigureMockMvc hoisted onto AbstractIntegrationTest;
#   - 8 @MockitoSpyBean declarations hoisted onto AbstractIntegrationTest;
#   - the VideoProviderAdapter-only / FileStorageService-only mock sets unified into one;
#   - MessageModerationSweeperIT (a full standalone context) moved onto the base.
# The 17 = 14 AbstractIntegrationTest configurations (pinned by IntegrationTestConventionTest's
# EXPECTED_DISTINCT_CONTEXT_KEYS) + 3 allowlisted sliced @SpringBootTest classes. Below capacity,
# missCount should be deterministic, so this ceiling is exact rather than padded.
#
# Two capacity checks were added alongside it, on the same final statistics line:
#   - size >= maxSize fails: a full cache means eviction, which is where order-dependent counts
#     come from;
#   - missCount - size > 1 fails: contexts were built more often than they are held, i.e. rebuilt.
#     The allowed 1 is RateLimitingAspectIT's @DirtiesContext(AFTER_CLASS), which closes its context
#     (size drops) while missCount keeps counting it; it is 0 only if that class happens to run last.
#     Seen in CI run 40016101569 before the cache filled: its early statistics lines read
#     size = 6/missCount = 7, size = 7/missCount = 8 once RateLimitingAspectIT had run. (Its final
#     line, size = 32/missCount = 47, is the full-cache state this story removes.)
CEILING="${2:-17}"
# Contexts that legitimately leave the cache while still counted in missCount (see above).
DIRTIED_ALLOWANCE=1

if [ ! -f "$LOG" ]; then
  echo "assert-context-count: build log not found: $LOG" >&2
  exit 1
fi

# -a (--text) is REQUIRED, not defensive. The build log contains null bytes from test output,
# so without it grep decides the file is binary and prints "binary file matches" INSTEAD of the
# match -- making this gate silently find nothing. That single missing flag is what made three
# successive attempts at this gate look like a file-plumbing problem.
# Every number below comes from ONE line: the final cache statistics line. Reading missCount and
# size with separate greps could pair values from different lines.
stats=$(grep -aoE 'size = [0-9]+, maxSize = [0-9]+, parentContextCount = [0-9]+, hitCount = [0-9]+, missCount = [0-9]+' "$LOG" | tail -1)
last=$(echo "$stats" | sed -nE 's/.*missCount = ([0-9]+)$/\1/p')

if [ -z "$last" ]; then
  echo "assert-context-count: no 'missCount = N' found in $LOG." >&2
  echo "The org.springframework.test.context.cache DEBUG logger in" >&2
  echo "src/test/resources/logback-test.xml is what emits it. Failing rather than" >&2
  echo "passing silently: a gate that cannot find its input is not a gate." >&2
  exit 1
fi

size=$(echo "$stats" | sed -nE 's/^size = ([0-9]+),.*/\1/p')
max_size=$(echo "$stats" | sed -nE 's/.*maxSize = ([0-9]+),.*/\1/p')

echo "Spring contexts built (missCount): $last   (ceiling: $CEILING)"
echo "Context cache at the end: size = ${size:-?}, maxSize = ${max_size:-?}"

if [ -z "$size" ] || [ -z "$max_size" ]; then
  echo "assert-context-count: could not parse 'size = N, maxSize = M' from the last statistics line." >&2
  echo "Last line seen: ${stats:-<none>}" >&2
  exit 1
fi

if [ "$size" -ge "$max_size" ]; then
  cat >&2 <<EOF

FAIL: the Spring test-context cache is full (size = $size, maxSize = $max_size).

A full cache evicts its least-recently-used context and rebuilds it when a later class needs
it again, so the number of contexts built starts to depend on test-class order. That is the
flakiness skillars-deferred-160 removed. Remove a context configuration rather than raising
the cache size: IntegrationTestConventionTest's distinctContextKeyCountIsPinned lists them all.
EOF
  exit 1
fi

if [ $((last - size)) -gt "$DIRTIED_ALLOWANCE" ]; then
  cat >&2 <<EOF

FAIL: $last contexts were built but only $size are held (allowance: $DIRTIED_ALLOWANCE).

Contexts were rebuilt. With room in the cache that means something is closing contexts:
most likely a new @DirtiesContext. The allowance covers RateLimitingAspectIT's AFTER_CLASS.
EOF
  exit 1
fi

if [ "$last" -gt "$CEILING" ]; then
  cat >&2 <<EOF

FAIL: the suite built $last Spring contexts, above the ceiling of $CEILING.

Each extra context is a full Spring Boot startup. Before AC1 it also meant another
PostgreSQL and another Redis container; that is fixed, but the startup cost is not.

The usual cause is a test class that declares its own @SpringBootTest, @ActiveProfiles,
@TestPropertySource, @Import or -- most often -- an extra @MockitoBean, instead of
extending AbstractIntegrationTest unchanged. IntegrationTestConventionTest catches most of
these in the test phase; if it passed and this failed, run the offline analysis to find
which class forked: IntegrationTestConventionTest's distinctContextKeyCountIsPinned
prints every configuration with its classes when its expected count does not match, e.g.

    mvn -o test -DskipFrontend -Dtest=IntegrationTestConventionTest

See docs/testing/ for why this matters and how to add a deliberate fork.
EOF
  exit 1
fi

echo "OK: context count within ceiling."
