# Story 8.2: Age-Tiered Messaging Access & Parent Visibility

Status: done

## Story

As a platform operator,
I want messaging access and parental oversight enforced automatically by the player's age tier,
so that under-13 players never communicate directly with coaches and parents of 13–17 players always retain read access.

## Acceptance Criteria

1. **Given** a message is sent to a conversation
   **When** `MessagingService.sendMessage()` is called
   **Then** `AgePolicyService.getMessagingPolicy(conversation.playerId)` is called to determine access rules — this call is never cached; age tier is evaluated fresh on every send
   **And** the following rules are enforced:

   | Age tier | Player can send | Parent can send | Coach can send | Parent read access |
   |---|---|---|---|---|
   | U10 (PROHIBITED policy) | ✗ blocked | ✓ | ✓ | Automatic — parent is primary participant |
   | 10–12 (PARENT_MANAGED policy) | ✗ blocked | ✓ | ✓ | Automatic — parent is primary participant |
   | 13–17 (SUPERVISED policy) | ✓ | ✓ | ✓ | Automatic — parent has oversight visibility |
   | 18+ (UNRESTRICTED policy) | ✓ | ✗ blocked | ✓ | None — parent excluded |

   **And** if a PLAYER role attempts to send in a PROHIBITED or PARENT_MANAGED conversation: `403` with `ErrorDto` code `messaging.playerDirectMessagingRestricted`
   **And** if a PARENT role attempts to send in an UNRESTRICTED (18+) conversation: `403` with `ErrorDto` code `messaging.parentMessagingRestrictedForAdult`
   **And** the coach's conversation label reflects the tier (see AC3 for `otherPartyName` labels)

2. **Given** a coach views their conversation list
   **When** `GET /api/messaging/conversations` is called by an authenticated coach
   **Then** all conversations are returned regardless of player age tier — the coach sees everyone they have a conversation with
   **And** the `otherPartyName` field reflects age-tier label:
   - PROHIBITED/PARENT_MANAGED (under-13): `"Parent of {playerFirstName}"`
   - SUPERVISED (13–17): `"{playerFirstName} & parent"`
   - UNRESTRICTED (18+): `"{playerFirstName}"`

3. **Given** a player views their conversation list
   **When** `GET /api/messaging/conversations` is called by an authenticated player (PLAYER role)
   **Then** only conversations where the player's age tier is SUPERVISED (13–17) or UNRESTRICTED (18+) are returned
   **And** players with PROHIBITED (U10) or PARENT_MANAGED (10–12) age tiers receive an empty list — the conversation exists in the DB but is not surfaced to the player

4. **Given** a parent views their conversation list
   **When** `GET /api/messaging/conversations` is called by an authenticated parent
   **Then** conversations for PROHIBITED/PARENT_MANAGED players (parent is primary participant) are returned — `otherPartyName` = coach's displayName
   **And** conversations for SUPERVISED (13–17) players (parent has oversight access) are also returned, with `otherPartyName` = `"{playerFirstName}'s conversation with {coachName}"`
   **And** conversations for UNRESTRICTED (18+) players are NOT returned — parent has no automatic visibility

5. **Given** a parent wants to read a minor player's conversations via the parental oversight endpoint
   **When** `GET /api/messaging/players/{playerId}/conversations` is called
   **Then** `@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)` is enforced
   **And** `playerProfileRepository.findByIdAndParentId(playerId, callerUserId)` verifies the parent owns the player — if absent: `403` `messaging.notAParty`, never `404`
   **And** if the player's age tier is UNRESTRICTED (18+): `403` with `ErrorDto` code `messaging.parentalOversightNotApplicable`
   **And** if the player's age tier is PROHIBITED, PARENT_MANAGED, or SUPERVISED: all conversations for that player are returned including BLOCKED ones (using `conversationRepository.findAllByPlayerId(playerId)`) — oversight must never hide safety-flagged history from parents

