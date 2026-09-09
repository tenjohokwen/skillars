# Story Review: skillars-deferred-105 CI Build-Warning Cleanup

**Reviewer:** Senior Dev Audit  
**Date:** 2026-09-09  
**Overall Assessment:** Story is well-structured and ready for dev. No false positives detected. Seven minor validation gaps identified below.

---

## Findings by Acceptance Criterion

### AC1: docker/build-push-action Bump to v7.3.0

**Status:** ✅ Ready, one verification gap

**Identified gaps:**
1. **Missing release-notes check for v7.1.x and v7.2.x** — The story documents that v7.0.0 made no breaking input changes and only removed two deprecated env vars. However, it does not verify release notes for v7.1.0 through v7.3.0 for additional removals or input changes. This is a *minor* gap because:
   - v7.0.0 was the major bump; minor/patch releases historically don't remove inputs
   - The dev agent instruction already says "re-resolve the SHA against the tag" and implicitly review GitHub for any issues
   - Worth confirming: dev should scan `https://github.com/docker/build-push-action/releases/tag/v7.3.0` for "BREAKING" or "removed" in release notes

2. **PR runner platform assumption** — The story says "the runner is amd64" (line 106) and assumes PR runs use the same platform as CI runs. GitHub-hosted `ubuntu-latest` is amd64, but this assumption is not explicitly stated. It's safe (GitHub's hosted runners are amd64), but should be explicit for audit clarity.

**Verification coverage:** ✅ Adequate
- The grep checks for deprecated env vars (`DOCKER_BUILD_NO_SUMMARY`, `DOCKER_BUILD_EXPORT_RETENTION_DAYS`) are correct and comprehensive.
- The check that "no self-hosted runners are in use" is thorough.
- Post-merge verification (PR CI shows no warning, Docker build still succeeds) is solid.

---

### AC2: Remove `--platform=linux/amd64` from Dockerfile

**Status:** ✅ Ready, two assumptions should be verified

**Identified gaps:**

1. **Base image platform-specific behavior not checked** — The story claims removing the flag won't affect builds because BuildKit + `docker/build-push-action`'s `platforms: linux/amd64` input already constrain the arch. However, it does not verify:
   - Whether `maven:3.9-eclipse-temurin-17` or `eclipse-temurin:17-jre-alpine` have platform-specific setup scripts or behaviors that might be triggered/bypassed by the flag
   - This is a *very low risk* — modern base images are platform-agnostic — but "assume" is present

2. **"Behavior-preserving" claim needs test execution** — The story lists verification steps (local `docker build .` emits no warning; Trivy scan still runs; image is functional), but does not require running these *before* the PR. The story says `pr-build.yml` and `ci.yml` will exercise the change, which is correct. However, the phrasing "A local docker build…" on line 117 reads like a suggestion, not a requirement. Clarification: this test *will* run in CI; no local-before-push is needed per project policy.

**Verification coverage:** ✅ Adequate
- The story correctly identifies that `platforms: linux/amd64` in `docker/build-push-action` already constrains the build.
- The reference to `.trivyignore` and `APK_UPGRADE_CACHE_BUST` as "leave exactly as-is" correctly preserves unrelated mechanisms.
- Post-merge CI verification (Trivy scan, healthcheck endpoint reachable) is comprehensive.

---

### AC3: Suppress `AntPathRequestMatcher` Deprecation Warning

**Status:** ✅ Ready, one architectural assumption unchallenged

**Identified gaps:**

1. **Runtime bean selection logic not explicitly verified** — The story claims:
   - "requestMatchers(String...) in SecurityConfiguration picks **at runtime** between AntPathRequestMatcher and PathPatternRequestMatcher depending on whether a unique PathPatternRequestMatcher.Builder bean exists."
   - The test "asserts every permitAll pattern resolves to the **same** set of URLs under *both* matchers."
   
   The story does not verify:
   - That the `PathPatternRequestMatcher.Builder` bean existence is actually checked at runtime in `SecurityConfiguration`
   - That the test currently exercises both code paths (with and without the bean in the test context)
   - What happens if the bean is present vs. absent during the test run
   
   **Why this matters:** If the test only exercises one code path (e.g., always without the bean), then removing the AntPathRequestMatcher comparison would be harmless, and the @SuppressWarnings is burying a non-functional assertion, not protecting a guard.
   
   **Risk level:** *Low* — The story references `skillars-deferred-91` AC15 and `skillars-deferred-92` AC15.1 as prior decisions. Those stories presumably validated the two-matcher guard, so this is audit heritage, not new code.

