# Story Review: skillars-deferred-107

**Reviewer:** Claude Code (Senior Dev Audit)  
**Date:** 2026-09-10  
**Branch:** story/deferred-107-config-range-safety-and-observability-decisions

---

## Summary

The story is **well-structured and comprehensive**, addressing genuine safety gaps (config range-safety, startup validation) and clearing long-deferred decisions (node_exporter isolation, credentials exposure, Alertmanager). The scope is appropriate and sequencing is sound. **No blockers found**, but three design clarifications and one correctness issue should be resolved before dev starts.

---

## Findings

### 🔴 Correctness Issue (AC2)

**`VideoAccessGuard.java:93` bound mismatch**

- **Location:** AC2 table, line 133
- **The issue:** The table specifies `getBoundedLong`, `[1, 3650]` for `coach_window_days` (which undergoes `Math.toIntExact` cast at `:93`). But `Math.toIntExact` throws if the value exceeds `Integer.MAX_VALUE` (~2.1B). A bound of `3650` (10 years) is conservative, not correct.
- **False assumption:** That all AC2 bounds should follow the "business logic max" pattern (like `pack.pause.maxDays` = 10 years). But `Math.toIntExact` has a *technical* limit that the story conflates with a *business* limit.
- **Impact:** If production ever stores a value in `(3650, Integer.MAX_VALUE]`, it will clamp to `3650` at read time. This may hide legitimate use cases (e.g., coach access expiry set to 20 years by mistake — should fail-fast, not clamp to 10).
- **Fix approach:** Clarify per-site whether the bound is business-driven (clamp) or technical (must not exceed). For `VideoAccessGuard`, either:
  - Set `[1, Integer.MAX_VALUE]` if values > 3650 are business-valid but rare, or
  - Document why coach access is capped at 10 years (business rule), then keep `[1, 3650]` with a comment.
- **Recommendation:** Dev must verify actual usage intent at this one call site before finalizing the bound.

---

### 🟠 Design Issue: AC2 — 1-arg `getBoundedLong(key, min, max)` variant conflicts with AC3 philosophy

**Inconsistent fail-fast strategy**

- **Location:** AC2 section, "1-arg `getLong(key)` sites" (lines 145–152)
- **The issue:** The story proposes adding `getBoundedLong(String key, long min, max)` (no default) that **throws `IllegalStateException` on out-of-range**, not clamp. But AC3's entire premise is that **some keys should fail-fast at startup, others should clamp+warn at read time**. By making the no-default variant throw, every 1-arg site gets automatic fail-fast (same as `failFast=true` in AC3), regardless of AC3's assessment.
- **False assumption:** That "no default" implies "fail on bad value". In reality:
  - Missing config → fail-loud (no default to fall back to). ✓
  - Bad config value → should follow AC3's per-key decision (fail-fast or clamp+warn), not auto-fail.
- **Missed flow:** A 1-arg site like `QuotaConfigService.java:22` (`video.quota.<tier>.storageBytes`) shouldn't auto-fail at startup just because it has no default. If AC3 marks it `failFast=false`, it should clamp+warn at runtime, not throw on boot.
- **Impact:** All 1-arg sites become implicit `failFast=true`, breaking the two-tier safety model AC3 intends.
- **Fix approach:** Either:
  - (Preferred) Don't add a no-default `getBoundedLong` variant. Instead, convert 1-arg sites to 2-arg by adding sensible defaults (even if just re-reading the stored value as the default). Less disruption, consistent behavior.
  - Or, add `getBoundedLong(key, min, max)` but have it **clamp to min/max** (not throw), same as the 2-arg variant. This requires no default, but can return a safe value. Then AC3 controls the fail-fast decision externally (startup assertion), not at the `getBoundedLong` call.