6. **Given** a parent reads messages in a minor player's conversation via `GET /api/messaging/players/{playerId}/conversations/{conversationId}/messages`
   **When** the request is processed
   **Then** same `HAS_PARENT_ROLE` + `findByIdAndParentId` ownership guard is enforced — `403` on mismatch
   **And** same UNRESTRICTED age check — `403` `messaging.parentalOversightNotApplicable` for 18+ player
   **And** conversation `playerId` must match the `{playerId}` path param — `403` if mismatch (prevents parent reading a different player's conversation by guessing conversationId)
   **And** all APPROVED messages (regardless of `senderRole`) are returned paginated
   **And** reading via this endpoint does NOT update any lastReadAt timestamps — unread counts are per-participant, this is a read-only oversight view

7. **Given** a player turns 13 or 18 and their age tier changes
   **When** subsequent messages are sent to existing conversations
   **Then** the new age tier rules apply immediately to all future sends — age policy is derived from `AgePolicyService` on every call (never cached on conversation)
   **And** existing message history is unaffected by the tier change
   **And** no automatic notification is sent; the coach sees the updated `otherPartyName` label on next list load

## Tasks / Subtasks

- [x] **Task 1 — Extend `MessagingErrorCode`** (AC: 1, 5, 6)
  - [x] Add 3 new codes to `MessagingErrorCode.java` in `platform.messaging.contract`:
    ```java
    PLAYER_DIRECT_MESSAGING_RESTRICTED("messaging.playerDirectMessagingRestricted"),
    PARENTAL_OVERSIGHT_NOT_APPLICABLE("messaging.parentalOversightNotApplicable"),
    PARENT_MESSAGING_RESTRICTED_FOR_ADULT("messaging.parentMessagingRestrictedForAdult");
    ```
  - [x] All 3 new codes return HTTP 403 via the existing `MessagingApiAdvice` (which maps all `OperationNotAllowedException` other than `INVALID_CONTENT` to 403) — no `MessagingApiAdvice` changes needed

- [x] **Task 2 — Create `AgeMessagingPolicy` enum** (AC: 1, 2, 3, 4)
  - [x] Create `AgeMessagingPolicy.java` in `platform.messaging.service`:
    ```java
    public enum AgeMessagingPolicy {
        PROHIBITED,      // U10: player blocked, parent is primary participant
        PARENT_MANAGED,  // AGE_10_12: same as PROHIBITED for player
        SUPERVISED,      // AGE_13_17: player can send, parent has oversight
        UNRESTRICTED;    // ADULT: player + coach only; parent excluded

        public static AgeMessagingPolicy from(MessagingPolicy policy) {
            if (!policy.canMessage()) return PROHIBITED;
            if (!policy.directAllowed()) return PARENT_MANAGED;
            if (policy.parentVisible()) return SUPERVISED;
            return UNRESTRICTED;
        }

        /** True if player role is blocked from sending in this conversation. */
        public boolean playerIsBlocked() { return this == PROHIBITED || this == PARENT_MANAGED; }

        /** True if parent role is blocked from sending (adult player). */
        public boolean parentIsBlocked() { return this == UNRESTRICTED; }

        /** True if parent has oversight visibility (oversight or primary). */
        public boolean parentHasAccess() { return this != UNRESTRICTED; }

        /** True if parent is the PRIMARY PARTICIPANT (not just an observer). */
        public boolean parentIsPrimary() { return this == PROHIBITED || this == PARENT_MANAGED; }

        /** True if the conversation should be visible in the player's own conversation list. */
        public boolean visibleToPlayer() { return this == SUPERVISED || this == UNRESTRICTED; }
    }
    ```
  - [x] Import `com.softropic.skillars.platform.security.contract.MessagingPolicy` — it is in `platform.security.contract`, NOT in `infrastructure`

- [x] **Task 3 — Inject `AgePolicyService` into `MessagingService` and enforce policies** (AC: 1, 2, 3, 4)
  - [x] Add `AgePolicyService` field to `MessagingService` via `@RequiredArgsConstructor` (constructor injection — just add the field, Lombok handles the rest):
    ```java
    private final AgePolicyService agePolicyService;  // from platform.security.service
    ```
  - [x] Modify `sendMessage()` — add age enforcement AFTER party verification, BEFORE creating the message:
    ```java
    // After verifyIsParty(conv, senderUserId, role):
    AgeMessagingPolicy agePolicy = AgeMessagingPolicy.from(
        agePolicyService.getMessagingPolicy(conv.getPlayerId()));
    if ("PLAYER".equals(role) && agePolicy.playerIsBlocked()) {
        throw new OperationNotAllowedException(
            "Player direct messaging is restricted for this age tier",
            MessagingErrorCode.PLAYER_DIRECT_MESSAGING_RESTRICTED);
    }
    if ("PARENT".equals(role) && agePolicy.parentIsBlocked()) {
        throw new OperationNotAllowedException(
            "Parent cannot send messages in adult player conversations",
            MessagingErrorCode.PARENT_MESSAGING_RESTRICTED_FOR_ADULT);
    }
    // Proceed with message creation
    ```
  - [x] Modify `getConversations()` for PLAYER role — evaluate tier once, skip DB list query entirely for under-13:
    ```java
    } else {  // PLAYER role
        // All conversations for this player share the same playerId = callerUserId,
        // so one policy lookup covers the whole list. Under-13 players get no list at all.
        AgeMessagingPolicy callerPolicy = AgeMessagingPolicy.from(
            agePolicyService.getMessagingPolicy(callerUserId));
        conversations = callerPolicy.visibleToPlayer()
            ? conversationRepository.findActiveByPlayerId(callerUserId)
            : List.of();
    }
    ```
  - [x] Modify `getConversations()` for PARENT role — filter out UNRESTRICTED (18+) conversations:
    ```java
    } else if ("PARENT".equals(role)) {
        List<Conversation> all = conversationRepository.findActiveByParentId(callerUserId);
        conversations = all.stream()
            .filter(c -> AgeMessagingPolicy.from(
                agePolicyService.getMessagingPolicy(c.getPlayerId())).parentHasAccess())
            .toList();
    }
    ```
  - [x] Modify `resolveOtherPartyName()` — add age-tier-aware labels for COACH role:
    ```java
    private String resolveOtherPartyName(Conversation conv, String role) {
        if ("COACH".equals(role)) {
            String playerName = playerProfileRepository.findById(conv.getPlayerId())
                .map(p -> p.getName()).orElse("Unknown Player");
            String playerFirstName = playerName.contains(" ")
                ? playerName.substring(0, playerName.indexOf(' '))
                : playerName;
            AgeMessagingPolicy agePolicy = AgeMessagingPolicy.from(
                agePolicyService.getMessagingPolicy(conv.getPlayerId()));
            return switch (agePolicy) {
                case PROHIBITED, PARENT_MANAGED -> "Parent of " + playerFirstName;
                case SUPERVISED -> playerFirstName + " & parent";
                case UNRESTRICTED -> playerFirstName;
            };
        } else if ("PARENT".equals(role)) {
            // Determine if parent is primary or oversight
            AgeMessagingPolicy agePolicy = AgeMessagingPolicy.from(
                agePolicyService.getMessagingPolicy(conv.getPlayerId()));
            if (agePolicy.parentIsPrimary()) {
                // Parent is primary participant — show coach name
                return coachProfileRepository.findById(conv.getCoachId())
                    .map(CoachProfile::getDisplayName).orElse("Unknown Coach");
            } else {
                // SUPERVISED: parent has oversight — show "{playerFirstName}'s conversation with {coachName}"
                String playerName = playerProfileRepository.findById(conv.getPlayerId())
                    .map(p -> p.getName()).orElse("Unknown Player");
                String playerFirstName = playerName.contains(" ")
                    ? playerName.substring(0, playerName.indexOf(' '))
                    : playerName;
                String coachName = coachProfileRepository.findById(conv.getCoachId())
                    .map(CoachProfile::getDisplayName).orElse("Unknown Coach");
                return playerFirstName + "'s conversation with " + coachName;
            }
        } else {
            // PLAYER role: show coach name
            return coachProfileRepository.findById(conv.getCoachId())
                .map(CoachProfile::getDisplayName).orElse("Unknown Coach");
        }
    }
    ```
  - [x] Modify `resolveRecipient()` — make SSE routing age-tier-aware **(required — current implementation routes UNRESTRICTED conversations to wrong party)**:
    ```java
    private Long resolveRecipient(Conversation conv, String role) {
        if ("COACH".equals(role)) {
            AgeMessagingPolicy agePolicy = AgeMessagingPolicy.from(
                agePolicyService.getMessagingPolicy(conv.getPlayerId()));
            // For 18+ (UNRESTRICTED): parent has no access — SSE must go to the player, not the parent.
            // For all other tiers: parent is primary participant or oversight — SSE goes to parent.
            if (agePolicy.parentIsBlocked()) {
                return conv.getPlayerId();
            }
            return conv.getParentId();
        } else {
            return coachProfileRepository.findById(conv.getCoachId())
                .map(CoachProfile::getUserId)
                .orElse(null);
        }
    }
    ```
    **Without this change**: a coach messaging an 18+ player sends SSE to the parent (who has no access) and the player never receives a real-time notification. The parent receiving "NEW_MESSAGE" SSE for a conversation they cannot open is a broken UX and a privacy concern.
    **Note — SUPERVISED (13–17) SSE gap (deferred)**: When a SUPERVISED player sends, the parent has oversight visibility but receives no SSE notification (recipient resolves to coach only). Fixing this requires multi-recipient SSE emission, which is out of scope for 8.2 — it requires changing `resolveRecipient` to return a list and updating the emission loop. Add a TODO comment at the `resolveRecipient` call site in `sendMessage()` for a future story.
  - [x] **IMPORTANT — N+1 mitigation note**: `agePolicyService.getMessagingPolicy(playerId)` loads `PlayerProfile` by ID (one DB call per conversation). This is acceptable for MVP for the COACH and PARENT `getConversations()` branches, and for `resolveRecipient()` (one call per send). The PLAYER branch is now a single call (not per conversation). Do NOT add caching — the epics explicitly require fresh tier evaluation.
  - [x] **IMPORTANT — `agePolicyService.getMessagingPolicy()` may throw `UserNotFoundException`** if the player is deleted. This inherits the existing deferred risk from Story 8.1 (player deletion is handled in a later story). Do not add special handling.

- [x] **Task 4 — Add parental oversight service methods** (AC: 5, 6)
  - [x] Add to `MessagingService`:
    ```java
    @Transactional(readOnly = true)
    public List<ConversationSummaryDto> getConversationsForPlayer(Long playerId, Long parentUserId) {
        // 1. Ownership guard — always 403, never 404
        playerProfileRepository.findByIdAndParentId(playerId, parentUserId)
            .orElseThrow(() -> new OperationNotAllowedException(
                "Parent does not own this player", MessagingErrorCode.NOT_A_PARTY));

        // 2. Age check — adult player conversations not surfaced to parents
        AgeMessagingPolicy agePolicy = AgeMessagingPolicy.from(
            agePolicyService.getMessagingPolicy(playerId));
        if (!agePolicy.parentHasAccess()) {
            throw new OperationNotAllowedException(
                "Parental oversight is not applicable for adult players",
                MessagingErrorCode.PARENTAL_OVERSIGHT_NOT_APPLICABLE);
        }

        // 3. Return all non-BLOCKED conversations for this player
        List<Conversation> conversations = conversationRepository.findActiveByPlayerId(playerId);
        return conversations.stream()
            .map(c -> toSummary(c, parentUserId, "PARENT"))
            .sorted(Comparator.comparing(ConversationSummaryDto::lastMessageAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    @Transactional(readOnly = true)
    public Page<MessageDto> getMessagesForPlayerConversation(
            Long playerId, Long conversationId, Long parentUserId, Pageable pageable) {
        // 1. Ownership guard
        playerProfileRepository.findByIdAndParentId(playerId, parentUserId)
            .orElseThrow(() -> new OperationNotAllowedException(
                "Parent does not own this player", MessagingErrorCode.NOT_A_PARTY));

        // 2. Age check
        AgeMessagingPolicy agePolicy = AgeMessagingPolicy.from(
            agePolicyService.getMessagingPolicy(playerId));
        if (!agePolicy.parentHasAccess()) {
            throw new OperationNotAllowedException(
                "Parental oversight is not applicable for adult players",
                MessagingErrorCode.PARENTAL_OVERSIGHT_NOT_APPLICABLE);
        }

        // 3. Load conversation and verify it belongs to this player
        var conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new OperationNotAllowedException(
                "Conversation not found or access denied", MessagingErrorCode.NOT_A_PARTY));
        if (!conv.getPlayerId().equals(playerId)) {
            throw new OperationNotAllowedException(
                "Conversation does not belong to this player", MessagingErrorCode.NOT_A_PARTY);
        }

        // 4. Return messages — do NOT update any lastReadAt (AC6: oversight view is read-only for tracking)
        Page<Message> page = messageRepository.findByConversationIdAndNotDeleted(conversationId, pageable);
        return page.map(this::toMessageDto);
    }
    ```
  - [x] **Note**: For `getMessagesForPlayerConversation`, throw `OperationNotAllowedException(NOT_A_PARTY)` (403) when conversation is not found — not `ResourceNotFoundException` (404). This is intentional: parent must not be able to enumerate valid conversationIds they don't own via 404 vs 403 distinction.
  - [x] **Note — BLOCKED conversations are included in the oversight list**: `getConversationsForPlayer` (AC5) uses `findAllByPlayerId` (no status filter) so BLOCKED conversations appear in the parent's oversight list. A BLOCKED conversation may represent a safety-flagged thread that a parent needs to see — hiding it silently from the oversight view is a safeguarding gap. `getMessagesForPlayerConversation` (AC6) also reaches BLOCKED conversations via `findById`. This is consistent: both endpoints surface BLOCKED history to parents.
  - [x] `toSummary(conv, parentUserId, "PARENT")` reuses the existing method — `resolveOtherPartyName` for PARENT role will correctly label both primary and oversight conversations per Task 3.

- [x] **Task 5 — Add parental oversight endpoints to `MessagingResource`** (AC: 5, 6)
  - [x] Add two new endpoints to `MessagingResource.java`:
    ```java
    @GetMapping("/players/{playerId}/conversations")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<List<ConversationSummaryDto>> getPlayerConversations(
            @PathVariable Long playerId) {
        Long parentUserId = resolveUserId();
        return ResponseEntity.ok(messagingService.getConversationsForPlayer(playerId, parentUserId));
    }

    @GetMapping("/players/{playerId}/conversations/{conversationId}/messages")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<Page<MessageDto>> getPlayerConversationMessages(
            @PathVariable Long playerId,
            @PathVariable Long conversationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long parentUserId = resolveUserId();
        return ResponseEntity.ok(
            messagingService.getMessagesForPlayerConversation(
                playerId, conversationId, parentUserId,
                PageRequest.of(Math.max(0, page), Math.min(size, 100))));
    }
    ```
  - [x] `@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)` is `"hasRole('ROLE_PARENT')"` — it exists in `SecurityConstants`
  - [x] No `@Valid` on path params — Spring auto-converts `Long` from path segments; invalid format produces 400 from Spring before reaching the method
  - [x] `MessagingApiAdvice` already maps `OperationNotAllowedException(NOT_A_PARTY)` → 403, `OperationNotAllowedException(PARENTAL_OVERSIGHT_NOT_APPLICABLE)` → 403 — no advice changes needed

- [x] **Task 6 — Frontend API additions** (AC: 5, 6)
  - [x] Add two new functions to `src/frontend/src/api/messaging.api.js`:
    ```js
    export const fetchPlayerConversations = (playerId) =>
      api.get(`/api/messaging/players/${playerId}/conversations`).then(r => r.data)

    export const fetchPlayerConversationMessages = (playerId, conversationId, page = 0, size = 20) =>
      api.get(`/api/messaging/players/${playerId}/conversations/${conversationId}/messages`, {
        params: { page, size }
      }).then(r => r.data)
    ```
  - [x] No new Vue pages or stores needed in this story — the existing `MessagingPage.vue` and `messaging.store.js` correctly render whatever `otherPartyName` the backend returns; age-tier labels are constructed server-side

- [x] **Task 7 — Tests** (AC: 1–7)
  - [x] **`AgeMessagingPolicyTest.java`** (unit — `src/test/java/com/softropic/skillars/platform/messaging/service/AgeMessagingPolicyTest.java`):
    ```
    from(MessagingPolicy.prohibited())  → PROHIBITED  → playerIsBlocked=true, parentIsBlocked=false, parentHasAccess=true, parentIsPrimary=true, visibleToPlayer=false
    from(MessagingPolicy.parentManaged()) → PARENT_MANAGED → same as PROHIBITED
    from(MessagingPolicy.supervised())  → SUPERVISED  → playerIsBlocked=false, parentIsBlocked=false, parentHasAccess=true, parentIsPrimary=false, visibleToPlayer=true
    from(MessagingPolicy.unrestricted()) → UNRESTRICTED → playerIsBlocked=false, parentIsBlocked=true, parentHasAccess=false, parentIsPrimary=false, visibleToPlayer=true
    ```
    Use plain JUnit 5 + AssertJ — no Spring context needed. Verify all 4 policies × 5 helper methods = 20 assertions.

  - [x] **`ParentalOversightResourceIT.java`** (`@SpringBootTest @Testcontainers` — same pattern as `ConversationResourceIT`):
    - Setup: create parent, ADULT player (age 18+), MINOR player (age 10), SUPERVISED player (age 15), coach, bookings for each pair, conversations
    - `GET /api/messaging/players/{minorPlayerId}/conversations` as parent → 200 with conversation list
    - `GET /api/messaging/players/{adultPlayerId}/conversations` as parent → 403 `messaging.parentalOversightNotApplicable`
    - `GET /api/messaging/players/{otherPlayerId}/conversations` where otherPlayer belongs to different parent → 403 `messaging.notAParty`
    - `GET /api/messaging/players/{supervisedPlayerId}/conversations/{conversationId}/messages` as parent → 200
    - Conversation mismatch: `GET /api/messaging/players/{minorPlayerId}/conversations/{conversationIdBelongingToAdult}/messages` → 403
    - Coach cannot access `/players/{playerId}/conversations` → 403 (HAS_PARENT_ROLE enforced)
    - `GET /api/messaging/conversations` as parent: adult player conversations NOT in list; minor/supervised conversations ARE in list
    - `GET /api/messaging/conversations` as parent: supervised (13-17) `otherPartyName` contains "&" or "conversation with" style label; primary (under-13) `otherPartyName` = coach displayName

  - [x] **`AgeTierTransitionTest.java`** (unit — mock-based, `src/test/java/com/softropic/skillars/platform/messaging/service/AgeTierTransitionTest.java`):
    - Mock `AgePolicyService` to return PARENT_MANAGED → `sendMessage("PLAYER")` → throws `OperationNotAllowedException` with `PLAYER_DIRECT_MESSAGING_RESTRICTED`
    - Mock returns PROHIBITED → `sendMessage("PLAYER")` → throws `OperationNotAllowedException` with `PLAYER_DIRECT_MESSAGING_RESTRICTED`
    - Mock returns SUPERVISED → `sendMessage("PLAYER")` → succeeds (no exception)
    - Mock returns UNRESTRICTED → `sendMessage("PARENT")` → throws `OperationNotAllowedException` with `PARENT_MESSAGING_RESTRICTED_FOR_ADULT`
    - Mock returns SUPERVISED → `sendMessage("PARENT")` → succeeds (no exception)
    - Mock returns PROHIBITED → `getConversations(userId, "PLAYER")` → empty list returned
    - Mock returns UNRESTRICTED → `getConversations(userId, "PARENT")` → conversation excluded
    - Mock returns UNRESTRICTED → `sendMessage("COACH")` → `resolveRecipient` returns `conv.getPlayerId()` (not `conv.getParentId()`)
    - Mock returns SUPERVISED → `sendMessage("COACH")` → `resolveRecipient` returns `conv.getParentId()`
    Use `@ExtendWith(MockitoExtension.class)`, mock all repositories and `AgePolicyService`.

## Dev Notes

### CRITICAL: No Flyway Migration Needed

Age tier is NOT stored on `conversations` — it is always derived from `AgePolicyService.getMessagingPolicy(playerId)` at query time. This ensures tier transitions are immediately reflected without any data migration. **Do not add a V66 migration for this story.**

### CRITICAL: `AgePolicyService` Is in `platform.security.service` — Never `infrastructure`

```java
import com.softropic.skillars.platform.security.service.AgePolicyService;
import com.softropic.skillars.platform.security.contract.MessagingPolicy;
```
`AgePolicyService.getMessagingPolicy(Long playerId)` already exists and returns `MessagingPolicy`. Do not create a new method or a new service — inject and use the existing one.

### CRITICAL: `PlayerProfile.getName()` Returns Full Name — No `getFirstName()`

`PlayerProfile` has a single `name` (String) field. To derive first name for labels:
```java
String firstName = name.contains(" ") ? name.substring(0, name.indexOf(' ')) : name;
```
Do not call any `getFirstName()` — it does not exist.

### CRITICAL: Ownership Guard Uses `findByIdAndParentId` Already in `PlayerProfileRepository`

```java
playerProfileRepository.findByIdAndParentId(playerId, parentUserId)
    .orElseThrow(() -> new OperationNotAllowedException(..., MessagingErrorCode.NOT_A_PARTY));
```
This method already exists in `PlayerProfileRepository` (line 13). Do not add a new method. Always throw 403 on ownership failure — **never 404**, even if the player doesn't exist.

### CRITICAL: `getMessagesForPlayerConversation` Uses `NOT_A_PARTY` (403), Not `ResourceNotFoundException` (404)

For the parental oversight messages endpoint, a missing conversation MUST return 403 (not 404) because parents must not be able to distinguish "conversation doesn't exist" from "I don't own this conversation". This is the opposite of the regular `getMessages()` which uses `ResourceNotFoundException` (404) for party members trying to access a nonexistent conversation.

### CRITICAL: `AgeMessagingPolicy` Goes in `platform.messaging.service` — Not Infrastructure

The `AgeMessagingPolicy` enum is in `platform.messaging.service` because it encodes business rules specific to the messaging domain. Do not put it in `infrastructure`. It imports `MessagingPolicy` from `platform.security.contract`.

### CRITICAL: PLAYER Role Is Forward-Compatibility Only in This Story

`SecurityConstants` has no `ROLE_PLAYER`. Players are shadow accounts (Story 1.4) and do not authenticate independently. The `resolveRole()` fallback in `MessagingResource` returns "PLAYER" only if no known role is found. The age restrictions in `sendMessage()` for "PLAYER" role are forward-compatibility guardrails for when player auth is added. They must still be implemented correctly — but no player-facing UI changes are needed in this story.

### CRITICAL: Parent Cannot Send in UNRESTRICTED (18+) Conversations — `parentIsBlocked()` Check

The `MessagingPolicy.unrestricted()` returns `{canMessage=true, parentVisible=false, directAllowed=true}`. The `parentIsBlocked()` helper maps `!policy.parentVisible()` (parentVisible=false) → parent cannot send in UNRESTRICTED conversations. Implement the check in `sendMessage()` as `agePolicy.parentIsBlocked()`.

### CRITICAL: `getConversationsForPlayer` Returns 403 on Nonexistent Player

`playerProfileRepository.findByIdAndParentId(playerId, parentUserId)` returns empty when the player doesn't exist OR doesn't belong to this parent. In both cases: 403 `NOT_A_PARTY`. This intentionally prevents enumeration.

### Modifying Existing `resolveOtherPartyName()` — Impact on Existing Tests

`ConversationResourceIT` creates an ADULT player (age 18) and a COACH. After 8.2 changes, `resolveOtherPartyName()` for the coach will call `agePolicyService.getMessagingPolicy(playerId)`. This adds a new DB call. Existing tests should still pass because:
- The ADULT player's `otherPartyName` (coach's view) becomes just `{playerFirstName}` (their first name) — the test only checks for `conversationId` key, not `otherPartyName` value
- If any existing test asserts `otherPartyName`, update it to the correct age-tier label

