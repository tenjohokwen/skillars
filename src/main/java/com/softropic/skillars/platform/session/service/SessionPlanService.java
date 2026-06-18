package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.booking.contract.BookingSnapshot;
import com.softropic.skillars.platform.booking.service.BookingQueryService;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.session.contract.CreateSessionPlanRequest;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                "Session plan can only be created for a confirmed or upcoming booking",
                SessionErrorCode.SESSION_BOOKING_NOT_OWNED);
        }

        List<SessionBlockData> blocks = mapBlocksFromRequest(req.blocks());
        Map<UUID, DrillMetadata> metaMap = resolveMetaMap(blocks);
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
        session.setStatus("DRAFT");

        Session saved;
        try {
            saved = sessionRepository.save(session);
        } catch (DataIntegrityViolationException ex) {
            throw new OperationNotAllowedException(
                "Session plan already exists for this booking",
                SessionErrorCode.SESSION_ALREADY_EXISTS);
        }
        return buildResponse(saved, metaMap);
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

        if ("COMPLETED".equals(session.getStatus())) {
            throw new OperationNotAllowedException(
                "Completed sessions cannot be modified",
                SessionErrorCode.SESSION_PLAN_LOCKED);
        }

        List<SessionBlockData> blocks = mapBlocksFromRequest(req.blocks());
        Map<UUID, DrillMetadata> metaMap = resolveMetaMap(blocks);
        List<DrillMetadata> allMeta = expandMetaForDna(blocks, metaMap);
        SessionDnaScore dna = dnaCalculator.calculate(allMeta);
        List<String> equipment = equipmentListService.generate(allMeta);

        session.setBlocks(blocks);
        session.setSessionDna(dna);
        session.setEquipmentList(equipment);
        session.setDevelopmentFocus(req.developmentFocus());
        session.setStatus(req.status() != null ? req.status() : session.getStatus());

        Session saved = sessionRepository.save(session);
        return buildResponse(saved, metaMap);
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

        Map<UUID, DrillMetadata> metaMap = resolveMetaMap(session.getBlocks());
        return buildResponse(session, metaMap);
    }

    @Transactional(readOnly = true)
    public Optional<SessionPlanResponse> findByBookingId(UUID bookingId, Long coachUserId) {
        drillLibraryService.checkSessionBuilderGate(coachUserId);
        UUID coachId = resolveCoachId(coachUserId);
        return sessionRepository.findByBookingIdAndCoachId(bookingId, coachId)
            .map(s -> {
                Map<UUID, DrillMetadata> metaMap = resolveMetaMap(s.getBlocks());
                return buildResponse(s, metaMap);
            });
    }

    private boolean isBookingPlannable(String status) {
        return "CONFIRMED".equals(status) || "UPCOMING".equals(status);
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

    private Map<UUID, DrillMetadata> resolveMetaMap(List<SessionBlockData> blocks) {
        if (blocks == null || blocks.isEmpty()) return Map.of();
        List<UUID> uniqueIds = blocks.stream()
            .flatMap(b -> b.drills() != null ? b.drills().stream() : Stream.empty())
            .map(SessionDrillRef::drillId)
            .distinct()
            .collect(Collectors.toList());
        if (uniqueIds.isEmpty()) return Map.of();
        return drillRepository.findAllById(uniqueIds).stream()
            .collect(Collectors.toMap(Drill::getId, Drill::getMetadata));
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

    private SessionPlanResponse buildResponse(Session session, Map<UUID, DrillMetadata> metaMap) {
        List<UUID> drillIds = session.getBlocks() == null ? List.of() :
            session.getBlocks().stream()
                .flatMap(b -> b.drills() != null ? b.drills().stream() : Stream.empty())
                .map(SessionDrillRef::drillId)
                .distinct()
                .collect(Collectors.toList());

        Map<UUID, DrillResponse> drillResponseMap = drillIds.isEmpty() ? Map.of()
            : drillRepository.findAllById(drillIds).stream()
                .collect(Collectors.toMap(Drill::getId, d ->
                    drillLibraryService.toResponse(d, false, List.of(), null, null, null)));

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