- **Recommendation:** Clarify whether 1-arg sites are intended to fail-fast (in which case, they don't need a separate test in AC3) or follow AC3's per-key decision (in which case, they need a clamp+warn variant, not throw).

---

### 🟠 Design Issue: AC3 — Per-tier/templated config keys are ambiguous

**Incomplete scope for `video.quota.<tier>.*` and `<type>.maxSizeBytes`**

- **Location:** AC3 section, "Keys that are per-tier / templated" (line 206–208)
- **The issue:** The story says "enumerate the known tiers/types (same list `QuotaConfigService` / `VideoTypeConstraints` use) or skip them in the assertion". This is a punt — either enumerate them all in `ConfigBounds`, or don't cover them. Leaving it as "pick one" creates a maintenance gap: if a new tier is added, the startup assertion doesn't automatically cover it.
- **Missed flow:** If `QuotaConfigService` gains a new tier (e.g., `video.quota.premium.<param>`), the developer might forget to add it to `ConfigBounds`. The assertion silently doesn't cover it.
- **Impact:** Tier-specific config ranges aren't enforced uniformly. Medium risk, since the read-time `getBoundedLong` still clamps, but defeats the startup-assertion goal (fail-fast on all critical keys).
- **Fix approach:**
  - (Preferred) Add a helper method to `ConfigBounds` that enumerates tiers/types dynamically: `List<String> getKnownTiers()` and `List<String> getKnownVideoTypes()`, then loop over them in `ConfigStartupAssertion` to generate the full key list at startup. This way, if `QuotaConfigService.getTiers()` changes, the assertion self-updates.
  - Or, make enumeration explicit and mandatory: the story must list all known tiers/types in `ConfigBounds` with a comment saying "keep in sync with `QuotaConfigService.getTiers()`".
- **Recommendation:** Don't leave this as "enumerate or skip". Pick one and document it clearly.

---

### 🟠 Design Issue: AC3 — Fail-fast key selection principle is implicit

**Unclear rationale for `failFast=true` split**

- **Location:** AC3 section, "**`failFast = true`** for: ..." (lines 200–204)
- **The issue:** The story lists 7 keys as `failFast=true` (data-destructive: `platform.message_retention_months`; flow-blocking: `pack.pause.maxDays`, `booking.batch.maxSize`, `disputes.submissionWindowDays`, `reviews.submissionWindowDays`, `platform.moderation_sla_minutes`, `platform.moderation_lock_timeout_minutes`) and everything else as `false`. But the principle is "flow-disabling or data-destructive". What about other keys?
  - `platform.video.lifecycle.blocked_to_archived_days` — if it's `0`, videos archive immediately (surprising behavior, but not data loss). Should it fail-fast?
  - `platform.video.playback.signed_url_ttl_minutes` — if it's `0`, signed URLs expire immediately (broken playback). Should it fail-fast?
  - `GdprExportService` `gdpr.export.urlExpiryHours` — if it's `0`, export links are dead on arrival. Should it fail-fast?
- **False assumption:** That the story's list is complete and well-justified. It's not clear why some keys are in and others aren't.
- **Missed flow:** An operator might misconfigure a video lifecycle key and not notice until the archival scheduler runs, silently fixing the wrong videos.
- **Impact:** Inconsistent safety depending on which key is misconfigured. Medium risk, since AC2's clamp still prevents the worst breakage.
- **Fix approach:** Document the fail-fast principle clearly:
  - Fail-fast = key's bad value causes data loss, permanent corruption, or a core flow to stop entirely (e.g., no disputes filed, no reviews submitted, no messages retained).
  - Clamp+warn = key's bad value is degraded but recoverable (e.g., slower processing, smaller batch sizes, delayed archival).
  Then apply this principle consistently to all 31 keys and document the rationale per key in `ConfigBounds`.
- **Recommendation:** Dev should present a filled-in table of all 31 keys with `failFast` flag and one-line reasoning before implementation.

---

### 🟠 Design Issue: AC3 — Metrics missing range context

**`config.value.misconfigured` counter lacks actionability**

- **Location:** AC3 section, metrics (lines 194–196)
- **The issue:** The story says to increment `config.value.misconfigured` with `tag("key", key)` and `tag("reason", "out_of_range")`. But an operator seeing this metric has no idea what range is expected. They have to go dig into the code or docs to find that `platform.message_retention_months` should be `[1, 600]`.
- **Missed flow:** An operator sees `config.value.misconfigured{key="platform.message_retention_months",reason="out_of_range"} = 1` and doesn't know what to fix it to.
- **Impact:** Reduced observability. The metric fires but doesn't guide remediation. Low risk (error log + docs mitigate this), but reduces the metric's value.
- **Fix approach:** Add the expected range to the metric tags:
  ```
  config.value.misconfigured{key="platform.message_retention_months",reason="out_of_range",expected_range="[1,600]"}
  ```
  This makes the metric self-explanatory in dashboards.
- **Recommendation:** Add range tags when incrementing the counter.

---

### 🟡 Design Issue: AC8 — Redis uid:gid mismatch needs explicit testing

**Redis data dir ownership (`999:1000`) doesn't match runtime uid:gid**

- **Location:** AC8 section, "**`redis` special case:**" (lines 414–417) and the `image_runtime_ugid` helper (lines 396–402)
- **The issue:** Redis container runs as `999:999` (or similar), but the data dir is `chown`ed `999:1000` (group `redis` = gid 1000, which doesn't match the runtime gid). The story acknowledges this but says "confirm the probe's gid handling doesn't regress that — if the probe returns `999:999`, keep the `:1000` group override with a comment". This is a special case that could be missed in testing.
- **Missed flow:** If the probe correctly detects `999:999`, the fix is to `chown 999:1000` (override the gid). But if the image version changes and Redis suddenly runs as `999:1000` (matching the dir), the probe will return that and `chown` will happen with no override needed. This is correct but fragile if not tested.
- **Impact:** Low — the `chown_if_needed` idempotency (line 536 note) should handle repeated calls safely. But if the gid logic is wrong, data dir permissions could become inconsistent.
- **Fix approach:** Add an explicit assertion in the manual test: check that after running the ownership fix with a redis image that runs as `999:999`, the dir is `999:1000`. Then test with a redis image that runs as `999:1000` and verify the dir stays `999:1000` (not downgraded).
- **Recommendation:** Dev should document the redis special case in a comment at the chown call site, and include it explicitly in the manual test exercise.

---

### 🟡 Testing Burden: AC2 — 31 call sites × per-site verification and bounds reasoning

**High developer judgment required, high test count**

- **Location:** AC2 section, "First-pass classification" table (lines 113–143)
- **The issue:** The story provides a table with 31 sites and suggested `[min, max]` bounds. But it says "the dev must re-confirm each against HEAD and adjust bounds with a one-line rationale comment at each site". This is a LOT of work:
  - Re-read each call site to understand its semantic constraints.
  - Verify the suggested bound makes sense (not too loose, not too tight).
  - Write a one-line rationale.
  - Add a test to verify the bound is used.
  That's 31 × (verification + comment + test) = 93 microtasks.
- **Risk:** High chance of a bound being wrong, or a test being weak/missing. The suggested bounds look reasonable for most sites, but without deep semantic knowledge (e.g., what is `development.timeline.coachAccessExpiryDays` really used for?), a dev might copy the table verbatim.
- **False assumption:** That the dev has the context to validate all 31 bounds. Some require deep domain knowledge (e.g., video tier quotas, coach access windows, development module semantics).
- **Impact:** If a bound is wrong, the worst case is over-conservative clamping (safe but degraded) or under-conservative clamping (data loss). Medium risk.
- **Fix approach:**
  - Add a note: "The bounds in this table are suggestions based on code review. For each site, consider: (a) What is the semantic minimum? (b) What is a reasonable business maximum? (c) Are there any technical limits (e.g., `Math.toIntExact`)?" This gives the dev a checklist.
  - Consider a prior review pass: have the code reviewer look at just the bounds table before dev starts, to validate the suggestions.
- **Recommendation:** Before starting AC2, dev should walk through the 31 sites with a code reviewer to confirm the bounds make sense. Don't blindly accept the table.

---

### 🟡 Documentation Gap: No guidance on local development config validation

**Dev flow for validating config locally isn't documented**

- **Location:** Implicit in AC3 (startup assertion skips in `dev` profile)
- **The issue:** AC3 says the startup assertion skips when the `dev` profile is active. This means a developer with a bad config value won't get an error at startup locally. They'll only see it when they hit the code path that reads the config, and the value gets clamped silently.
- **Missed flow:** A dev sets `platform.message_retention_months = 0` locally for testing, and the startup assertion skips. The dev then tests some other feature, the retention scheduler runs (scheduled task), silently clamps to the default, and the dev never realizes their config was bad.
- **Impact:** Low — a dev testing config changes would likely test the relevant feature immediately. But it's a gap in the test story.
- **Fix approach:** Add a note in AC3 (or Dev Notes): "Local development with the `dev` profile bypasses the startup assertion. Developers should manually validate critical config values (e.g., by reading the log output from `ConfigStartupAssertion` even though it's skipped, or by running the app with `dev` profile disabled)."
- **Recommendation:** Add a one-liner to the Dev Notes about validating config locally.

---

### 🟡 Design Note: AC1 — Default mismatch scenario not addressed

**What if production has an out-of-range value stored when the fix ships?**

- **Location:** AC1 section, "Seed" (lines 71–72)
- **The issue:** AC1 says "the default must stay `90L` to match production". But what if production already has `pack.pause.maxDays = 0` (or some negative value) stored in the DB from a past misconfiguration? When the fix ships and reads the config, `getBoundedLong` will clamp to `90L` silently. This is correct behavior (AC3 will log an error and increment a counter), but it's worth documenting: **the fix doesn't fail if production has a bad value; it clamps + warns + the startup assertion catches it on next restart**.
- **False assumption:** That production's config is always valid. It might not be if the DB was manually edited or corrupted.
- **Impact:** Low — the story's approach handles it correctly. But the story should be explicit: "If production has `pack.pause.maxDays ≤ 0` at the time this fix ships, the startup assertion (AC3) will catch it and prevent app startup until the operator fixes the value."
- **Recommendation:** Add one sentence to AC1 noting this scenario.

---

### ✅ Strengths

1. **Strong sequencing:** AC1+AC2+AC3 build on each other logically; AC4 is independent; AC5-AC7 are doc-only; AC8 is isolated; AC9 wraps up. Developers can't accidentally do them out of order.

2. **Clear ledger hygiene:** AC9 is explicit and includes a reconstruction check, preventing future confusion.

3. **Precedent-driven:** The story references existing code patterns (`getBoundedLong`, `TlsStartupAssertion`, `verify(configService)` test idiom) so the dev has clear examples to follow.

4. **Realistic scope:** The story acknowledges trade-offs (e.g., "don't test `getBoundedLong`'s own behavior"; "ConfigStartupAssertion location is a dev call") rather than over-prescribing.

5. **Security-conscious:** AC6 documents the `/proc/<pid>/environ` risk and rationale, and AC5/AC7 record decisions that would otherwise be re-litigated.

6. **Deployment-aware:** AC3's docs section (monitoring.md) ensures operators know the config ranges before editing production values.

---

### 📋 Recommendations for Dev

1. **Before starting:** Walk through the AC2 table (31 call sites) with a code reviewer to validate the suggested `[min, max]` bounds. Flag any that need domain expertise (e.g., video tier quotas).

2. **AC1+AC2+AC3 integration:** Ensure `ConfigBounds` is a single source-of-truth that both the call sites and the startup assertion reference. Don't scatter the bounds if avoidable.

3. **AC3 fail-fast decision:** Document the principle (data loss / core flow disabled → fail-fast; degraded but recoverable → clamp+warn) and apply it consistently to all 31 keys. Present the final table before coding.

4. **AC3 per-tier keys:** Pick one approach (enumerate all tiers/types or skip them) and document why. Don't leave it as "pick one".

5. **AC2 1-arg variant:** Clarify whether `getBoundedLong(key, min, max)` should throw (auto-fail-fast) or clamp (follow AC3 decision). If throw, ensure it aligns with AC3's `failFast` list for those keys.

6. **AC8 redis testing:** Explicitly test the `999:1000` gid override in the manual exercise. Document it in code comments.

7. **Local dev validation:** Add a note in the Dev Notes about how developers can validate critical config locally (given that the startup assertion skips in `dev` profile).

8. **Documentation:** Ensure the monitoring.md config ranges table includes the fail-fast flag, so operators know which bad values will stop the app vs. clamp.

---

## Verdict

**Ready for development with notes.** The story is comprehensive and well-designed. The three design issues (#2, #3, #4) need clarification before coding to avoid rework. The correctness issue (#1, VideoAccessGuard bound) must be resolved. Recommendations #1 and #3 (pre-dev review of bounds and fail-fast split) will reduce risk. No blockers; the story is deployable once these items are addressed.