### Injecting `AgePolicyService` Into `MessagingService`

`MessagingService` uses `@RequiredArgsConstructor` for constructor injection. Add `AgePolicyService` as a new field:
```java
private final AgePolicyService agePolicyService;  // add after existing fields
```
Lombok generates the constructor argument automatically. `MessagingService` already imports `platform.security.service` transitively (via `PlayerProfileRepository`), so the new import will compile.

### Note: SUPERVISED Parents Have Dual-Path Access — `lastReadAt` Behaviour Is Intentional

SUPERVISED (13–17) parents appear in `conv.parentId`, so they pass `verifyIsParty()` and can call the regular `GET /conversations/{id}/messages` endpoint, which **does** update their `lastReadAt`. The oversight endpoint's non-updating behaviour is a deliberate feature for background/silent reads, not a restriction that blocks the regular path. A SUPERVISED parent using the regular endpoint and advancing their `lastReadAt` is expected, correct behaviour — they are a conversation participant. No code change is needed; the AC6 guarantee applies only to the oversight endpoint.

### `getConversations()` PARENT Branch — Use Streaming Filter, Not New Queries

The epics dev notes say: "service-layer union of primary-participant query and oversight query — two separate queries merged in service, not a SQL UNION." Implement as:
1. Single `findActiveByParentId(callerUserId)` query — loads all non-BLOCKED conversations for this parent
2. Stream filter: exclude those where `agePolicy.parentHasAccess() == false` (UNRESTRICTED)
3. `toSummary()` for remaining — `resolveOtherPartyName("PARENT")` returns appropriate label per tier

