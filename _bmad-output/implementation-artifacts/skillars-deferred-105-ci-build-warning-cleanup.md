# skillars-deferred-105: CI Build-Warning Cleanup (GitHub Actions Node 20, Dockerfile `--platform`, Spring deprecations, MapStruct unmapped targets)

**Status:** done | **Epic:** deferred | **Priority:** low
**Story ID:** deferred-105
**Branch:** `story/deferred-105-ci-build-warning-cleanup`
**Created:** 2026-09-09
**Base:** master @ `f65a000e` (immediately after `skillars-deferred-103` merge, PR #161)

> **Numbering note:** `skillars-deferred-104` is reserved for the `frontend-test-framework-initiative`
> story (Vitest + Vue Test Utils stand-up) per `skillars-deferred-103` decision D3. This warnings
> cleanup takes `-105` to keep that reservation intact.

---

## Story Overview

As the Skillars platform team,
I want every build warning the remote GitHub CI currently emits driven to zero,
so that a genuinely new warning in a future PR is visible instead of being lost in a wall of
known, ignored noise — and so the toolchain deprecations (GitHub Actions Node 20, Spring Security
`AntPathRequestMatcher`, Spring Data JPA `Specification.where`) are handled on our schedule rather
than on the day they become hard errors.

This is a **warnings-only** story. There is **no functional or behavioral change** to any endpoint,
query result, Docker image contents, or CI job graph. Every acceptance criterion's verification is
`mvn -B verify` green + the relevant existing tests still passing + (for AC1/AC2) the PR build's
Docker build / scan / push steps still succeeding.

### The seven warning classes (verbatim from the CI run on the pre-story build)

| # | Warning | Location | AC |
|---|---------|----------|----|
| 1 | `Node.js 20 is deprecated. ... actions target Node.js 20 but are being forced to run on Node.js 24: docker/build-push-action@10e90e36...` | `.github/actions/docker-build/action.yml` | AC1 |
| 2 | `FromPlatformFlagConstDisallowed: FROM --platform flag should not use constant value "linux/amd64"` | `Dockerfile#L15` | AC2 |
| 3 | `FromPlatformFlagConstDisallowed: FROM --platform flag should not use constant value "linux/amd64"` | `Dockerfile#L2` | AC2 |
| 4 | `AntPathRequestMatcher ... has been deprecated and marked for removal` | `src/test/java/com/softropic/skillars/platform/security/config/AppEndpointsConventionTest.java#L135` | AC3 |
| 5 | `non-varargs call of varargs method with inexact argument type for last parameter` | `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java#L689` | AC4 |
| 6 | `where(Specification<T>) in ... Specification has been deprecated and marked for removal` | `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachSearchSpecification.java#L24` | AC5 |
| 7a | `Unmapped target properties: "id, createdBy, createdDate, lastModifiedBy, lastModifiedDate, status".` | `src/main/java/com/softropic/skillars/platform/security/audit/api/AuditTrailMapper.java#L13` | AC6 |
| 7b | `Unmapped target properties: "id, createdBy, ... verificationStatus".` | `src/main/java/com/softropic/skillars/platform/security/service/UserMapper.java#L34` | AC6 |
| 7c | `Unmapped target properties: "verificationTier, averageRating, reviewCount, statusChangedAt".` | `src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachProfileMapper.java#L22` | AC6 |

> Line numbers are accurate as of base `f65a000e`. The dev agent **must re-diff each cited line
> before editing** — unrelated commits age line numbers.

---

## Project-owner decisions (captured + signed off 2026-09-09 during story creation)

| # | Question | Decision (SIGNED OFF) |
|---|----------|----------------------|
| D1 | MapStruct unmapped-target warnings (AC6): explicit `@Mapping(target = …, ignore = true)` per property, per-method `@BeanMapping(unmappedTargetPolicy = ReportingPolicy.IGNORE)`, or mapper-wide `IGNORE`? | **Split.** `CoachProfileMapper.toEntity` → explicit `@Mapping(… ignore = true)` for its 4 fields (mapper already lists every field; keeps the "new column ⇒ build tells you" guard). `AuditTrailMapper.toAuditLog` + `UserMapper.toUser` → per-**method** `@BeanMapping(unmappedTargetPolicy = ReportingPolicy.IGNORE)` (6 / ~20 unmapped fields, all audit/persistence plumbing — enumerating them is noise). Per-method, **not** mapper-wide, so every sibling method keeps the default `WARN` guard. |
| D2 | `Dockerfile` `FROM --platform=linux/amd64` (AC2): delete the flag, or switch to `$BUILDPLATFORM`? | **Delete the flag from both `FROM` lines.** The build is single-arch and `docker/build-push-action` is already passed `platforms: linux/amd64` — the Dockerfile self-pin is redundant. `$BUILDPLATFORM` is only worth it if a multi-arch build is on the roadmap; it is not. |
| D3 | `Specification.where(...)` replacement (AC5): start the chain from `isActive()`, or use `Specification.allOf(...)`? | **Start from `isActive()`** (`isActive()` and `inCity(...)` are always non-null; `Specification#and` stays null-tolerant and is not deprecated). Smallest diff, reads like the existing chain. `allOf(...)` is an acceptable alternative — dev's discretion. Do **not** use `Specification.unrestricted()` (not needed; may be absent in the pinned Spring Data JPA 3.5.x). |
| D4 | `docker/build-push-action` target version (AC1): latest `v7.3.0`, or minimal jump to `v7.0.0`? | **`v7.3.0`** (latest, 2026-07-01). Consistent with the repo already running current majors of every other action (`actions/checkout@v7.0.1`, `actions/setup-java@v6.0.0`, `docker/setup-buildx-action@v4.2.0`). v7.0.0 is the major that moved to Node 24; v7.3.0 carries only patch/minor changes on top. |

---

## Acceptance Criteria

### AC1: Bump `docker/build-push-action` to a Node 24 release

- **File:** `.github/actions/docker-build/action.yml:41`
- **Current (verified at base):**
  `uses: docker/build-push-action@10e90e3645eae34f1e60eeb005ba3a3d33f178e8  # v6.19.2` — v6.x runs on Node 20.
- **Change to:**
  `uses: docker/build-push-action@53b7df96c91f9c12dcc8a07bcb9ccacbed38856a  # v7.3.0`
  (v7.3.0 tag → commit `53b7df96c91f9c12dcc8a07bcb9ccacbed38856a`; resolved via
  `gh api repos/docker/build-push-action/git/refs/tags/v7.3.0` on 2026-09-09 — the dev agent must
  re-resolve and confirm the SHA still matches the `# v7.3.0` comment before committing).
- **Why v7 is safe here (all release notes v7.0.0 → v7.3.0 reviewed during story creation, 2026-09-09):**
  - v7.0.0 is the major that switched the action runtime to Node 24. It made **no changes** to the
    inputs this repo uses: `context`, `platforms`, `push`, `load`, `tags`, `labels`, `build-args`,
    `cache-from`, `cache-to` are all unchanged.
  - The only removals in v7.0.0 are the long-deprecated env vars `DOCKER_BUILD_NO_SUMMARY` and
    `DOCKER_BUILD_EXPORT_RETENTION_DAYS`. Grep the repo (`grep -rn "DOCKER_BUILD_NO_SUMMARY\|DOCKER_BUILD_EXPORT_RETENTION_DAYS" .github/`)
    to confirm neither is set — expected: no hits.
  - v7.1.0 → v7.3.0 are **dependency bumps only** (`@docker/actions-toolkit`, `undici`, `vite`,
    `tar`, etc.) plus **one additive, opt-in feature** in v7.1.0 (Git-context URL query-format
    support). No input removals, no input renames, no behavior change to the default build path.
    (Release notes: `gh api repos/docker/build-push-action/releases`.)
  - v7 requires GitHub Actions Runner ≥ v2.327.1. GitHub-hosted `ubuntu-latest` is well past this,
    and **every** workflow/job here runs on it — `grep -rn "runs-on:" .github/workflows` returns
    `ubuntu-latest` for all jobs in `ci.yml`, `pr-build.yml` and `deploy.yml`; no self-hosted
    runners, so the PR build and the CI build run on the same (amd64) platform.
- **Sibling Docker actions — leave alone:** `docker/setup-buildx-action@…  # v4.2.0`
  (`action.yml:37`) and `docker/login-action@…  # v4.6.0` (`ci.yml:109`) are already on Node-24 v4
  majors. The CI warning named **only** `build-push-action`.
- **Scope check:** `grep -rn "build-push-action" .github/` — expected exactly one hit (this composite
  action). `pr-build.yml:90` and `ci.yml:237` invoke the composite via `uses: ./.github/actions/docker-build`;
  they need no change.
- **Convention:** keep the SHA-pin + trailing `# vX.Y.Z` comment (every `uses:` in `ci.yml` follows
  this).
- **Verify:**
  - PR CI run for this branch shows **no** `Node.js 20 is deprecated` annotation.
  - `pr-build.yml` still builds + Trivy-scans the image; `ci.yml` still builds + pushes `:latest` +
    the digest-select jq in `ci.yml` (~L197–L205) still resolves the `linux/amd64` manifest digest.

### AC2: Remove the constant `--platform` flag from both `FROM` instructions in `Dockerfile`

- **File:** `Dockerfile:2` (builder stage) and `Dockerfile:15` (runtime stage)
- **Current:**
  - L2: `FROM --platform=linux/amd64 maven:3.9-eclipse-temurin-17 AS builder`
  - L15: `FROM --platform=linux/amd64 eclipse-temurin:17-jre-alpine`
- **Rule `FromPlatformFlagConstDisallowed`:** a constant `--platform` in `FROM` hard-pins the
  Dockerfile to one architecture and defeats any future multi-platform build from it.
- **Fix (per D2):** delete `--platform=linux/amd64` from both lines:
  - L2 → `FROM maven:3.9-eclipse-temurin-17 AS builder`
  - L15 → `FROM eclipse-temurin:17-jre-alpine`
- **Why this is behavior-preserving:** the build is genuinely single-arch. `docker/build-push-action`
  is passed `platforms: linux/amd64` (`action.yml:16` default, not overridden by either caller), so
  BuildKit already constrains the target platform. The runner is amd64 (`ubuntu-latest`), so the
  builder stage still runs natively (no emulation). Nothing in the repo depends on the Dockerfile
  self-pinning its arch. Both base tags — `maven:3.9-eclipse-temurin-17` and
  `eclipse-temurin:17-jre-alpine` — are published as multi-arch manifest lists, so with the flag
  gone BuildKit resolves the `linux/amd64` variant from the `platforms:` input exactly as the
  constant flag did; there is no platform-specific entrypoint/setup logic in either image that the
  flag was gating.
- **Do NOT:**
  - use `--platform=$BUILDPLATFORM` / `$TARGETPLATFORM` — unnecessary indirection for a single-arch
    build (revisit only if multi-arch is ever put on the roadmap).
  - rename the stage to `AS builder_amd64` — that per-arch-stage pattern would churn the
    `COPY --chown=… --from=builder …` reference at `Dockerfile:41` for no benefit.
  - touch anything else in the file: the `APK_UPGRADE_CACHE_BUST` arg + `apk upgrade` layer, the
    non-root `appuser`, `JAVA_TOOL_OPTIONS`, the jar `COPY`, `EXPOSE`, `HEALTHCHECK`, `ENTRYPOINT`
    all stay exactly as-is.
- **Verify (CI is the gate — no local build required, per project policy):**
  - The PR CI BuildKit run emits **no** `FromPlatformFlagConstDisallowed` check warning for either
    `FROM` line (this is where the warning is raised today).
  - `pr-build.yml` Trivy scan still runs against a built `linux/amd64` image; `ci.yml` build/push
    still produces a working image (healthcheck endpoint `:8367/manage/health` reachable in the
    smoke/deploy path).

### AC3: Suppress the `AntPathRequestMatcher` removal warning in `AppEndpointsConventionTest` — do not remove the usage

- **File:** `src/test/java/com/softropic/skillars/platform/security/config/AppEndpointsConventionTest.java`
  — import at line 6, usage at line 135 (`boolean ant = new AntPathRequestMatcher(pattern).matches(request);`)
- **Warning:** `org.springframework.security.web.util.matcher.AntPathRequestMatcher` is deprecated
  for removal in Spring Security 6.x (project is on Spring Security 6 via Spring Boot 3.5.11).
- **This usage is deliberate and load-bearing.** The class javadoc (added by `skillars-deferred-91`
  AC15 / refined by `skillars-deferred-92` AC15.1) explains it: `requestMatchers(String...)` in
  `SecurityConfiguration` picks **at runtime** between `AntPathRequestMatcher` and
  `PathPatternRequestMatcher` depending on whether a unique `PathPatternRequestMatcher.Builder` bean
  exists. The test method `everyPatternMatchesIdenticallyUnderBothSemantics()` (the only user of
  `AntPathRequestMatcher` in the file — line 135) is a **static semantic-equivalence assertion**: it
  starts no Spring context and instantiates both matchers directly, then asserts every `permitAll`
  pattern × probe path agrees between them. Because it does not depend on which matcher Spring would
  resolve, the guard is live **unconditionally** — it is exactly what lets the rest of the file
  reason about the public surface without knowing the bean state. Deleting the Ant comparison would
  reduce the method to comparing `PathPatternRequestMatcher` against itself (it would assert
  nothing), not remove dead code.
- **Fix:** add `@SuppressWarnings("removal")` at the **smallest scope** that covers line 135 — the
  enclosing `@Test` method (or a private helper if the loop is extracted). Add a one-line comment at
  the suppression: the Ant matcher is compared here on purpose; when Spring Security actually removes
  it, this test's premise ("runtime picks Ant *or* PathPattern") must be re-evaluated, not just the
  import swapped.
- **Do NOT** switch the line to `PathPatternRequestMatcher` — then the test compares PathPattern to
  itself and asserts nothing.
- **Verify:** `mvn -B verify` compiles the test sources with **no** "has been deprecated and marked
  for removal" annotation for this file; `AppEndpointsConventionTest` still runs and passes.

### AC4: Fix the non-varargs varargs call in `ApiAdvice.logErrorAndReturnDTO`

- **File:** `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java:689`
- **Current:**
  ```java
  private ErrorDto logErrorAndReturnDTO(Throwable throwable, String defaultMsg, String msgKey, String... args) {
      final String helpCode = logError(throwable, defaultMsg);
      return toErrorDTO(msgKey, defaultMsg, helpCode, args);   // L689
  }

  private ErrorDto toErrorDTO(final String msgKey, final String defaultMessage, String helpCode, final Object... args) { ... }
  ```
- **Warning:** `args` here is `String[]` (from `String... args`), and `toErrorDTO`'s last parameter
  is `Object... args`. Passing a `String[]` where `Object...` is expected is ambiguous to javac —
  "spread this array as the varargs" vs "pass the array as a single `Object` element". It picks
  spread and warns.
- **Intent is spread** — those args flow into
  `messageSource.getMessage(msgKey, args, defaultMessage, locale)` as the `{0}`/`{1}` placeholders.
- **Fix:** cast at the call site to state it explicitly:
  ```java
  return toErrorDTO(msgKey, defaultMsg, helpCode, (Object[]) args);
  ```
  Behavior is identical (it was already spreading); the cast removes the ambiguity and the warning.
- **Do NOT** change either method signature, and do not touch the three
  `handleSecErrorAndReturnDTO(...)` overloads just above (they pass `String... args` into
  `logErrorAndReturnDTO`'s own `String...` param — no mismatch there).
- **Verify:** `mvn -B verify` — no warning annotation on `ApiAdvice.java:689`; `ApiAdvice` /
  error-envelope tests still pass; a validation-failure response still renders localized
  placeholder substitutions from the passed args (e.g. any existing `ApiAdvice` MockMvc test that
  asserts on a parameterized message).

### AC5: Replace deprecated `Specification.where(...)` in `CoachSearchSpecification.build`

- **File:** `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachSearchSpecification.java:22-31`
- **Warning:** `Specification.where(Specification)` is deprecated for removal in Spring Data JPA 3.5
  (removed in 4.0 — `null` specifications are no longer supported).
- **Current:**
  ```java
  return Specification
      .where(isActive())
      .and(inCity(p.city()))
      .and(inDistrict(p.district()))
      .and(hasSkill(p.skill()))
      .and(hasAgeGroup(p.ageGroup()))
      .and(minPrice(p.minPrice()))
      .and(maxPrice(p.maxPrice()))
      .and(hasMinRating(p.minRating()));
  ```
- **Facts (verified in-file + at the caller, at base `f65a000e`):**
  - `isActive()` — unconditional single-line lambda return, **cannot** return `null`:
    ```java
    private static Specification<CoachProfile> isActive() {
        return (root, q, cb) -> root.get("status").in(CoachProfileStatus.ACTIVE, CoachProfileStatus.REDUCED);
    }
    ```
  - `inCity(String city)` — unconditional lambda return, **cannot** return `null`:
    ```java
    private static Specification<CoachProfile> inCity(String city) {
        return (root, q, cb) -> cb.equal(cb.lower(root.get("city")), city.toLowerCase());
    }
    ```
    `city` is required and cannot be null/blank at this point: `CoachMarketplaceResource.java:49`
    declares `@RequestParam @NotBlank String city`, `CoachSearchParams.city` is documented
    `required — enforced via @RequestParam @NotBlank`, and `CoachSearchService.java:44` comments
    "status=ACTIVE and city filter always applied". (Note the *current* deprecated code already
    calls `inCity(p.city())` unconditionally and would NPE on a null `city` inside the lambda — the
    fix does not change that; it is not introducing a new null path.)
  - `inDistrict`, `hasSkill`, `hasAgeGroup`, `minPrice`, `maxPrice`, `hasMinRating` → each returns
    `null` when its param is absent/blank (verified: each opens with an `if (… ) return null;`
    guard). The current code depends on `.and(null)` being null-safe.
  - `Specification#and(Specification)` is **not** deprecated and remains null-tolerant.
  - **Equivalence:** `Specification.where(x)` for a non-null `x` returns `x` unchanged (the only
    thing `where` adds over plain `x` is turning a `null` argument into an always-true spec — and
    the argument here, `isActive()`, is never null). So `isActive().and(...)` produces an
    identical `Specification` tree to `Specification.where(isActive()).and(...)`; this is a
    provably behavior-neutral swap, not a semantic change.
- **Fix (per D3):** drop the `where()` wrapper, start from the always-present first spec:
  ```java
  return isActive()
      .and(inCity(p.city()))
      .and(inDistrict(p.district()))
      .and(hasSkill(p.skill()))
      .and(hasAgeGroup(p.ageGroup()))
      .and(minPrice(p.minPrice()))
      .and(maxPrice(p.maxPrice()))
      .and(hasMinRating(p.minRating()));
  ```
  No other change; `and(null)` stays null-safe. Keep the trailing comment about the language filter
  being applied separately in `CoachSearchService` via native query.
- **Alternative (acceptable):** `Specification.allOf(isActive(), inCity(...), inDistrict(...), …)` —
  `allOf` also tolerates null elements. Bigger diff, reads less like the existing chain. Do **not**
  introduce `Specification.unrestricted()`.
- **Verify:** `mvn -B verify` — no deprecation annotation on line 24; marketplace coach-search
  tests still pass: ACTIVE + REDUCED coaches returned (REDUCED sorted after ACTIVE), and the
  city / district / skill / age-group / min-price / max-price / min-rating filters each still
  applied when their param is set and skipped when it is null.

### AC6: Resolve MapStruct "Unmapped target properties" warnings in three mappers

MapStruct 1.6.3 runs with the default `unmappedTargetPolicy = WARN`. Each warning lists target
entity properties the mapper leaves unset. None of these should be mapper-set — they are audit /
persistence plumbing or server-derived lifecycle fields. The fix records that the omission is
intentional; **no runtime behavior changes** (the targets were already left unset).

- **6a — `AuditTrailMapper.toAuditLog` (`AuditTrailMapper.java:13`)**
  Unmapped: `id, createdBy, createdDate, lastModifiedBy, lastModifiedDate, status` — all
  `AbstractAuditingEntity` / Envers fields set by JPA/Hibernate.
  **Fix (per D1):** annotate the `toAuditLog` method with
  `@BeanMapping(unmappedTargetPolicy = ReportingPolicy.IGNORE)` (import
  `org.mapstruct.BeanMapping` + `org.mapstruct.ReportingPolicy`). Per-method only — do **not** add
  `unmappedTargetPolicy` to the `@Mapper` annotation, so `toAuditTrail(AuditLog)` keeps its `WARN`
  guard. Keep `@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)` exactly as-is
  (do not rewrite to the `"spring"` string form).

- **6b — `UserMapper.toUser` (`UserMapper.java:34`)**
  Unmapped: `id, createdBy, createdDate, lastModifiedBy, lastModifiedDate, requestId, sessionId,
  status, addresses, activationKey, locked, resetKey, resetExpiration, accountExpiration,
  persistentTokens, activationDate, skillarsRole, verificationStatus` — audit plumbing plus fields
  only the persistence / registration layer sets; `toUser` intentionally builds a partial entity.
  **Fix (per D1):** annotate the `toUser` method with
  `@BeanMapping(unmappedTargetPolicy = ReportingPolicy.IGNORE)`. Per-method only — `toUserDto(User)`,
  `addressToAddressDto(Address)`, and the `@AfterMapping` helpers keep their `WARN` guard. Leave the
  existing `@Mapping(source = "dob", target = "dateOfBirth")` on `toUser` in place.

- **6c — `CoachProfileMapper.toEntity(ProfileBuilderStep1Request, Long)` (`CoachProfileMapper.java:22`)**
  Unmapped: `verificationTier, averageRating, reviewCount, statusChangedAt` — server-derived /
  lifecycle fields, never client-supplied.
  **Fix (per D1):** add explicit ignores to the existing `@Mapping` stack on this method:
  ```java
  @Mapping(target = "verificationTier", ignore = true)
  @Mapping(target = "averageRating", ignore = true)
  @Mapping(target = "reviewCount", ignore = true)
  @Mapping(target = "statusChangedAt", ignore = true)
  ```
  This mapper already enumerates every field explicitly (`id`, `bio`, `photoUrl`, `status`,
  `createdAt` are already `ignore = true`), so keeping that style preserves the "new `CoachProfile`
  column ⇒ build tells you to decide" safety net. Do **not** touch the second `toEntity`
  (`AvailabilityWindowRequest`, `UUID`) overload — it has no warning.

- **Verify:** `mvn -B verify` — **zero** "Unmapped target properties" annotations originating from
  these three files; the generated `AuditTrailMapperImpl`, `UserMapperImpl`, `CoachProfileMapperImpl`
  still compile; audit-trail, user-DTO round-trip, and coach profile-builder step-1 tests still pass.

### AC7: Confirm the CI build-warning set is clean and sweep for siblings

- After AC1–AC6, the remote CI run for this branch's PR shows **none** of the seven warning classes
  in the table above.
- Fresh sweep for other occurrences of the same anti-patterns. **"Identical kind" = the fix is the
  same token-level edit as this story's, with no logic/conditional/test change**; anything needing
  judgement is a follow-up, not silent scope creep:
  - `grep -rn "Specification.where(" src/main` — in-story if it is a `Specification.where(x).and(y)…`
    chain where `x` is a provably non-null spec (drop `where(` + de-`Specification.`). If the
    `where(...)` wraps a nullable expression, is nested in a branch, or is a lone
    `Specification.where(x)` with no `.and`, defer it.
  - `grep -rn "AntPathRequestMatcher" src` — in-story only for a test/assertion where the fix is a
    scoped `@SuppressWarnings("removal")` (like AC3). Any **production** usage is a design change —
    defer to its own story.
  - `grep -rn "build-push-action\|node20\|node16\|node12" .github` — in-story if it is a pinned
    action bump to a Node-24 release with no input changes; defer anything with breaking inputs.
  - `grep -rn "unmappedTargetPolicy\|@Mapper(" src/main` plus the full CI log — for any *other*
    mapper emitting an unmapped-target warning in the same run, apply the D1 rule (explicit
    `@Mapping(ignore=true)` if the mapper already enumerates every field; else per-method
    `@BeanMapping(... IGNORE)`). If closing a warning would require actually mapping a field
    (i.e. it is a real missed mapping, not plumbing), defer and flag it.
  - `Dockerfile` — only one in the repo; both `FROM` lines handled by AC2.
- Record the sweep result (found nothing / fixed N siblings — list them / deferred M — list them
  with why) in the Dev Agent Record.
- **No functional change:** every endpoint, query result, image, and CI job graph behaves exactly as
  before this story.

---

## Tasks / Subtasks

- [x] **AC1** — bump `docker/build-push-action` in `.github/actions/docker-build/action.yml:41` to
      `@53b7df96c91f9c12dcc8a07bcb9ccacbed38856a  # v7.3.0` (re-resolve the SHA against the tag first);
      grep-confirm no `DOCKER_BUILD_NO_SUMMARY` / `DOCKER_BUILD_EXPORT_RETENTION_DAYS` usage and no
      other `build-push-action` pin.
- [x] **AC2** — delete `--platform=linux/amd64` from `Dockerfile:2` and `Dockerfile:15`; change
      nothing else in the file.
- [x] **AC3** — add scoped `@SuppressWarnings("removal")` + explanatory comment around
      `AppEndpointsConventionTest.java:135`; keep the `AntPathRequestMatcher` comparison.
- [x] **AC4** — cast the vararg pass-through to `(Object[]) args` at `ApiAdvice.java:689`.
- [x] **AC5** — replace `Specification.where(isActive())` with `isActive()` as the chain head in
      `CoachSearchSpecification.java:22-24`.
- [x] **AC6a** — `@BeanMapping(unmappedTargetPolicy = ReportingPolicy.IGNORE)` on
      `AuditTrailMapper.toAuditLog` (add imports).
- [x] **AC6b** — `@BeanMapping(unmappedTargetPolicy = ReportingPolicy.IGNORE)` on `UserMapper.toUser`
      (add imports).
- [x] **AC6c** — four explicit `@Mapping(target = …, ignore = true)` lines on
      `CoachProfileMapper.toEntity(ProfileBuilderStep1Request, Long)`.
- [x] **AC7** — run the sibling sweep greps; fix trivial siblings or log follow-ups; push branch and
      confirm the PR CI "build" job shows a clean warnings summary. _(Greps run — no in-scope
      siblings found; local validation done. PR CI confirmation pending push.)_

---

## Dev Notes

### Files touched (all UPDATE, no NEW)

| File | Change | Risk |
|------|--------|------|
| `.github/actions/docker-build/action.yml` | one `uses:` line, v6.19.2 → v7.3.0 | Low — no input changes in v7; sibling actions already v4/Node24 |
| `Dockerfile` | remove `--platform=linux/amd64` from 2 `FROM` lines | Low — buildx `platforms:` input already constrains arch; runner is amd64 |
| `src/test/java/.../security/config/AppEndpointsConventionTest.java` | `@SuppressWarnings("removal")` + comment | None — test logic unchanged |
| `src/main/java/.../security/api/ApiAdvice.java` | `(Object[])` cast on one call | None — was already spreading |
| `src/main/java/.../marketplace/service/CoachSearchSpecification.java` | drop `Specification.where(` wrapper | Low — `isActive()`/`inCity()` always non-null; `and(null)` still null-safe |
| `src/main/java/.../security/audit/api/AuditTrailMapper.java` | `@BeanMapping` on one method + 2 imports | None — targets already unset |
| `src/main/java/.../security/service/UserMapper.java` | `@BeanMapping` on one method + 2 imports | None — targets already unset |
| `src/main/java/.../marketplace/contract/CoachProfileMapper.java` | 4 `@Mapping(ignore=true)` lines | None — targets already unset |

### Existing-code context the dev agent must preserve

- **`CoachSearchSpecification`** — the `build(...)` method's contract: `isActive()` must *always*
  apply (it is what includes REDUCED coaches and is relied on by the sort in `CoachSearchService`);
  the six optional sub-specs must remain skippable via `null`. The new chain head `isActive().and(...)`
  keeps both. Language filtering is deliberately **not** in this Specification (native query in
  `CoachSearchService` — PostgreSQL array `ANY()` is not expressible in JPA Criteria). Do not "fix"
  that.
- **`ApiAdvice`** — the three `handleSecErrorAndReturnDTO` overloads (`AuthenticationException`,
  `AuthorizationException`, `SecException`) each forward `String... args` to `logErrorAndReturnDTO`,
  which forwards to `toErrorDTO(Object... args)`. Only the last hop has the `String[] → Object[]`
  mismatch; the cast goes there and nowhere else.
- **`AppEndpointsConventionTest`** — this is a build-failing convention guard (like
  `MigrationConventionLintTest`). Its assertion that PathPattern and Ant semantics agree for every
  `permitAll` pattern is a live security-surface check. `@SuppressWarnings("removal")` must be the
  *only* change; the two-matcher comparison stays.
- **`UserMapper.toUser`** — `toUser` is intentionally partial (registration / persistence layers set
  `status`, `activationKey`, `skillarsRole`, `verificationStatus`, `addresses`, etc.). The
  `@BeanMapping` IGNORE only silences the report; it does not and must not start mapping those.
- **`Dockerfile`** — the `APK_UPGRADE_CACHE_BUST` mechanism (arg + `apk upgrade` layer, fed
  `${{ github.run_id }}-${{ github.run_attempt }}` by the composite action) exists to defeat stale
  BuildKit layer caching of OS-package patches. It is unrelated to the `--platform` flag and must be
  left exactly as-is. Same for `.trivyignore` / `trivyignores` wiring (tech-debt TD-5).

### Testing standards

- Backend: `mvn -B verify` is the sole full gate (CI-only — do not run `mvn verify` locally per
  project policy). No new tests are required — this story adds no behavior; existing unit /
  `@SpringBootTest` + Testcontainers suites are the regression net.
- The two convention guards that could react to these changes — `AppEndpointsConventionTest`
  (AC3) and any MapStruct-generated-impl compilation (AC6) — run inside `mvn -B verify`.
- CI Docker path: `pr-build.yml` (build + Trivy scan, `load: true`) and `ci.yml` (build + push
  `:latest`, digest select) both exercise the composite action changed in AC1 and the `Dockerfile`
  changed in AC2. A green PR run covers both.

### Project Structure Notes

- No package / module / naming changes. All edits are in-place single-file modifications.
- GitHub Actions pin convention (`ci.yml`): every `uses:` is a full commit SHA with a trailing
  `# vX.Y.Z` comment. AC1 must follow it.
- MapStruct convention (`project-context.md`): mappers live in `contract` or `service` packages
  (all three here already do). `@BeanMapping` is per-method; prefer it over mapper-wide policy
  changes so unrelated methods keep their guard.
- Java 17 / MapStruct 1.6.3 / Spring Boot 3.5.11 / Spring Security 6 / Spring Data JPA 3.5.x
  (`pom.xml`). `Specification.allOf` and `@BeanMapping(unmappedTargetPolicy=…)` are both available
  at these versions.

### References

- CI warning source: remote GitHub Actions run on the pre-story `master` build (user-provided, 2026-09-09).
- [Source: .github/actions/docker-build/action.yml#L37-L58] — composite Docker build action, the single `build-push-action` pin.
- [Source: .github/workflows/ci.yml#L33-L58, #L102-L109, #L197-L240] — SHA-pin convention; Docker login/build/push + digest select.
- [Source: .github/workflows/pr-build.yml#L88-L92] — PR build invokes the composite; Trivy scan follows.
- [Source: Dockerfile#L1-L45] — both `FROM` lines; `APK_UPGRADE_CACHE_BUST` rationale (L18-L33).
- [Source: src/test/java/com/softropic/skillars/platform/security/config/AppEndpointsConventionTest.java#L1-L140] — class javadoc explains why both matchers are compared (`skillars-deferred-91` AC15 / `skillars-deferred-92` AC15.1).
- [Source: src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java#L680-L705] — `handleSecErrorAndReturnDTO` overloads → `logErrorAndReturnDTO` → `toErrorDTO(Object... args)`.
- [Source: src/main/java/com/softropic/skillars/platform/marketplace/service/CoachSearchSpecification.java#L21-L114] — `build(...)` chain + the six null-returning sub-specs.
- [Source: src/main/java/com/softropic/skillars/platform/security/audit/api/AuditTrailMapper.java] — `toAuditLog` unmapped audit fields.
- [Source: src/main/java/com/softropic/skillars/platform/security/service/UserMapper.java#L24-L34] — `toUser` intentionally partial.
- [Source: src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachProfileMapper.java#L11-L22] — `toEntity` already enumerates every field.
- [Source: _bmad-output/project-context.md] — MapStruct convention, Java 17, "no local mvn verify".
- [Source: _bmad-output/planning-artifacts/tech-debt.md#TD-5] — `.trivyignore` / Dockerfile `apk upgrade` context (do not disturb).
- [Source: src/main/java/com/softropic/skillars/platform/marketplace/api/CoachMarketplaceResource.java#L49] — `@RequestParam @NotBlank String city` (the city-required invariant AC5 relies on).
- [Source: src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachSearchParams.java#L8] — `city` field documented "required — enforced via @RequestParam @NotBlank".
- External: GitHub Changelog — "Deprecation of Node 20 on GitHub Actions runners" (2025-09-19); `docker/build-push-action` release notes v7.0.0 → v7.3.0 (v7.0.0 = Node 24 + two deprecated env-var removals, no input changes; v7.1–v7.3 = dependency bumps + one additive Git-context feature); Docker build-checks `FromPlatformFlagConstDisallowed`; Spring Data JPA `Specification.where` deprecation (GH issue spring-projects/spring-data-jpa#3893, replacement `allOf`).

---

## Dev Agent Record

### Agent Model Used

claude-sonnet-5 (Claude Code `/bmad-dev-story`)

### Debug Log References

- `mvn -Dfrontend.skip=true -DskipTests -Dmaven.compiler.showDeprecation=true -Dmaven.compiler.showWarnings=true compile` → BUILD SUCCESS; none of the 5 changed `src/main` files appear in the compiler warning list (no `non-varargs`, no `Specification…deprecated`, no `Unmapped target properties`). The three MapStruct impls (`AuditTrailMapperImpl`, `UserMapperImpl`, `CoachProfileMapperImpl`) regenerated with byte-identical setter bodies — no target property added or removed.
- `mvn -Dfrontend.skip=true -DskipTests test-compile` (forced recompile of `AppEndpointsConventionTest`) → BUILD SUCCESS; that file no longer emits the `AntPathRequestMatcher … deprecated/marked for removal` warning it did at base (scoped `@SuppressWarnings("removal")` covers the only usage; javac does not warn on the import line).
- `mvn surefire:test -Dtest=ApiAdviceTest,AppEndpointsConventionTest` → 13 tests, 0 failures.
- `mvn verify -Dit.test=CoachMarketplaceResourceIT,CoachProfileBuilderIT` (Testcontainers) → `CoachMarketplaceResourceIT` 15/15 green (AC5: ACTIVE+REDUCED returned, all seven optional filters still applied/skipped correctly), `CoachProfileBuilderIT` 34/34 green (AC6c: profile-builder step-1 mapping unaffected). BUILD SUCCESS.
- `gh api repos/docker/build-push-action/git/refs/tags/v7.3.0` → `53b7df96c91f9c12dcc8a07bcb9ccacbed38856a` — matches the `# v7.3.0` pin comment (AC1).
- Per project policy (`docs/validation-strategy.md`, memory "No Local mvn verify") the full `mvn verify` was **not** run locally — remote GitHub CI is the authoritative gate for the complete suite and for the CI-only warning classes (AC1 Node 20 annotation, AC2 BuildKit `FromPlatformFlagConstDisallowed`).

### Completion Notes List

- **Implemented 2026-09-09.** All eight subtasks (AC1–AC7) applied exactly as specified; zero behavior change. Eight files touched, all UPDATE (no NEW, no DELETE).
- **AC1** — `.github/actions/docker-build/action.yml:41`: `docker/build-push-action` `@10e90e36… # v6.19.2` → `@53b7df96c91f9c12dcc8a07bcb9ccacbed38856a # v7.3.0`. SHA re-resolved against the tag (matches). `grep -rn "DOCKER_BUILD_NO_SUMMARY\|DOCKER_BUILD_EXPORT_RETENTION_DAYS" .github/` → no hits (the two env vars removed in v7.0.0 are unused here). `grep -rn "build-push-action" .github/` → one real `uses:` pin (this composite); `pr-build.yml` / `ci.yml` invoke it via `uses: ./.github/actions/docker-build` and need no change. Sibling `setup-buildx-action` (v4.2.0) and `login-action` (v4.6.0) left alone — already Node-24 majors, and the CI warning named only `build-push-action`.
- **AC2** — `Dockerfile`: removed `--platform=linux/amd64` from both `FROM` lines (L2 builder, L15 runtime). Nothing else in the file changed — `APK_UPGRADE_CACHE_BUST` arg + `apk upgrade` layer, non-root `appuser`, `JAVA_TOOL_OPTIONS`, jar `COPY --from=builder`, `EXPOSE`, `HEALTHCHECK`, `ENTRYPOINT` all untouched. `docker/build-push-action` is still passed `platforms: linux/amd64` (composite default), so BuildKit still targets amd64; both base tags are multi-arch manifest lists.
- **AC3** — `AppEndpointsConventionTest.java`: added `@SuppressWarnings("removal")` on the single `@Test` method `everyPatternMatchesIdenticallyUnderBothSemantics()` (smallest scope covering the `new AntPathRequestMatcher(pattern)` usage, which shifted from L135 → L141 with the added 5-line comment). Comment records that the Ant-vs-PathPattern comparison is deliberate and that Spring Security's eventual removal of `AntPathRequestMatcher` must trigger a re-evaluation of the test's premise, not a mechanical import swap. Comparison logic unchanged; test still 5/5 green.
- **AC4** — `ApiAdvice.java:689`: `toErrorDTO(msgKey, defaultMsg, helpCode, args)` → `toErrorDTO(msgKey, defaultMsg, helpCode, (Object[]) args)`. Both method signatures unchanged; the three `handleSecErrorAndReturnDTO` overloads above untouched (their `String... → String...` forwarding has no mismatch). Behavior identical — javac was already spreading; the cast just states it. `ApiAdviceTest` 8/8 green.
- **AC5** — `CoachSearchSpecification.java`: `Specification.where(isActive()).and(...)` → `isActive().and(...)`. `where(x)` for a non-null `x` returns `x` unchanged, and `isActive()` is an unconditional lambda that can never be null, so the resulting `Specification` tree is identical. The six nullable optional sub-specs still chain via null-tolerant, non-deprecated `Specification#and`. Trailing language-filter comment kept. `import …Specification` still needed (return type + private helpers). `CoachMarketplaceResourceIT` 15/15 green.
- **AC6a** — `AuditTrailMapper.java`: `@BeanMapping(unmappedTargetPolicy = ReportingPolicy.IGNORE)` on `toAuditLog` only (+ `org.mapstruct.BeanMapping`, `org.mapstruct.ReportingPolicy` imports + explanatory comment). `@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)` kept verbatim; `toAuditTrail` keeps the default `WARN` guard. Generated `AuditTrailMapperImpl.toAuditLog` sets the exact same 12 fields as before.
- **AC6b** — `UserMapper.java`: `@BeanMapping(unmappedTargetPolicy = ReportingPolicy.IGNORE)` on `toUser` only (+ `BeanMapping`, `ReportingPolicy` imports + comment). Existing `@Mapping(source = "dob", target = "dateOfBirth")` kept. `toUserDto`, `addressToAddressDto`, and the two `@AfterMapping` helpers keep their `WARN` guard. Generated `UserMapperImpl.toUser` sets the exact same 16 fields as before — no field started being mapped.
- **AC6c** — `CoachProfileMapper.java`: four `@Mapping(target = …, ignore = true)` lines (`verificationTier`, `averageRating`, `reviewCount`, `statusChangedAt`) appended to the existing explicit `@Mapping` stack on `toEntity(ProfileBuilderStep1Request, Long)`. Verified all four properties exist on `CoachProfile`. The second `toEntity(AvailabilityWindowRequest, UUID)` overload untouched. Generated `CoachProfileMapperImpl` unchanged (those targets were, and remain, unset). `CoachProfileBuilderIT` 34/34 green.
- **AC7 sibling sweep (2026-09-09):**
  - `grep -rn "Specification.where(" src/main` → **no other occurrences**. The only hit was the one fixed in AC5.
  - `grep -rn "AntPathRequestMatcher" src` → only `AppEndpointsConventionTest.java` (import, javadoc prose, the new comment, and the L141 usage). **No production usage** — nothing to defer.
  - `grep -rn "build-push-action\|node20\|node16\|node12" .github` → only the one `uses:` pin (now v7.3.0) plus two unrelated comment strings. **No other action to bump.**
  - `grep -rn "unmappedTargetPolicy\|@Mapper(" src/main` → four other `@Mapper` interfaces exist (`ConfigMapper`, `PlayerProfileMapper`, `SkillDefinitionMapper`, plus the three fixed here). **None was named in the CI build-warning set**, and a full `mvn compile` with `-Dmaven.compiler.showWarnings=true` produced **no "Unmapped target properties" annotation** from any file after this story's fixes. Nothing to defer.
  - `Dockerfile` — one in the repo (the `.claude/worktrees/…` copy is a git-worktree artifact, not part of this tree); both `FROM` lines handled by AC2.
  - **Deferred / out of scope (noted, not fixed):** a `mvn test-compile` still emits unrelated `deprecation` warnings in *test* sources — `RandomStringUtils.randomAlphabetic/randomNumeric` (`LoginAttemptsServiceTest`), `Assertions.catchThrowableOfType` (`DefaultMessageBundleFallbackIT`), `UriComponentsBuilder.fromHttpUrl` (`HttpTestClient`), `DaoAuthenticationProvider.setUserDetailsService` (`JWTAuthorizationFilterTest`), plus several `commons-lang3 StringUtils` deprecations in `src/main`. **None of these is in the story's seven-warning CI set**, and each needs a genuine API migration (not a token-level shim like AC3/AC4/AC5), so per AC7's "identical kind" rule they are follow-up work, not silent scope creep here.
- **No functional change:** every endpoint, query result, Docker image, and CI job graph behaves exactly as before this story. No new tests added (none required — no behavior change); existing unit + Testcontainers suites are the regression net, and the two convention guards (`AppEndpointsConventionTest`, MapStruct impl compilation) both still pass.

---

- Story created 2026-09-09 by ultimate-story-context analysis. All seven warning classes traced to
  exact base-commit lines; fixes are mechanical/deprecation-shims. Project-owner decisions D1–D4
  signed off 2026-09-09 (Split for AC6, delete-the-flag for AC2, `v7.3.0` for AC1, chain-from-`isActive()`
  for AC5). No open questions — proceed with the ACs as written.
- Review pass 2026-09-09 (`story-review.md`) applied: `build-push-action` v7.1.0–v7.3.0 release notes
  verified (deps + one additive feature, no input changes) so AC1 no longer punts that to the dev;
  `isActive()` / `inCity()` bodies quoted verbatim in AC5 and the `@NotBlank` city invariant
  cross-checked at `CoachMarketplaceResource.java:49`, with an explicit `where(nonNull) ≡ nonNull`
  equivalence note (the review's "NPE instead of null-check" concern was a false positive — the
  current code does not null-check `inCity`'s non-null return either); AC3 reworded to make clear the
  Ant matcher backs a *static* equivalence assertion, live regardless of runtime bean state; AC2
  verify reworded to CI-is-the-gate (no local build) + multi-arch-manifest note; AC7 "identical
  kind" defined per-pattern. No AC or fix approach changed — all review items were
  documentation/verification hardening.

### File List

All UPDATE (no NEW, no DELETE):

- `.github/actions/docker-build/action.yml` — AC1: `build-push-action` v6.19.2 → v7.3.0 (one `uses:` line).
- `Dockerfile` — AC2: removed `--platform=linux/amd64` from both `FROM` instructions (L2, L15).
- `src/test/java/com/softropic/skillars/platform/security/config/AppEndpointsConventionTest.java` — AC3: `@SuppressWarnings("removal")` + 5-line explanatory comment on `everyPatternMatchesIdenticallyUnderBothSemantics()`.
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java` — AC4: `(Object[])` cast on the `toErrorDTO(...)` call at L689.
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachSearchSpecification.java` — AC5: dropped the deprecated `Specification.where(...)` wrapper; chain now starts from `isActive()`.
- `src/main/java/com/softropic/skillars/platform/security/audit/api/AuditTrailMapper.java` — AC6a: `@BeanMapping(unmappedTargetPolicy = ReportingPolicy.IGNORE)` on `toAuditLog` + 2 imports + comment.
- `src/main/java/com/softropic/skillars/platform/security/service/UserMapper.java` — AC6b: `@BeanMapping(unmappedTargetPolicy = ReportingPolicy.IGNORE)` on `toUser` + 2 imports + comment.
- `src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachProfileMapper.java` — AC6c: four `@Mapping(target = …, ignore = true)` lines on `toEntity(ProfileBuilderStep1Request, Long)`.

Workflow bookkeeping (not code):

- `_bmad-output/implementation-artifacts/sprint-status.yaml` — `skillars-deferred-105-ci-build-warning-cleanup`: `ready-for-dev` → `in-progress` → `review`.
- `_bmad-output/implementation-artifacts/skillars-deferred-105-ci-build-warning-cleanup.md` — this file (task checkboxes, Dev Agent Record, File List, Change Log, Status).

### Change Log

| Date | Change |
|------|--------|
| 2026-09-09 | Implemented AC1–AC7 (warnings-only cleanup — CI build-warning sweep). 8 source/config files updated, no behavior change. Targeted validation green: `compile`/`test-compile` clean of the seven warning classes; `ApiAdviceTest` 8/8, `AppEndpointsConventionTest` 5/5, `CoachMarketplaceResourceIT` 15/15, `CoachProfileBuilderIT` 34/34. Full `mvn verify` + CI-only warning verification (AC1 Node 20, AC2 BuildKit check) deferred to GitHub CI per project policy. Status → review. |
