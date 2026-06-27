# Story 8.3: Gemini Content Moderation

Status: done

## Story

As a platform operator,
I want all messages moderated by Gemini before delivery,
so that harmful, abusive, or inappropriate content is blocked before it reaches any user, especially minors.

## Acceptance Criteria

1. **Given** the Gemini moderation implementation is deployed
   **When** the application context starts
   **Then** `GeminiModerationService` is annotated `@Primary` and `@Service`, replacing the `PassThroughModerationService` stub from Story 8.1 — the stub remains in the codebase named `@Service("passThrough")` with no `@Primary` annotation and is used only in tests where Gemini is not wired

2. **Given** a message is submitted
   **When** `ModerationService.moderate(messageId, content)` is called
   **Then** a Gemini API call is made OUTSIDE the `@Transactional` boundary — Gemini I/O must not hold a DB transaction open
   **And** after the Gemini call completes, a new `@Transactional` write updates `messages.moderationStatus` and conditionally sets `messages.deliveredAt`
   **And** the Gemini prompt instructs the model to evaluate the content against: hate speech, sexual content, threats or violence, personal contact information solicitation, advertising or spam, content inappropriate for minors; the prompt is stored in `platform.messaging.moderation.gemini.prompt-template` application property — never hardcoded in source

3. **Given** Gemini returns a SAFE verdict for a message
   **When** the moderation result is processed
   **Then** `messages.moderationStatus` is set to `APPROVED` and `messages.deliveredAt = now()`
   **And** an SSE event `{ type: "NEW_MESSAGE", messageId, conversationId }` is emitted to the recipient — delivery is only triggered after APPROVED

4. **Given** Gemini returns an UNSAFE verdict
   **When** the moderation result is processed
   **Then** `messages.moderationStatus` is set to `BLOCKED`
   **And** no SSE event is emitted to the recipient — the blocked message is never delivered
   **And** the SENDER receives an SSE event `{ type: "MESSAGE_BLOCKED", messageId, conversationId }` — the sender is informed their message was not delivered but NOT told the specific reason
   **And** no `ErrorDto` is returned on the original POST — the POST returns `202 Accepted` when the message is received; moderation outcome is communicated via SSE

5. **Given** Gemini returns an UNCERTAIN verdict (content flagged for human review)
   **When** the moderation result is processed
   **Then** `messages.moderationStatus` is set to `UNDER_REVIEW`
   **And** no SSE event is emitted — the message is held pending admin action
   **And** the message appears in the admin moderation queue (Epic 10 wires this)

6. **Given** the Gemini API call fails (network error, timeout, non-2xx response)
   **When** the moderation service processes the result
   **Then** the system is fail-closed: `messages.moderationStatus` is set to `UNDER_REVIEW` — failed moderation holds the message, never delivers it
   **And** a `ModerationFailureEvent` is published (logged at WARN level with `messageId`, `conversationId`, `failureReason`)
   **And** NO retry is attempted inline

7. **Given** message content exceeds the Gemini API input limit
   **When** `GeminiModerationService.moderate()` is called
   **Then** content is truncated to `platform.messaging.moderation.gemini.max-input-chars` (from application properties) before sending to Gemini — truncation is logged at DEBUG level with `messageId`
   **And** the message content stored in `messages.content` is always the FULL original content — only the Gemini input is truncated

8. **Given** a `BLOCKED` or `UNDER_REVIEW` message
   **When** `GET /api/messaging/conversations/{conversationId}/messages` is called
   **Then** `BLOCKED` messages are returned with `content = null` and `moderationStatus = BLOCKED`
   **And** `UNDER_REVIEW` messages are returned with `content = null` and `moderationStatus = UNDER_REVIEW`

## Tasks / Subtasks

- [x] **Task 1 — Infrastructure: `GeminiClient`** (AC: 2, 6, 7)
  - [x] Create `infrastructure/gemini/` package with:
    - [x] `GeminiProperties.java` (`@ConfigurationProperties(prefix = "infrastructure.gemini")`) with fields: `apiKey` (String), `apiBaseUrl` (String default `https://generativelanguage.googleapis.com`), `model` (String default `gemini-2.0-flash`), `timeoutSeconds` (int default 30)
    - [x] `GeminiClient.java` interface with method: `ModerationVerdict evaluate(String content)`
    - [x] `GeminiClientImpl.java` — RestTemplate-based implementation using `RestTemplateBuilder` (same pattern as `ArachnidConfig`). Uses `POST {apiBaseUrl}/v1beta/models/{model}:generateContent?key={apiKey}`. Parses response `candidates[0].content.parts[0].text` and maps to `ModerationVerdict.SAFE/UNSAFE/UNCERTAIN`. Any `RestClientException` or parse failure → throw `GeminiException`
    - [x] `GeminiApiResponse.java` record — nested structure matching `{"candidates":[{"content":{"parts":[{"text":"..."}]}}]}`; use Jackson (`@JsonIgnoreProperties(ignoreUnknown=true)`) since Gemini returns many fields
    - [x] `GeminiException.java` — `RuntimeException` subclass in the same package
    - [x] `GeminiConfig.java` — `@Configuration @EnableConfigurationProperties(GeminiProperties.class)` that creates a `GeminiClient` bean using `RestTemplateBuilder` with connect/read timeouts from `GeminiProperties.timeoutSeconds`
  - [x] **Gemini response text mapping**: Trim and uppercase the response text, then:
    - `"SAFE"` → `ModerationVerdict.SAFE`
    - `"UNSAFE"` → `ModerationVerdict.UNSAFE`
    - anything else (including `"UNCERTAIN"`) → `ModerationVerdict.UNCERTAIN`