Do NOT add SQL UNION queries or new repository methods for this.

**N+1 note — double policy lookup for PARENT branch**: `getMessagingPolicy` is called once to filter, then again inside `resolveOtherPartyName()` — 2 lookups per surviving conversation. A comment marks this in the code. Acceptable for MVP; reduce in a later optimisation story.

### `getConversations()` PLAYER Branch — Single Policy Lookup, No Stream Filter

Do NOT add a stream filter per conversation. All conversations returned by `findActiveByPlayerId(callerUserId)` belong to the same player (`callerUserId`), so calling `getMessagingPolicy` once outside the query is sufficient. For under-13 players (`!visibleToPlayer()`), skip the DB query entirely and return `List.of()`. See Task 3 for the exact code pattern.

### `toSummary()` Is Reused for Both Regular and Oversight Endpoints

`toSummary(Conversation conv, Long callerUserId, String role)` is called from:
- Regular `getConversations()` with the authenticated user's id and role
- New `getConversationsForPlayer()` with `parentUserId` and `"PARENT"` role

When called from the oversight endpoint with `parentUserId` as `callerUserId` and `"PARENT"` as role, the `unreadCount` will reflect the parent's read state (using `conv.getParentLastReadAt()`). This is correct behavior for the oversight view.

### `MessagingApiAdvice` Is Unchanged

