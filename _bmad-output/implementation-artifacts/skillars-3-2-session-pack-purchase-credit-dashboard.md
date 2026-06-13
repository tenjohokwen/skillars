# Story skillars-3.2: Session Pack Purchase & Credit Dashboard

Status: done

## Story

As a parent,
I want to purchase a session pack for my child and always see how many credits remain,
so that I know what capacity we have before requesting a booking.

## Acceptance Criteria

1. **AC 1: SessionPackTracker — Credit Display** — Given a parent views a coach's profile or booking page, when the `SessionPackTracker` component renders, then it displays credits used vs. total for the active pack with this coach in one of four visual states: Healthy (>30% remaining, neutral), Warning (<30%, amber), Critical (1–2 credits left, amber + "Buy more sessions" CTA), Exhausted (0 credits, red + "Buy sessions" CTA); and if no pack exists yet with this coach, the Exhausted state is shown with a "Buy sessions" CTA; and the tracker is always visible before any booking action — it cannot be scrolled past without seeing it.

2. **AC 2: Purchase Flow** — Given a parent taps "Buy sessions" on a coach's profile, when the purchase flow opens, then they see all available session pack options for that coach (sessionCount, totalPrice, per-session equivalent price) plus the per-session single booking option; and a `SessionPackTracker` showing current credits (if any) is visible at the top of the purchase screen.

3. **AC 3: Pack Creation on Purchase** — Given a parent selects a session pack and confirms purchase, when payment is captured (stubbed in this story; Stripe wired in Epic 7), then a `session_packs_purchased` record is created (`id UUID, parentId Long, playerId Long, coachId UUID, sessionCount INT, creditsRemaining INT, purchasedAt TIMESTAMPTZ, status VARCHAR('ACTIVE')`), `creditsRemaining` is set to `sessionCount`, and the `SessionPackTracker` updates immediately to reflect the new credit balance.

4. **AC 4: Booking Block Enforcement** — Given a parent attempts to request a booking when `creditsRemaining = 0` for all packs with that coach, when the booking form is rendered, then the form is not submitted by the frontend (disabled CTA), and the `SessionPackTracker` is shown in Exhausted state with a "Buy sessions" CTA. Backend `hasCredits()` guard will enforce this at the API layer in Story 3.3.

5. **AC 5: FIFO Credit Deduction** — Given a parent has multiple active session packs with the same coach, when a session is completed and a credit is deducted (called from Story 3.6), then credits are consumed from the oldest active pack first (FIFO by `purchasedAt ASC`); and when a pack reaches `creditsRemaining = 0`, its status transitions to `EXHAUSTED`; and a `SessionPackExhaustedEvent` is published via `ApplicationEventPublisher` for downstream listeners (FR-PAY-009). `deductCredit()` is implemented in this story and unit-tested; it is wired by Story 3.6.

6. **AC 6: Session Pack Dashboard** — Given a parent views the Player Portal, when they navigate to the session pack dashboard, then all active and exhausted packs are listed with coach name, original credit count, credits remaining, purchase date, and pack status; and an empty state ("No session packs yet — find a coach to get started") with a marketplace CTA is shown if no packs exist (UX-DR25).

## Tasks / Subtasks

- [x] Task 1: Flyway migration V30 — `booking.session_packs_purchased` table (AC: 3)
  - [x] Create `src/main/resources/db/migration/V30__booking_session_packs.sql`
  - [x] DDL: `booking.session_packs_purchased` table — see Dev Notes for full schema
  - [x] Include CHECK constraint: `credits_remaining >= 0`, `session_count > 0`, `credits_remaining <= session_count`
  - [x] Add index: `idx_spp_player_coach_status` on `(player_id, coach_id, status, purchased_at)` — supports FIFO deduction query

- [x] Task 2: Backend entity and repository (AC: 3, 5)
  - [x] Create `SessionPackPurchased.java` entity in `platform.booking.repo` — see Dev Notes for fields and types; `id` is UUID, `parentId` and `playerId` are **Long** (not UUID — see naming note)
  - [x] Create `SessionPackPurchasedRepository.java` extending `JpaRepository<SessionPackPurchased, UUID>` in `platform.booking.repo`
  - [x] Add: `findByParentIdAndPlayerId(Long parentId, Long playerId)` — for dashboard listing
  - [x] Add: `@Query @Lock(PESSIMISTIC_WRITE) findActivePacksForDeduction(Long playerId, UUID coachId)` — ORDER BY `purchasedAt ASC`, ACTIVE status, creditsRemaining > 0 — SELECT FOR UPDATE for concurrent-safe deduction
  - [x] Add: `boolean hasActiveCredits(Long playerId, UUID coachId)` — any ACTIVE pack with creditsRemaining > 0

- [x] Task 3: PaymentGateway interface and stub (AC: 3)
  - [x] Create `PaymentGateway.java` interface in `platform.booking.contract` — single method `String capturePayment(BigDecimal amount, String currency)`
  - [x] Create `StubPaymentGateway.java` in `platform.booking.service` — `@Component`, always returns `UUID.randomUUID().toString()` as payment ref; add `TODO(7.1): Replace with StripePaymentGateway`
  - [x] No conditional bean wiring needed — Epic 7 adds `platform.payment` module with real Stripe; in that story the stub will be removed and the interface moved