- [x] **Task 2 — `ModerationVerdict` enum** (AC: 2–7)
  - [x] Create `platform/messaging/service/ModerationVerdict.java`:
    ```java
    public enum ModerationVerdict { SAFE, UNSAFE, UNCERTAIN }
    ```

- [x] **Task 3 — `ModerationFailureEvent` record** (AC: 6)
  - [x] Create `platform/messaging/contract/ModerationFailureEvent.java`:
    ```java
    public record ModerationFailureEvent(Long messageId, Long conversationId, String failureReason) {}
    ```

- [x] **Task 4 — `ModerationResultApplier` service** (AC: 3, 4, 5, 6, 8)
  - [x] Create `platform/messaging/service/ModerationResultApplier.java` with `@Service @RequiredArgsConstructor @Slf4j`. Inject: `MessageRepository`, `ConversationRepository`, `CoachProfileRepository`, `AgePolicyService`, `MessagingEmitterRegistry`, `ApplicationEventPublisher`
  - [x] Implement `@Transactional public ModerationResult applyResult(Long messageId, ModerationVerdict verdict)`:
    1. `Message message = messageRepository.findById(messageId).orElse(null)` — log WARN and return `new ModerationResult(MessageModerationStatus.PENDING, null)` if null
    2. Map verdict to status:
       - `SAFE` → `APPROVED`, `deliveredAt = Instant.now()`
       - `UNSAFE` → `BLOCKED`, `deliveredAt = null`
       - `UNCERTAIN` → `UNDER_REVIEW`, `deliveredAt = null`
    3. `message.setModerationStatus(status); message.setDeliveredAt(deliveredAt); messageRepository.save(message)`
    4. For `SAFE`: resolve recipient via `resolveRecipient(message.getConversationId(), message.getSenderRole().name())`, register `TransactionSynchronization.afterCommit { emitterRegistry.emit(recipientId, "NEW_MESSAGE", Map.of("type","NEW_MESSAGE","messageId",msgId,"conversationId",convId)) }` (only if `recipientId != null`)
    5. For `UNSAFE`: register `afterCommit { emitterRegistry.emit(message.getSenderId(), "MESSAGE_BLOCKED", Map.of("type","MESSAGE_BLOCKED","messageId",msgId,"conversationId",convId)) }`
    6. Return `new ModerationResult(status, deliveredAt)`
  - [x] Implement `private Long resolveRecipient(Long conversationId, String senderRole)`:
    - Load `conv = conversationRepository.findById(conversationId).orElse(null)` → return null if null
    - If `"COACH".equals(senderRole)`: `AgeMessagingPolicy.from(agePolicyService.getMessagingPolicy(conv.getPlayerId()))` → if `parentIsBlocked()` → return `conv.getPlayerId()`; else → return `conv.getParentId()`
    - Else: return `coachProfileRepository.findById(conv.getCoachId()).map(CoachProfile::getUserId).orElse(null)`
    - **Note**: This duplicates `MessagingService.resolveRecipient()` — acceptable duplication to avoid circular dependency. Do NOT introduce a shared helper that causes `MessagingService → ModerationResultApplier → MessagingService` cycle.