All 3 new error codes produce 403 responses via the existing catch-all in `MessagingApiAdvice`:
```java
HttpStatus status = MessagingErrorCode.INVALID_CONTENT.getErrorCode().equals(code)
    ? HttpStatus.BAD_REQUEST
    : HttpStatus.FORBIDDEN;  // covers all new codes
```
No modification to `MessagingApiAdvice` needed.

### Test Setup for `ParentalOversightResourceIT`

Follow the exact pattern from `ConversationResourceIT.java`:
- Use `@Sql({SecurityIT.SEC_DATA_SQL_PATH})` + `@BeforeEach` `transactionTemplate.execute()`
- Use fixed Long IDs in the range `97x_xxx_xxx_xxx` to avoid collision with other ITs
- Create players with `date_of_birth = LocalDate.now().minusYears(10)` (for U10/PARENT_MANAGED) and `LocalDate.now().minusYears(15)` (for SUPERVISED) and `LocalDate.now().minusYears(18)` (for UNRESTRICTED)
- **`AgePolicyService.getAgeTier()` calls `LocalDate.now()`** — the age tier is computed from the player's `date_of_birth` using `Period.between(dob, LocalDate.now()).getYears()`. Use exact ages: `minusYears(10)` → U10 (age = 10 ≤ u10MaxAge), `minusYears(15)` → AGE_13_17, `minusYears(18)` → ADULT (age = 18 > teenMax=17).
- **Check default config values** in `AgePolicyService`: `u10MaxAge=10, youngTeenMax=12, teenMax=17`. These come from `ConfigService` with defaults from `AgePolicy.defaults()`.

