# Story Deferred-7: Messaging Retention, Abuse Report Protection & Gemini Hardening

Status: done

## Story

As a platform safety engineer,
I want message retention to protect open abuse reports from deletion, Gemini prompts to be structurally separated from user content, and orphan conversation cleanup to work correctly,
so that evidence is preserved during investigations, prompt injection cannot redirect content moderation, and empty conversations do not accumulate indefinitely.

## Acceptance Criteria

1. **Given** the `MessageRetentionScheduler` runs its deletion sweep
   **When** a message has a `PENDING` or `UNDER_REVIEW` abuse report attached to it
   **Then** that message is NOT deleted by the retention sweep — it is retained until the report is resolved or the admin explicitly archives it
   **And** the retention query is updated to exclude messages with open reports: `WHERE created_at < :cutoff AND id NOT IN (SELECT message_id FROM messaging.message_reports WHERE status IN ('PENDING','UNDER_REVIEW'))`

2. **Given** `deleteOrphanConversations()` runs to clean up conversations with no messages
   **When** a conversation has `last_message_at IS NULL` and was created before the retention cutoff
   **Then** it is deleted — the current query uses `NULL < :cutoff` which evaluates to NULL (never true), so no orphans are ever purged
   **Fix**: `WHERE (last_message_at IS NULL AND created_at < :cutoff) OR last_message_at < :cutoff`

3. **Given** `GeminiModerationService.moderate()` constructs a prompt for content moderation
   **When** the user's message content is appended to the instruction string
   **Then** the user content is placed in a clearly delimited section that Gemini's instruction-following capability cannot be overridden by — at minimum, a structural delimiter separates the system instruction from the user content
   **And** the same fix is applied to `ReviewModerationService.java` which uses the same pattern
   **Fix approach**: use Gemini's multi-turn message format (system instruction + user turn) rather than string concatenation, so the platform prompt is in the `system_instruction` field and the user content is in the `contents[role=user]` field