**Verification coverage:** ✅ Adequate
- The story correctly prohibits switching to `PathPatternRequestMatcher` (which would make the test compare PathPattern to itself).
- The scoped `@SuppressWarnings("removal")` + explanatory comment is the right fix.
- Test passes verification is included.

---

### AC4: Fix Non-Varargs Varargs Call in `ApiAdvice.logErrorAndReturnDTO`

**Status:** ✅ Ready, no gaps

**Identified gaps:** None

**Notes:**
- The cast `(Object[]) args` correctly suppresses the javac ambiguity warning without changing runtime behavior.
- The three `handleSecErrorAndReturnDTO` overloads correctly remain untouched (they have no mismatch: `String... args` → `String...`).
- Verification (existing tests still pass; localized message substitution still works) is adequate.

---

### AC5: Replace Deprecated `Specification.where(...)`

**Status:** ✅ Ready, two precondition assumptions unchallenged

**Identified gaps:**

1. **`isActive()` always-non-null assumption not explicitly verified** — The story claims:
   - "isActive() → always returns a non-null Specification (status IN (ACTIVE, REDUCED))."
   
   This assumption is stated as fact but not verified against the actual method. The story should have:
   - Quoted or verified the `isActive()` method definition
   - Confirmed it unconditionally returns a Specification (no branches that could return null)
   
   **Why this matters:** If `isActive()` can return null under any condition, the new chain starting with `isActive().and(...)` would throw NPE instead of the current (deprecated) `Specification.where(null)` null-check.
   
   **Risk level:** *Very low* — `isActive()` building a "status IN (ACTIVE, REDUCED)" constant spec is unlikely to have branches. But this is an assumption, not verified.

2. **`inCity(String)` "city is required" assumption** — The story claims:
   - "inCity(String) → always non-null (city is required; unconditional cb.equal)."
   
   The story does not verify:
   - That the caller `CoachSearchService` enforces city as required (i.e., does not pass null)
   - That the ProfileBuilderStep1Request marks city as @NotNull
   - What happens downstream if city is unexpectedly null at runtime
   
   **Why this matters:** The fix depends on `inCity(p.city())` never being null. If city can be null at the call site, the new chain would throw NPE.
   
   **Risk level:** *Low* — The story says "city is required" as domain fact. The verification includes "city / district / skill filters each still applied when their param is set and skipped when it is null", which implicitly tests city=null handling. But the assumption is not cross-verified with the caller.

**Verification coverage:** ✅ Adequate
- The alternative `Specification.allOf(...)` is correctly noted as acceptable.
- The decision to start from `isActive()` instead of using `allOf(...)` is sound (smaller diff, reads like existing code).
- Post-merge test verification (ACTIVE/REDUCED coaches returned, filters skipped when null) validates the behavior is preserved.

---

### AC6: Resolve MapStruct Unmapped Target Properties

**Status:** ✅ Ready, one consistency assumption and one test-coverage gap

**Identified gaps:**

1. **AC6a and AC6b: Per-method @BeanMapping scope is correct, but edge case not considered** — The story correctly uses `@BeanMapping(unmappedTargetPolicy = ReportingPolicy.IGNORE)` on only the forward mapping (toAuditLog, toUser), so reverse mappings keep the WARN guard for new columns.
   
   However, the story does not explicitly verify:
   - What happens if a new audit/persistence field is added to the source DTO (e.g., a new field in AuditLog that should trigger a warning in toAuditTrail)
   - The story says "Per-method only" to preserve the guard, which is correct, but doesn't document what the guard should catch
   
   **Risk level:** *Very low* — The strategy (per-method IGNORE, mapper-wide WARN default) is architecturally sound. This is documentation, not a bug.

2. **AC6c: CoachProfileMapper.toEntity consistency** — The story says:
   - "This mapper already enumerates every field explicitly (`id`, `bio`, `photoUrl`, `status`, `createdAt` are already `ignore = true`), so keeping that style preserves the 'new column ⇒ build tells you' guard."
   - The story adds 4 new `@Mapping(target = …, ignore = true)` lines for the unmapped fields.
   
   The story does not verify:
   - That the 4 new fields (`verificationTier`, `averageRating`, `reviewCount`, `statusChangedAt`) are truly server-derived and should **never** be mapped
   - Whether there are tests that would catch if these fields were incorrectly set (e.g., a test that checks verificationTier is correctly left as its default/null value)
   
   **Risk level:** *Low* — The story contextualizes these as "server-derived / lifecycle fields, never client-supplied", which is domain knowledge. The decision is correct. But no test is explicitly cited to validate that the fields remain unset.