### Module Package Structure (No Changes)

All new code stays within existing packages:
```
platform.messaging.service/
  + AgeMessagingPolicy.java        ← new
  ~ MessagingService.java          ← modified
platform.messaging.contract/
  ~ MessagingErrorCode.java        ← modified (3 new codes)
platform.messaging.api/
  ~ MessagingResource.java         ← modified (2 new endpoints)
src/frontend/src/api/
  ~ messaging.api.js               ← modified (2 new functions)
```

### CRITICAL: `resolveRecipient()` Must Be Updated — SSE Routing Is Age-Tier-Dependent

The Story 8.1 `resolveRecipient()` always returned `conv.getParentId()` when the COACH sends. This is wrong for UNRESTRICTED (18+) conversations: the parent has no access, yet receives the SSE event, while the 18+ player (the legitimate recipient) is never notified. `resolveRecipient()` must be rewritten as shown in Task 3. Do not carry the "SSE recipient is unchanged" note from 8.1 forward — that assumption no longer holds once age tiers are enforced.

### CRITICAL: Epic AC1 References Wrong Method Signature — `getAgeTier` vs `getMessagingPolicy`

Epic AC1 says `AgePolicyService.getAgeTier(conversation.playerId)`. This is incorrect — `getAgeTier()` takes a `LocalDate`, not a `Long playerId`. The story corrects this to `AgePolicyService.getMessagingPolicy(Long playerId)`, the method that actually exists for this use case. This is analogous to the 18+ table error fix below: the epic AC contained a method-name error that the story fixes without comment in the epic.

