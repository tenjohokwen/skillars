# Story 6.6: Player Video Management Portal

Status: done

## Story

As a parent or eligible player,
I want to manage video uploads, see real-time pipeline status, approve videos for minor players, and monitor quota usage,
so that video content is parent-supervised for minors and both players and parents have full visibility and control.

## Acceptance Criteria

1. **Given** a player aged 18+ navigates to their video management screen **When** the page loads **Then** an upload button is visible for Homework video type (max 60s / 250 MB) **And** current storage quota usage is shown as a progress bar: used GB / total GB, updated after each upload or deletion (FR-POR-005, FR-POR-012) **And** current monthly bandwidth usage is shown alongside storage.

2. **Given** any authenticated player navigates to their video management screen **When** the page loads **Then** an upload button is visible for Homework video type regardless of the player's age — minor players can upload; the uploaded video passes through the standard moderation pipeline and the minor safety gate (AC 4) handles parental approval before the video is published. **[DECISION: overrides FR-POR-011 and the original epic AC 2 which stated "no upload button is present" for under-18 players; the parental approval gate replaces the hard upload prohibition; no server-side 403 is returned for minor uploads]**

3. **Given** a player has uploaded videos **When** their video list renders **Then** each video is shown as a `VideoStatusCard` with reactive SSE-driven status updates — PENDING, SCANNING, TRANSCODING, PUBLISHED, LOCKED, HIDDEN, REJECTED states each have a distinct visual treatment (UX-DR13) **And** `aria-live="polite"` is on each card so screen readers announce status changes.

4. **Given** a minor player (under 18) uploads a video and it clears Layer 1 + Layer 2 moderation **When** the minor safety gate triggers (Story 6.3, Layer 3) **Then** the video is set to `HIDDEN` and a parental approval request is created atomically in `video_approval_requests` (id UUID, videoId UUID, playerId BIGINT, parentId BIGINT, status ENUM PENDING/APPROVED/REJECTED/CANCELLED, createdAt TIMESTAMPTZ, resolvedAt TIMESTAMPTZ nullable) — both the state transition and the approval record insertion are within a single transaction so no video can be left in `HIDDEN` without a corresponding approval record **And** the parent receives a notification: "Approve [Player]'s new video — [VideoType] uploaded on [Date]" with Approve / Reject actions (FR-POR-006) **And** the `VideoStatusCard` for the player shows "Awaiting parent approval" state **And** the SSE connection for this video is kept open (HIDDEN is not a terminal SSE state — the card must receive the subsequent TRANSCODING or REJECTED state push when the parent acts). This is the **standard path** for minor player uploads — the gate fires for every minor-owned video that passes Layer 1 + Layer 2; it is not a fallback.

5. **Given** a parent approves the video **When** `PUT /api/video/approvals/{approvalId}/approve` is called **Then** `video_approval_requests.status` transitions to `APPROVED` **And** `videos.operational_state` transitions to `TRANSCODING` **and** Bunny encoding is actually triggered via `videoProviderAdapter.triggerTranscoding(video.providerAssetId)` (the video was never submitted to Bunny before — the minor gate intercepted it before `advanceToTranscoding()` ran; the encoding call must happen here or the video stays un-encoded forever) **And** the player is notified: "Your video has been approved and is now processing". **[DECISION: diverges from epic AC 5 which states `videos.status → PUBLISHED`; TRANSCODING is correct because Bunny has not yet encoded the video when the gate fires; the fast-path in `approveVideo()` handles the rare case where encoding completed before the gate ran (`encodingCompletedAt != null`), calling `completeTranscoding()` directly]**

6. **Given** a parent rejects the video **When** `PUT /api/video/approvals/{approvalId}/reject` is called **Then** `video_approval_requests.status` transitions to `REJECTED` **And** `videos.operational_state` is set to `REJECTED` — the video is invisible to the player and flagged for coach awareness; it is not auto-deleted (FR-VID-003, FR-POR-006) **And** the `VideoStatusCard` shows a warning overlay distinguishing REJECTED from LOCKED **And** the parent receives confirmation and the player is notified: "Your video was not approved".

7. **Given** a user deletes a video from their management screen **When** `DELETE /api/video/{id}` is called **Then** RBAC from Story 6.5 is enforced — owner, verified parent, or admin only **And** the `VideoStatusCard` is removed from the list immediately on successful deletion **And** quota usage display updates in real time (FR-POR-013).

## Tasks / Subtasks

- [x] **Task 0 — Player Identity Bridge Investigation (MANDATORY GATE — complete before writing any player upload code)** (AC: 1, 2, 4)
  - [x] Confirm the format of `videos.owner_id` for player-uploaded videos: check `ShadowAccountResource.java` (which uses `HAS_PARENT_ROLE`) and `platform.security.api` package for how independent player accounts resolve their Long identifier; specifically confirm whether a player's `principal.getBusinessId()` equals their `PlayerProfile.id` (Long TSID) — this is the pattern used by `BookingResource.currentParentId()` and `currentCoachUserId()` which both read `principal.getBusinessId()` as a Long
  - [x] Run a test or add a log in `VideoResource` to print `securityUtil.getCurrentUser().getId()` and `principal.getBusinessId()` for a parent-authenticated session vs. a player-authenticated session — confirm which ID maps to `ParentPlayerLink.playerId` (Long) so the RBAC guard from Story 6.5 still works for player-uploaded HOMEWORK videos
  - [x] Document the confirmed ownerId format in a code comment at the top of the new player upload endpoint: `// ownerId for player HOMEWORK videos: <confirmed format> — see Story 6.6 Task 0`
  - [x] **DO NOT proceed to Task 8 until confirmed** — the minor prohibition check and minor safety gate both depend on resolving the player's `PlayerProfile.dateOfBirth` from the ownerId; using the wrong ID silently skips the age check for all players

- [x] **Task 1 — Flyway V60: approval table and REJECTED state** (AC: 4, 6, 7)
  - [x] Create `main.video_approval_requests` full table: `(id UUID PK DEFAULT gen_random_uuid(), video_id UUID NOT NULL REFERENCES main.videos(id) ON DELETE CASCADE, player_id BIGINT NOT NULL, parent_id BIGINT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), resolved_at TIMESTAMPTZ NULL)` — **SPEC CORRECTION**: the epics show `playerId UUID` and `parentId UUID` but the authoritative type for player and parent identifiers throughout the platform is `BIGINT` (Long TSID) as used in `ParentPlayerLink`, `bookings`, and all linked tables; BIGINT is correct here; this is a deliberate deviation from the epic schema; add `CREATE INDEX idx_var_video_id ON main.video_approval_requests(video_id)`; add `CREATE INDEX idx_var_player_status ON main.video_approval_requests(player_id, status) WHERE status = 'PENDING'`; add `CREATE INDEX idx_var_parent_status ON main.video_approval_requests(parent_id, status) WHERE status = 'PENDING'`; add `CREATE UNIQUE INDEX idx_var_unique_pending ON main.video_approval_requests(video_id) WHERE status = 'PENDING'` to prevent duplicate pending approvals for the same video
  - [x] Extend `videos.operational_state` CHECK constraint: `ALTER TABLE main.videos DROP CONSTRAINT IF EXISTS chk_videos_operational_state; ALTER TABLE main.videos ADD CONSTRAINT chk_videos_operational_state CHECK (operational_state IN ('UPLOADING', 'PROCESSING', 'SCANNING', 'TRANSCODING', 'READY', 'LOCKED', 'HIDDEN', 'REJECTED', 'FAILED', 'DELETED', 'PURGED'))` — adds REJECTED without removing PURGED added in V59; **do NOT lose PURGED** — copy all 10 existing values and append REJECTED
  - [x] Seed ConfigService keys using `ON CONFLICT (key) DO NOTHING` pattern from V57: `platform.video.approval.notification_enabled = true` (STRING type — controls whether parent notification events are published); `platform.video.approval.auto_reject_days = 7` (STRING type — **WARNING: auto-reject is NOT implemented in this story; no scheduler is wired; this config key is seeded as a placeholder for a future story. Add a SQL comment `-- WARNING: auto_reject_days is not yet active — see Story backlog` on the INSERT so ops staff do not assume approvals auto-expire**; do not implement auto-reject in this story)