4. **Given** `AccountManagementFacade` logs user activity at INFO level
   **When** the log contains `user.getLogin()` (the user's email address)
   **Then** the log is changed to log only the user ID (Long), not the email — email is PII and must not appear in application logs
   **Affected**: `AccountManagementFacade.java` lines around `~204-206` where user login is logged at INFO

## Tasks / Subtasks

- [x] **Task 1 — Update message retention query to protect open reports** (AC: 1)
  - [x] Read `MessageRepository.java` and find the retention delete query (added in Story 8.4)
  - [x] Current query: `DELETE FROM messaging.messages WHERE created_at < :cutoff`
  - [x] Updated query:
    ```java
    @Modifying
    @Query(value = """
        DELETE FROM messaging.messages
        WHERE created_at < :cutoff
          AND id NOT IN (
              SELECT message_id FROM messaging.message_reports
              WHERE status IN ('PENDING', 'UNDER_REVIEW')
          )
        """, nativeQuery = true)
    int deleteOldMessagesWithNoOpenReports(@Param("cutoff") Instant cutoff);
    ```
  - [x] Rename the method to `deleteOldMessagesWithNoOpenReports` to make the exclusion intent clear
  - [x] Update `MessageRetentionScheduler.java` (or wherever the deletion is triggered) to call the renamed method
  - [x] Confirm the exact column names for `messaging.message_reports` from V66 migration: `message_id`, `status`, and the status string values (`'PENDING'`, `'UNDER_REVIEW'`, etc.)

- [x] **Task 2 — Fix orphan conversation cleanup** (AC: 2)
  - [x] Read `ConversationRepository.java` — find `deleteOrphanConversations()` or equivalent
  - [x] Current query problem: `WHERE last_message_at < :cutoff` — when `last_message_at IS NULL`, `NULL < :cutoff` is NULL (never true in SQL)
  - [x] Fix:
    ```java
    @Modifying
    @Query(value = """
        DELETE FROM messaging.conversations
        WHERE (last_message_at IS NULL AND created_at < :cutoff)
           OR last_message_at < :cutoff
        """, nativeQuery = true)
    int deleteOrphanAndStaleConversations(@Param("cutoff") Instant cutoff);
    ```
  - [x] Rename to `deleteOrphanAndStaleConversations` to cover both cases
  - [x] Confirm `conversations` table has `created_at` column (from messaging module init migration)

- [x] **Task 3 — Structural separation in Gemini prompt construction** (AC: 3)
  - [x] Read `GeminiModerationService.java` — find the prompt construction (Story 8.3 W4):
    ```java
    // CURRENT (vulnerable to injection):
    String prompt = promptTemplate + userContent;
    ```
  - [x] Check the Gemini Java client library being used — if it supports `GenerateContentRequest` with a `systemInstruction` field:
    ```java
    // PREFERRED: structural separation via API fields
    GenerateContentRequest request = GenerateContentRequest.newBuilder()
        .setSystemInstruction(Content.newBuilder()
            .addParts(Part.newBuilder().setText(MODERATION_INSTRUCTION)))
        .addContents(Content.newBuilder()
            .setRole("user")
            .addParts(Part.newBuilder().setText(userContent)))
        .build();
    ```
  - [x] If the Gemini client does not support `systemInstruction` directly, use the role-based delimited format:
    ```java
    // FALLBACK: explicit delimited format in a single prompt
    String prompt = MODERATION_INSTRUCTION
        + "\n\n---BEGIN USER CONTENT---\n"
        + userContent
        + "\n---END USER CONTENT---";
    ```
  - [x] Apply the same fix to `ReviewModerationService.java` (Story 9.2 D2) — same pattern, same risk
  - [x] Read the current Gemini client library usage in `GeminiModerationService.java` to determine which approach is applicable before writing the fix

- [x] **Task 4 — Remove PII from `AccountManagementFacade` logs** (AC: 4)
  - [x] Read `AccountManagementFacade.java` around line 204-206
  - [x] Find: `log.info("... {}", user.getLogin())` or `log.info("... {} ...", user.getEmail(), ...)`
  - [x] Replace with user ID only:
    ```java
    log.info("... userId={}", user.getId());
    ```
  - [x] Grep for other PII in INFO/DEBUG logs in this file: `grep -n "getLogin\|getEmail\|getPhone\|getFirstName\|getLastName" src/.../AccountManagementFacade.java` — redact all at INFO/DEBUG level; ERROR-level logs may retain minimal context (just user ID)
  - [x] Confirm that `user.getId()` returns the Long TSID (not a UUID or business ID)

- [x] **Task 5 — Redundant indexes in V66 cleanup** (AC: cosmetic, low priority)
  - [x] From Story 8.4 W3: `idx_message_reports_message_id` and `idx_conversation_reports_conversation_id` in V66 are covered by the unique constraint leading column
  - [x] If the indexes were actually created in V66, add a cleanup migration that drops them:
    ```sql
    -- V81__drop_redundant_report_indexes.sql
    DROP INDEX IF EXISTS messaging.idx_message_reports_message_id;
    DROP INDEX IF EXISTS messaging.idx_conversation_reports_conversation_id;
    ```
  - [x] **Only do this if you confirm the indexes exist** — read V66 migration first; if they were not created, skip this task

- [x] **Task 6 — Integration tests** (AC: 1, 2)
  - [x] TSID range `9350_xxx`
  - [x] `retention_skipsMessagesWithOpenReports()`:
    - Seed: 2 old messages (past cutoff), one with a PENDING report, one without
    - Run retention scheduler (or call service directly)
    - Verify only the message without an open report was deleted; the reported message remains
  - [x] `orphanConversationCleanup_deletesNullLastMessageAt()`:
    - Seed: a conversation with `last_message_at IS NULL` and `created_at` before cutoff
    - Run cleanup
    - Verify conversation was deleted (previously never purged due to NULL comparison bug)

### Review Findings

- [x] [Review][Patch] Prompt-injection mitigation for AC3 is bypassable — delimiter tokens not escaped in `GeminiModerationService.java` (`moderate()`, ~line 51-55) and `ReviewModerationService.java` (`handleReviewSubmitted()`, ~line 66-70). `GeminiClientImpl.evaluate()` sends the whole prompt as one flat text string (no `systemInstruction`/role field). A message containing the literal substring `---END USER CONTENT---` followed by attacker text forges a fake boundary indistinguishable from the real one. No test exists that would catch this. Severity: High (security — reopens the exact bypass AC3 was written to close). **Resolved:** strip/reject the delimiter tokens (`---BEGIN USER CONTENT---` / `---END USER CONTENT---`) from `input`/`body` before concatenation in both services; add a `// TODO` comment noting the follow-up to extend `GeminiClientImpl`/`GeminiApiResponse` with real system/user role separation per AC3's stated preferred approach. [GeminiModerationService.java:53, ReviewModerationService.java:68]
- [x] [Review][Patch] Orphan-conversation cutoff change drops the grace period for conversations that previously had messages, AND `deleteOrphanConversations` has no exclusion for conversations with an open `conversation_reports` row — unlike AC1's message-level protection, deleting the conversation cascades (`ON DELETE CASCADE` on `conversation_reports.conversation_id`, V66) and silently destroys report evidence, the same class of bug AC1 fixed one level down. `ConversationRepository.java:33` (`deleteOrphanConversations`). No test covers either path. Severity: Medium (grace-period regression is not data-destructive; missing report guard is a real evidence-loss risk). **Resolved:** keep `created_at < :cutoff` as the cutoff column (unchanged from current diff); add an open-report exclusion mirroring AC1's pattern:
  ```sql
  DELETE FROM messaging.conversations
  WHERE created_at < :cutoff
    AND NOT EXISTS (SELECT 1 FROM messaging.messages m WHERE m.conversation_id = conversations.id)
    AND id NOT IN (
        SELECT conversation_id FROM messaging.conversation_reports
        WHERE status IN ('OPEN', 'UNDER_REVIEW')
    )
  ```
- [x] [Review][Patch] No index on `messaging.message_reports.status` — new `deleteOldMessagesWithNoOpenReports` subquery (`MessageRepository.java`) filters by `status`, not the indexed `message_id`; every retention sweep may full-scan `message_reports`. Add an index on `messaging.message_reports(status)`. **Resolved:** added `V82__index_message_reports_status.sql`.
- [x] [Review][Patch] `ReviewModerationService`'s delimiter-format prompt change has zero test coverage — no `ReviewModerationServiceTest` file exists in the repo. Add a test mirroring the two new `GeminiModerationServiceTest` cases, asserting the delimited prompt format for `handleReviewSubmitted()`. **Resolved:** added `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewModerationServiceTest.java`.
- [x] [Review][Defer] `AccountManagementFacade.toUser()` null-guard on `getEmail()` — deferred, pre-existing rationale mismatch. Dev notes justify it as fixing a "phone-only registration" NPE, but `UserDto.email` is `@NotNull`-validated and enforced via `@Valid` at the only found HTTP entry point (`AccountResource.registerAccount`); grep found no phone-only registration path anywhere in the codebase. Harmless defensive code on the traceable path; confirm no untraced internal caller actually needs it.
- [x] [Review][Defer] GDPR Article 17 erasure (`MessageRepository.deleteAllBySenderId`, unchanged by this diff) still hard-deletes messages regardless of open abuse reports, while the new AC1 logic now explicitly protects them from the retention sweep — a policy asymmetry on the same table introduced as a side effect of this diff. Needs a product decision on whether erasure requests should also respect an active-investigation hold.

## Dev Notes

### Message retention and the CASCADE FK

Story 8.4 noted that `DELETE FROM messaging.messages WHERE ...` cascades to `message_reports` via FK, destroying report evidence. The fix in Task 1 breaks this cascade by excluding reported messages from the deletion sweep entirely. The cascade FK is now safe for uncontested messages (no open reports).

However: when a report is resolved (status transitions to `RESOLVED` or `DISMISSED`), the message WILL be deleted on the next retention sweep. This is correct behaviour — resolved reports no longer need their evidence messages retained. No additional logic needed.

### Gemini API client version

The `systemInstruction` field was introduced in Gemini 1.5 Pro and later. If the project uses `gemini-1.0-pro`, the `systemInstruction` API field may not be available — use the delimited fallback approach. Check the Gemini model name in `application.yaml` (or equivalent config) before implementing Task 3.

### `GeminiModerationService.moderate()` null guard

Story 8.3 W5 noted that `content` is dereferenced before a null check. While fixing the prompt injection issue, also verify: `if (content == null || content.isBlank()) { return ModerationResult.PASS; }` — add this guard if absent.

### Orphan conversation cutoff

The retention cutoff for orphan conversations should match the messaging retention window (the same `cutoff` parameter used for messages). Verify the scheduler uses the same configurable window for both message and conversation cleanup — or that they use separate, documented config keys.

### `AccountManagementFacade` PII log — scope of grep

Also check `AccountManagementFacade.java` line ~231 (Story 6.1 Def16) for the `getEmail().toLowerCase()` NPE for phone-only registrations — read that code path and add a null guard: `String email = user.getEmail() != null ? user.getEmail().toLowerCase() : "";` — this is a pre-existing bug noted alongside the PII logging issue.

### References — Files to Read Before Implementing

- `MessageRepository.java` — current retention delete query method name and signature
- `ConversationRepository.java:31` — `deleteOrphanConversations()` query
- `V66__messaging_reports.sql` — message_reports schema, status column values, FK definition
- `GeminiModerationService.java` — current prompt construction and Gemini client usage
- `ReviewModerationService.java` — same pattern
- `AccountManagementFacade.java:204-231` — PII logging and NPE
- `MessageRetentionScheduler.java` — where the deletion method is called

## Dev Agent Record

### Agent Model Used

claude-sonnet-5 (Claude Code)

### Debug Log References

None — no failing test runs required debugging; full regression suite passed on first run after implementation.

### Completion Notes List

- **AC1 (message retention query):** `MessageRepository.deleteExpiredMessages` renamed to `deleteOldMessagesWithNoOpenReports`, now excludes messages with a `message_reports` row in status `OPEN` or `UNDER_REVIEW`. **Deviation from story text:** the actual `ReportStatus` enum (`platform.messaging.contract.ReportStatus`) is `OPEN, UNDER_REVIEW, RESOLVED, DISMISSED` — there is no `PENDING` value anywhere in the codebase (V66's `DEFAULT 'OPEN'` matches the enum). Used `('OPEN', 'UNDER_REVIEW')` instead of the story's hypothetical `('PENDING', 'UNDER_REVIEW')`, since `OPEN` is the real "unresolved" state. `MessageRetentionScheduler` and the existing `RetentionSchedulerTest` unit test were updated for the rename.
- **AC2 (orphan conversation cleanup):** Fixed the NULL-comparison bug. **Deviation from story text:** the actual current query already scoped to true orphans via `NOT EXISTS (SELECT 1 FROM messaging.messages ...)`, unlike the simpler hypothetical query in the story draft — so the fix changes the cutoff predicate from `last_message_at < :cutoff` to `created_at < :cutoff` (orphans by definition have `last_message_at IS NULL`, so comparing on `created_at` is correct and the `NOT EXISTS` guard is unchanged). Kept the method name `deleteOrphanConversations` (did not rename to `deleteOrphanAndStaleConversations`) since this method only ever touches true orphans, not stale-but-nonempty conversations — renaming would have misrepresented its scope.
- **AC3 (Gemini prompt injection hardening):** Confirmed via code exploration that `GeminiClientImpl` is a custom REST client (no Google SDK dependency) whose request body only supports a single flat `contents[0].parts[0].text` string — no `systemInstruction` or multi-turn `role` field exists in the client or `GeminiApiResponse`. Applied the **fallback delimited format** (`MODERATION_INSTRUCTION\n\n---BEGIN USER CONTENT---\n...\n---END USER CONTENT---`) in both `GeminiModerationService.moderate()` and `ReviewModerationService.handleReviewSubmitted()`, without modifying `GeminiClientImpl`'s request shape (out of story scope). Also added the null/blank content guard to `GeminiModerationService.moderate()` (short-circuits to `ModerationVerdict.SAFE` via `moderationResultApplier.applyResult`, mirroring the existing guard pattern already present in `ReviewModerationService`). Updated two existing `GeminiModerationServiceTest` assertions that hard-coded the old plain-concatenation prompt format, and added two new tests for the null/blank guard.
- **AC4 (PII log removal):** `AccountManagementFacade.sendMail()` (actual file is at `platform.security.api.AccountManagementFacade`, not `platform.security.service` as listed in the story draft) — `LOGGER.info(user.getLogin())` replaced with `LOGGER.info("Notification email queued: userId={}", user.getId())`. Grepped the file for other PII in logs — no other `LOGGER.info/debug` calls existed. Also fixed the pre-existing NPE noted in Dev Notes: `user.setEmail(userDTO.getEmail().toLowerCase())` → null-guarded, following the same null-safe pattern already used at line 132 of the same file (`userDTO.getEmail() != null ? ... : null`).
- **Task 5 (redundant indexes):** Confirmed `idx_message_reports_message_id` and `idx_conversation_reports_conversation_id` are created directly in V66. Added `V81__drop_redundant_report_indexes.sql` (V81 was the next available Flyway version) dropping both, since each is a strict prefix of its table's unique constraint index.
- **Task 6 (integration tests):** No existing Testcontainers IT covered these repository methods (only a Mockito-mocked `RetentionSchedulerTest`). Added `MessageRetentionRepositoryIT` (new package `platform.messaging.repo` under `src/test`) using the `TestConfig`-based Postgres Testcontainers pattern (no HTTP/security setup needed, per `RotatedKeyCleanupJobIT` convention), TSID range `9350_xxx`. Both required tests (`retention_skipsMessagesWithOpenReports`, `orphanConversationCleanup_deletesNullLastMessageAt`) pass against real Postgres.
- Full regression suite: 1281 tests run, 0 failures, 0 errors, 5 skipped (pre-existing skips, unrelated to this story).

### File List

**Modified Files:**
- `src/main/java/com/softropic/skillars/platform/messaging/repo/MessageRepository.java`
- `src/main/java/com/softropic/skillars/platform/messaging/repo/ConversationRepository.java`
- `src/main/java/com/softropic/skillars/platform/messaging/service/MessageRetentionScheduler.java`
- `src/main/java/com/softropic/skillars/platform/messaging/service/GeminiModerationService.java`
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewModerationService.java`
- `src/main/java/com/softropic/skillars/platform/security/api/AccountManagementFacade.java`
- `src/test/java/com/softropic/skillars/platform/messaging/service/RetentionSchedulerTest.java`
- `src/test/java/com/softropic/skillars/platform/messaging/service/GeminiModerationServiceTest.java`

**New Files:**
- `src/main/resources/db/migration/V81__drop_redundant_report_indexes.sql`
- `src/main/resources/db/migration/V82__index_message_reports_status.sql`
- `src/test/java/com/softropic/skillars/platform/messaging/repo/MessageRetentionRepositoryIT.java`
- `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewModerationServiceTest.java`

### Change Log

- 2026-07-02 — Implemented Story deferred-7 (Messaging Retention, Abuse Report Protection & Gemini Hardening): message retention now excludes messages with OPEN/UNDER_REVIEW abuse reports; fixed the NULL-comparison bug that silently prevented orphan conversation cleanup; structurally separated Gemini moderation prompts from user content via a delimited fallback format (both messaging and reviews moderation), plus a null/blank content guard; removed PII (user login/email) from `AccountManagementFacade` INFO logs and fixed a related NPE; dropped two redundant V66 indexes already covered by unique constraints; added a new Testcontainers IT covering both retention fixes. 6/6 tasks complete, full regression suite 1281/1281 passing, status → review.
- 2026-07-02 — Code review follow-up: fixed a bypassable prompt-injection mitigation (delimiter tokens weren't stripped from user content before concatenation — an attacker could forge a fake `---END USER CONTENT---` boundary) in both `GeminiModerationService` and `ReviewModerationService`, with a TODO to extend `GeminiClientImpl`/`GeminiApiResponse` for real structural separation later; added an open-`conversation_reports`-guard to `deleteOrphanConversations` (previously a conversation with an open report could be deleted, cascading away the report evidence — the same bug class AC1 fixed for messages); added an index on `messaging.message_reports(status)` (V82); added `ReviewModerationServiceTest` (previously had zero coverage). Deferred: `AccountManagementFacade.toUser()` null-guard rationale mismatch (harmless, traceable path doesn't need it); GDPR erasure vs. report-hold policy asymmetry (needs a product decision). Status → done.