- [x] **Task 5 — `GeminiModerationService`** (AC: 1–7)
  - [x] Create `platform/messaging/service/GeminiModerationService.java` with `@Primary @Service @RequiredArgsConstructor @Slf4j`. Inject: `GeminiClient`, `ModerationResultApplier`, `MessageRepository`, `ApplicationEventPublisher`
  - [x] Bind prompt config with:
    ```java
    @Value("${platform.messaging.moderation.gemini.prompt-template}")
    private String promptTemplate;
    
    @Value("${platform.messaging.moderation.gemini.max-input-chars:4000}")
    private int maxInputChars;
    ```
  - [x] Implement `moderate(Long messageId, String content)` — **NO `@Transactional`** on this method (it's called from a NOT_SUPPORTED context — see Task 7):
    ```java
    @Override
    public ModerationResult moderate(Long messageId, String content) {
        String input = content.length() > maxInputChars
            ? content.substring(0, maxInputChars)
            : content;
        if (input.length() < content.length()) {
            log.debug("Truncated content for Gemini: messageId={}, originalLen={}, truncatedLen={}",
                messageId, content.length(), input.length());
        }
        
        ModerationVerdict verdict;
        String failureReason = null;
        try {
            verdict = geminiClient.evaluate(promptTemplate + input);
        } catch (Exception e) {
            log.warn("Gemini moderation failed: messageId={}, reason={}", messageId, e.getMessage());
            verdict = ModerationVerdict.UNCERTAIN;
            failureReason = e.getMessage();
        }
        
        ModerationResult result = moderationResultApplier.applyResult(messageId, verdict);
        
        if (failureReason != null) {
            Long conversationId = messageRepository.findById(messageId)
                .map(Message::getConversationId).orElse(null);
            publisher.publishEvent(new ModerationFailureEvent(messageId, conversationId, failureReason));
        }
        
        return result;
    }
    ```

- [x] **Task 6 — Update `PassThroughModerationService`** (AC: 1)
  - [x] Add `@Qualifier` name to `@Service`:
    ```java
    @Service("passThrough")
    ```
    (Remove `@Primary` if it was ever added — it should NOT have `@Primary`)
  - [x] Inject `ModerationResultApplier` and delegate to it so SSE behavior is consistent:
    ```java
    @Override
    @Transactional
    public ModerationResult moderate(Long messageId, String content) {
        return moderationResultApplier.applyResult(messageId, ModerationVerdict.SAFE);
    }
    ```
  - [x] Remove the old `MessageRepository` injection and direct save logic — `ModerationResultApplier` handles everything
  - [x] The `@Transactional` annotation on `moderate()` is kept — when `PassThroughModerationService` is used in tests with a real Spring context (no Gemini), it starts its own tx for `applyResult()`. This is consistent with how `GeminiModerationService` operates

- [x] **Task 7 — Refactor `MessagingService.sendMessage()`** (AC: 2, 3, 4)
  - [x] Add `TransactionTemplate transactionTemplate` field (inject via `@RequiredArgsConstructor`) — this requires adding `TransactionTemplate` to the Spring context; it's available automatically via Spring when JPA is on classpath
  - [x] Override the class-level `@Transactional` on `sendMessage()` ONLY:
    ```java
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MessageDto sendMessage(Long conversationId, String content, Long senderUserId, String role) {
    ```
    **Why NOT_SUPPORTED**: Gemini call must not hold a DB connection open. The initial message save is committed first via `transactionTemplate`, so `ModerationResultApplier` can find the committed row in its own `@Transactional`. Suspended outer tx would hold row locks that deadlock `ModerationResultApplier`'s REQUIRES_NEW — NOT_SUPPORTED avoids this entirely.
  - [x] Replace the inline save block with a committed sub-transaction:
    ```java
    // Save message PENDING — commits immediately so ModerationResultApplier can see it
    final long[] savedMessageId = {0L};
    transactionTemplate.execute(status -> {
        Conversation c = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation not found", "conversation"));
        var message = new Message();
        message.setConversationId(conversationId);
        message.setSenderId(senderUserId);
        message.setSenderRole(SenderRole.valueOf(role));
        message.setContent(content);
        message.setModerationStatus(MessageModerationStatus.PENDING);
        message.setCreatedAt(Instant.now());
        Message saved = messageRepository.save(message);
        c.setLastMessageAt(Instant.now());
        conversationRepository.save(c);
        savedMessageId[0] = saved.getId();
        return null;
    });
    long messageId = savedMessageId[0];
    ```
    **Note**: Re-load `conv` INSIDE the lambda to avoid detached entity (the `conv` loaded before for party verification was in its own mini-tx / different persistence context)
  - [x] Remove the old SSE registration block (`TransactionSynchronizationManager.registerSynchronization` inside `sendMessage()`) — SSE is now handled entirely by `ModerationResultApplier.applyResult()`
  - [x] Remove the `message.setModerationStatus(...)` / `message.setDeliveredAt(...)` lines after `moderate()` — `ModerationResultApplier` writes these to DB directly
  - [x] After `moderate()`, reload message from DB for the response DTO:
    ```java
    moderationService.moderate(messageId, content);
    Message finalMessage = messageRepository.findById(messageId)
        .orElseThrow(() -> new ResourceNotFoundException("Message not found", "message"));
    return toMessageDto(finalMessage);
    ```
  - [x] The validation, party verification, and age enforcement blocks BEFORE the save remain unchanged (they load `conv` via their own mini-transactions in NOT_SUPPORTED context)
  - [x] **IMPORTANT**: The `conv` variable loaded for party verification is a READ in NOT_SUPPORTED context. A new `conv` is loaded INSIDE the `transactionTemplate.execute()` lambda for the save. This is intentional — two separate reads are needed.

- [x] **Task 8 — Update `MessagingEmitterRegistry`** (AC: 3, 4)
  - [x] Add an overloaded emit method that accepts an event type name:
    ```java
    public void emit(Long recipientUserId, String eventType, Object event) {
        if (recipientUserId == null) return;
        SseEmitter emitter = emitters.get(recipientUserId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name(eventType).data(event));
        } catch (IOException | IllegalStateException e) {
            log.debug("Failed to emit {} to userId {}, removing emitter", eventType, recipientUserId);
            emitters.remove(recipientUserId, emitter);
        }
    }
    ```
  - [x] Keep the existing `emit(Long recipientUserId, Object event)` method unchanged (used by other callers if any)

- [x] **Task 9 — Update `MessagingResource.sendMessage()`** (AC: 4)
  - [x] Change the response status from `HttpStatus.CREATED` (201) to `HttpStatus.ACCEPTED` (202):
    ```java
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    ```

- [x] **Task 10 — Application properties** (AC: 2, 7)
  - [x] Add to `src/main/resources/application.yaml`:
    ```yaml
    infrastructure:
      gemini:
        api-key: ${GEMINI_API_KEY:}
        api-base-url: https://generativelanguage.googleapis.com
        model: gemini-2.0-flash
        timeout-seconds: 30
    
    platform:
      messaging:
        moderation:
          gemini:
            prompt-template: "Evaluate the following message for harmful content. Reply with exactly one word: SAFE, UNSAFE, or UNCERTAIN.\n\nContent is UNSAFE if it contains: hate speech, sexual content, threats or violence, personal contact information solicitation, advertising or spam, content inappropriate for minors.\n\nUNCERTAIN means you cannot clearly determine safety.\n\nMessage:\n"
            max-input-chars: 4000
    ```
  - [x] Add to `src/main/resources/application-dev.yaml` (under the existing `platform` or `infrastructure` section):
    ```yaml
    infrastructure:
      gemini:
        api-key: ${GEMINI_API_KEY:dev-key}
    ```
  - [x] Add to `src/test/resources/application-test.yaml`:
    ```yaml
    infrastructure:
      gemini:
        api-key: "test-gemini-key"
        api-base-url: "${wiremock.server.baseUrl:http://localhost:9999}"
        model: gemini-test
        timeout-seconds: 5
    
    platform:
      messaging:
        moderation:
          gemini:
            prompt-template: "Test: evaluate content. Reply SAFE, UNSAFE, or UNCERTAIN.\n\nMessage:\n"
            max-input-chars: 100
    ```

- [x] **Task 11 — Update existing ITs** (AC: 3, 4)
  - [x] **`ConversationResourceIT.java`**: Add `@MockitoBean GeminiClient geminiClient` and in `@BeforeEach` configure it to return `ModerationVerdict.SAFE` for all calls: `when(geminiClient.evaluate(any())).thenReturn(ModerationVerdict.SAFE)`. Also update any assertions on HTTP status from `201` to `202`.
  - [x] **`ParentalOversightResourceIT.java`**: Same — add `@MockitoBean GeminiClient geminiClient` stubbed to `ModerationVerdict.SAFE`. Update any 201 → 202 assertions.
  - [x] **`AgeTierTransitionTest.java`**: Four changes required:
    1. Add `@Mock TransactionTemplate transactionTemplate` and configure it to execute the lambda synchronously so the message-save path runs:
       ```java
       lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
           TransactionCallback<?> cb = inv.getArgument(0);
           return cb.doInTransaction(null);
       });
       ```
    2. **Delete `sendMessage_coachRole_unrestrictedPolicy_sseGoesToPlayer()` and `sendMessage_coachRole_supervisedPolicy_sseGoesToParent()`**. After the Task 7 refactor, `sendMessage()` no longer calls `TransactionSynchronizationManager.registerSynchronization()` — SSE routing moves entirely to `ModerationResultApplier.applyResult()`. Since `moderationService` is a `@Mock` in this test, `applyResult()` is never called, the synchronization list is always empty, and both `verify(messagingEmitterRegistry).emit(...)` assertions would throw `WantedButNotInvoked`. SSE routing coverage migrates to `ModerationResultApplierTest` (Task 13).
    3. Remove `TransactionSynchronizationManager.initSynchronization()` from `@BeforeEach` and `TransactionSynchronizationManager.clearSynchronization()` from `@AfterEach` — these are no longer needed.
    4. Remove the `org.springframework.transaction.support.TransactionSynchronizationManager` import and any `Map` / synchronization imports that become unused.

- [x] **Task 12 — New tests** (AC: 2–7)
  - [x] **`GeminiModerationServiceTest.java`** (unit, `platform/messaging/service/`):
    - `@ExtendWith(MockitoExtension.class)`, `@Mock GeminiClient geminiClient`, `@Mock ModerationResultApplier moderationResultApplier`, `@Mock MessageRepository messageRepository`, `@Mock ApplicationEventPublisher publisher`, `@InjectMocks GeminiModerationService service`
    - `@BeforeEach`: set `promptTemplate = "Test prompt:\n"`, `maxInputChars = 100` via `ReflectionTestUtils.setField()`
    - `geminiClient_returnsSafe_callsApplyResultWithSafe()`: stub `geminiClient.evaluate(any())` → `SAFE`; call `service.moderate(1L, "hello")`; verify `moderationResultApplier.applyResult(1L, ModerationVerdict.SAFE)` called
    - `geminiClient_returnsUnsafe_callsApplyResultWithUnsafe()`: stub `UNSAFE`; verify `applyResult(1L, ModerationVerdict.UNSAFE)`
    - `geminiClient_returnsUncertain_callsApplyResultWithUncertain()`: stub `UNCERTAIN`; verify `applyResult(1L, ModerationVerdict.UNCERTAIN)`
    - `geminiClient_throwsException_callsApplyResultWithUncertain_publishesFailureEvent()`:
      - stub `geminiClient.evaluate(any())` → throw `new GeminiException("timeout", null)`
      - stub `messageRepository.findById(1L)` → `Optional.of(mockMessage)` where `mockMessage.getConversationId() = 99L`
      - call `service.moderate(1L, "hello")`
      - verify `applyResult(1L, ModerationVerdict.UNCERTAIN)` called
      - verify `publisher.publishEvent(new ModerationFailureEvent(1L, 99L, "timeout"))`
    - `contentExceedsMaxInputChars_truncatedBeforeSending()`:
      - Content length > 100; verify `geminiClient.evaluate(argThat(s -> s.endsWith(content.substring(0, 100))))` (actual content truncated in prompt)
    - `contentWithinMaxInputChars_notTruncated()`: verify full content passed

  - [x] **`ModerationFailClosedIT.java`** (`@SpringBootTest @Testcontainers` in `platform/messaging/api/`):
    - `@MockitoBean GeminiClient geminiClient` stubbed to throw `GeminiException`
    - Setup: reuse `ConversationResourceIT` setup pattern (same coach/player/parent IDs in 9820xxxxx range)
    - Test: send a message via `POST /api/messaging/conversations/{id}/messages` as coach → expect `202 Accepted`
    - Query message from DB directly (via `JdbcTemplate`) → `moderation_status = 'UNDER_REVIEW'`, `delivered_at IS NULL`
    - Verify NO SSE event received by recipient (no SSE in UNDER_REVIEW path)
    - Verify `ModerationFailureEvent` published (use `ApplicationEvents` spy or verify via `@EventListener` capture bean)

  - [x] **`BlockedMessageContentHidingIT.java`** (`@SpringBootTest @Testcontainers` in `platform/messaging/api/`):
    - `@MockitoBean GeminiClient geminiClient` stubbed to return `ModerationVerdict.UNSAFE`
    - Setup: send message as coach → expect `202`; use ID range `9830_xxx_xxx_xxx`
    - Call `GET /api/messaging/conversations/{id}/messages` as coach → verify message in list with `content = null`, `moderationStatus = "BLOCKED"`
    - Also verify sender (coach) receives SSE `MESSAGE_BLOCKED` event (if SSE subscriber is set up)
    - Verify `GET /api/messaging/conversations/{id}/messages` as parent → same: `content = null`, `moderationStatus = "BLOCKED"`

- [x] **Task 13 — `ModerationResultApplierTest`** (AC: 3, 4, 5, 8)
  - [x] Create `platform/messaging/service/ModerationResultApplierTest.java`:
    - `@ExtendWith(MockitoExtension.class)`, `@Mock MessageRepository messageRepository`, `@Mock ConversationRepository conversationRepository`, `@Mock CoachProfileRepository coachProfileRepository`, `@Mock AgePolicyService agePolicyService`, `@Mock MessagingEmitterRegistry messagingEmitterRegistry`, `@Mock ApplicationEventPublisher publisher`, `@InjectMocks ModerationResultApplier applier`
    - Shared setup: stub `messageRepository.findById(1L)` → message with `conversationId=10L`, `senderId=COACH_USER_ID`, `senderRole=COACH`; stub `conversationRepository.findById(10L)` → conv with `playerId=PLAYER_ID`, `parentId=PARENT_ID`, `coachId=COACH_UUID`
    - Enable synchronization: call `TransactionSynchronizationManager.initSynchronization()` in `@BeforeEach` and `clearSynchronization()` in `@AfterEach` so `afterCommit` callbacks can be manually fired
    - `applyResult_safeVerdict_setsApprovedAndDeliveredAt()`: call `applier.applyResult(1L, SAFE)` inside a `@Transactional` test method (or use `TransactionTemplate` to commit); verify `message.getModerationStatus() == APPROVED` and `deliveredAt != null`
    - `applyResult_safeVerdict_coachSender_parentManagedPolicy_sseGoesToParent()`:
      - stub `agePolicyService.getMessagingPolicy(PLAYER_ID)` → `PARENT_MANAGED`
      - call `applier.applyResult(1L, SAFE)`; fire `getSynchronizations().forEach(s -> s.afterCommit())`
      - verify `messagingEmitterRegistry.emit(PARENT_ID, "NEW_MESSAGE", argThat(map -> map containsEntry "messageId" → 1L))`
    - `applyResult_safeVerdict_coachSender_unrestrictedPolicy_sseGoesToPlayer()`:
      - stub `agePolicyService.getMessagingPolicy(PLAYER_ID)` → `UNRESTRICTED`
      - fire afterCommit; verify `emit(PLAYER_ID, "NEW_MESSAGE", ...)`
    - `applyResult_unsafeVerdict_setsBlockedAndEmitsSenderSse()`:
      - call `applier.applyResult(1L, UNSAFE)`; fire afterCommit
      - verify `message.getModerationStatus() == BLOCKED`, `deliveredAt == null`
      - verify `emit(COACH_USER_ID, "MESSAGE_BLOCKED", ...)`
    - `applyResult_uncertainVerdict_setsUnderReview_noSse()`:
      - call `applier.applyResult(1L, UNCERTAIN)`; fire afterCommit
      - verify `message.getModerationStatus() == UNDER_REVIEW`, `deliveredAt == null`
      - verify `messagingEmitterRegistry` never called
    - `applyResult_messageNotFound_logsWarnAndReturnsPending()`:
      - stub `messageRepository.findById(1L)` → `Optional.empty()`
      - call `applier.applyResult(1L, SAFE)`; verify result status is `PENDING`, no repository saves

## Dev Notes

### CRITICAL: Transaction Boundary — Why `NOT_SUPPORTED` + `TransactionTemplate`

The class-level `@Transactional` on `MessagingService` must be overridden on `sendMessage()` with `@Transactional(propagation = NOT_SUPPORTED)`. The problem:

- **If outer `@Transactional` is ACTIVE during Gemini call**: A DB connection is held open for the full Gemini HTTP round-trip (potentially 30s). This exhausts the connection pool under load.
- **If outer tx holds the row AND inner tx tries to UPDATE same row (REQUIRES_NEW)**: PostgreSQL row lock → deadlock.

**Correct sequence**:
1. `sendMessage()` → NOT_SUPPORTED (no outer tx)
2. `transactionTemplate.execute()` → save message PENDING + update conversation → **COMMITS** (row now visible to other transactions)
3. `moderationService.moderate()` → Gemini HTTP call (no tx held) → `moderationResultApplier.applyResult()` → `@Transactional` creates NEW tx (REQUIRED), sees committed row, writes status → **COMMITS** → `afterCommit` fires SSE
4. Reload message from DB → return DTO

**DO NOT** attempt to keep the outer `@Transactional` on `sendMessage()` and use `NOT_SUPPORTED` only on `GeminiModerationService.moderate()` — this causes the deadlock described above.

### CRITICAL: `GeminiClient` Goes in `infrastructure.gemini` — Not Platform

`GeminiClient` is a pure API adapter with no business knowledge. It receives a string (the combined prompt+content) and returns `ModerationVerdict`. The mapping of `ModerationVerdict` to `MessageModerationStatus` is business logic in `ModerationResultApplier` (platform). Follow the same boundary as `ArachnidClient` (pure hash-check adapter) vs. `ModerationOrchestrationService` (platform orchestration).

`GeminiConfig` creates the `GeminiClient` bean via `RestTemplateBuilder` — same pattern as `ArachnidConfig`:
```java
@Bean
public GeminiClient geminiClient(GeminiProperties props, RestTemplateBuilder builder) {
    RestTemplate restTemplate = builder
        .connectTimeout(Duration.ofSeconds(props.getTimeoutSeconds()))
        .readTimeout(Duration.ofSeconds(props.getTimeoutSeconds()))
        .build();
    return new GeminiClientImpl(restTemplate, props.getApiKey(), props.getApiBaseUrl(), props.getModel());
}
```

### CRITICAL: `ModerationResultApplier` Must Not Depend on `MessagingService`

`ModerationResultApplier` duplicates the `resolveRecipient()` logic from `MessagingService`. This is intentional — injecting `MessagingService` into `ModerationResultApplier` would create a circular dependency chain. Copy the logic verbatim (it's short). Do not introduce a shared helper in `infrastructure.*` — recipient resolution involves `AgePolicyService` (a platform concern).

### CRITICAL: `Conversation` Entity Is Detached When Reused Across Mini-Transactions

In `sendMessage()` (NOT_SUPPORTED), the `conv` loaded for party verification is in a different persistence context than the `transactionTemplate` sub-transaction. **Do NOT pass `conv` into the lambda and call `conversationRepository.save(conv)` on it** — this causes a JPA `DetachedObjectException`. Instead, load a fresh `Conversation` INSIDE the lambda:
```java
transactionTemplate.execute(status -> {
    Conversation c = conversationRepository.findById(conversationId).orElseThrow(...);
    c.setLastMessageAt(Instant.now());
    conversationRepository.save(c);
    // ...save message...
    return null;
});
```

### CRITICAL: SSE `MESSAGE_BLOCKED` Goes to the SENDER, Not the Recipient

For UNSAFE verdict: the SSE goes to `message.getSenderId()` (the person who sent the message that was blocked). This is different from the APPROVED case where SSE goes to the RECIPIENT (the person receiving the new message). Do not confuse these:
- APPROVED → SSE → `resolveRecipient(conversationId, senderRole)` (the OTHER person)
- BLOCKED → SSE → `message.getSenderId()` (the sender themselves, notifying THEIR UI)

### CRITICAL: Gemini API Request Format

The Gemini REST API (no Java SDK — use RestTemplate directly):
- URL: `{apiBaseUrl}/v1beta/models/{model}:generateContent?key={apiKey}`
- Method: POST with `Content-Type: application/json`
- Body:
  ```json
  {
    "contents": [{"parts": [{"text": "{promptTemplate}{messageContent}"}]}],
    "generationConfig": {"maxOutputTokens": 10, "temperature": 0}
  }
  ```
- Response: `{"candidates":[{"content":{"parts":[{"text":"SAFE"}]},"finishReason":"STOP"}]}`
- Parse: `candidates[0].content.parts[0].text` → trim + uppercase → match to verdict

**The `promptTemplate` already ends with `\n` (or similar separator)**. In `GeminiClientImpl.evaluate(String combinedPrompt)`, the parameter is the full text (`promptTemplate + truncatedContent`). `GeminiModerationService` concatenates prompt + content BEFORE calling `evaluate()`. `GeminiClientImpl` wraps it in the API body structure.

Use `@JsonIgnoreProperties(ignoreUnknown = true)` on all Gemini response record types — the real API returns many additional fields (safety ratings, model version, usage metadata).

### CRITICAL: `PassThroughModerationService` Qualifier for Test Injection

After this story, `PassThroughModerationService` is annotated `@Service("passThrough")`. Unit tests that need it injected explicitly use:
```java
@Qualifier("passThrough") ModerationService moderationService
```
Integration tests (which load the full Spring context) get `GeminiModerationService` by default (it is `@Primary`). All existing integration tests MUST add `@MockitoBean GeminiClient geminiClient` with `when(geminiClient.evaluate(any())).thenReturn(ModerationVerdict.SAFE)` to avoid real Gemini calls during tests.

### CRITICAL: `AgeTierTransitionTest` Needs `TransactionTemplate` Mock — And Two Tests Must Be Deleted

`AgeTierTransitionTest` uses `@InjectMocks MessagingService`. After adding `TransactionTemplate transactionTemplate` to `MessagingService`, `@InjectMocks` will inject it if `@Mock TransactionTemplate transactionTemplate` is declared. Add this mock and configure it:
```java
@Mock TransactionTemplate transactionTemplate;

@BeforeEach void setUp() {
    lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
        TransactionCallback<?> cb = inv.getArgument(0);
        return cb.doInTransaction(null);
    });
}
```
Without this, the lambda inside `sendMessage()` would call `null.execute()` and NPE.

**Additionally, delete `sendMessage_coachRole_unrestrictedPolicy_sseGoesToPlayer()` and `sendMessage_coachRole_supervisedPolicy_sseGoesToParent()`**. These tests work by calling `TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit())` after `sendMessage()`. After the Task 7 refactor, `sendMessage()` no longer registers any synchronizations — SSE registration moves to `ModerationResultApplier.applyResult()`. Since `moderationService` is a `@Mock` in this test, `applyResult()` is never entered, the synchronization list is empty, and both `verify(messagingEmitterRegistry).emit(...)` calls throw `WantedButNotInvoked`. These tests cannot be fixed in-place; their SSE routing coverage belongs in `ModerationResultApplierTest` (Task 13). Also remove `TransactionSynchronizationManager.initSynchronization()` from `@BeforeEach` and `clearSynchronization()` from `@AfterEach` — they become dead setup once the deleted tests are gone.

### Flyway Migration: None Required

`messaging.messages.moderation_status` already exists in `V65__messaging_module_init.sql` as `VARCHAR(20) NOT NULL DEFAULT 'PENDING'`. No schema changes needed.

### `toMessageDto()` Content Hiding Is Already Implemented

`MessagingService.toMessageDto()` already suppresses `content` to `null` for BLOCKED and UNDER_REVIEW:
```java
boolean contentHidden = msg.getModerationStatus() == MessageModerationStatus.BLOCKED
    || msg.getModerationStatus() == MessageModerationStatus.UNDER_REVIEW;
String content = contentHidden ? null : msg.getContent();
```
AC 8 is already satisfied by this existing code. **Do not change `toMessageDto()`.** The test `BlockedMessageContentHidingIT` verifies this path end-to-end.

### `ModerationService.moderate()` Interface Is Unchanged

Do NOT modify the `ModerationService` interface. Both `GeminiModerationService` and `PassThroughModerationService` implement `moderate(Long messageId, String content)`. The `sendMessage()` caller only knows the interface, not the implementation.

### `MessagingEmitterRegistry.emit()` Overload — Old Method Becomes Dead Code

The existing `emit(Long recipientUserId, Object event)` uses hardcoded `"NEW_MESSAGE"` as event name. Its only current caller is `MessagingService.sendMessage()`, which is removed by Task 7. After this story the old overload has no callers. Keep it in place — removing it is out of scope — but do not route any new SSE through it. `ModerationResultApplier` must use the NEW `emit(Long recipientUserId, String eventType, Object event)` overload for both `NEW_MESSAGE` and `MESSAGE_BLOCKED` events.

### Module Package Structure

```
infrastructure/gemini/               ← NEW package
  + GeminiClient.java               (interface)
  + GeminiClientImpl.java           (impl)
  + GeminiProperties.java           (@ConfigurationProperties)
  + GeminiConfig.java               (@Configuration)
  + GeminiApiResponse.java          (DTO record for parsing)
  + GeminiException.java            (RuntimeException)

platform/messaging/service/
  + ModerationVerdict.java          (enum: SAFE, UNSAFE, UNCERTAIN)
  + GeminiModerationService.java    (@Primary @Service — new)
  + ModerationResultApplier.java    (@Service — new)
  ~ MessagingService.java           (sendMessage() refactored — add TransactionTemplate)
  ~ PassThroughModerationService.java (add qualifier, delegate to ModerationResultApplier)
  ~ MessagingEmitterRegistry.java   (add emit(userId, eventType, data) overload)

platform/messaging/contract/
  + ModerationFailureEvent.java     (record — new)

platform/messaging/api/
  ~ MessagingResource.java          (sendMessage: 201 → 202)

src/main/resources/
  ~ application.yaml               (add infrastructure.gemini + platform.messaging.moderation.gemini)
  ~ application-dev.yaml
  ~ application-test.yaml
```

### Test Infrastructure Notes

- `ModerationFailClosedIT` and `BlockedMessageContentHidingIT` use `@MockitoBean GeminiClient` — this replaces the real `GeminiClientImpl` bean in the test context. `GeminiModerationService` receives the mock and can be exercised with controlled verdicts.
- Use ID range `9820_xxx_xxx_xxx` for `ModerationFailClosedIT` and `9830_xxx_xxx_xxx` for `BlockedMessageContentHidingIT`. Verified ranges already taken: `9700` → `ConversationResourceIT`, `9800` → `MessagingAccessControlIT`, `9810` → `ParentalOversightResourceIT`.

### 8.2 Learnings Applied

- **SSE routing is age-tier-dependent**: `resolveRecipient()` in `ModerationResultApplier` applies the same age-tier-aware routing introduced in 8.2 — for COACH sender: `parentIsBlocked()` → route to player; else → route to parent.
- **`UserNotFoundException` deferred risk**: `agePolicyService.getMessagingPolicy()` in `ModerationResultApplier.resolveRecipient()` can throw `UserNotFoundException` for deleted players — same deferred risk as in MessagingService. Do not add special handling; player deletion is a future story concern.
- **N+1 note**: `ModerationResultApplier.applyResult()` loads message + conversation (for SSE routing) = 2 queries per moderation. Acceptable for MVP.

### References

- `PassThroughModerationService.java` — `platform/messaging/service/PassThroughModerationService.java` (pattern to keep; update to delegate to `ModerationResultApplier`)
- `ModerationService.java` (interface) — `platform/messaging/contract/ModerationService.java` (unchanged)
- `ArachnidClientImpl.java` — `infrastructure/arachnid/ArachnidClientImpl.java` (RestTemplate pattern to follow for `GeminiClientImpl`)
- `ArachnidConfig.java` — `infrastructure/arachnid/ArachnidConfig.java` (RestTemplateBuilder config pattern)
- `ArachnidProperties.java` — `infrastructure/arachnid/ArachnidProperties.java` (@ConfigurationProperties pattern)
- `MessagingEmitterRegistry.java` — `platform/messaging/service/MessagingEmitterRegistry.java` (add overload here)
- `MessagingService.java` — `platform/messaging/service/MessagingService.java` (refactor `sendMessage()`)
- `ConversationResourceIT.java` — `test/platform/messaging/api/ConversationResourceIT.java` (add `@MockitoBean GeminiClient`, update 201→202)
- `ParentalOversightResourceIT.java` — `test/platform/messaging/api/ParentalOversightResourceIT.java` (add `@MockitoBean GeminiClient`)
- `AgeTierTransitionTest.java` — `test/platform/messaging/service/AgeTierTransitionTest.java` (add `@Mock TransactionTemplate`; delete two SSE routing tests; remove `TransactionSynchronizationManager` setup)
- `ModerationResultApplierTest.java` — NEW: `test/platform/messaging/service/ModerationResultApplierTest.java`
- `ModerationResultApplier.java` — NEW: `platform/messaging/service/ModerationResultApplier.java`
- `GeminiModerationService.java` — NEW: `platform/messaging/service/GeminiModerationService.java`
- `V65__messaging_module_init.sql` — confirms no Flyway migration needed

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

- All 13 tasks implemented. `GeminiClient` infrastructure package created following Arachnid patterns. `ModerationVerdict` enum and `ModerationFailureEvent` record created. `ModerationResultApplier` handles all moderation result persistence and SSE routing via `afterCommit` synchronization. `GeminiModerationService` is `@Primary`, orchestrates Gemini calls outside `@Transactional`, delegates to `ModerationResultApplier` for DB writes. `MessagingService.sendMessage()` refactored to `NOT_SUPPORTED` propagation with `TransactionTemplate` sub-tx for message save. `PassThroughModerationService` updated to delegate to `ModerationResultApplier`. `MessagingEmitterRegistry` has new `emit(userId, eventType, data)` overload. Response 201→202. All YAML configs updated. Two SSE routing tests deleted from `AgeTierTransitionTest`; SSE routing coverage moved to `ModerationResultApplierTest`. 26 unit tests pass: 6 `GeminiModerationServiceTest`, 7 `ModerationResultApplierTest`, 13 `AgeTierTransitionTest`.

### File List

- `src/main/java/com/softropic/skillars/infrastructure/gemini/GeminiProperties.java` (new)
- `src/main/java/com/softropic/skillars/infrastructure/gemini/GeminiClient.java` (new)
- `src/main/java/com/softropic/skillars/infrastructure/gemini/GeminiClientImpl.java` (new)
- `src/main/java/com/softropic/skillars/infrastructure/gemini/GeminiApiResponse.java` (new)
- `src/main/java/com/softropic/skillars/infrastructure/gemini/GeminiException.java` (new)
- `src/main/java/com/softropic/skillars/infrastructure/gemini/GeminiConfig.java` (new)
- `src/main/java/com/softropic/skillars/platform/messaging/service/ModerationVerdict.java` (new)
- `src/main/java/com/softropic/skillars/platform/messaging/contract/ModerationFailureEvent.java` (new)
- `src/main/java/com/softropic/skillars/platform/messaging/service/ModerationResultApplier.java` (new)
- `src/main/java/com/softropic/skillars/platform/messaging/service/GeminiModerationService.java` (new)
- `src/main/java/com/softropic/skillars/platform/messaging/service/PassThroughModerationService.java` (modified)
- `src/main/java/com/softropic/skillars/platform/messaging/service/MessagingService.java` (modified)
- `src/main/java/com/softropic/skillars/platform/messaging/service/MessagingEmitterRegistry.java` (modified)
- `src/main/java/com/softropic/skillars/platform/messaging/api/MessagingResource.java` (modified)
- `src/main/resources/application.yaml` (modified)
- `src/main/resources/application-dev.yaml` (modified)
- `src/test/resources/application-test.yaml` (modified)
- `src/test/java/com/softropic/skillars/platform/messaging/service/GeminiModerationServiceTest.java` (new)
- `src/test/java/com/softropic/skillars/platform/messaging/service/ModerationResultApplierTest.java` (new)
- `src/test/java/com/softropic/skillars/platform/messaging/api/ModerationFailClosedIT.java` (new)
- `src/test/java/com/softropic/skillars/platform/messaging/api/BlockedMessageContentHidingIT.java` (new)
- `src/test/java/com/softropic/skillars/platform/messaging/api/ConversationResourceIT.java` (modified)
- `src/test/java/com/softropic/skillars/platform/messaging/api/ParentalOversightResourceIT.java` (modified)
- `src/test/java/com/softropic/skillars/platform/messaging/service/AgeTierTransitionTest.java` (modified)

### Review Findings

- [x] [Review][Decision→Patch] `ModerationVerdict` moved from `platform.messaging.service` to `platform.messaging.contract`; all imports updated across infra + platform + test files
- [x] [Review][Patch] API key moved from URL query param to `x-goog-api-key` header in `GeminiClientImpl` — prevents key leaking into logs via `RestClientException.getMessage()` and `ModerationFailureEvent.failureReason` [`GeminiClientImpl.java`]
- [x] [Review][Patch] Fail-fast added to `GeminiConfig.geminiClient()` — throws `IllegalStateException` at startup if `GEMINI_API_KEY` is blank [`GeminiConfig.java`]
- [x] [Review][Patch] AC6: WARN log restructured in `GeminiModerationService` to include `conversationId` — log now emitted after `conversationId` is fetched, only on failure path [`GeminiModerationService.java`]
- [x] [Review][Patch] Dead `resolveRecipient(Conversation, String)` removed from `MessagingService` [`MessagingService.java`]
- [x] [Review][Patch] `Thread.sleep(200)` and misleading async comment removed from `ModerationFailClosedIT`; method signature cleaned up [`ModerationFailClosedIT.java`]
- [x] [Review][Defer] TOCTOU: age policy + party checks run without a transaction before committed message save — spec-designed (NOT_SUPPORTED), window is narrow [`MessagingService.java:129-145`] — deferred, pre-existing
- [x] [Review][Defer] Message orphaned in PENDING on JVM crash or `applyResult()` DB exception after initial tx commit — no cleanup path [`MessagingService.java:167` / `GeminiModerationService.java:53`] — deferred, pre-existing
- [x] [Review][Defer] `conv.getParentId()` returned without null guard for SUPERVISED policy in `ModerationResultApplier.resolveRecipient()` — same gap exists in `MessagingService.resolveRecipient()` [`ModerationResultApplier.java:104`] — deferred, pre-existing
- [x] [Review][Defer] Prompt injection: user content appended directly to instruction prompt with no structural separation — design concern, future hardening story [`GeminiModerationService.java:46`] — deferred, pre-existing
- [x] [Review][Defer] `content` dereferenced before null check in `GeminiModerationService.moderate()` — guarded only at call-site [`GeminiModerationService.java:35`] — deferred, pre-existing

## Change Log

| Date | Version | Description | Author |
|------|---------|-------------|--------|
| 2026-06-26 | 1.0 | Implemented Story 8.3: Gemini content moderation. Created GeminiClient infrastructure (GeminiProperties, GeminiClientImpl, GeminiConfig, GeminiApiResponse, GeminiException). Created ModerationVerdict enum, ModerationFailureEvent record, ModerationResultApplier service. Created GeminiModerationService (@Primary). Refactored MessagingService to NOT_SUPPORTED + TransactionTemplate. Refactored PassThroughModerationService to delegate to ModerationResultApplier. Added emit(userId, eventType, data) overload to MessagingEmitterRegistry. Changed sendMessage response 201→202. Updated YAML configs. 26 unit tests passing. | claude-sonnet-4-6 |
