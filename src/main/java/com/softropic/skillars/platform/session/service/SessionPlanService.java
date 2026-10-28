package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.booking.contract.BookingSnapshot;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.contract.BookingStatusChangedEvent;
import com.softropic.skillars.platform.booking.service.BookingQueryService;
import com.softropic.skillars.platform.booking.service.BookingStateMachine;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.session.contract.CreateSessionPlanRequest;
import com.softropic.skillars.platform.session.repo.SessionStatus;
import com.softropic.skillars.platform.session.contract.DrillMetadata;
import com.softropic.skillars.platform.session.contract.DrillResponse;
import com.softropic.skillars.platform.session.contract.SessionBlockData;
import com.softropic.skillars.platform.session.contract.SessionBlockDrillResponse;
import com.softropic.skillars.platform.session.contract.SessionBlockRequest;
import com.softropic.skillars.platform.session.contract.SessionBlockResponse;
import com.softropic.skillars.platform.session.contract.SessionDnaScore;
import com.softropic.skillars.platform.session.contract.SessionDrillRef;
import com.softropic.skillars.platform.session.contract.SessionErrorCode;
import com.softropic.skillars.platform.session.contract.SessionPlanResponse;
import com.softropic.skillars.platform.session.contract.UpdateSessionPlanRequest;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.Session;
import com.softropic.skillars.platform.session.repo.SessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class SessionPlanService {

    private final SessionRepository sessionRepository;
    private final DrillRepository drillRepository;
    private final BookingQueryService bookingQueryService;
    private final CoachProfileService coachProfileService;
    private final DrillLibraryService drillLibraryService;
    private final SessionDnaCalculator dnaCalculator;
    private final EquipmentListService equipmentListService;
    private final BookingStateMachine bookingStateMachine;
    private final PessimisticLockRetryer lockRetryer;
    private final EntityManager entityManager;

    public SessionPlanResponse createSession(CreateSessionPlanRequest req, Long coachUserId) {
        drillLibraryService.checkSessionBuilderGate(coachUserId);
        UUID coachId = resolveCoachId(coachUserId);

        if (sessionRepository.existsByBookingId(req.bookingId())) {
            throw new OperationNotAllowedException(
                "Session plan already exists for this booking",
                SessionErrorCode.SESSION_ALREADY_EXISTS);
        }

        BookingSnapshot booking = bookingQueryService.getBookingSnapshot(req.bookingId())
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found", "booking"));

        if (!booking.coachId().equals(coachId)) {
            throw new OperationNotAllowedException(
                "Booking is not owned by this coach",
                SessionErrorCode.SESSION_BOOKING_NOT_OWNED);
        }

        if (!isBookingPlannable(booking.status())) {
            throw new OperationNotAllowedException(
                "Session plan can only be created for a confirmed booking",
                SessionErrorCode.SESSION_BOOKING_NOT_OWNED);
        }

        List<SessionBlockData> blocks = mapBlocksFromRequest(req.blocks());
        List<Drill> drills = fetchUsableDrillsOrThrow(blocks, coachId);
        Map<UUID, DrillMetadata> metaMap = toMetaMap(drills);
        List<DrillMetadata> allMeta = expandMetaForDna(blocks, metaMap);
        SessionDnaScore dna = dnaCalculator.calculate(allMeta);
        List<String> equipment = equipmentListService.generate(allMeta);

        Session session = new Session();
        session.setBookingId(req.bookingId());
        session.setCoachId(coachId);
        session.setPlayerId(booking.playerId());
        session.setBlocks(blocks);
        session.setSessionDna(dna);
        session.setEquipmentList(equipment);
        session.setDevelopmentFocus(req.developmentFocus());
        session.setStatus(SessionStatus.DRAFT);

        Session saved;
        try {
            saved = sessionRepository.save(session);
        } catch (DataIntegrityViolationException ex) {
            throw new OperationNotAllowedException(
                "Session plan already exists for this booking",
                SessionErrorCode.SESSION_ALREADY_EXISTS);
        }
        return buildResponse(saved, metaMap, toResponseMap(drills));
    }

    public SessionPlanResponse updateSession(UUID sessionId, UpdateSessionPlanRequest req, Long coachUserId) {
        drillLibraryService.checkSessionBuilderGate(coachUserId);
        UUID coachId = resolveCoachId(coachUserId);

        Session session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResourceNotFoundException("Session not found", "session"));

        if (!session.getCoachId().equals(coachId)) {
            throw new OperationNotAllowedException(
                "Session is not owned by this coach",
                SessionErrorCode.SESSION_BOOKING_NOT_OWNED);
        }

        requireOpen(session);

        // fix/content-consistency-and-lost-updates: Session had no @Version (C5 added one) and the save() below
        // rewrites every column, status included. Without the row lock, handleBookingCompleted
        // committing COMPLETED after the read above was overwritten with this request's stale
        // DRAFT/SAVED. Lock AFTER the ownership check (an unauthorised caller must not be able to pin
        // the row) and refresh: findByIdForUpdate returns the instance already managed from findById
        // without reloading it. Then re-check the status on the fresh, locked state. The completion
        // and cancellation listeners use atomic conditional UPDATEs that take the same row lock.
        lockRetryer.withBoundedRetry("SessionPlanService.updateSession", () -> {
            Session locked = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found", "session"));
            entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE);
            return locked;
        });
        requireOpen(session);

        BookingSnapshot booking = bookingQueryService.getBookingSnapshot(session.getBookingId())
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found", "booking"));
        String bookingStatus = booking.status();
        if (bookingStatus == null) {
            throw new OperationNotAllowedException(
                "Booking status is invalid; session plan can no longer be modified",
                SessionErrorCode.SESSION_PLAN_LOCKED);
        }
        if (bookingStateMachine.isTerminal(BookingStatus.valueOf(bookingStatus))) {
            throw new OperationNotAllowedException(
                "Booking is no longer active; session plan can no longer be modified",
                SessionErrorCode.SESSION_PLAN_LOCKED);
        }

        List<SessionBlockData> blocks = mapBlocksFromRequest(req.blocks());
        List<Drill> drills = fetchUsableDrillsOrThrow(blocks, coachId);
        Map<UUID, DrillMetadata> metaMap = toMetaMap(drills);
        List<DrillMetadata> allMeta = expandMetaForDna(blocks, metaMap);
        SessionDnaScore dna = dnaCalculator.calculate(allMeta);
        List<String> equipment = equipmentListService.generate(allMeta);

        session.setBlocks(blocks);
        session.setSessionDna(dna);
        session.setEquipmentList(equipment);
        session.setDevelopmentFocus(req.developmentFocus());
        session.setStatus(req.status() != null ? req.status() : session.getStatus());

        Session saved = sessionRepository.save(session);
        return buildResponse(saved, metaMap, toResponseMap(drills));
    }

    private static void requireOpen(Session session) {
        if (SessionStatus.COMPLETED.equals(session.getStatus()) || SessionStatus.CANCELLED.equals(session.getStatus())) {
            throw new OperationNotAllowedException(
                "Completed sessions cannot be modified",
                SessionErrorCode.SESSION_PLAN_LOCKED);
        }
    }

    /**
     * Atomic conditional UPDATE, not load-then-save (fix/content-consistency-and-lost-updates): the
     * old read-modify-write could interleave with {@code updateSession}'s own full-row save. The
     * UPDATE takes the row lock, so it serialises with {@code updateSession}'s locked section.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("taskExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleBookingCompleted(BookingCompletedEvent event) {
        int updated = sessionRepository.markCompletedByBookingId(event.getBookingId(), Instant.now());
        if (updated == 0) {
            log.debug("No open session plan for completed booking {} — nothing to transition", event.getBookingId());
        }
    }

    /**
     * Deferred-78 AC8: {@code handleBookingCompleted} above is the only listener that ever
     * transitions a session plan's status, and it only fires on {@code BookingCompletedEvent} — a
     * booking that instead becomes CANCELLED/DECLINED/NO_SHOW/etc. never locked its paired
     * DRAFT/SAVED session plan. Subscribes to the same {@code BookingStatusChangedEvent} {@link
     * com.softropic.skillars.platform.booking.service.BookingBatchStatusListener} already consumes
     * — {@code BookingService.transitionInternal} is the single chokepoint that publishes it on
     * every status write. {@code isTerminal()} is authoritative and dynamic by design; this
     * deliberately does not hardcode a second list of terminal statuses to check against.
     *
     * <p>Forward-only, per project-owner decision: an already-orphaned session plan against an
     * already-terminal booking predating this AC is left as-is, not backfilled.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleBookingTerminalNonCompletion(BookingStatusChangedEvent event) {
        // event.newStatus() is a BookingStatus name, not a session status — compare with BookingStatus.
        if (BookingStatus.COMPLETED.name().equals(event.newStatus())
                || !bookingStateMachine.isTerminal(BookingStatus.valueOf(event.newStatus()))) {
            return;
        }
        // Same atomic conditional-UPDATE shape as handleBookingCompleted: only DRAFT/SAVED move.
        sessionRepository.markCancelledIfOpenByBookingId(event.bookingId(), Instant.now());
    }

    @Transactional(readOnly = true)
    public SessionPlanResponse getSession(UUID sessionId, Long coachUserId) {
        drillLibraryService.checkSessionBuilderGate(coachUserId);
        UUID coachId = resolveCoachId(coachUserId);

        Session session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResourceNotFoundException("Session not found", "session"));

        if (!session.getCoachId().equals(coachId)) {
            throw new ResourceNotFoundException("Session not found", "session");
        }

        List<Drill> drills = fetchDrills(session.getBlocks(), session.getCoachId());
        return buildResponse(session, toMetaMap(drills), toResponseMap(drills));
    }

    @Transactional(readOnly = true)
    public Optional<SessionPlanResponse> findByBookingId(UUID bookingId, Long coachUserId) {
        drillLibraryService.checkSessionBuilderGate(coachUserId);
        UUID coachId = resolveCoachId(coachUserId);
        return sessionRepository.findByBookingIdAndCoachId(bookingId, coachId)
            .map(s -> {
                List<Drill> drills = fetchDrills(s.getBlocks(), s.getCoachId());
                return buildResponse(s, toMetaMap(drills), toResponseMap(drills));
            });
    }

    private boolean isBookingPlannable(String status) {
        if ("CONFIRMED".equals(status)) {
            return true;
        }
        if ("UPCOMING".equals(status)) {
            log.warn("isBookingPlannable called with UPCOMING status; no session-plan creation window remains once a booking is UPCOMING");
            return false;
        }
        log.warn("isBookingPlannable called with unexpected status: {}", status);
        return false;
    }

    private UUID resolveCoachId(Long userId) {
        return coachProfileService.getCoachIdByUserId(userId);
    }

    private List<SessionBlockData> mapBlocksFromRequest(List<SessionBlockRequest> reqs) {
        return reqs.stream().map(req -> {
            List<SessionDrillRef> drillRefs = req.drills().stream()
                .map(d -> new SessionDrillRef(d.drillId(), d.order()))
                .sorted(Comparator.comparingInt(SessionDrillRef::order))
                .collect(Collectors.toList());
            return new SessionBlockData(req.blockType(), req.blockName(),
                req.durationMinutes(), drillRefs);
        }).collect(Collectors.toList());
    }

    /**
     * skillars-deferred-162 AC4.2: write path. Every referenced drill must be a PLATFORM drill or one of
     * {@code coachId}'s own PRIVATE drills, any status (D162-E). Null, unknown and foreign-PRIVATE ids all
     * get the same 404 before anything is saved; previously unknown ids were silently dropped while the
     * reference was still stored, and a foreign PRIVATE drill was stored and returned in full.
     */
    private List<Drill> fetchUsableDrillsOrThrow(List<SessionBlockData> blocks, UUID coachId) {
        List<UUID> ids = drillIds(blocks);
        if (ids.isEmpty()) return List.of();
        List<Drill> drills = ids.contains(null) ? List.of() : drillRepository.findAllById(ids);
        DrillUsagePolicy.requireAllUsable(ids, drills, coachId);
        return drills;
    }

    // Read path. skillars-deferred-162 AC4.3: a plan saved before the write-side check may still reference
    // another coach's PRIVATE drill; it is left out of the response (and of DNA/SLU) rather than returned.
    private List<Drill> fetchDrills(List<SessionBlockData> blocks, UUID coachId) {
        List<UUID> ids = drillIds(blocks);
        if (ids.isEmpty()) return List.of();
        return DrillUsagePolicy.usableOnly(drillRepository.findAllById(ids), coachId);
    }

    // Single source of a block list's distinct referenced drill ids (nulls kept for the write-side check).
    private static List<UUID> drillIds(List<SessionBlockData> blocks) {
        if (blocks == null || blocks.isEmpty()) return List.of();
        return blocks.stream()
            .flatMap(b -> b.drills() != null ? b.drills().stream() : Stream.empty())
            .map(SessionDrillRef::drillId)
            .distinct()
            .collect(Collectors.toList());
    }

    private Map<UUID, DrillMetadata> toMetaMap(List<Drill> drills) {
        return drills.stream().collect(Collectors.toMap(Drill::getId, Drill::getMetadata));
    }

    private Map<UUID, DrillResponse> toResponseMap(List<Drill> drills) {
        return drills.stream().collect(Collectors.toMap(Drill::getId, d ->
            drillLibraryService.toResponse(d, false, List.of(), null, null, null)));
    }

    private List<DrillMetadata> expandMetaForDna(List<SessionBlockData> blocks,
                                                  Map<UUID, DrillMetadata> metaMap) {
        return blocks.stream()
            .flatMap(b -> b.drills() != null ? b.drills().stream() : Stream.empty())
            .map(ref -> metaMap.get(ref.drillId()))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    private int calculateBlockSlu(List<SessionDrillRef> drillRefs,
                                   Map<UUID, DrillMetadata> metaMap) {
        if (drillRefs == null || drillRefs.isEmpty()) return 0;
        return drillRefs.stream()
            .map(ref -> metaMap.get(ref.drillId()))
            .filter(Objects::nonNull)
            .mapToInt(m -> {
                int weightSum = (m.skillWeighting() != null && !m.skillWeighting().isEmpty())
                    ? m.skillWeighting().values().stream().mapToInt(Integer::intValue).sum()
                    : 1;
                return m.repDensity() * weightSum;
            })
            .sum();
    }

    private SessionPlanResponse buildResponse(Session session, Map<UUID, DrillMetadata> metaMap,
                                               Map<UUID, DrillResponse> drillResponseMap) {
        List<SessionBlockResponse> blockResponses = (session.getBlocks() == null
            ? List.<SessionBlockData>of() : session.getBlocks()).stream()
            .map(block -> {
                List<SessionBlockDrillResponse> drillResponses = (block.drills() != null
                    ? block.drills() : List.<SessionDrillRef>of()).stream()
                    .sorted(Comparator.comparingInt(SessionDrillRef::order))
                    .map(ref -> new SessionBlockDrillResponse(ref.drillId(), ref.order(),
                        drillResponseMap.get(ref.drillId())))
                    .collect(Collectors.toList());
                int slu = calculateBlockSlu(block.drills(), metaMap);
                return new SessionBlockResponse(block.blockType(), block.blockName(),
                    block.durationMinutes(), drillResponses, slu);
            }).collect(Collectors.toList());

        return new SessionPlanResponse(
            session.getId(), session.getBookingId(), session.getCoachId(),
            session.getPlayerId(), blockResponses, session.getSessionDna(),
            session.getEquipmentList(), session.getDevelopmentFocus(),
            session.getStatus(), session.getCreatedAt(), session.getUpdatedAt(),
            session.getSourceTemplateId(), session.getSourceTemplateName()
        );
    }
}