- [x] Task 4: `SessionPackExhaustedEvent` (AC: 5)
  - [x] Create `SessionPackExhaustedEvent.java` in `platform.booking.contract` — extends `ApplicationEvent`, fields: `UUID packId`, `Long playerId`, `UUID coachId`

- [x] Task 5: `SessionPackService` in `platform.booking.service` (AC: 3, 4, 5, 6)
  - [x] Inject: `SessionPackPurchasedRepository`, `CoachAvailabilityWindowRepository` (from marketplace — verify coach exists), `SessionPackRepository` (from marketplace — to load offered pack tier details), `PlayerProfileRepository` (from security — to verify player ownership), `PaymentGateway`, `ApplicationEventPublisher`
  - [x] Implement `getPacksForPlayer(Long parentId, Long playerId)` — verifies playerId belongs to parentId (throw `OperationNotAllowedException` if not), returns all packs sorted by status DESC (ACTIVE first), then purchasedAt DESC
  - [x] Implement `purchasePack(Long parentId, Long playerId, UUID coachId, UUID sessionPackId)` — see Dev Notes for logic: verify ownership, load pack tier, stub payment, create record; `@Transactional`
  - [x] Implement `purchaseSingleSession(Long parentId, Long playerId, UUID coachId)` — same as purchasePack but `sessionCount=1`; use `CoachProfile.perSessionPrice`; `@Transactional`
  - [x] Implement `hasCredits(Long playerId, UUID coachId)` — delegates to `repository.hasActiveCredits()`; used by Story 3.3 booking gate
  - [x] Implement `getCreditsRemaining(Long playerId, UUID coachId)` — sum of `creditsRemaining` across ACTIVE packs; used by `SessionPackTracker` via GET endpoint
  - [x] Implement `deductCredit(Long playerId, UUID coachId)` — `@Transactional`, `SELECT FOR UPDATE`, FIFO; throws `OperationNotAllowedException("booking.creditsExhausted")` if no credits; publishes `SessionPackExhaustedEvent` when pack hits 0; wired by Story 3.6

- [x] Task 6: `SessionPackResource` in `platform.booking.api` (AC: 3, 4, 6)
  - [x] `GET /api/bookings/players/{playerId}/packs` — `@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)` — optional query param `coachId` for filtering (used by SessionPackTracker); returns `List<SessionPackPurchasedResponse>`
  - [x] `POST /api/bookings/players/{playerId}/packs/purchase` — `@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)` — body: `PurchaseSessionPackRequest`; returns 201 with `SessionPackPurchasedResponse`
  - [x] Add `@Observed(name = "booking.sessionPacks")` on the class
  - [x] All DTOs as Java records in `platform.booking.contract` — see Dev Notes for schemas
  - [x] Parent ownership: extract `parentId` via `((Principal) securityUtil.getCurrentUser()).getBusinessId()` → `Long.parseLong()` pattern (identical to `ShadowAccountResource`)

- [x] Task 7: Frontend — `booking.api.js` additions (AC: 3, 4, 6)
  - [x] Add `getPlayerPacks(playerId, coachId)` — `GET /api/bookings/players/${playerId}/packs` with optional `coachId` query param
  - [x] Add `purchaseSessionPack(playerId, request)` — `POST /api/bookings/players/${playerId}/packs/purchase`

- [x] Task 8: Frontend — `booking.store.js` additions (AC: 1, 3, 4, 6)
  - [x] Add reactive state: `sessionPacks = ref([])`, `packsLoading = ref(false)`, `packsError = ref(null)`
  - [x] Add `loadPlayerPacks(playerId, coachId)` — optional coachId filter; populates `sessionPacks`
  - [x] Add `purchasePack(playerId, request)` — calls API, then reloads packs via `loadPlayerPacks`
  - [x] Add computed `creditsForCoach(coachId)` — sums `creditsRemaining` for ACTIVE packs with that coach from `sessionPacks`

