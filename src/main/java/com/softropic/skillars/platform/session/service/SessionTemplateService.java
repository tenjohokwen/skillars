package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.booking.contract.BookingSnapshot;
import com.softropic.skillars.platform.booking.contract.PlannableBookingSummary;
import com.softropic.skillars.platform.booking.service.BookingQueryService;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.session.contract.CreateTemplateRequest;
import com.softropic.skillars.platform.session.contract.DrillMetadata;
import com.softropic.skillars.platform.session.contract.DrillResponse;
import com.softropic.skillars.platform.session.contract.RenameTemplateRequest;
import com.softropic.skillars.platform.session.contract.SessionBlockData;
import com.softropic.skillars.platform.session.contract.SessionBlockDrillResponse;
import com.softropic.skillars.platform.session.contract.SessionBlockResponse;
import com.softropic.skillars.platform.session.contract.SessionDrillRef;
import com.softropic.skillars.platform.session.contract.SessionErrorCode;
import com.softropic.skillars.platform.session.contract.SessionPlanResponse;
import com.softropic.skillars.platform.session.contract.SessionTemplateResponse;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.Session;
import com.softropic.skillars.platform.session.repo.SessionRepository;
import com.softropic.skillars.platform.session.repo.SessionTemplate;
import com.softropic.skillars.platform.session.repo.SessionTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class SessionTemplateService {

    private final SessionTemplateRepository sessionTemplateRepository;
    private final SessionRepository sessionRepository;
    private final DrillRepository drillRepository;
    private final CoachProfileService coachProfileService;
    private final DrillLibraryService drillLibraryService;
    private final BookingQueryService bookingQueryService;

    public SessionTemplateResponse createTemplate(CreateTemplateRequest req, Long coachUserId) {
        drillLibraryService.checkSessionBuilderGate(coachUserId);
        UUID coachId = resolveCoachId(coachUserId);

        Session session = sessionRepository.findById(req.sessionId())
            .orElseThrow(() -> new ResourceNotFoundException("Session not found", "session"));

        if (!session.getCoachId().equals(coachId)) {
            throw new ResourceNotFoundException("Session not found", "session");
        }

        SessionTemplate template = new SessionTemplate();
        template.setCoachId(coachId);
        template.setName(req.name());
        template.setBlocks(session.getBlocks());
        template.setSessionDna(session.getSessionDna());
        template.setEquipmentList(session.getEquipmentList());
        template.setDevelopmentFocus(session.getDevelopmentFocus());
        template.setDeployCount(0);
        template.setStatus("ACTIVE");

        SessionTemplate saved = sessionTemplateRepository.save(template);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<SessionTemplateResponse> listTemplates(Long coachUserId) {
        drillLibraryService.checkSessionBuilderGate(coachUserId);
        UUID coachId = resolveCoachId(coachUserId);
        return sessionTemplateRepository.findByCoachIdAndStatus(coachId, "ACTIVE")
            .stream().map(this::toResponse).toList();
    }

    public void renameTemplate(UUID templateId, RenameTemplateRequest req, Long coachUserId) {
        drillLibraryService.checkSessionBuilderGate(coachUserId);
        UUID coachId = resolveCoachId(coachUserId);

        SessionTemplate t = sessionTemplateRepository.findByIdAndCoachId(templateId, coachId)
            .orElseThrow(() -> new OperationNotAllowedException("Template not owned", SessionErrorCode.TEMPLATE_NOT_OWNED));

        if ("ARCHIVED".equals(t.getStatus())) {
            throw new OperationNotAllowedException("Template has been deleted", SessionErrorCode.TEMPLATE_NOT_OWNED);
        }

        t.setName(req.name());
        sessionTemplateRepository.save(t);
    }

    public void deleteTemplate(UUID templateId, Long coachUserId) {
        drillLibraryService.checkSessionBuilderGate(coachUserId);
        UUID coachId = resolveCoachId(coachUserId);

        SessionTemplate t = sessionTemplateRepository.findByIdAndCoachId(templateId, coachId)
            .orElseThrow(() -> new OperationNotAllowedException("Template not owned", SessionErrorCode.TEMPLATE_NOT_OWNED));

        if ("ARCHIVED".equals(t.getStatus())) {
            throw new OperationNotAllowedException("Template has been deleted", SessionErrorCode.TEMPLATE_NOT_OWNED);
        }

        t.setStatus("ARCHIVED");
        sessionTemplateRepository.save(t);
    }

    /** How far ahead the deploy picker looks for plannable bookings. */
    static final Duration DEPLOYABLE_BOOKING_HORIZON = Duration.ofDays(60);

    /**
     * feat/coach-workflow-gaps A19: the bookings {@link #deployTemplate} would accept for this coach — upcoming
     * (start after now, within {@link #DEPLOYABLE_BOOKING_HORIZON}), in a status {@link #isBookingPlannable}
     * admits, and without a session plan yet. Replaces the raw booking-UUID text field in the template vault.
     * Same gate as {@link #listTemplates}. A read-only hint: deploy re-checks every condition itself.
     */
    @Transactional(readOnly = true)
    public List<PlannableBookingSummary> listDeployableBookings(Long coachUserId) {
        drillLibraryService.checkSessionBuilderGate(coachUserId);
        UUID coachId = resolveCoachId(coachUserId);
        Instant now = Instant.now();
        List<PlannableBookingSummary> candidates = bookingQueryService.findCoachBookings(
            coachId, PLANNABLE_BOOKING_STATUSES, now, now.plus(DEPLOYABLE_BOOKING_HORIZON));
        if (candidates.isEmpty()) return List.of();
        Set<UUID> planned = new HashSet<>(sessionRepository.findBookingIdsWithPlan(
            candidates.stream().map(PlannableBookingSummary::bookingId).toList()));
        return candidates.stream()
            .filter(b -> !planned.contains(b.bookingId()) && b.playerId() != null)
            .toList();
    }

    public SessionPlanResponse deployTemplate(UUID templateId, UUID bookingId, Long coachUserId) {
        drillLibraryService.checkSessionBuilderGate(coachUserId);
        UUID coachId = resolveCoachId(coachUserId);

        SessionTemplate t = sessionTemplateRepository.findByIdAndCoachId(templateId, coachId)
            .orElseThrow(() -> new OperationNotAllowedException("Template not owned", SessionErrorCode.TEMPLATE_NOT_OWNED));

        if ("ARCHIVED".equals(t.getStatus())) {
            throw new OperationNotAllowedException("Template has been deleted", SessionErrorCode.TEMPLATE_NOT_OWNED);
        }

        BookingSnapshot booking = bookingQueryService.getBookingSnapshot(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found", "booking"));

        if (!booking.coachId().equals(coachId)) {
            throw new OperationNotAllowedException(
                "Booking is not owned by this coach", SessionErrorCode.SESSION_BOOKING_NOT_OWNED);
        }

        if (!isBookingPlannable(booking.status())) {
            throw new OperationNotAllowedException(
                "Session plan can only be created for a confirmed booking",
                SessionErrorCode.SESSION_BOOKING_NOT_OWNED);
        }

        if (sessionRepository.existsByBookingId(bookingId)) {
            throw new OperationNotAllowedException(
                "Session plan already exists for this booking", SessionErrorCode.SESSION_ALREADY_EXISTS);
        }

        if (booking.playerId() == null) {
            throw new OperationNotAllowedException(
                "Booking has no player assigned", SessionErrorCode.SESSION_BOOKING_NOT_OWNED);
        }

        // skillars-deferred-162 AC4.2: deploy saves a session directly, bypassing SessionPlanService's
        // write-side check, so apply the same rule here for the deploying coach. A template copied from a
        // plan saved before that check could otherwise re-deploy another coach's PRIVATE drill forever.
        List<UUID> templateDrillIds = drillIds(t.getBlocks());
        if (!templateDrillIds.isEmpty()) {
            DrillUsagePolicy.requireAllUsable(templateDrillIds,
                templateDrillIds.contains(null) ? List.of() : drillRepository.findAllById(templateDrillIds),
                coachId);
        }

        Session session = new Session();
        session.setBookingId(bookingId);
        session.setCoachId(coachId);
        session.setPlayerId(booking.playerId());
        session.setBlocks(t.getBlocks() != null ? new ArrayList<>(t.getBlocks()) : new ArrayList<>());
        session.setSessionDna(t.getSessionDna());
        session.setEquipmentList(t.getEquipmentList());
        session.setDevelopmentFocus(t.getDevelopmentFocus());
        session.setStatus("DRAFT");
        session.setSourceTemplateId(t.getId());
        session.setSourceTemplateName(t.getName());

        Session saved = sessionRepository.save(session);
        sessionTemplateRepository.incrementDeployCount(t.getId(), Instant.now());

        return buildResponse(saved);
    }

    /** The statuses {@link #isBookingPlannable} returns true for — keep the two in step. */
    static final List<String> PLANNABLE_BOOKING_STATUSES = List.of("CONFIRMED");

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

    private SessionTemplateResponse toResponse(SessionTemplate t) {
        int drillCount = t.getBlocks() == null ? 0
            : t.getBlocks().stream().mapToInt(b -> b.drills() != null ? b.drills().size() : 0).sum();
        return new SessionTemplateResponse(
            t.getId(), t.getCoachId(), t.getName(), drillCount,
            t.getSessionDna(), t.getLastDeployedAt(), t.getDeployCount(), t.getCreatedAt()
        );
    }

    // mirrors SessionPlanService.buildResponse — keep in sync (including the AC4.3 ownership filter)
    private SessionPlanResponse buildResponse(Session session) {
        List<SessionBlockData> blocks = session.getBlocks() == null ? List.of() : session.getBlocks();
        List<UUID> drillIds = drillIds(blocks);

        List<Drill> drills = drillIds.isEmpty() ? List.of()
            : DrillUsagePolicy.usableOnly(drillRepository.findAllById(drillIds), session.getCoachId());
        Map<UUID, DrillResponse> drillResponseMap = drills.stream()
            .collect(Collectors.toMap(Drill::getId, d ->
                drillLibraryService.toResponse(d, false, List.of(), null, null, null)));
        Map<UUID, DrillMetadata> metaMap = drills.stream()
            .collect(Collectors.toMap(Drill::getId, Drill::getMetadata));

        List<SessionBlockResponse> blockResponses = blocks.stream().map(block -> {
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

    private static List<UUID> drillIds(List<SessionBlockData> blocks) {
        if (blocks == null || blocks.isEmpty()) return List.of();
        return blocks.stream()
            .flatMap(b -> b.drills() != null ? b.drills().stream() : Stream.empty())
            .map(SessionDrillRef::drillId)
            .distinct()
            .collect(Collectors.toList());
    }

    private int calculateBlockSlu(List<SessionDrillRef> drillRefs, Map<UUID, DrillMetadata> metaMap) {
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
}