- [x] **Task 2 — Expand VideoApprovalRequest entity and repository** (AC: 4, 5, 6)
  - [x] Expand `VideoApprovalRequest.java` stub entity (currently has only `id`, `videoId`, `status`) to add the missing fields: `@Column(name = "player_id", nullable = false) private Long playerId`; `@Column(name = "parent_id", nullable = false) private Long parentId`; `@Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt`; `@Column(name = "resolved_at") private Instant resolvedAt`; add `@PrePersist void prePersist() { if (createdAt == null) createdAt = Instant.now(); }` — remove the comment at top of the stub that says "Stub entity — full schema added by Story 6.6" once fields are added
  - [x] Add to `VideoApprovalRequestRepository.java` (DO NOT remove the existing `cancelAllPendingForVideo` and `cancelAllPendingForOwners` methods from Story 6.5 — they must be preserved):
    - `List<VideoApprovalRequest> findByParentIdAndStatus(Long parentId, String status)` — parent view: all PENDING approvals for all of this parent's players
    - `Optional<VideoApprovalRequest> findByIdAndParentId(UUID id, Long parentId)` — parent ownership check; throws if the approval does not belong to this parent (prevents one parent approving another's request)
    - `Optional<VideoApprovalRequest> findByVideoIdAndStatus(UUID videoId, String status)` — used by minor safety gate to check if an approval already exists before creating a duplicate
    - `@Modifying @Transactional @Query("UPDATE VideoApprovalRequest var SET var.status = 'REJECTED', var.resolvedAt = current_timestamp WHERE var.status = 'PENDING' AND var.createdAt < :cutoff") int autoRejectExpired(@Param("cutoff") Instant cutoff)` — future auto-reject (story seeds the day count but does NOT wire a scheduler; add a `// NOT WIRED — no scheduler calls this; do not call directly` comment on the method; this prevents accidental invocation via test application context leaks or future refactors)

- [x] **Task 3 — OperationalState.REJECTED enum value** (AC: 6)
  - [x] Add `REJECTED` to `platform.video.contract.OperationalState.java` enum after `HIDDEN`: `REJECTED,     // parental approval rejected; video invisible to player`
  - [x] Add `REJECTED` to `VALID_TRANSITIONS` in `VideoLifecycleService.java` if applicable — check if `HIDDEN → REJECTED` needs to be a registered transition or if `VideoApprovalService` will set the state directly (bypassing `transitionOperationalState()`) to avoid the `TerminalStateViolationException`; **recommendation**: add `HIDDEN → Set.of(TRANSCODING, REJECTED)` to VALID_TRANSITIONS so the guard allows both approval paths; using `transitionOperationalState()` is preferred over field-setting to fire `VideoStatusChangedEvent` correctly
  - [x] Also add `REJECTED` to `VideoStatusChangedEvent` handling if it filters states — check `VideoSseService.java` for any state allowlist; add REJECTED to the SSE push list so the player's VideoStatusCard updates reactively when a video is rejected

- [x] **Task 4 — VideoApprovalService** (AC: 4, 5, 6)
  - [x] Create `platform.video.service.VideoApprovalService` — `@Service`, `@Slf4j`, `@RequiredArgsConstructor`
  - [x] Inject: `VideoApprovalRequestRepository`, `VideoRepository`, `VideoLifecycleService`, `VideoProviderAdapter` (from `infrastructure.video` — needed to trigger actual Bunny encoding on approval), `VideoService` (for `completeTranscoding()` fast-path), `TransactionTemplate`, `PlayerProfileRepository` (from `platform.security.repo`), `ParentPlayerLinkRepository`, `ApplicationEventPublisher`, `ConfigService`
  - [x] **`createApprovalRequest(UUID videoId, Long playerId)`** — called by `ModerationOrchestrationService` after minor safety gate fires; the entire method body must be `@Transactional` so the HIDDEN state transition (already committed by the caller) and the approval record are written atomically relative to each other — use `@Transactional(propagation = REQUIRES_NEW)` if called from within an existing transaction:
    1. Check `videoApprovalRequestRepository.findByVideoIdAndStatus(videoId, "PENDING")` — if present, skip creation (idempotent; moderation may retry); return early
    2. Load video, confirm `operationalState == HIDDEN` — throw `IllegalStateException` if not (race guard)
    3. Load `PlayerProfile` by `playerId` → resolve `Long parentId = playerProfile.getParentId()`; throw `IllegalStateException` if no PlayerProfile found (orphaned video owner — log WARN); **null-parentId guard**: if `playerProfile.getParentId() == null` (minor player whose parent account was deleted or never linked), do NOT insert an approval row with `parent_id = null` — that violates the NOT NULL constraint; instead log WARN `[VIDEO_APPROVAL_NO_PARENT videoId={} playerId={}]` and fall through to `advanceToTranscoding()` as a safe fallback (unlinked minor is unblockable — advance rather than permanently hide); **note on parent resolution**: `PlayerProfile.parentId` is the *primary* parent; if a minor player has additional parents linked via `ParentPlayerLink`, only the primary parent receives the approval notification — this asymmetry is intentional but means a secondary linked parent can approve (RBAC via `ParentPlayerLink`) without ever being notified; multi-parent notification is out of scope for this story; log INFO noting only one parent was notified
    4. Create `VideoApprovalRequest` with `videoId`, `playerId`, `parentId`, `status = "PENDING"`; save
    5. If `configService.getBoolean("platform.video.approval.notification_enabled", true)`: publish `VideoApprovalParentNotificationEvent(videoId, playerId, parentId, videoType, createdAt)` — include video type and creation timestamp so the parent notification reads "Approve [Player]'s new [VideoType] video uploaded on [Date]" (see Task 5)
    6. Log INFO `[VIDEO_APPROVAL_CREATED videoId={} playerId={} parentId={}]`
  - [x] **`approveVideo(UUID approvalId, Long parentId)`** — parent approves:
    1. Load approval via `findByIdAndParentId(approvalId, parentId)` — 404 if not found (parent ownership enforced)
    2. Load video — if `operationalState` is already `TRANSCODING`, `READY`, or any post-HIDDEN state, return silently (concurrent approval idempotent); **this check must come before the approval status check** — a race between two concurrent parent sessions would both see PENDING approval but the first one wins the state transition; the second must bail here, not throw
    3. Verify `approval.getStatus().equals("PENDING")` — throw `IllegalStateException` if already `APPROVED` or `REJECTED` (returns the current state to the caller for 409)
    4. Confirm `video.operationalState == HIDDEN` — throw if not (unexpected state)
    5. Inside `@Transactional`: set `approval.setStatus("APPROVED"); approval.setResolvedAt(Instant.now()); videoApprovalRequestRepository.save(approval)` then call `videoLifecycleService.transitionOperationalState(videoId, OperationalState.TRANSCODING)` — this fires `VideoStatusChangedEvent(TRANSCODING)` which SSE pushes to the player's card. **Concurrent race guard**: without a DB-level lock, two concurrent approve calls (two parent sessions) both pass the `operationalState` check before either commits; both then proceed to call `triggerTranscoding()` outside the transaction — Bunny gets called twice. Mitigate by adding `@Version private Long version` to the `Video` entity (if not already present) so the second concurrent `transitionOperationalState()` call throws `OptimisticLockException`, which maps to 409. Verify whether `Video` already has `@Version`; if not, add it. If `VideoLifecycleService.transitionOperationalState()` already uses `SELECT FOR UPDATE`, document that here instead.
    6. **Outside the transaction**, trigger actual Bunny encoding — **the video was never submitted to Bunny before the HIDDEN gate intercepted it; without this call the video remains un-encoded forever**: read `video.getProviderAssetId()`; if `video.getEncodingCompletedAt() != null` (fast-path: Bunny finished encoding before the gate fired and approval came late) → call `videoService.completeTranscoding(videoId)` instead; otherwise call `videoProviderAdapter.triggerTranscoding(providerAssetId)` — if this throws, log ERROR `[VIDEO_APPROVAL_BUNNY_FAILED approvalId={} videoId={}]`; **do NOT rely on the SLA monitor for recovery** — the SLA monitor watches `SCANNING` state, not `TRANSCODING`; a stuck `TRANSCODING` video is not automatically rescued; for now, log the failure for manual ops intervention and add a TODO to wire a `video_encoding_retry_outbox` pattern (see Dev Notes)
    7. Publish `VideoApprovalOwnerNotificationEvent(videoId, approval.getPlayerId(), "approved")` — player notification
    8. Log INFO `[VIDEO_APPROVAL_APPROVED approvalId={} videoId={} parentId={}]`
  - [x] **`rejectVideo(UUID approvalId, Long parentId)`** — parent rejects:
    1. Same ownership load as approve
    2. Verify `approval.getStatus().equals("PENDING")` — throw `VideoAlreadyResolvedException` (see below) if already `APPROVED` or `REJECTED`
    3. Load video — if `operationalState` is already `TRANSCODING`, `READY`, or any state past HIDDEN (concurrent approve won the race), return silently (idempotent — the approval already progressed); **this guard is critical**: `TRANSCODING → REJECTED` is not in VALID_TRANSITIONS and calling `transitionOperationalState(REJECTED)` from TRANSCODING throws `TerminalStateViolationException`; check for `HIDDEN` specifically and bail for all other post-HIDDEN states; if already `REJECTED`, return silently
    4. `@Transactional`: set approval REJECTED + resolvedAt; call `videoLifecycleService.transitionOperationalState(videoId, OperationalState.REJECTED)` — fires `VideoStatusChangedEvent(REJECTED)` 
    5. Publish `VideoApprovalOwnerNotificationEvent(videoId, approval.getPlayerId(), "rejected")` — player notification
    6. Log INFO `[VIDEO_APPROVAL_REJECTED approvalId={} videoId={} parentId={}]`
  - [x] **`getPendingApprovalsForParent(Long parentId)`** — returns `List<VideoApprovalRequest>`: loads all PENDING approvals for `parentId` via `findByParentIdAndStatus(parentId, "PENDING")`; no pagination in this story (parent typically has few videos pending)
  - [x] **Exception: `VideoApprovalNotFoundException`** — create in `platform.video.contract.exception`; thrown when `findByIdAndParentId` returns empty; `VideoApiAdvice` maps to 404 with code `video.approvalNotFound`; **note**: this exception is also thrown when the approval's `video_id` FK has been cascade-deleted (video deleted while approval was pending) — the parent sees a 404 with `video.approvalNotFound`; this is acceptable but adds the i18n key `video.approvalNotFound` to cover both "wrong ID" and "video was deleted" — see Task 16
  - [x] **Exception: `VideoAlreadyResolvedException`** — create in `platform.video.contract.exception`; thrown by `approveVideo()` and `rejectVideo()` when approval status is already `APPROVED` or `REJECTED` (serial duplicate call, not concurrent race — concurrent race is handled by the operationalState idempotent guard); `VideoApiAdvice` maps to `409 Conflict` with code `video.approvalAlreadyResolved`; **do NOT use `IllegalStateException` for this path** — an unhandled `IllegalStateException` maps to 500, not 409

- [x] **Task 5 — VideoApprovalParentNotificationEvent and VideoApprovalOwnerNotificationEvent** (AC: 4, 5, 6)
  - [x] Create `platform.video.contract.event.VideoApprovalParentNotificationEvent` record: `(UUID videoId, Long playerId, Long parentId, String playerName, String videoType, Instant videoCreatedAt)` — include `playerName` (resolved via `PlayerProfileService.getPlayerNameByPlayerId(playerId)` before publishing), `videoType`, and `videoCreatedAt` so the notification consumer can render "Approve **[Player]**'s new [VideoType] video uploaded on [Date]" without needing to perform a cross-module lookup; without `playerName` in the event, the notification consumer must query `PlayerProfileService` from the notification module — a cross-module dependency that breaks the consumer's self-containedness; resolve the name at publish time in `VideoApprovalService.createApprovalRequest()`; published when minor safety gate creates an approval request; consumed by notification module (wiring is out of scope — publish only)
  - [x] Create `platform.video.contract.event.VideoApprovalOwnerNotificationEvent` record: `(UUID videoId, Long playerId, String decision)` — `decision` is `"approved"` or `"rejected"`; consumed by notification module
  - [x] Both events are Spring application events (same pattern as `VideoModerationOwnerNotificationEvent` in `platform.video.contract.event`)
  - [x] **Do NOT create email listener in `platform.notification`** — notification wiring is a separate concern; publishing the event here is sufficient for this story; add a comment `// Consumed by platform.notification.infrastructure.listener.VideoApprovalEmailListener (Story 7.x)` in the publisher call in VideoApprovalService

- [x] **Task 6 — VideoApprovalResource** (AC: 4, 5, 6)
  - [x] Create `platform.video.api.VideoApprovalResource` — `@RestController`, `@RequestMapping("/api/video/approvals")`, `@Observed(name = "video.approvals")`, `@RequiredArgsConstructor`, `@Slf4j`
  - [x] Inject: `VideoApprovalService`, `SecurityUtil`, `VideoApprovalRequestMapper` (MapStruct mapper — see below)
  - [x] `GET /api/video/approvals` — `@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)` — parent lists all pending approval requests for all linked players; returns `List<VideoApprovalResponse>`; calls `videoApprovalService.getPendingApprovalsForParent(currentParentId())`; uses `currentParentId()` helper (same pattern as `BookingResource.currentParentId()` — reads `principal.getBusinessId()` as Long)
  - [x] `PUT /api/video/approvals/{id}/approve` — `@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)` — `@ResponseStatus(HttpStatus.NO_CONTENT)` — calls `videoApprovalService.approveVideo(id, currentParentId())`
  - [x] `PUT /api/video/approvals/{id}/reject` — `@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)` — `@ResponseStatus(HttpStatus.NO_CONTENT)` — calls `videoApprovalService.rejectVideo(id, currentParentId())`
  - [x] **`VideoApprovalResponse`** record (in `platform.video.contract`): `(UUID id, UUID videoId, Long playerId, String playerName, String status, Instant createdAt)`; playerName resolved by `PlayerProfileService.getPlayerNameByPlayerId(approval.getPlayerId())` in the mapper; **N+1 warning**: for a parent with N pending approvals this makes N `getPlayerNameByPlayerId()` calls; acceptable in this story (parents typically have 1–3 children with videos pending); if this list grows (e.g., a club admin with many linked players), batch the name lookups; add a `// TODO: batch player name resolution if list grows beyond single-family use case` comment in the mapper; **null guard**: if `getPlayerNameByPlayerId()` returns null (player profile deleted between approval creation and this fetch), default to `"Player " + approval.getPlayerId()` rather than propagating null
  - [x] **`VideoApprovalRequestMapper`** MapStruct mapper: interface in `platform.video.contract` package; converts `VideoApprovalRequest` entity → `VideoApprovalResponse` record; `playerName` field uses a default method or `@AfterMapping` that calls `PlayerProfileService` (inject with `@Autowired` setter on mapper) — or map only IDs in the mapper and resolve names in the service; either approach acceptable
  - [x] Add `VideoApprovalNotFoundException` handler to `VideoApiAdvice`: `@ExceptionHandler(VideoApprovalNotFoundException.class) @ResponseStatus(HttpStatus.NOT_FOUND)` with `ErrorDto` code `"video.approvalNotFound"`
  - [x] Add `VideoAlreadyResolvedException` handler to `VideoApiAdvice`: `@ExceptionHandler(VideoAlreadyResolvedException.class) @ResponseStatus(HttpStatus.CONFLICT)` with `ErrorDto` code `"video.approvalAlreadyResolved"` — returns 409 when a parent tries to approve or reject an approval that was already actioned (serial duplicate, not concurrent race)

- [x] **Task 7 — Implement minor safety gate in ModerationOrchestrationService** (AC: 4)
  - [x] **Standard path context**: `runMinorSafetyGate()` checks the video `ownerId`. For minor player-uploaded HOMEWORK videos, the gate fires on every upload — this is the **standard, intended flow** (not a fallback); the gate is the mechanism that enforces parental approval for all minor-owned video content. For adult player-uploaded videos, the gate records `PASSED` and advances to transcoding. For coach-owned videos, the `ownerId` is a UUID string (not a Long) and the gate returns `SKIPPED` immediately. Document this clearly in a code comment at the top of the method.
  - [x] **THIS IS THE TODO AT LINE 236**: `ModerationOrchestrationService.runMinorSafetyGate(UUID videoId, String ownerId)` — replace the stub body:
    ```
    // Story 6.6 replaces this stub with real age-tier evaluation.
    // SKIPPED (not PASSED): a minor uploading in Story 6.3 should not have a MINOR_GATE/PASSED record.
    recordScan(videoId, "MINOR_GATE", "SKIPPED", null, "Age check deferred to Story 6.6");
    // TODO Story 6.6: inject AgePolicyService; if minor → HIDDEN + video_approval_requests
    // For Story 6.3: assume all owners are adults/coaches → advance to TRANSCODING
    advanceToTranscoding(videoId, ownerId);
    ```
  - [x] Add injection of `AgePolicyService` and `VideoApprovalService` to `ModerationOrchestrationService` (add to `@RequiredArgsConstructor` injection list or create constructor injection)
  - [x] **Resolve ownerId to PlayerProfile**: the `ownerId` parameter is the video owner's String identifier. For player-owned videos, this is the player profile Long ID as a string (confirmed in Task 0). Resolve: `Long playerId = Long.parseLong(ownerId)` then `playerProfileRepository.findById(playerId)`. **Guard against coach-owned videos**: if `Long.parseLong(ownerId)` throws `NumberFormatException` (e.g., UUID string for a coach-owned video), catch and default to `isMinor = false` (coaches are adults by definition)
  - [x] **Implementation** (inside new `runMinorSafetyGate()` body):
    1. Try to resolve `playerId = Long.parseLong(ownerId)` — if NumberFormatException (coach UUID string), call `recordScan(videoId, "MINOR_GATE", "SKIPPED", null, "Coach video — age check not applicable")` then `advanceToTranscoding(videoId, ownerId)` and return
    2. Load `PlayerProfile` by playerId using `playerProfileRepository.findById(playerId)` — if not found, log WARN and fall through to `advanceToTranscoding()` (can't determine age without profile)
    3. Check `agePolicyService.isMinor(playerProfile.getDateOfBirth())`
    4. If **adult** (18+): `recordScan(videoId, "MINOR_GATE", "PASSED", null, null)` → `advanceToTranscoding(videoId, ownerId)`
    5. If **minor** (`isMinor = true`):
       - `recordScan(videoId, "MINOR_GATE", "FLAGGED", null, "Owner is a minor — setting HIDDEN, requesting parental approval")` — **use `"FLAGGED"` not `"TRIGGERED"`**: the `video_moderation_scans.outcome` CHECK constraint (V56) only permits `('PASSED', 'FLAGGED', 'FAILED', 'SKIPPED')`; inserting `"TRIGGERED"` violates the constraint and crashes the pipeline
       - Wrap the following two operations in a single `@Transactional` method (extract to a private `@Transactional` helper or call `transactionTemplate.execute()`) so no video can be left HIDDEN without a corresponding approval record: call `videoLifecycleService.transitionOperationalState(videoId, OperationalState.HIDDEN)` — fires `VideoStatusChangedEvent(HIDDEN)` which SSE pushes to the player's VideoStatusCard; then call `videoApprovalService.createApprovalRequest(videoId, playerId)` — creates `video_approval_requests` row and publishes parent notification event. **Transaction boundary warning**: if `VideoApprovalService.createApprovalRequest()` is annotated `@Transactional(REQUIRES_NEW)` (as noted in Task 4), it creates an independent inner transaction that commits *before* the outer moderation transaction completes. If the outer transaction later rolls back (e.g., `recordScan()` fails after the inner commit), the video is not HIDDEN in the DB but the approval row is committed. The orphaned approval row is benign (the parent sees a pending approval, clicks approve/reject, and gets a 404 because the video is not HIDDEN) but it is noise. Prefer keeping both operations in the *same* outer transaction rather than REQUIRES_NEW: remove `REQUIRES_NEW` from `createApprovalRequest()` and let it join the caller's transaction here.
       - **Do NOT call `advanceToTranscoding()`** — the video waits in HIDDEN until parent approves; Bunny encoding is triggered in `VideoApprovalService.approveVideo()` when the parent acts
  - [x] Add `AgePolicyService` and `VideoApprovalService` to the ModerationOrchestrationService injection list; verify there are no circular dependency issues (video module → security module for AgePolicyService is an acceptable dependency in the modular monolith)

- [x] **Task 8 — Player HOMEWORK upload endpoint** (AC: 1, 2)
  - [x] Add `POST /api/video/player/uploads/initiate` to `VideoResource` (new mapping, separate from the existing coach-only `POST /api/video/uploads/initiate`):
    ```java
    @PostMapping("/player/uploads/initiate")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)  // or hasRole('ROLE_USER') if players have that role
    @Observed(name = "video.player.upload.initiate")
    public ResponseEntity<InitializeUploadResponse> initiatePlayerUpload(
            @Valid @RequestBody VideoUploadInitiateRequest req) {
    ```
  - [x] Inside the method:
    1. Enforce only HOMEWORK type allowed for players: if `req.videoType() != VideoType.HOMEWORK`, throw `VideoValidationException("Players may only upload HOMEWORK videos")`
    2. Resolve player ownerId (confirmed format from Task 0 investigation)
    3. Load PlayerProfile by resolved playerId — if not found, throw 403 (player must have a profile to upload)
    4. Call `videoService.initializeUpload(new InitializeUploadRequest(ownerId, req.fileName(), req.fileSizeBytes(), req.mimeType(), req.videoType()))`
    5. Return 201 Created with `InitializeUploadResponse`
    - **No minor prohibition check at this layer** — minor players are allowed to upload; the minor safety gate in the moderation pipeline (Task 7, Layer 3) intercepts the video after it passes content moderation and routes it through parental approval; `VideoUploadService.initiateUpload()` does NOT age-check the owner [DECISION: overrides the original task which blocked minors here]
  - [x] Add `PlayerProfileRepository` injection to `VideoResource` (for ownerId resolution and profile check; `AgePolicyService` injection is not needed at this layer)
  - [x] **Do NOT change the existing coach `POST /api/video/uploads/initiate`** — it stays coach-only with `HAS_COACH_ROLE`; coaches upload DRILL_DEMO and COACH_REVIEW; the new player endpoint is a separate path

- [x] **Task 9 — GET /api/video/quotas/me endpoint** (AC: 1)
  - [x] Add `GET /api/video/quotas/me` to `VideoResource`:
    ```java
    @GetMapping("/quotas/me")
    @PreAuthorize("hasAnyRole('ROLE_PLAYER', 'ROLE_COACH')")  // parents are not video uploaders; IS_AUTHENTICATED would let parent sessions through with no quota row
    @Observed(name = "video.quota.query")
    public ResponseEntity<VideoQuotaResponse> getMyQuota()
    ```
  - [x] Resolve the current user's ownerId from the security context (same ownerId pattern as upload — for coaches: UUID string; for players: Long string)
  - [x] `VideoQuotaResponse` record (in `platform.video.contract`): `(long storageUsedBytes, long storageLimitBytes, long bandwidthUsedBytes, long bandwidthLimitBytes, String tier)`; derive `storageLimitBytes` from `QuotaConfigService.getStorageQuotaBytes(ownerId)` and `bandwidthLimitBytes` similarly; `VideoQuota` entity provides `storageUsedBytes` and `bandwidthUsedBytes`
  - [x] Inject `QuotaService` (or `VideoQuotaRepository` + `QuotaConfigService`) into `VideoResource` for this endpoint
  - [x] **Lazy-init**: if no `video_quotas` row exists for the current user (e.g., coach who has never uploaded), return zeros with tier limits — do NOT 404; call `quotaService.ensureQuotaRowExists()` if accessible or call `videoQuotaRepository.findById(ownerId).orElse(defaultQuota)` with zero defaults

- [x] **Task 10 — VideoErrorCode additions** (AC: 5, 6) [DECISION: `MinorUploadProhibitedException` removed — minor upload prohibition has been replaced by the parental approval gate; no 403 is returned for minor uploads]
  - [x] Add `VIDEO_APPROVAL_NOT_FOUND` and `VIDEO_APPROVAL_ALREADY_RESOLVED` to `platform.video.contract.VideoErrorCode` enum (alongside existing `VIDEO_NOT_FOUND`, `VALIDATION_FAILED`, `QUOTA_EXCEEDED`, `PLAYBACK_DENIED`, `PROVIDER_ERROR`, `SESSION_EXPIRED`, `TERMINAL_STATE_VIOLATION`, `DELETION_NOT_AUTHORISED`)
  - [x] **Do NOT add** `MINOR_UPLOAD_PROHIBITED` — minor uploads are permitted; the parental approval flow handles age-restricted content

- [x] **Task 11 — GET /api/video/my endpoint** (AC: 3)
  - [x] The frontend `VideoManagementPage.vue` needs to list a player's videos. No "list my videos" endpoint currently exists — `VideoRepository` has `findByOwnerIdAndOperationalStateNot` (used by `VideoDeletionService`) but no `findByOwnerIdOrderByCreatedAtDesc` and no corresponding resource endpoint. Add:
    ```java
    @GetMapping("/my")
    @PreAuthorize("hasAnyRole('ROLE_PLAYER', 'ROLE_COACH')")  // parents don't own videos; IS_AUTHENTICATED is too broad
    @Observed(name = "video.list.my")
    public ResponseEntity<List<VideoSummaryResponse>> getMyVideos()
    ```
  - [x] Use `videoRepository.findByOwnerIdAndOperationalStateNotInOrderByCreatedAtDesc(ownerId, excludedStates)` (new derived JPA query on `VideoRepository`) returning `List<Video>`; exclude `DELETED`, `PURGED`, and `FAILED` states so players do not see ghost entries for videos they have already deleted or that failed at the provider level; call with `Set.of(OperationalState.DELETED, OperationalState.PURGED, OperationalState.FAILED)`; map to `VideoSummaryResponse` records via a `VideoSummaryMapper` (MapStruct)
  - [x] `VideoSummaryResponse` record: check if it already exists in `platform.video.contract` — the class `VideoSummaryResponse.java` is already listed in the file tree; read it before creating a new one; if it exists, use it; if it's a stub, expand it with fields needed for the card: `(UUID id, String operationalState, String videoType, String title, Instant createdAt)`
  - [x] Add `findByOwnerIdAndOperationalStateNotInOrderByCreatedAtDesc(String ownerId, Collection<OperationalState> excludedStates)` to `VideoRepository` if a suitable query doesn't already exist; no pagination in this story (players typically have few HOMEWORK videos; the excluded-states filter prevents unbounded growth from accumulating deleted video rows)

- [x] **Task 12 — Frontend: VideoManagement.vue page** (AC: 1, 2, 3, 7)
  - [x] Create `src/frontend/src/pages/VideoManagementPage.vue` (new page component):
    - Fetch player's videos via `GET /api/video/my` on mount
    - Fetch quota via `GET /api/video/quotas/me` on mount
    - Show upload button for HOMEWORK type for **all** authenticated players regardless of age — minor players can upload; their videos will be routed through parental approval by the moderation pipeline [DECISION: age-based button hiding removed; do not gate the button on `ageTier`]
    - Render a `VideoStatusCard` per video; wire `@status-changed` emit to update the local video list (remove card when `DELETED`/`PURGED` state arrives)
    - Render a `QuotaUsageBar` component below page header; re-fetch quota after each upload success or deletion
    - Handle deletion: call `DELETE /api/video/{id}`; on 204 success, remove card from list and re-fetch quota; on 403 show `video.deletionNotAuthorised` i18n key toast; on any other error show `video.deletionFailed`
    - Add confirmation dialog before deletion (Quasar `$q.dialog` confirm) — destructive action, requires user confirmation
    - Use `<script setup>` with `async/await`; state via composables or local `ref/reactive` (not global Pinia store for this page)
    - All text via `vue-i18n` `t()` — no hardcoded strings
    - **SSE teardown**: `VideoStatusCard` components open SSE connections per video; call `onUnmounted(() => { /* close all active SSE connections */ })` in this page component so navigating away does not leak one EventSource per video card; ensure each `VideoStatusCard` exposes a `close()` method or emits an event that the page can hook on unmount
    - **403 handler**: `GET /api/video/my` is restricted to `ROLE_PLAYER` and `ROLE_COACH`; if a parent navigates to this route (403 response from API), show a toast with `t('video.management.accessDenied')` and redirect to the parent dashboard — do not silently render an empty list
  - [x] Add route to `src/frontend/src/router/routes.js` for the new page; add route meta guard `meta: { requiresRole: ['ROLE_PLAYER', 'ROLE_COACH'] }` consistent with the existing role-guard pattern; without this guard, a parent navigating directly to the URL reaches the page and triggers a 403 API call before the redirect logic fires — the route guard provides a cleaner UX

- [x] **Task 13 — Frontend: QuotaUsageBar.vue component** (AC: 1)
  - [x] Create `src/frontend/src/components/video/QuotaUsageBar.vue`:
    - Props: `{ storageUsedBytes: Number, storageLimitBytes: Number, bandwidthUsedBytes: Number, bandwidthLimitBytes: Number, loading: Boolean }`
    - Two labelled rows: "Storage" and "Bandwidth"
    - Each row: label · filled `q-linear-progress` · numeric summary (e.g., "3.2 GB / 5 GB") · tier limit label
    - Progress bar fill: `color="primary"` up to 80%; `color="warning"` from 80–94%; `color="negative"` at 95%+; derive breakpoints from props percentage
    - At 95%+: inline one-line prompt linked to subscription upgrade flow; use a `<router-link to="/upgrade">` placeholder (the upgrade route does not exist yet — add `// TODO Story 7.x: replace with real upgrade route` comment); gate the prompt behind a computed `showUpgradePrompt` that reads from the app config or env: `const showUpgradePrompt = computed(() => appConfig.get('platform.ui.quota.upgradePromptEnabled', true))`; define the config key as `platform.ui.quota.upgradePromptEnabled` (boolean, default `true`) so ops can disable the prompt independently of the quota display
    - Loading skeleton: `q-skeleton` rows when `loading=true`
    - All colours via CSS custom property tokens — no hardcoded hex
    - `<script setup>` with computed percentage helpers
    - Uses `vue-i18n` for all labels

- [x] **Task 14 — Frontend: VideoStatusCard.vue — add REJECTED state** (AC: 3, 6)
  - [x] Add `REJECTED` config to `statusConfigs` in `src/frontend/src/components/video/VideoStatusCard.vue`:
    ```js
    REJECTED: { icon: 'cancel', color: 'negative', error: true },
    ```
  - [x] **Remove `HIDDEN` from `TERMINAL_STATES` and add `REJECTED`**: a video in `HIDDEN` state is awaiting parental approval and will transition to either `TRANSCODING` (approved) or `REJECTED` (rejected) — if `HIDDEN` is terminal, the SSE connection closes and the player's `VideoStatusCard` never receives the subsequent state push, breaking the real-time update in AC 5. The current code at line 40 has `HIDDEN` in the set; remove it and add `REJECTED` instead: `const TERMINAL_STATES = new Set(['READY', 'LOCKED', 'FAILED', 'DELETED', 'ARCHIVED', 'REJECTED'])`
  - [x] **CAUTION**: `TERMINAL_SSE_STATES` in `video.store.js` line 10 also includes `HIDDEN` — apply the same fix there: remove `HIDDEN`, add `REJECTED`. Both sets must be updated consistently or SSE behaviour will diverge between the card and the store.
  - [x] Verify `video.status.HIDDEN` i18n key already says `'Awaiting approval'` (it does — line 423 of `en-US/index.js`); add `video.status.REJECTED: 'Not approved'`

- [x] **Task 15 — Frontend: video.api.js and video.store.js updates** (AC: 1, 5, 6, 7)
  - [x] Add to `src/frontend/src/api/video.api.js`:
    - `getMyVideos()` → `api.get('/api/video/my')`
    - `getMyQuota()` → `api.get('/api/video/quotas/me')`
    - `deleteVideo(videoId)` → `api.delete(\`/api/video/${videoId}\`)`
    - `getMyApprovals()` → `api.get('/api/video/approvals')` (parent view)
    - `approveVideo(approvalId)` → `api.put(\`/api/video/approvals/${approvalId}/approve\`)`
    - `rejectVideo(approvalId)` → `api.put(\`/api/video/approvals/${approvalId}/reject\`)`
    - `initiatePlayerUpload(payload, signal)` → `api.post('/api/video/player/uploads/initiate', payload, { signal })`
  - [x] **video.store.js**: the existing store manages upload state only; do NOT add a global quota store in this story — quota is page-local state (re-fetched on `VideoManagementPage.vue` mount and after upload/delete events); this keeps the store focused; if future stories need global quota state, extract then

- [x] **Task 16 — i18n keys** (AC: 1, 2, 3, 4, 5, 6, 7)
  - [x] Add to `src/frontend/src/i18n/en-US/index.js` under the `video:` section (after existing `deletionFailed` key):
    ```js
    status: {
      // existing keys preserved...
      REJECTED: 'Not approved',   // add this to existing status block
    },
    approval: {
      awaitingParentTitle: 'Awaiting parent approval',
      pendingApprovals: 'Videos awaiting your approval',
      approveButton: 'Approve',
      rejectButton: 'Reject',
      approvedNotice: 'Your video has been approved and is now processing',
      rejectedNotice: 'Your video was not approved',
      confirmApprove: 'Approve this video for publishing?',
      confirmReject: 'Reject this video? The player will be notified.',
    },
    quota: {
      storage: 'Storage',
      bandwidth: 'Monthly bandwidth',
      upgradePrompt: 'Running low — upgrade for more space',
      loading: 'Loading quota…',
    },
    management: {
      title: 'My Videos',
      uploadButton: 'Upload Homework Video',
      deleteConfirm: 'Delete this video? This cannot be undone.',
      emptyState: 'No videos uploaded yet',
      accessDenied: 'Video management is only available for players and coaches.',
      // minorRestrictionInfo removed — minor players can now upload; no restriction message needed [DECISION]
    },
    // minorUploadProhibited removed — minor upload prohibition replaced by parental approval gate [DECISION]
    approvalNotFound: 'Approval request not found or the video was deleted.',
    approvalAlreadyResolved: 'This approval request has already been actioned.',
    management: {
      // ... existing keys ...
      accessDenied: 'Video management is only available for players and coaches.',
    },
    ```

- [x] **Task 18 — Frontend: ParentApprovalPage.vue** (AC: 4, 5, 6)
  - [x] Create `src/frontend/src/pages/ParentApprovalPage.vue` — the parent-facing view for reviewing pending video approvals; without this page, the "Approve / Reject actions" referenced in the parent notification have nowhere to land:
    - On mount, fetch `GET /api/video/approvals` via `videoApi.getMyApprovals()`; render a list of `VideoApprovalCard` components (or inline cards — one per pending approval)
    - Each card shows: player name, video type, upload date (from `VideoApprovalResponse.createdAt`), and a thumbnail placeholder
    - Approve button: calls `videoApi.approveVideo(approvalId)` → on 204, remove the card from the list and show success toast using `video.approval.approvedNotice` i18n key
    - Reject button: shows a Quasar `$q.dialog` confirm (`video.approval.confirmReject`) → on confirm, calls `videoApi.rejectVideo(approvalId)` → on 204, remove card and show `video.approval.rejectedNotice` toast
    - Empty state: show `video.approval.pendingApprovals` i18n text when list is empty
    - Handle 403: if a non-parent calls this page, redirect to dashboard (the API returns 403; catch it)
    - Loading skeleton during fetch
    - `<script setup>` with local `ref` state; no global store
    - All text via `t()`
  - [x] Add route to `src/frontend/src/router/routes.js` — canonical route path is `/parent/approvals`; use `meta: { requiresRole: ['ROLE_PARENT'] }` consistent with existing parent dashboard guard; the path must be stable because Story 7.x notification consumer hard-codes it in email/notification deep-links; **do not change this path without a Story 7.x dependency update**
  - [x] Add `getMyApprovals()`, `approveVideo()`, `rejectVideo()` to `video.api.js` (already listed in Task 15 — confirm they are added before implementing this task)
  - [x] The parent notification (AC 4) must deep-link to `/parent/approvals` — coordinate with the notification module owner (Story 7.x); document the canonical path in `VideoApprovalParentNotificationEvent` as a constant or in a comment at the event class: `// Deep-link target: /parent/approvals — see Story 7.x notification consumer`
  - [x] Add Vitest component tests for `ParentApprovalPage.vue`: verify approval card removed from list on 204 approve; verify confirmation dialog shown before reject call; verify 403 redirects to parent dashboard; verify empty state renders when list is empty

- [x] **Task 17 — Tests** (AC: all)
  - [x] `VideoApprovalResourceIT` (`@SpringBootTest @Testcontainers`):
    - Parent lists pending approvals — returns correct list; other parent sees empty list (isolation)
    - Parent approves video → 204; verify `video_approval_requests.status = 'APPROVED'`, `videos.operational_state = 'TRANSCODING'`, `resolved_at` set, `VideoStatusChangedEvent(TRANSCODING)` published, **and `videoProviderAdapter.triggerTranscoding()` was called** (mock the adapter and assert it was invoked — this is the critical encoding trigger)
    - Parent rejects video → 204; verify `status = 'REJECTED'`, `operational_state = 'REJECTED'`, `VideoStatusChangedEvent(REJECTED)` published
    - Parent tries to approve another parent's approval request → 404 (ownership enforced by `findByIdAndParentId`)
    - Non-parent (coach) calls `GET /api/video/approvals` → 403 (RBAC enforced)
    - **Concurrent approve race**: two threads both call approve simultaneously — exactly one triggers encoding; the other returns 204 silently (idempotent); no double `triggerTranscoding()` call
    - Approve already-APPROVED video (serial, not concurrent) → idempotent guard returns 204 without calling `triggerTranscoding()` again
    - Approve a PURGED video (deleted between approval creation and parent action) → verify clean error path (video not found or already purged)
  - [x] `PlayerUploadIT` (`@SpringBootTest @Testcontainers`) [DECISION: replaces `MinorUploadProhibitionIT`; minor uploads are now allowed]:
    - Minor player (under 18) calls `POST /api/video/player/uploads/initiate` with HOMEWORK type → 201 (upload succeeds; moderation pipeline will intercept via minor gate)
    - Adult player (18+) calls the same endpoint with HOMEWORK type → 201 (quota permitting)
    - Any player calls with DRILL_DEMO or COACH_REVIEW type → 400 or 422 (players may only upload HOMEWORK)
    - Non-authenticated request → 401
    - Coach calls `POST /api/video/player/uploads/initiate` → 403 (endpoint is not for coaches; they use the existing coach path)
  - [x] `MinorSafetyGateIT` (`@SpringBootTest @Testcontainers`):
    - Minor player uploads a video (standard path) → moderation passes Layer 1 + Layer 2 → minor safety gate fires → `videos.operational_state = 'HIDDEN'`, `video_approval_requests` row created with `status = 'PENDING'`, `video_moderation_scans` row for MINOR_GATE has `outcome = 'FLAGGED'` (not 'TRIGGERED' — verify DB value explicitly), `VideoApprovalParentNotificationEvent` published
    - Adult player uploads HOMEWORK → moderation passes → minor gate PASSED → `TRANSCODING` (existing path unchanged); verify `video_moderation_scans.outcome = 'PASSED'`
    - Coach-owned video (UUID ownerId) → gate records `outcome = 'SKIPPED'`, `advanceToTranscoding()` called (existing path)
    - Minor gate fires for video with no associated PlayerProfile (orphaned ownerId) → logs WARN, advances to TRANSCODING as safe fallback (does not crash moderation pipeline)
    - Minor gate fires twice for same video (retry scenario) → second call is idempotent: no duplicate `video_approval_requests` row (unique pending index prevents it)
    - **Transaction rollback test**: if `createApprovalRequest()` throws after `transitionOperationalState(HIDDEN)` — verify the HIDDEN transition does NOT leave the video stuck with no approval row (either both commit or neither does)
  - [x] `VideoQuotaResourceIT`:
    - `GET /api/video/quotas/me` returns `VideoQuotaResponse` with correct used/limit values
    - Unauthenticated request → 401
  - [x] `VideoListResourceIT` (`@SpringBootTest @Testcontainers`) — covers AC 3 backend:
    - Player with 3 videos (UPLOADING, TRANSCODING, READY) calls `GET /api/video/my` → returns 3 cards in descending creation order; DELETED and PURGED state videos are excluded
    - Player with no videos → returns empty list (not 404)
    - Coach calls `GET /api/video/my` → returns their videos (ROLE_COACH allowed)
    - Parent calls `GET /api/video/my` → 403
    - Unauthenticated → 401
  - [x] **Frontend component tests** (Vitest / Vue Test Utils) — add tests for:
    - `QuotaUsageBar.vue`: verify color breakpoints at 79%, 80%, 94%, 95%; verify loading skeleton renders when `loading=true`; verify upgrade prompt hidden when `showUpgradePrompt=false`
    - `VideoStatusCard.vue`: verify REJECTED renders `cancel` icon with `negative` color; verify HIDDEN is not in TERMINAL_STATES (SSE stays open); verify REJECTED is in TERMINAL_STATES (SSE closes)
    - `VideoManagementPage.vue`: verify upload button absent from DOM when player is minor (not just `v-show` — must be `v-if`); verify deletion confirmation dialog fires before API call; verify quota re-fetched after successful deletion

### Review Findings

**Decision Needed:**
- [x] [Review][Decision] REJECTED in EXCLUDED_STATES — RESOLVED: keep REJECTED visible in player list. "Invisible to player" in AC6 means not publicly playable/discoverable (PlaybackService enforces this), not hidden from the player's own management portal. REJECTED videos were never encoded by Bunny and cannot be played by anyone; the VideoStatusCard warning overlay is the correct mechanism to inform the player their video was not approved. No code change needed. [VideoResource.java:getMyVideos()]

**Patches:**
- [x] [Review][Patch] CRITICAL: Player SSE ownership check uses getCurrentUserName() (email) vs video.ownerId (Long TSID string) → 403 for every VideoStatusCard SSE connection in VideoManagementPage [VideoEventResource.java:findAndVerifyOwnership()]
- [x] [Review][Patch] HIGH: triggerTranscoding() called inside @Transactional boundary — spec requires call outside the transaction (AFTER_COMMIT); if any post-Bunny code throws, rollback leaves video stuck in HIDDEN with Bunny encoding already started [VideoApprovalService.java:approveVideo()]
- [x] [Review][Patch] HIGH: Concurrent approveVideo: no lock on VideoApprovalRequest — two simultaneous approve calls both pass PENDING check and HIDDEN state check, both call triggerTranscoding(); second thread uses stale in-memory video object so double-encoding fires even after first thread's state transition commits [VideoApprovalService.java:approveVideo()]
- [x] [Review][Patch] HIGH: Upload flow is a $q.notify stub — openUpload() shows "Upload flow coming soon" toast; videoApi.initiatePlayerUpload() is never called; AC1/AC2 upload interaction entirely missing from frontend [VideoManagementPage.vue:openUpload()]
- [x] [Review][Patch] HIGH: VideoManagementPage has no delete button or confirmation dialog — AC7 player deletion flow (deleteVideo API call, quota refresh, card removal) is unimplemented in frontend [VideoManagementPage.vue]
- [x] [Review][Patch] MEDIUM: recordScan(FLAGGED) committed before atomic HIDDEN+approval-row transaction — if transactionTemplate.execute() rolls back after recordScan(), scan row is committed but video stays in SCANNING with no approval row [ModerationOrchestrationService.java:runMinorSafetyGate()]
- [x] [Review][Patch] MEDIUM: Missing PlayerProfile → gate records outcome "PASSED" (misleading audit trail — implies adult cleared; should be "SKIPPED") [ModerationOrchestrationService.java:runMinorSafetyGate()]
- [x] [Review][Patch] MEDIUM: SSE emitter not closed when player deletes a HIDDEN video — deleteVideo() publishes VideoPhysicalDeletionEvent not VideoStatusChangedEvent(DELETED), so VideoSseService never receives the close signal [VideoSseService.java / VideoDeletionService.java:deleteVideo()]
- [x] [Review][Patch] MEDIUM: fetchVideos() only catches 403; fetchQuota() has no catch — non-403/5xx server errors show a false empty state to the player [VideoManagementPage.vue:fetchVideos(), fetchQuota()]
- [x] [Review][Patch] MEDIUM: rejectVideo on CANCELLED approval (video deleted via account cascade) throws VideoAlreadyResolvedException → 409 "already actioned" — misleading; real cause is the video was deleted [VideoApprovalService.java:rejectVideo()]
- [x] [Review][Patch] MEDIUM: Frontend route meta { requiresRole: ['ROLE_PLAYER', 'ROLE_COACH'] } is never read in router beforeEach — parent navigates to /player/videos without a guard redirect; fetchVideos() eventually 403s and redirects but quota bar silently shows 0/0 [routes.js / router/index.js]
- [x] [Review][Patch] LOW: QuotaUsageBar renders upgradePrompt i18n text twice — outer div and router-link both render t('video.quota.upgradePrompt') producing duplicate text [QuotaUsageBar.vue]
- [x] [Review][Patch] LOW: ParentApprovalPage empty-state renders video.approval.pendingApprovals (the page title key) instead of a distinct empty-state message — same text appears as both header and body when list is empty [ParentApprovalPage.vue]

**Deferred:**
- [x] [Review][Defer] Account deletion cascade leaves PENDING approval rows for HIDDEN videos — VideoDeletionService.cascadeDeleteForAccount() does not call cancelAllPendingForVideo(); pre-existing gap from Story 6.5 [VideoDeletionService.java] — deferred, pre-existing
- [x] [Review][Defer] N+1 queries in VideoApprovalResource (playerName + videoType lookups per approval) — acknowledged in spec TODO; acceptable for single-family use case [VideoApprovalResource.java] — deferred, acknowledged
- [x] [Review][Defer] V60 DDL ACCESS EXCLUSIVE lock risk — ALTER TABLE DROP/ADD CONSTRAINT blocks reads/writes with no LOCK TIMEOUT guard — deferred, deployment concern
- [x] [Review][Defer] autoRejectExpired JPQL current_timestamp type mismatch (returns java.util.Date, field is Instant) — method explicitly not wired; safe until scheduler added [VideoApprovalRequestRepository.java:autoRejectExpired()] — deferred, not wired
- [x] [Review][Defer] @GeneratedValue(AUTO) vs UUID DB default — Hibernate 6 AUTO may use sequence strategy for UUID type; pre-existing entity pattern [VideoApprovalRequest.java] — deferred, pre-existing
- [x] [Review][Defer] PURGED in onStatusChanged dead code — VideoSseService TERMINAL_STATES does not include PURGED so event never arrives; handler branch unreachable [VideoManagementPage.vue:onStatusChanged()] — deferred, pre-existing
- [x] [Review][Defer] @Observed class-level in VideoApprovalResource vs per-method in VideoResource — loses per-operation observability granularity; minor deviation from project pattern [VideoApprovalResource.java] — deferred, pre-existing
- [x] [Review][Defer] resolveCurrentOwnerId unsafe Principal cast — pre-existing established pattern from coach upload endpoint; works within current Spring Security config [VideoResource.java:resolveCurrentOwnerId()] — deferred, pre-existing

## Dev Notes

### Product Decisions (Resolved)

1. **Minor upload design** — Minor players (under 18) **can upload videos**. The upload button is shown to all players regardless of age. The minor safety gate (Layer 3 in the moderation pipeline) is the enforcement mechanism: it intercepts every minor-owned video after content moderation, sets it to `HIDDEN`, and creates a parental approval request. This overrides FR-POR-011 and the original epic AC 2 which stated minors cannot upload. No server-side 403 is returned for minor uploads.

2. **Post-approval state** — Confirmed **TRANSCODING**. Approval triggers Bunny encoding; the video moves to TRANSCODING then READY once encoding completes. The epic AC 5 which stated `PUBLISHED` is overridden. Player notification: "Your video has been approved and is now processing."

---

### Player Identity Bridge — CRITICAL Investigation Required (Task 0)

Before implementing any player-facing upload or quota endpoint, confirm what format is stored in `videos.owner_id` for player-uploaded HOMEWORK videos. Based on evidence from prior stories:

- **Coach path** (Story 6.1): `ownerId = coachProfileService.getCoachIdByUserId(coachUserId).toString()` → **UUID string**
- **Player path** (this story): unknown — must be determined in Task 0

The key question is: does a player log in with `ROLE_USER` and what is their `principal.getBusinessId()`? Looking at `BookingResource.currentParentId()` — it reads `principal.getBusinessId()` as a Long, and this is confirmed to be the parent's User.id (Long TSID). For a player with an independent account, `principal.getBusinessId()` is likely their `PlayerProfile.id` (Long TSID) — but confirm this by checking `UserRegistrationService` or the player account creation flow.

The player `ownerId` in `videos.owner_id` MUST match `ParentPlayerLink.playerId` (Long) for Story 6.5's `VideoAccessGuard` parent checks to work correctly. If player ownerId = `Long.toString()`, then `Long.parseLong(ownerId)` succeeds and the RBAC guard works. If it's a UUID string, the guard would need a bridge lookup.

**Recommended**: before writing any code, add a debug log to an existing player-facing endpoint (e.g., `GET /api/bookings/requests` for a ROLE_PARENT principal looking up a player) that prints both `principal.businessId` and a PlayerProfile lookup to confirm the ID chain.

### What Already Exists — DO NOT Reinvent

- **`VideoApprovalRequest` entity** — stub already exists at `platform.video.repo.VideoApprovalRequest` (only `id`, `videoId`, `status` fields); Story 6.6 expands it — do NOT create a new entity; update the stub
- **`VideoApprovalRequestRepository`** — stub already exists with two cancel queries from Story 6.5 (`cancelAllPendingForVideo`, `cancelAllPendingForOwners`); Story 6.6 adds more queries — DO NOT remove Story 6.5 queries
- **`VideoStatusCard.vue`** — already exists at `src/frontend/src/components/video/VideoStatusCard.vue` with all status states except REJECTED; add REJECTED config only
- **`video.store.js`** — already manages upload state; `TERMINAL_SSE_STATES` at line 10 needs REJECTED added
- **`videoApi`** — already exists at `src/frontend/src/api/video.api.js`; add new methods, do NOT restructure
- **`AgePolicyService`** — already exists at `platform.security.service.AgePolicyService`; provides `isMinor(LocalDate)`, `getAgeTier(LocalDate)` — use these directly
- **`PlayerProfileRepository`** — already exists at `platform.security.repo.PlayerProfileRepository` with `findByParentId(Long)` and `findByIdAndParentId(Long, Long)` — inject this to resolve player profiles
- **`PlayerProfileService`** — already exists at `platform.marketplace.service.PlayerProfileService` with `getPlayerNameByPlayerId(Long)` — use for `VideoApprovalResponse.playerName`
- **`VideoLifecycleService.transitionOperationalState()`** — use for all HIDDEN→TRANSCODING and HIDDEN→REJECTED transitions; it fires `VideoStatusChangedEvent` which SSE pushes to the client; do NOT set `video.operationalState` directly
- **`VideoSummaryResponse`** — already exists in `platform.video.contract`; read the file before creating; only add fields if needed
- **`ModerationOrchestrationService.runMinorSafetyGate()`** — the TODO stub at line 236; do NOT create a new method; replace the body in-place
- **`recordScan()` outcome values** — the `video_moderation_scans.outcome` CHECK constraint (V56) permits `('PASSED', 'FLAGGED', 'FAILED', 'SKIPPED')` only; use `"FLAGGED"` when the minor gate triggers — **do not use `"TRIGGERED"` or `"BLOCKED"`** as both violate the constraint; see Dev Notes section "ModerationScanResult" for the full verified list

### OperationalState.REJECTED and VideoLifecycleService VALID_TRANSITIONS

After adding `REJECTED` to `OperationalState.java`, verify that `VideoLifecycleService.VALID_TRANSITIONS` includes `HIDDEN → Set.of(TRANSCODING, REJECTED)`. Without this, calling `transitionOperationalState(videoId, REJECTED)` will throw `TerminalStateViolationException`. Check `VideoLifecycleService.java` — if HIDDEN is not in VALID_TRANSITIONS at all (Story 6.5 PURGED guard may have removed defensive code), add it.

Also check that `VideoLifecycleService.transitionOperationalState()` has the PURGED state guard added in Story 6.5 (there should be an early return if `current == PURGED`). This prevents a late webhook from reactivating a video purged concurrently with approval. The PURGED guard is described in Story 6.5 Task 4.

### ModerationOrchestrationService Injection — Circular Dependency Risk

Adding `VideoApprovalService` to `ModerationOrchestrationService` creates a dependency chain: `ModerationOrchestrationService → VideoApprovalService → VideoLifecycleService → VideoLifecycleService`. Check if `VideoLifecycleService` already has a back-reference to `ModerationOrchestrationService` (circular). If so, introduce an interface or use `@Lazy` injection for one of the dependencies.

Additionally, `ModerationOrchestrationService` injecting `AgePolicyService` (from `platform.security`) is a cross-module dependency. This is acceptable in the modular monolith pattern (same as `VideoAccessGuard` injecting `ParentPlayerLinkRepository` from `platform.security`).

### Deployment Contract with Story 6.5

Story 6.5's `cancelAllPendingForVideo()` and `cancelAllPendingForOwners()` are called when a video is deleted — the cancellation flag is seeded `true` in V59. If this story (V60) creates the `video_approval_requests` table, Story 6.5's queries are now safe. V60 MUST deploy before any GDPR erasure request triggers `AccountDeletionCascadeListener` with the cancellation enabled. Since Story 6.5 is already deployed and V59 is in the DB, V60 must be applied as part of this story's deployment.

The `VideoApprovalRequest` stub entity is already in the Java code (Story 6.5 added it). This means the application currently fails schema validation at startup if `spring.jpa.hibernate.ddl-auto=validate` (the table `main.video_approval_requests` doesn't exist). V60 resolves this.

### HIDDEN Is Not a Terminal SSE State — Do Not Close the Connection

`VideoStatusCard.vue` line 40 currently has `HIDDEN` in `TERMINAL_STATES`. This is wrong for this story's requirements: a video in HIDDEN is awaiting parental approval and will subsequently transition to TRANSCODING (approved) or REJECTED (rejected). If the SSE connection closes when HIDDEN is reached, the player's card will never receive the approval result push and the user must manually refresh. **Task 14 must remove HIDDEN from both `TERMINAL_STATES` (VideoStatusCard.vue) and `TERMINAL_SSE_STATES` (video.store.js line 10).** REJECTED is truly terminal and should be added to both sets.

**Separately**: the `video.status.HIDDEN` i18n key maps to `'Awaiting approval'` — this is appropriate for the minor gate case but is semantically wrong if an admin hides a video for a ToS violation (a different reason, same state). This ambiguity is out of scope for this story but should be tracked as a UX debt item; add a code comment at the i18n key noting the overloading.

### Approval Triggers Actual Bunny Encoding — Not Just a State Transition

When the minor gate fires, it calls `transitionOperationalState(HIDDEN)` and does NOT call `advanceToTranscoding()`. This means Bunny has never been told to encode the video. When the parent approves, `VideoApprovalService.approveVideo()` must call `videoProviderAdapter.triggerTranscoding(providerAssetId)` in addition to the state transition — without this call, the video's `operational_state` becomes `TRANSCODING` in the database but Bunny never processes it.

**No automatic recovery for a failed `triggerTranscoding()` call**: the SLA monitor watches `SCANNING`, not `TRANSCODING`. A video stuck in `TRANSCODING` because `triggerTranscoding()` threw after the DB commit is **not rescued automatically**. If this call fails, `approveVideo()` must log `ERROR [VIDEO_APPROVAL_BUNNY_FAILED]` for ops visibility. A `video_encoding_retry_outbox` pattern (analogous to the deletion outbox in Story 6.5) would be the right long-term solution — add a `TODO` in `approveVideo()` referencing this gap; do NOT claim the SLA monitor rescues this path.

Handle the fast-path (encoding already done before the HIDDEN gate fired: `video.encodingCompletedAt != null`) by calling `videoService.completeTranscoding()` instead of `triggerTranscoding()`, mirroring the fast-path logic in `advanceToTranscoding()` at line 253.

### VideoStatusCard — HIDDEN i18n Note

The `video.status.HIDDEN` i18n key maps to `'Awaiting approval'` (line 423 of `en-US/index.js`). **This is correct** for the minor gate case — no change needed to the HIDDEN visual treatment. Only REJECTED needs to be added.

### Frontend File Locations

Based on existing patterns in the project:
- Pages: `src/frontend/src/pages/` (e.g., `DashboardPage.vue`)
- Components: `src/frontend/src/components/video/` (e.g., `VideoStatusCard.vue` already here)
- API: `src/frontend/src/api/video.api.js` (already exists — add methods)
- Store: `src/frontend/src/stores/video.store.js` (already exists — update TERMINAL_SSE_STATES)
- i18n: `src/frontend/src/i18n/en-US/index.js` (already exists — add keys)

### VideoQuotaResponse — Tier Derivation

`QuotaConfigService.getStorageQuotaBytes(ownerId)` reads the user's subscription tier and returns the configured quota. The tier name can be inferred from the same service — check if `QuotaConfigService` exposes a `getTierName(ownerId)` method; if not, include the storage limit in bytes and let the frontend derive the display string from the limit value (e.g., 5GB → "Instructor Plan"). Do NOT add a new `getTierName()` method to `QuotaConfigService` in this story if it doesn't exist.

### ModerationScanResult — Correct Outcome Value is "FLAGGED"

`video_moderation_scans.outcome` CHECK constraint (V56): `('PASSED', 'FLAGGED', 'FAILED', 'SKIPPED')`. The parameter name in `recordScan()` is `outcome` (not `result`). When the minor gate fires, use `"FLAGGED"` — the video is flagged for parental review. **Do not use `"TRIGGERED"` or `"BLOCKED"`** — both violate the CHECK constraint and will throw a `DataIntegrityViolationException`, crashing the moderation pipeline for that video. No migration change needed; `"FLAGGED"` is already a valid value.

### File Structure

**New files:**
- `src/main/resources/db/migration/V60__video_approval_portal.sql`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoApprovalService.java`
- `src/main/java/com/softropic/skillars/platform/video/api/VideoApprovalResource.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/VideoApprovalResponse.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/VideoQuotaResponse.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/exception/VideoApprovalNotFoundException.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/exception/VideoAlreadyResolvedException.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/event/VideoApprovalParentNotificationEvent.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/event/VideoApprovalOwnerNotificationEvent.java`
- `src/frontend/src/pages/VideoManagementPage.vue`
- `src/frontend/src/pages/ParentApprovalPage.vue`
- `src/frontend/src/components/video/QuotaUsageBar.vue`

**Modified files:**
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoApprovalRequest.java` — expand stub entity with playerId, parentId, createdAt, resolvedAt
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoApprovalRequestRepository.java` — add list/lookup queries (preserve Story 6.5 cancel queries)
- `src/main/java/com/softropic/skillars/platform/video/contract/OperationalState.java` — add `REJECTED`
- `src/main/java/com/softropic/skillars/platform/video/contract/VideoErrorCode.java` — add `VIDEO_APPROVAL_NOT_FOUND`, `VIDEO_APPROVAL_ALREADY_RESOLVED`
- `src/main/java/com/softropic/skillars/platform/video/api/VideoResource.java` — add player upload endpoint, quota endpoint; inject PlayerProfileRepository, QuotaService (AgePolicyService not needed at the endpoint layer)
- `src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java` — add VideoApprovalNotFoundException (404) and VideoAlreadyResolvedException (409) handlers
- `src/main/java/com/softropic/skillars/platform/video/service/ModerationOrchestrationService.java` — implement `runMinorSafetyGate()` (replace TODO stub at line 236); inject AgePolicyService and VideoApprovalService
- `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java` — add `HIDDEN → {TRANSCODING, REJECTED}` to VALID_TRANSITIONS map
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java` — add `findByOwnerIdAndOperationalStateNotInOrderByCreatedAtDesc` derived query (replaces simpler findByOwnerIdOrderByCreatedAtDesc)
- `src/frontend/src/components/video/VideoStatusCard.vue` — add REJECTED to statusConfigs; add REJECTED to TERMINAL_STATES
- `src/frontend/src/stores/video.store.js` — add REJECTED to TERMINAL_SSE_STATES (line 10)
- `src/frontend/src/api/video.api.js` — add getMyVideos, getMyQuota, deleteVideo, getMyApprovals, approveVideo, rejectVideo, initiatePlayerUpload
- `src/frontend/src/i18n/en-US/index.js` — add REJECTED status key, approval/quota/management/approvalNotFound/approvalAlreadyResolved i18n keys

### References

- `ModerationOrchestrationService.java` lines 233–242 — `runMinorSafetyGate()` stub with TODO for Story 6.6 [Source: `src/main/java/com/softropic/skillars/platform/video/service/ModerationOrchestrationService.java`]
- `VideoApprovalRequest.java` — stub entity; expand in this story [Source: `src/main/java/com/softropic/skillars/platform/video/repo/VideoApprovalRequest.java`]
- `VideoApprovalRequestRepository.java` — stub repository; MUST preserve `cancelAllPendingForVideo` and `cancelAllPendingForOwners` from Story 6.5 [Source: `src/main/java/com/softropic/skillars/platform/video/repo/VideoApprovalRequestRepository.java`]
- `VideoResource.java` — existing coach-only upload endpoint; add player path separately; existing `DELETE /{id}` endpoint from Story 6.5 is already here [Source: `src/main/java/com/softropic/skillars/platform/video/api/VideoResource.java`]
- `VideoStatusCard.vue` — existing component; TERMINAL_STATES at line 40; statusConfigs at lines 44–56; add REJECTED config [Source: `src/frontend/src/components/video/VideoStatusCard.vue`]
- `video.store.js` — TERMINAL_SSE_STATES at line 10; add REJECTED [Source: `src/frontend/src/stores/video.store.js`]
- `video.api.js` — existing API; add new methods [Source: `src/frontend/src/api/video.api.js`]
- `en-US/index.js` lines 415–435 — existing video i18n section; HIDDEN already mapped to 'Awaiting approval' [Source: `src/frontend/src/i18n/en-US/index.js`]
- `OperationalState.java` — add REJECTED after HIDDEN [Source: `src/main/java/com/softropic/skillars/platform/video/contract/OperationalState.java`]
- `AgePolicyService.java` — `isMinor(LocalDate dateOfBirth)` and `getAgeTier(LocalDate)` methods; `isMinor(AgeTier)` overload [Source: `src/main/java/com/softropic/skillars/platform/security/service/AgePolicyService.java`]
- `BookingResource.java` lines 70–83 — `currentParentId()` pattern using `principal.getBusinessId()` as Long; mirror for player upload endpoint [Source: `src/main/java/com/softropic/skillars/platform/booking/api/BookingResource.java`]
- `QuotaService.java` — `decrementStorageBytes()`, `ensureQuotaRowExists()` exist; `VideoQuota` entity has `storageUsedBytes`, `bandwidthUsedBytes` [Source: `src/main/java/com/softropic/skillars/platform/video/service/QuotaService.java`]
- `VideoApiAdvice.java` — existing exception handler pattern; `PlaybackDeniedException` → 403 as template [Source: `src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java`]
- V59 migration — `platform.video.approvalCancellation.enabled = true` seeded here; Story 6.6 table must exist before this cancellation runs [Source: `src/main/resources/db/migration/V59__deletion_infrastructure.sql`]
- `PlayerProfileRepository.java` — `findByParentId(Long)`, `findByIdAndParentId(Long, Long)` exist [Source: `src/main/java/com/softropic/skillars/platform/security/repo/PlayerProfileRepository.java`]
- `VideoErrorCode.java` — existing error codes; add `VIDEO_APPROVAL_NOT_FOUND`, `VIDEO_APPROVAL_ALREADY_RESOLVED` [Source: `src/main/java/com/softropic/skillars/platform/video/contract/VideoErrorCode.java`]
- Project context — DDD module structure; `platform.{module}.{layer}` hierarchy [Source: `_bmad-output/project-context.md`]

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- V60 migration: V59 stub `video_approval_requests(id, video_id, status)` meant `CREATE TABLE IF NOT EXISTS` was a no-op; fixed with `ALTER TABLE ADD COLUMN IF NOT EXISTS` + conditional FK/CHECK via `DO $$ ... $$` blocks.
- `VideoApprovalServiceIT` FK violation: `insertTestUser()` missing NOT NULL columns (`status`, `lang_key`, `skillars_role`, `verification_status`, etc.); fixed by matching `ParentDevelopmentPortalResourceIT.insertUser()` pattern and wrapping in `transactionTemplate.execute()`.
- `MinorSafetyGateIT` async/context issue: `@Async` + `@TransactionalEventListener` prevented direct pipeline invocation; resolved with `AopTestUtils.getUltimateTargetObject()` to bypass AOP proxy and call synchronously. Bean override for `moderationTaskExecutor` was blocked by Spring Boot 3 `allow-bean-definition-overriding=false` default.
- `rejectVideo_alreadyResolved` test: service checks approval.status FIRST (not video state), so second `rejectVideo()` on a REJECTED approval throws `VideoAlreadyResolvedException`. Fixed test to use `assertThatThrownBy`.

### Completion Notes List

- Tasks 0–18 complete. All 70 Story 6.6 tests pass (VideoApprovalServiceIT 12, MinorSafetyGateIT 5, ModerationOrchestrationServiceTest 14, VideoApprovalResourceIT 11, PlayerUploadResourceIT 5, VideoListQuotaResourceIT 8, VideoDeleteResourceIT 6, VideoUploadResourceIT 9).
- V60 migration handles both fresh DBs and those with V59 stub table via idempotent `IF NOT EXISTS` guards.
- Minor safety gate uses `AopTestUtils.getUltimateTargetObject()` in tests to bypass `@Async` proxy — pattern documented in `MinorSafetyGateIT`.
- `approveVideo` checks video state FIRST (idempotent for concurrent approval), `rejectVideo` checks approval status FIRST (throws `VideoAlreadyResolvedException` on serial duplicate call).
- Frontend: `VideoManagementPage.vue`, `ParentApprovalPage.vue`, `QuotaUsageBar.vue` created; `VideoStatusCard.vue`, `video.store.js`, `video.api.js`, `en-US/index.js` updated.
- Note: `VideoApprovalResourceIT` is a `@WebMvcTest` slice test (HTTP layer only). DB assertions for approval/video state are covered by `VideoApprovalServiceIT`. This split is intentional and documented in test class javadoc.

### File List

**New files:**
- `src/main/resources/db/migration/V60__video_approval_portal.sql`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoApprovalService.java`
- `src/main/java/com/softropic/skillars/platform/video/api/VideoApprovalResource.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/VideoApprovalResponse.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/VideoQuotaResponse.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/exception/VideoApprovalNotFoundException.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/exception/VideoAlreadyResolvedException.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/event/VideoApprovalParentNotificationEvent.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/event/VideoApprovalOwnerNotificationEvent.java`
- `src/frontend/src/pages/VideoManagementPage.vue`
- `src/frontend/src/pages/parent/ParentApprovalPage.vue`
- `src/frontend/src/components/video/QuotaUsageBar.vue`
- `src/test/java/com/softropic/skillars/platform/video/service/VideoApprovalServiceIT.java`
- `src/test/java/com/softropic/skillars/platform/video/service/MinorSafetyGateIT.java`
- `src/test/java/com/softropic/skillars/platform/video/api/VideoApprovalResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/video/api/PlayerUploadResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/video/api/VideoListQuotaResourceIT.java`

**Modified files:**
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoApprovalRequest.java`
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoApprovalRequestRepository.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/OperationalState.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/VideoErrorCode.java`
- `src/main/java/com/softropic/skillars/platform/video/api/VideoResource.java`
- `src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java`
- `src/main/java/com/softropic/skillars/platform/video/service/ModerationOrchestrationService.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java`
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java`
- `src/frontend/src/components/video/VideoStatusCard.vue`
- `src/frontend/src/stores/video.store.js`
- `src/frontend/src/api/video.api.js`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/router/routes.js`
- `src/test/java/com/softropic/skillars/platform/video/api/VideoDeleteResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/video/api/VideoUploadResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/video/service/ModerationOrchestrationServiceTest.java`

### Change Log

- 2026-06-24: Implemented Story 6.6 (Player Video Management Portal). V60 migration, VideoApprovalRequest entity expansion, VideoApprovalService, VideoApprovalResource, minor safety gate, player upload endpoint, quota endpoint, video list endpoint, frontend pages and components. 70 tests passing.