### CRITICAL: Epic AC Table Has an Error — Story AC1 Table Is Correct

The Epic 8.2 table shows `Parent can send = ✓` for the 18+ row. A later explicit AC in the same epic says parent gets `403 messaging.parentMessagingRestrictedForAdult` for 18+ conversations. The story's AC1 table (which shows `✗ blocked` for parent in 18+) correctly follows the explicit AC and overrides the erroneous table entry. If you read the epic table and think the story is wrong — it isn't; the epic table has an oversight.

### Story 8.1 Learnings Applied

- **No `@Valid` on `@RequestBody`** — validated in service layer (same pattern as `SendMessageRequest`)
- **SSE recipient for PLAYER and PARENT roles is unchanged** — for PLAYER role still returns coach; for PARENT role still returns coach. SSE for COACH role is now age-tier-aware (see `resolveRecipient()` subtask in Task 3 and the CRITICAL note above).
- **`DataIntegrityViolationException` race condition** — not relevant for this story (no new insert operations with unique constraints)
- **`Instant.EPOCH` sentinel** for null lastReadAt — already in `toSummary()`, no change needed
- **`MessagingApiAdvice` order = `HIGHEST_PRECEDENCE`** — already set; all new codes routed correctly to 403

### References

- `AgePolicyService.java` — `platform/security/service/AgePolicyService.java` (getMessagingPolicy, getAgeTier)
- `MessagingPolicy.java` — `platform/security/contract/MessagingPolicy.java` (prohibited/parentManaged/supervised/unrestricted factories)
- `AgeTier.java` — `platform/security/contract/AgeTier.java` (U10/AGE_10_12/AGE_13_17/ADULT)
- `PlayerProfile.java` — `platform/security/repo/PlayerProfile.java` (getName, dateOfBirth, parentId)
- `PlayerProfileRepository.java` — `platform/security/repo/PlayerProfileRepository.java` (findByIdAndParentId already at line 13)
- `MessagingService.java` — `platform/messaging/service/MessagingService.java` (resolveOtherPartyName, getConversations, sendMessage — all to be modified)
- `ConversationRepository.java` — `platform/messaging/repo/ConversationRepository.java` (findActiveByPlayerId already exists)
- `SecurityConstants.java` — `infrastructure/security/SecurityConstants.java` (HAS_PARENT_ROLE = "hasRole('ROLE_PARENT')" at line 35)
- `MessagingErrorCode.java` — `platform/messaging/contract/MessagingErrorCode.java` (extend with 3 new codes)
- `ConversationResourceIT.java` — `test/platform/messaging/api/ConversationResourceIT.java` (test setup pattern to follow)

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

