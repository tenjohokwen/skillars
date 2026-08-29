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
CEILING="${2:-37}"

if [ ! -f "$LOG" ]; then
  echo "assert-context-count: build log not found: $LOG" >&2
  exit 1
fi

# -a (--text) is REQUIRED, not defensive. The build log contains null bytes from test output,
# so without it grep decides the file is binary and prints "binary file matches" INSTEAD of the
# match -- making this gate silently find nothing. That single missing flag is what made three
# successive attempts at this gate look like a file-plumbing problem.
last=$(grep -aoE 'missCount = [0-9]+' "$LOG" | tail -1 | grep -oE '[0-9]+' || true)

if [ -z "$last" ]; then
  echo "assert-context-count: no 'missCount = N' found in $LOG." >&2
  echo "The org.springframework.test.context.cache DEBUG logger in" >&2
  echo "src/test/resources/logback-test.xml is what emits it. Failing rather than" >&2
  echo "passing silently: a gate that cannot find its input is not a gate." >&2
  exit 1
fi

echo "Spring contexts built (missCount): $last   (ceiling: $CEILING)"

if [ "$last" -gt "$CEILING" ]; then
  cat >&2 <<EOF

FAIL: the suite built $last Spring contexts, above the ceiling of $CEILING.

Each extra context is a full Spring Boot startup. Before AC1 it also meant another
PostgreSQL and another Redis container; that is fixed, but the startup cost is not.

The usual cause is a test class that declares its own @SpringBootTest, @ActiveProfiles,
@TestPropertySource, @Import or -- most often -- an extra @MockitoBean, instead of
extending AbstractIntegrationTest unchanged. IntegrationTestConventionTest catches most of
these in the test phase; if it passed and this failed, run the offline analysis to find
which class forked:

    python3 scratchpad/ctxkeys.py

See docs/testing/ for why this matters and how to add a deliberate fork.
EOF
  exit 1
fi

echo "OK: context count within ceiling."