**Verification coverage:** ✅ Adequate
- The decision to split AC6 into three different approaches (per-method IGNORE for 6a/6b, explicit @Mapping for 6c) is well-justified and internally consistent.
- Post-merge tests (audit-trail, user-DTO round-trip, coach profile-builder tests) validate behavior is preserved.
- No new tests required; existing suites cover regression.

---

### AC7: Confirmation and Sibling Sweep

**Status:** ✅ Ready, one ambiguity in criteria

**Identified gaps:**

1. **"Trivial siblings" not explicitly defined** — The story says:
   - "fix in-story only if each is a one-line mechanical change of the identical kind; otherwise record as a follow-up, do not expand scope silently"
   
   The story does not define what "one-line mechanical change of the identical kind" means in context:
   - For `Specification.where()`: does this mean only the first `.where()` removal, or all removals of the same pattern?
   - For `AntPathRequestMatcher` usages: does this mean only in tests, or also in production code? (The story searches `src`, which includes both.)
   - For unmapped-target warnings: does this mean only warnings in the same CI run, or any mapper currently WARN'ing?
   
   **Why this matters:** If a sibling call site requires a logic change (e.g., a `Specification.where()` nested inside a conditional), the dev might not know whether to fix it in-story or defer.
   
   **Suggested clarification for dev:** All siblings of the exact pattern (e.g., `Specification.where(…).and(…)` → drop `where(`) are in-story fixes. If a sibling requires conditional logic or additional testing, defer as a follow-up story.

**Verification coverage:** ✅ Adequate
- The grep patterns are comprehensive: `Specification.where(`, `AntPathRequestMatcher`, `build-push-action|node20|node16`, `unmappedTargetPolicy|@Mapper(`.
- The sweep is required (line 280: "Record the sweep result … in the Dev Agent Record"), which is correct.
- The instruction to record in the Dev Agent Record ensures traceability.

---

## Cross-Cutting Observations

### ✅ No False Positives Detected

All seven acceptance criteria correctly identify real issues:
1. **AC1:** Node 20 deprecation is real; v7.3.0 is available and safe.
2. **AC2:** `FromPlatformFlagConstDisallowed` rule is real (Docker build-checks); flag removal is safe for single-arch.
3. **AC3:** `AntPathRequestMatcher` removal deprecation is real (Spring Security 6 roadmap); suppression with comment is appropriate.
4. **AC4:** Varargs ambiguity warning is real (javac caveat); cast is correct.
5. **AC5:** `Specification.where()` deprecation is real (Spring Data JPA 3.5 → 4.0); `.and(null)` remains safe.
6. **AC6:** MapStruct unmapped-target warnings are real; split strategy (explicit vs. per-method IGNORE) is sound.
7. **AC7:** Sibling sweep validates no silent scope creep; CI green is the acceptance gate.

### ✅ Project Structure Preserved

- No functional or behavioral change to endpoints, queries, or image contents.
- Existing test suites provide regression coverage.
- No new tests required (warnings-only story).
- Convention alignment (GitHub Actions SHA-pin + comment; MapStruct per-method IGNORE; Java 17 / Spring Boot 3.5.11 versions) is respected.

### ✅ Decision Rationale Clear

All four project-owner decisions (D1–D4) are documented and have signed-off rationale:
- D1: Split MapStruct strategy balances safety (CoachProfileMapper explicit enumerations catch new columns) vs. noise (AuditTrailMapper/UserMapper audit plumbing).
- D2: Delete `--platform` flag (redundant with `docker/build-push-action`'s input; revisit only if multi-arch planned).
- D3: Start chain from `isActive()` (smallest diff, reads like existing code).
- D4: Bump to v7.3.0 latest (consistent with repo's other v4/v6/v7 major versions).

---

## Summary

**Recommendation:** Proceed with dev. The seven identified gaps are documentation/verification matters (unchallenged but reasonable assumptions), not design flaws. None would change the acceptance criteria or fix approach.

**For the dev agent:**
- Before AC1 commit: Scan `docker/build-push-action` v7.3.0 release notes for "BREAKING" or "removed".
- Before AC5 commit: Spot-check `CoachSearchSpecification.isActive()` and the caller `CoachSearchService.build()` to confirm city parameter is truly required.
- After AC7 sibling sweep: If a sibling requires logic changes or extra testing, record as deferred follow-up story (do not expand scope).

No false positives, no missed flows, story is ready.
