# Story Deferred-7: Messaging Retention, Abuse Report Protection & Gemini Hardening

Status: backlog

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

- [ ] **Task 1 — Update message retention query to protect open reports** (AC: 1)
  - [ ] Read `MessageRepository.java` and find the retention delete query (added in Story 8.4)
  - [ ] Current query: `DELETE FROM messaging.messages WHERE created_at < :cutoff`
  - [ ] Updated query:
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
  - [ ] Rename the method to `deleteOldMessagesWithNoOpenReports` to make the exclusion intent clear
  - [ ] Update `MessageRetentionScheduler.java` (or wherever the deletion is triggered) to call the renamed method
  - [ ] Confirm the exact column names for `messaging.message_reports` from V66 migration: `message_id`, `status`, and the status string values (`'PENDING'`, `'UNDER_REVIEW'`, etc.)

- [ ] **Task 2 — Fix orphan conversation cleanup** (AC: 2)
  - [ ] Read `ConversationRepository.java` — find `deleteOrphanConversations()` or equivalent
  - [ ] Current query problem: `WHERE last_message_at < :cutoff` — when `last_message_at IS NULL`, `NULL < :cutoff` is NULL (never true in SQL)
  - [ ] Fix:
    ```java
    @Modifying
    @Query(value = """
        DELETE FROM messaging.conversations
        WHERE (last_message_at IS NULL AND created_at < :cutoff)
           OR last_message_at < :cutoff
        """, nativeQuery = true)
    int deleteOrphanAndStaleConversations(@Param("cutoff") Instant cutoff);
    ```
  - [ ] Rename to `deleteOrphanAndStaleConversations` to cover both cases
  - [ ] Confirm `conversations` table has `created_at` column (from messaging module init migration)

- [ ] **Task 3 — Structural separation in Gemini prompt construction** (AC: 3)
  - [ ] Read `GeminiModerationService.java` — find the prompt construction (Story 8.3 W4):
    ```java
    // CURRENT (vulnerable to injection):
    String prompt = promptTemplate + userContent;
    ```
  - [ ] Check the Gemini Java client library being used — if it supports `GenerateContentRequest` with a `systemInstruction` field:
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
  - [ ] If the Gemini client does not support `systemInstruction` directly, use the role-based delimited format:
    ```java
    // FALLBACK: explicit delimited format in a single prompt
    String prompt = MODERATION_INSTRUCTION
        + "\n\n---BEGIN USER CONTENT---\n"
        + userContent
        + "\n---END USER CONTENT---";
    ```
  - [ ] Apply the same fix to `ReviewModerationService.java` (Story 9.2 D2) — same pattern, same risk
  - [ ] Read the current Gemini client library usage in `GeminiModerationService.java` to determine which approach is applicable before writing the fix

- [ ] **Task 4 — Remove PII from `AccountManagementFacade` logs** (AC: 4)
  - [ ] Read `AccountManagementFacade.java` around line 204-206
  - [ ] Find: `log.info("... {}", user.getLogin())` or `log.info("... {} ...", user.getEmail(), ...)`
  - [ ] Replace with user ID only:
    ```java
    log.info("... userId={}", user.getId());
    ```
  - [ ] Grep for other PII in INFO/DEBUG logs in this file: `grep -n "getLogin\|getEmail\|getPhone\|getFirstName\|getLastName" src/.../AccountManagementFacade.java` — redact all at INFO/DEBUG level; ERROR-level logs may retain minimal context (just user ID)
  - [ ] Confirm that `user.getId()` returns the Long TSID (not a UUID or business ID)

- [ ] **Task 5 — Redundant indexes in V66 cleanup** (AC: cosmetic, low priority)
  - [ ] From Story 8.4 W3: `idx_message_reports_message_id` and `idx_conversation_reports_conversation_id` in V66 are covered by the unique constraint leading column
  - [ ] If the indexes were actually created in V66, add a cleanup migration that drops them:
    ```sql
    -- V81__drop_redundant_report_indexes.sql
    DROP INDEX IF EXISTS messaging.idx_message_reports_message_id;
    DROP INDEX IF EXISTS messaging.idx_conversation_reports_conversation_id;
    ```
  - [ ] **Only do this if you confirm the indexes exist** — read V66 migration first; if they were not created, skip this task

- [ ] **Task 6 — Integration tests** (AC: 1, 2)
  - [ ] TSID range `9350_xxx`
  - [ ] `retention_skipsMessagesWithOpenReports()`:
    - Seed: 2 old messages (past cutoff), one with a PENDING report, one without
    - Run retention scheduler (or call service directly)
    - Verify only the message without an open report was deleted; the reported message remains
  - [ ] `orphanConversationCleanup_deletesNullLastMessageAt()`:
    - Seed: a conversation with `last_message_at IS NULL` and `created_at` before cutoff
    - Run cleanup
    - Verify conversation was deleted (previously never purged due to NULL comparison bug)

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

### Debug Log References

### Completion Notes List

### File List

**Modified Files:**
- `src/main/java/com/softropic/skillars/platform/messaging/repo/MessageRepository.java`
- `src/main/java/com/softropic/skillars/platform/messaging/repo/ConversationRepository.java`
- `src/main/java/com/softropic/skillars/platform/messaging/scheduler/MessageRetentionScheduler.java`
- `src/main/java/com/softropic/skillars/platform/messaging/service/GeminiModerationService.java`
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewModerationService.java`
- `src/main/java/com/softropic/skillars/platform/security/service/AccountManagementFacade.java`
- *(optional)* `src/main/resources/db/migration/V81__drop_redundant_report_indexes.sql`