- [x] Task 9: RENAME existing SessionPackTracker, CREATE real credit tracker (AC: 1, 2)
  - [x] RENAME `src/frontend/src/components/marketplace/SessionPackTracker.vue` → `SessionPackPricingDisplay.vue` (it's a price display, not a credit tracker)
  - [x] UPDATE import in `CoachPublicProfilePage.vue`: `SessionPackTracker` → `SessionPackPricingDisplay`
  - [x] CREATE `src/frontend/src/components/booking/SessionPackTracker.vue` — the REAL 4-state credit tracker (see Dev Notes for implementation details)
  - [x] `SessionPackTracker.vue` props: `{ creditsRemaining: Number, sessionCount: Number, coachId: String }` — zero-state (no pack) renders as Exhausted
  - [x] On CTA click: emits `buy-sessions` event — parent component navigates to purchase flow

- [x] Task 10: Frontend — `SessionPackPurchasePage.vue` (AC: 2, 3)
  - [x] Create `src/frontend/src/pages/parent/SessionPackPurchasePage.vue`
  - [x] Shows `SessionPackTracker` (current credits) at top of page
  - [x] Lists coach's available `sessionPacks` (from marketplace store / coach profile data) + per-session option
  - [x] On select + confirm: calls `booking.store.js:purchasePack()`; on success shows confirmation and navigates back
  - [x] Add route: `{ path: '/parent/coaches/:coachId/purchase-sessions', component: SessionPackPurchasePage, meta: { requiresAuth: true, role: 'PARENT' } }`

- [x] Task 11: Frontend — `SessionPackDashboardPage.vue` (AC: 6)
  - [x] Create `src/frontend/src/pages/parent/SessionPackDashboardPage.vue`
  - [x] On mount: calls `booking.store.js:loadPlayerPacks(playerId)` (no coachId filter — all packs)
  - [x] Lists packs: coach name, sessionCount, creditsRemaining, purchasedAt, status chip
  - [x] Empty state: "No session packs yet — find a coach to get started" + marketplace CTA (`/marketplace`)
  - [x] Add route: `{ path: '/parent/players/:playerId/packs', component: SessionPackDashboardPage, meta: { requiresAuth: true, role: 'PARENT' } }`

- [x] Task 12: Frontend — Wire SessionPackTracker into CoachPublicProfilePage (AC: 1)
  - [x] Import new `SessionPackTracker` from `src/components/booking/SessionPackTracker.vue` (NOT the renamed pricing display)
  - [x] On mount: `booking.store.loadPlayerPacks(playerId, coachId)` — only if parent is logged in
  - [x] Pass computed `creditsForCoach(coachId)` and `sessionCount` from store to `SessionPackTracker` component
  - [x] Show tracker before the "Book a session" CTA — cannot scroll past it without seeing it
  - [x] On `buy-sessions` event: navigate to `SessionPackPurchasePage`

- [x] Task 13: i18n keys (AC: 1, 2, 6)
  - [x] Add under `booking.packs.*` namespace in `src/frontend/src/i18n/en/index.js`:
    - `booking.packs.healthyStatus`: "{{remaining}} sessions remaining"
    - `booking.packs.warningStatus`: "Only {{remaining}} sessions left"
    - `booking.packs.criticalStatus`: "{{remaining}} session(s) left — buy more"
    - `booking.packs.exhaustedStatus`: "No sessions remaining"
    - `booking.packs.buySessions`: "Buy sessions"
    - `booking.packs.buyMoreSessions`: "Buy more sessions"
    - `booking.packs.purchaseTitle`: "Buy sessions with {{coachName}}"
    - `booking.packs.perSession`: "Per session"
    - `booking.packs.sessionsBundle`: "{{count}}-session bundle"
    - `booking.packs.pricePerSession`: "{{price}} per session"
    - `booking.packs.confirmPurchase`: "Confirm purchase"
    - `booking.packs.dashboardTitle`: "Your session packs"
    - `booking.packs.emptyState`: "No session packs yet — find a coach to get started"
    - `booking.packs.emptyStateCta`: "Find a coach"
    - `booking.packs.activeStatus`: "Active"
    - `booking.packs.exhaustedLabel`: "Exhausted"

- [x] Task 14: Unit tests — `SessionPackServiceTest` (AC: 5)
  - [x] `@ExtendWith(MockitoExtension.class)` — NO `@SpringBootTest`
  - [x] Mock: `SessionPackPurchasedRepository`, `PlayerProfileRepository`, `ApplicationEventPublisher`, `PaymentGateway`
  - [x] Test: `deductCredit_singleActivePack_decrementsCredits`
  - [x] Test: `deductCredit_fifo_oldestPackConsumedFirst` — two packs, older has more credits
  - [x] Test: `deductCredit_packExhausted_statusChangesToExhaustedAndEventPublished`
  - [x] Test: `deductCredit_noActiveCredits_throwsOperationNotAllowedException`
  - [x] Test: `hasCredits_activePackExists_returnsTrue`
  - [x] Test: `hasCredits_allPacksExhausted_returnsFalse`
  - [x] Test: `purchasePack_validRequest_createsRecordWithCorrectCredits`
  - [x] Use Instancio for `SessionPackPurchased` test data; AssertJ for assertions

- [x] Task 15: Integration tests — `SessionPackResourceIT` (AC: 3, 6)
  - [x] Follow exact annotation pattern from `AvailabilityResourceIT.java` (same 5 annotations)
  - [x] Use distinct IDs: `PARENT_ID = 9400000001L`, `PLAYER_ID = 9400000002L`, fresh `COACH_PROFILE_ID = UUID.randomUUID()` per test setup
  - [x] Test: `getPlayerPacks_noPacks_returnsEmptyList`
  - [x] Test: `purchasePack_validRequest_returns201AndPersistsRecord`
  - [x] Test: `getPlayerPacks_afterPurchase_returnsPackWithCorrectCredits`
  - [x] Test: `purchasePack_playerNotOwnedByParent_returns403`
  - [x] Test: `purchasePack_coachNotFound_returns404`
  - [x] Use `jdbcTemplate.update("DELETE FROM booking.session_packs_purchased WHERE parent_id = ?", PARENT_ID)` in teardown — never string-concatenate IDs

## Dev Notes

### ⚠️ CRITICAL: parentId and playerId Are LONG, Not UUID

The epics spec says "parentId UUID, playerId UUID" but this is **wrong** based on the actual codebase:
- `PlayerProfile.id` is `Long` (TSID from `BaseEntity`) — schema table: `main.player_profiles.id BIGINT`
- Parent user IDs are `Long` (TSID) from `main.user.id BIGINT`
- Only `CoachProfile.id` is UUID (explicitly `@GeneratedValue(strategy = GenerationType.UUID)`)

Use `Long parentId` and `Long playerId` in the entity and all service signatures. The `coachId` remains UUID.

Ownership check pattern (identical to `ShadowAccountResource.java`):
```java
Long parentId = Long.parseLong(((Principal) securityUtil.getCurrentUser()).getBusinessId());
```

### ⚠️ CRITICAL: SessionPackTracker.vue Naming Conflict

The EXISTING `src/frontend/src/components/marketplace/SessionPackTracker.vue` is a **pricing display** component (shows offered pack tiers for purchase on the coach profile page). It is NOT a credit tracker. Story 3.2 must:

1. **RENAME** existing file to `SessionPackPricingDisplay.vue` in the same directory
2. **UPDATE** the import in `CoachPublicProfilePage.vue` (line 222: `import SessionPackTracker from '...'` → `import SessionPackPricingDisplay from '...'`) AND the component reference in the template (line 162)
3. **CREATE** a new `src/frontend/src/components/booking/SessionPackTracker.vue` that is the REAL 4-state credit tracker

Failing to rename will cause the dev agent to use the wrong component.

### ⚠️ CRITICAL: Endpoint Naming — Use /api/bookings/ (Plural)

The epics dev notes say `/api/booking/players/{playerId}/packs` (singular). This violates the architecture rule: **"Never: `/api/booking/` (singular)"**. Story 3.1 confirmed the plural convention in `booking.api.js`:
```
/api/bookings/coaches/{coachId}/availability
```

Use:
- `GET /api/bookings/players/{playerId}/packs` (optional `?coachId=` query param)
- `POST /api/bookings/players/{playerId}/packs/purchase`

### Flyway Migration V30

Next available migration is **V30** (V29 was `booking_module_init.sql` from Story 3.1).

```sql
-- V30__booking_session_packs.sql

CREATE TABLE booking.session_packs_purchased (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id         BIGINT       NOT NULL,
    player_id         BIGINT       NOT NULL,
    coach_id          UUID         NOT NULL,
    session_count     INT          NOT NULL,
    credits_remaining INT          NOT NULL,
    purchased_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT chk_spp_status CHECK (status IN ('ACTIVE', 'EXHAUSTED', 'EXPIRED')),
    CONSTRAINT chk_spp_credits_non_negative CHECK (credits_remaining >= 0),
    CONSTRAINT chk_spp_session_count_positive CHECK (session_count > 0),
    CONSTRAINT chk_spp_credits_le_count CHECK (credits_remaining <= session_count)
);

CREATE INDEX idx_spp_player_coach_status ON booking.session_packs_purchased (player_id, coach_id, status, purchased_at);
CREATE INDEX idx_spp_parent_player ON booking.session_packs_purchased (parent_id, player_id);
```

Do **NOT** create a PostgreSQL enum type — use `VARCHAR(20)` with CHECK constraint (consistent with existing patterns, avoids Flyway enum migration complexity).

### SessionPackPurchased Entity

```java
// platform.booking.repo.SessionPackPurchased
@Entity
@Table(schema = "booking", name = "session_packs_purchased")
@Getter @Setter @NoArgsConstructor
public class SessionPackPurchased {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "parent_id", nullable = false)
    private Long parentId;          // Long TSID, NOT UUID

    @Column(name = "player_id", nullable = false)
    private Long playerId;          // Long TSID, NOT UUID

    @Column(name = "coach_id", nullable = false)
    private UUID coachId;           // CoachProfile UUID

    @Column(name = "session_count", nullable = false)
    private int sessionCount;

    @Column(name = "credits_remaining", nullable = false)
    private int creditsRemaining;

    @Column(name = "purchased_at", nullable = false, updatable = false)
    private Instant purchasedAt;

    @Column(nullable = false, length = 20)
    private String status;          // "ACTIVE" | "EXHAUSTED" | "EXPIRED"

    @PrePersist
    void onCreate() {
        if (this.purchasedAt == null) this.purchasedAt = Instant.now();
        if (this.status == null) this.status = "ACTIVE";
    }
}
```

Define status constants in the service class to avoid magic strings:
```java
private static final String STATUS_ACTIVE    = "ACTIVE";
private static final String STATUS_EXHAUSTED = "EXHAUSTED";
```

### SessionPackPurchasedRepository

```java
public interface SessionPackPurchasedRepository extends JpaRepository<SessionPackPurchased, UUID> {

    List<SessionPackPurchased> findByParentIdAndPlayerId(Long parentId, Long playerId);

    // FIFO deduction — SELECT FOR UPDATE
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT s FROM SessionPackPurchased s
        WHERE s.playerId = :playerId
          AND s.coachId = :coachId
          AND s.status = 'ACTIVE'
          AND s.creditsRemaining > 0
        ORDER BY s.purchasedAt ASC
        """)
    List<SessionPackPurchased> findActivePacksForDeduction(
        @Param("playerId") Long playerId,
        @Param("coachId") UUID coachId
    );

    @Query("""
        SELECT COALESCE(SUM(s.creditsRemaining), 0) FROM SessionPackPurchased s
        WHERE s.playerId = :playerId AND s.coachId = :coachId AND s.status = 'ACTIVE'
        """)
    int sumActiveCredits(@Param("playerId") Long playerId, @Param("coachId") UUID coachId);

    @Query("""
        SELECT COUNT(s) > 0 FROM SessionPackPurchased s
        WHERE s.playerId = :playerId AND s.coachId = :coachId
          AND s.status = 'ACTIVE' AND s.creditsRemaining > 0
        """)
    boolean hasActiveCredits(@Param("playerId") Long playerId, @Param("coachId") UUID coachId);
}
```

### PaymentGateway Interface and Stub

```java
// platform.booking.contract.PaymentGateway
public interface PaymentGateway {
    /** Returns a payment reference ID. */
    String capturePayment(BigDecimal amount, String currency);
}

// platform.booking.service.StubPaymentGateway
@Component
public class StubPaymentGateway implements PaymentGateway {
    // TODO(7.1): Remove and replace with StripePaymentGateway from platform.payment
    @Override
    public String capturePayment(BigDecimal amount, String currency) {
        return UUID.randomUUID().toString();
    }
}
```

### SessionPackExhaustedEvent

```java
// platform.booking.contract.SessionPackExhaustedEvent
public class SessionPackExhaustedEvent extends ApplicationEvent {
    private final UUID packId;
    private final Long playerId;
    private final UUID coachId;

    public SessionPackExhaustedEvent(Object source, UUID packId, Long playerId, UUID coachId) {
        super(source);
        this.packId = packId;
        this.playerId = playerId;
        this.coachId = coachId;
    }

    public UUID getPackId() { return packId; }
    public Long getPlayerId() { return playerId; }
    public UUID getCoachId() { return coachId; }
}
```

### SessionPackService: purchasePack Logic

```java
@Transactional
public SessionPackPurchasedResponse purchasePack(Long parentId, Long playerId, UUID coachId, UUID sessionPackId) {
    // 1. Verify player ownership
    PlayerProfile player = playerProfileRepository.findById(playerId)
        .orElseThrow(() -> new ResourceNotFoundException("Player not found"));
    if (!Objects.equals(player.getParentId(), parentId)) {
        throw new OperationNotAllowedException("Parent does not own this player profile");
    }

    // 2. Load offered pack tier from marketplace
    SessionPack offered = sessionPackRepository.findById(sessionPackId)
        .orElseThrow(() -> new ResourceNotFoundException("Session pack not found"));
    if (!Objects.equals(offered.getCoachId(), coachId)) {
        throw new OperationNotAllowedException("Pack does not belong to this coach");
    }

    // 3. Stub payment capture (Epic 7 replaces with Stripe)
    paymentGateway.capturePayment(offered.getTotalPrice(), "EUR");

    // 4. Create purchased pack record
    SessionPackPurchased pack = new SessionPackPurchased();
    pack.setParentId(parentId);
    pack.setPlayerId(playerId);
    pack.setCoachId(coachId);
    pack.setSessionCount(offered.getSessionCount());
    pack.setCreditsRemaining(offered.getSessionCount());
    return toResponse(repository.save(pack));
}
```

For `purchaseSingleSession`: similar logic but load `perSessionPrice` from `CoachProfile` and set `sessionCount = 1`.

### SessionPackService: deductCredit Logic

```java
@Transactional
public void deductCredit(Long playerId, UUID coachId) {
    List<SessionPackPurchased> packs = repository.findActivePacksForDeduction(playerId, coachId);
    if (packs.isEmpty()) {
        throw new OperationNotAllowedException("booking.creditsExhausted");
    }
    SessionPackPurchased pack = packs.get(0); // oldest first (FIFO)
    pack.setCreditsRemaining(pack.getCreditsRemaining() - 1);
    if (pack.getCreditsRemaining() == 0) {
        pack.setStatus(STATUS_EXHAUSTED);
        repository.save(pack);
        eventPublisher.publishEvent(
            new SessionPackExhaustedEvent(this, pack.getId(), playerId, coachId)
        );
    } else {
        repository.save(pack);
    }
}
```

### PurchaseSessionPackRequest DTO

```java
// platform.booking.contract.PurchaseSessionPackRequest
public record PurchaseSessionPackRequest(
    @NotNull UUID coachId,
    UUID sessionPackId     // null = per-session single purchase
) {}
```

When `sessionPackId` is null, service calls `purchaseSingleSession`; otherwise calls `purchasePack`.

### SessionPackPurchasedResponse DTO

```java
// platform.booking.contract.SessionPackPurchasedResponse
public record SessionPackPurchasedResponse(
    UUID id,
    UUID coachId,
    String coachDisplayName,    // loaded via CoachProfileRepository
    int sessionCount,
    int creditsRemaining,
    Instant purchasedAt,
    String status
) {}
```

### GET /api/bookings/players/{playerId}/packs — coachId Filter

The optional `?coachId=` query param lets the `SessionPackTracker` load credits for a specific coach efficiently:
```java
@GetMapping("/players/{playerId}/packs")
public ResponseEntity<List<SessionPackPurchasedResponse>> getPlayerPacks(
    @PathVariable Long playerId,
    @RequestParam(required = false) UUID coachId
) {
    Long parentId = currentParentId();
    return ResponseEntity.ok(sessionPackService.getPacksForPlayer(parentId, playerId, coachId));
}
```

When `coachId` is null, returns all packs (for dashboard). When provided, filters to that coach's packs only (for tracker).

### Frontend: Real SessionPackTracker.vue (Credit Tracker)

Create at `src/frontend/src/components/booking/SessionPackTracker.vue`:

```vue
<template>
  <div class="session-pack-tracker" :class="stateClass">
    <div class="tracker-credits">{{ creditsLabel }}</div>
    <q-linear-progress :value="progressRatio" :color="progressColor" />
    <div v-if="showCta" class="tracker-cta">
      <q-btn flat dense :label="ctaLabel" @click="$emit('buy-sessions')" />
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps({
  creditsRemaining: { type: Number, default: 0 },
  sessionCount: { type: Number, default: 0 },    // 0 = no pack exists
})

const emit = defineEmits(['buy-sessions'])
const { t } = useI18n()

const progressRatio = computed(() =>
  props.sessionCount > 0 ? props.creditsRemaining / props.sessionCount : 0
)

// 4 states: healthy >30%, warning <30%, critical 1-2 left, exhausted 0
const state = computed(() => {
  if (props.creditsRemaining === 0 || props.sessionCount === 0) return 'exhausted'
  if (props.creditsRemaining <= 2) return 'critical'
  if (props.creditsRemaining / props.sessionCount < 0.3) return 'warning'
  return 'healthy'
})

const stateClass = computed(() => `tracker--${state.value}`)
const showCta = computed(() => state.value === 'critical' || state.value === 'exhausted')
const ctaLabel = computed(() =>
  state.value === 'exhausted' ? t('booking.packs.buySessions') : t('booking.packs.buyMoreSessions')
)
// Use CSS token colours — no hardcoded hex
const progressColor = computed(() =>
  state.value === 'healthy' ? 'var(--accent-primary)' :
  state.value === 'exhausted' ? 'var(--color-error)' : 'var(--color-warning)'
)
const creditsLabel = computed(() => {
  if (state.value === 'exhausted') return t('booking.packs.exhaustedStatus')
  return t(`booking.packs.${state.value}Status`, { remaining: props.creditsRemaining })
})
</script>
```

Note: `progressColor` as a CSS custom property string won't bind directly to Quasar's `q-linear-progress :color` — use a class binding or inline style instead. All colour tokens via CSS variables; no hardcoded hex (UX spec constraint).

### Cross-Module Dependencies

`SessionPackService` legitimately uses across `platform.*` modules (monolith-stage permitted):
- `platform.marketplace.repo.SessionPackRepository` — reads coach's offered pack tiers
- `platform.marketplace.repo.CoachProfileRepository` — resolves coachId, loads per-session price, loads coach display name for response
- `platform.security.repo.PlayerProfileRepository` — verifies player-to-parent ownership

These cross-module imports are the same pattern established by Story 3.1 (`AvailabilityService` importing `CoachAvailabilityWindowRepository` from marketplace).

### Ownership Verification in Service

```java
private void verifyPlayerOwnership(Long parentId, Long playerId) {
    PlayerProfile player = playerProfileRepository.findById(playerId)
        .orElseThrow(() -> new ResourceNotFoundException("Player not found"));
    if (!Objects.equals(player.getParentId(), parentId)) {
        throw new OperationNotAllowedException("Forbidden: player profile not owned by this parent");
    }
}
```

Call this at the start of `getPacksForPlayer`, `purchasePack`, and `purchaseSingleSession`.

### hasCredits() — Story 3.3 Dependency

`SessionPackService.hasCredits(Long playerId, UUID coachId)` is called by Story 3.3's `BookingService` before accepting a booking request. This method must be `public` and on `SessionPackService`. Story 3.3 will inject `SessionPackService` from `platform.booking`.

### IT Test Setup Pattern

Identical annotations to `AvailabilityResourceIT.java`:
```java
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", ...})
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
public class SessionPackResourceIT {
    private static final long PARENT_ID = 9400000001L;
    private static final long PLAYER_ID = 9400000002L;
    private static final String PARENT_EMAIL = "parent.packs@skillars-test.com";
    private UUID coachProfileId;  // created fresh in @BeforeEach
```

In `@BeforeEach`: insert parent user, player profile (linked to parent), coach user + coach profile. In `@AfterEach`: delete `booking.session_packs_purchased WHERE parent_id = ?`, then clean up coach profile, player profile, users.

IT test for `purchasePack_playerNotOwnedByParent_returns403`: create a second parent user (`9400000003L`), try to purchase packs for `PLAYER_ID` — must return 403.

### Previous Story Learnings (from Story 3.1)

- `HttpTestClient.makeHttpRequest()` is the actual method name — not `client.post()` or `.exchange()`
- Use distinct `PARENT_ID` values per IT class — `9300000001L` and `9300000002L` are taken by `AvailabilityResourceIT`; start at `9400000001L`
- Never `DELETE FROM table` with no WHERE predicate in teardown
- Cross-module repo imports are permitted in the monolith — no need to create duplicate entities
- Exception ownership failures → `OperationNotAllowedException` → 403; missing resources → `ResourceNotFoundException` → 404. Never use `ResponseStatusException` (the generic `ApiAdvice` Throwable handler swallows it as 500)
- `@PreAuthorize` annotation is mandatory on every endpoint — no unprotected endpoints
- Vue Composition API `<script setup>` for all components; `async/await` for all async operations; `AbortController` for debounced API calls with `onUnmounted` cleanup

### Project Structure Notes

**New backend files:**
- `src/main/java/com/softropic/skillars/platform/booking/repo/SessionPackPurchased.java`
- `src/main/java/com/softropic/skillars/platform/booking/repo/SessionPackPurchasedRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/SessionPackService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/StubPaymentGateway.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/SessionPackResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/PaymentGateway.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/SessionPackExhaustedEvent.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/PurchaseSessionPackRequest.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/SessionPackPurchasedResponse.java`
- `src/main/resources/db/migration/V30__booking_session_packs.sql`

**Modified backend files:**
- None — existing booking files unchanged

**New frontend files:**
- `src/frontend/src/components/booking/SessionPackTracker.vue` ← REAL credit tracker
- `src/frontend/src/pages/parent/SessionPackPurchasePage.vue`
- `src/frontend/src/pages/parent/SessionPackDashboardPage.vue`

**Renamed frontend files:**
- `src/frontend/src/components/marketplace/SessionPackTracker.vue` → `SessionPackPricingDisplay.vue`

**Modified frontend files:**
- `src/frontend/src/api/booking.api.js` — add `getPlayerPacks`, `purchaseSessionPack`
- `src/frontend/src/stores/booking.store.js` — add session packs state + actions
- `src/frontend/src/i18n/en/index.js` — add `booking.packs.*` keys
- `src/frontend/src/router/routes.js` — add two new parent routes
- `src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue` — update import + add tracker + buy-sessions handler

**New test files:**
- `src/test/java/com/softropic/skillars/platform/booking/service/SessionPackServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/SessionPackResourceIT.java`

### References

- Epic source: `_bmad-output/planning-artifacts/skillars-epics.md` lines 974–1015 (Story 3.2 full text + dev notes)
- Architecture: `_bmad-output/planning-artifacts/architecture.md` — endpoint naming (lines 315–329), credit tracking decision (lines 112–123), module map (lines 55–70), DB naming (lines 351–366)
- UX spec: `_bmad-output/planning-artifacts/ux-design-specification.md` — SessionPackTracker component (lines 702–705), UX-DR12 (line 234)
- Previous story learnings: `_bmad-output/implementation-artifacts/skillars-3-1-coach-availability-management.md` — IT test patterns, exception handling, cross-module repo usage, Vue patterns
- Existing SessionPackTracker (pricing display — to be renamed): `src/frontend/src/components/marketplace/SessionPackTracker.vue`
- CoachPublicProfilePage import to update: `src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue` line 222
- Cross-module repo to inject (marketplace): `src/main/java/com/softropic/skillars/platform/marketplace/repo/SessionPackRepository.java`
- Cross-module repo to inject (marketplace): `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java`
- Cross-module repo to inject (security): `src/main/java/com/softropic/skillars/platform/security/repo/PlayerProfileRepository.java`
- Parent role annotation: `SecurityConstants.HAS_PARENT_ROLE` (confirmed in `ShadowAccountResource.java` lines 34, 44)
- IT test pattern reference: `src/test/java/com/softropic/skillars/platform/booking/api/AvailabilityResourceIT.java`

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

N/A

### Completion Notes List

- Implemented `SessionPackPurchased` entity with `@PrePersist` defaulting status to "ACTIVE" and purchasedAt to `Instant.now()`.
- `SessionPackService` uses `CoachPricingRepository` for `purchaseSingleSession` (perSessionPrice lives in `coach_pricing` table, not `CoachProfile` entity — the Dev Notes reference to `CoachProfile.perSessionPrice` was incorrect based on actual schema).
- `CoachAvailabilityWindowRepository` injection specified in the story task list was replaced with `CoachProfileRepository` (the correct dependency for verifying coach exists and loading display name).
- Existing `SessionPackTracker.vue` (pricing display) was renamed to `SessionPackPricingDisplay.vue`; a new `SessionPackTracker.vue` built as a CSS-variable-based progress bar (not Quasar `q-linear-progress`) to bind color via CSS custom properties correctly.
- The "Book a session" CTA on `CoachPublicProfilePage` is disabled when `hasCreditsForCoach` is false (AC 4 frontend enforcement).
- Unit test mock simulates `@PrePersist` behavior since JPA lifecycle hooks don't fire in Mockito context.
- All 7 unit tests pass; all 5 integration tests pass; full suite BUILD SUCCESS.

### File List

**New backend files:**
- `src/main/resources/db/migration/V30__booking_session_packs.sql`
- `src/main/java/com/softropic/skillars/platform/booking/repo/SessionPackPurchased.java`
- `src/main/java/com/softropic/skillars/platform/booking/repo/SessionPackPurchasedRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/SessionPackService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/StubPaymentGateway.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/SessionPackResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/PaymentGateway.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/SessionPackExhaustedEvent.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/PurchaseSessionPackRequest.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/SessionPackPurchasedResponse.java`

**New frontend files:**
- `src/frontend/src/components/booking/SessionPackTracker.vue`
- `src/frontend/src/pages/parent/SessionPackPurchasePage.vue`
- `src/frontend/src/pages/parent/SessionPackDashboardPage.vue`

**Renamed frontend files:**
- `src/frontend/src/components/marketplace/SessionPackTracker.vue` → `src/frontend/src/components/marketplace/SessionPackPricingDisplay.vue`

**Modified frontend files:**
- `src/frontend/src/api/booking.api.js`
- `src/frontend/src/stores/booking.store.js`
- `src/frontend/src/i18n/en/index.js`
- `src/frontend/src/router/routes.js`
- `src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue`

**New test files:**
- `src/test/java/com/softropic/skillars/platform/booking/service/SessionPackServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/SessionPackResourceIT.java`

### Change Log

- 2026-06-13: Implemented Story 3.2 — session pack purchase flow, credit tracking, FIFO deduction, booking block enforcement, session pack dashboard. 7 unit tests + 5 integration tests added. Full regression suite passes.

### Review Findings

_Code review performed 2026-06-13. 1 decision-needed, 12 patch, 9 deferred, 6 dismissed._

**Decision Needed:**
- [x] [Review][Decision] `sessionPacks` store global replacement vs. merge — resolved: always load unfiltered, filter client-side (Option A) — `loadPlayerPacks` replaces the entire `sessionPacks` array on every call. A filtered call (with `coachId`) overwrites the global list, leaving components on other pages reading only that coach's packs. After purchase, reload is also filtered by `coachId`. Design choice required: (A) always load unfiltered and filter client-side, (B) maintain separate `allPacks` and `coachPacks` refs, or (C) upsert/merge by coachId.

**Patches:**
- [x] [Review][Patch] Currency hardcoded "EUR" in `purchasePack()` — ignores coach's actual currency; `purchaseSingleSession` correctly uses `pricing.getCurrency()` but `purchasePack` passes literal `"EUR"` [SessionPackService.java:purchasePack]
- [x] [Review][Patch] MapStruct `SessionPackMapper` `@Mapping` source ambiguity — `source = "coachDisplayName"` is ambiguous when `SessionPackPurchased` has no such field; MapStruct may not auto-map remaining fields or may fail at compile time [SessionPackMapper.java]
- [x] [Review][Patch] `playerStore.activePlayerId` not initialized on coach profile page — `handleBuySessions` navigates to `?playerId=null` when no active player is set [CoachPublicProfilePage.vue:handleBuySessions]
- [x] [Review][Patch] Booking CTA silently disabled with no loading state — `:disable="authStore.isParent && !hasCreditsForCoach"` is `true` while packs are loading or if `activePlayerId` is null, with no visual feedback [CoachPublicProfilePage.vue]
- [x] [Review][Patch] `meta.role: 'PARENT'` never checked by router `beforeEach` guard — non-parent authenticated users can navigate to parent routes; only backend `@PreAuthorize` blocks them [routes.js + src/frontend/src/router/index.js]
- [x] [Review][Patch] Hardcoded untranslated strings in 4 locations: (1) `sessions remaining` in `SessionPackDashboardPage.vue`, (2) `` Save ${formatPrice(saved)} `` in `SessionPackPricingDisplay.vue`, (3) `` ${pack.sessionCount} sessions `` fallback in `SessionPackPricingDisplay.vue`, (4) `'Purchase failed. Please try again.'` in `SessionPackPurchasePage.vue`
- [x] [Review][Patch] `savings()` span always rendered even when savings is `""` — produces a dangling flex gap [SessionPackPricingDisplay.vue:savings]
- [x] [Review][Patch] `progressPercent` has no `Math.min(100, ...)` clamp — can render > 100% if `creditsRemaining > sessionCount` [SessionPackTracker.vue:progressPercent]
- [x] [Review][Patch] `:coach-id` prop passed to `SessionPackTracker` but not declared in its `defineProps` — silently becomes an HTML attribute [SessionPackPurchasePage.vue]
- [x] [Review][Patch] Test name misleading: `purchasePack_coachNotFound_returns404` actually tests unknown `sessionPackId`, not unknown coach [SessionPackResourceIT.java]
- [x] [Review][Patch] `route.params.playerId` dead fallback — route has no `:playerId` param; always `undefined`; only `route.query.playerId` is used [SessionPackPurchasePage.vue]
- [x] [Review][Patch] `businessId` leaked in `InsufficientAuthenticationException` message — internal ID exposed in error response [SessionPackResource.java:currentParentId]

**Deferred:**
- [x] [Review][Defer] Payment charged before record persisted — `capturePayment` called before `repository.save`; `@Transactional` does not roll back external gateway call [SessionPackService.java] — deferred, stub has no real money; TODO(7.1) already flags; Epic 7 scope
- [x] [Review][Defer] Payment reference (transaction ID) never stored — return value of `capturePayment` discarded; no `payment_reference` column in schema — deferred, Epic 7 scope
- [x] [Review][Defer] `deductCredit()` has no `parentId` re-authorization — acknowledged by TODO(3.3); Story 3.3 must supply a verified `playerId` [SessionPackService.java:deductCredit] — deferred, Story 3.3 scope
- [x] [Review][Defer] No concurrency integration test for `deductCredit` / pessimistic lock — complex to write reliably [SessionPackResourceIT.java] — deferred, pre-existing; dedicated testing story
- [x] [Review][Defer] `@Sql(SecurityIT.SEC_DATA_SQL_PATH)` ordering dependency undocumented — pre-existing IT pattern from Story 3.1 [SessionPackResourceIT.java] — deferred, pre-existing
- [x] [Review][Defer] `tearDown` deletes `main.sec` / `main.refresh_tokens` without `WHERE` — pre-existing pattern matching other IT classes [SessionPackResourceIT.java] — deferred, pre-existing
- [x] [Review][Defer] `PlayerProfileRepository.findById` used instead of `findByIdAndParentId` — pattern divergence from repo's isolation contract [SessionPackService.java:verifyPlayerOwnership] — deferred, not a correctness bug
- [x] [Review][Defer] No DB-level state machine constraints — ACTIVE+credits=0 or EXHAUSTED+credits>0 not prevented at DB layer [V30__booking_session_packs.sql] — deferred, defense-in-depth
- [x] [Review][Defer] In-memory `coachId` filter in `getPacksForPlayer` — loads all packs then Java stream filters [SessionPackService.java:getPacksForPlayer] — deferred, performance optimization