**Task 1 — MessagingErrorCode extended** with 3 new 403 codes: `PLAYER_DIRECT_MESSAGING_RESTRICTED`, `PARENTAL_OVERSIGHT_NOT_APPLICABLE`, `PARENT_MESSAGING_RESTRICTED_FOR_ADULT`. No changes to `MessagingApiAdvice` — existing catch-all maps all non-INVALID_CONTENT codes to 403.

**Task 2 — AgeMessagingPolicy enum created** in `platform.messaging.service` with `from(MessagingPolicy)` factory and 5 helper predicates. Imports `MessagingPolicy` from `platform.security.contract` as specified.

**Task 3 — MessagingService modified** with `AgePolicyService` injection via `@RequiredArgsConstructor`. Four changes: (1) `sendMessage()` enforces PLAYER/PARENT age restrictions after party verification; (2) `getConversations()` PLAYER branch returns empty list for under-13 with one policy lookup; (3) `getConversations()` PARENT branch stream-filters out UNRESTRICTED conversations; (4) `resolveOtherPartyName()` is now age-tier-aware for COACH and PARENT roles; (5) `resolveRecipient()` routes SSE to player (not parent) for UNRESTRICTED 18+ conversations. TODO comment left at SUPERVISED multi-recipient SSE gap for future story.

**Task 4 — Two new oversight service methods added** to MessagingService: `getConversationsForPlayer()` and `getMessagesForPlayerConversation()`. Both enforce ownership guard (always 403, never 404), age check, and conversation player-mismatch guard. Messages endpoint intentionally does NOT update lastReadAt.

**Task 5 — Two new oversight endpoints added** to MessagingResource: `GET /api/messaging/players/{playerId}/conversations` and `GET /api/messaging/players/{playerId}/conversations/{conversationId}/messages`. Both guarded by `@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)`.

**Task 6 — Frontend API extended** with `fetchPlayerConversations` and `fetchPlayerConversationMessages` functions in `messaging.api.js`. Prettier-compliant, uses async `.then(r => r.data)` chaining (consistent with existing functions).

**Task 7 — Three test files authored and passing** (43 total unit tests + IT tests across all messaging test classes, 0 failures):
- `AgeMessagingPolicyTest`: 4 tests, all 4 policy mappings × 5 helper methods verified (20 assertions)
- `AgeTierTransitionTest`: 14 tests — player/parent blocking, SUPERVISED/UNRESTRICTED pass-through, getConversations filtering, SSE recipient routing for COACH role, initiateConversation parent age gate, and full guard coverage for getConversationsForPlayer / getMessagesForPlayerConversation oversight methods
- `ParentalOversightResourceIT`: 10 integration tests covering oversight CRUD, 403 scenarios, adult exclusion, otherPartyName labels, SUPERVISED list endpoint, and lastReadAt no-op guarantee
- Regression: `ConversationResourceIT` (12) and `MessagingAccessControlIT` (4) both pass with no changes

### File List

- `src/main/java/com/softropic/skillars/platform/messaging/contract/MessagingErrorCode.java`
- `src/main/java/com/softropic/skillars/platform/messaging/service/AgeMessagingPolicy.java` (new)
- `src/main/java/com/softropic/skillars/platform/messaging/service/MessagingService.java`
- `src/main/java/com/softropic/skillars/platform/messaging/api/MessagingResource.java`
- `src/frontend/src/api/messaging.api.js`
- `src/test/java/com/softropic/skillars/platform/messaging/service/AgeMessagingPolicyTest.java` (new)
- `src/test/java/com/softropic/skillars/platform/messaging/service/AgeTierTransitionTest.java` (new)
- `src/test/java/com/softropic/skillars/platform/messaging/api/ParentalOversightResourceIT.java` (new)

### Review Findings

- [x] [Review][Decision] `toMessageDto()` UNDER_REVIEW content suppression confirmed intentional — test added: `AgeTierTransitionTest.sendMessage_underReviewModeration_returnsNullContent()` [`MessagingService.java:387-389`]
- [x] [Review][Defer] `resolveOtherPartyName()` COACH branch — `agePolicyService.getMessagingPolicy()` throws `UserNotFoundException` for deleted players, crashing the coach's entire getConversations() call [`MessagingService.java:311`] — deferred, explicitly deferred by story spec (player deletion handled in a future story); blast radius expanded from send-path-only (8.1) to read-path
- [x] [Review][Defer] `getConversations()` PARENT stream filter — same `UserNotFoundException` propagation for deleted player crashes the entire parent conversation list [`MessagingService.java:103-105`] — deferred, explicitly deferred by story spec; same expanded blast radius concern as above

## Change Log

- Story 8.2 implementation complete — age-tiered messaging enforcement, parental oversight endpoints, age-tier-aware SSE routing, and 3 new test classes (Date: 2026-06-26)
