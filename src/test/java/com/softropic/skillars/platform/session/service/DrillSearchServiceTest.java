package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.session.contract.DrillMetadata;
import com.softropic.skillars.platform.session.contract.DrillResponse;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.DrillTagRepository;
import com.softropic.skillars.platform.session.repo.DrillVideoRefRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DrillSearchServiceTest {

    @Mock private DrillRepository drillRepository;
    @Mock private DrillVideoRefRepository drillVideoRefRepository;
    @Mock private DrillTagRepository drillTagRepository;
    @Mock private ConfigService configService;
    @Mock private CoachProfileService coachProfileService;
    @Mock private VideoRepository videoRepository;
    @Mock private VideoProviderAdapter videoProviderAdapter;

    private DrillLibraryService service;

    private static final Long COACH_USER_ID = 9500000099L;
    private static final UUID COACH_PROFILE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DrillLibraryService(drillRepository, drillVideoRefRepository, drillTagRepository, configService, coachProfileService, videoRepository, videoProviderAdapter);
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_PROFILE_ID);
        lenient().when(drillVideoRefRepository.findByDrillIdIn(anyList())).thenReturn(List.of());
    }

    // ── search query ─────────────────────────────────────────────────────────────

    @Test
    void listDrills_qMatchingDrillName_returnsMatchingDrill() {
        Drill ball = buildDrill("Ball Mastery Drill", "A drill for ball control", List.of("Keep eyes on ball"), "PLATFORM", null);
        Drill other = buildDrill("Finishing Drill", "Shoot and score", List.of("Strong leg"), "PLATFORM", null);
        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(ball, other));
        when(drillRepository.findClonesBySourceIdsAndCoach(anyList(), any(UUID.class))).thenReturn(List.of());

        List<DrillResponse> results = service.listDrills("PLATFORM", "ball", null, null, null, null, COACH_USER_ID);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("Ball Mastery Drill");
    }

    @Test
    void listDrills_qMatchingCoachingPoint_returnsMatchingDrill() {
        Drill drill1 = buildDrill("Drill A", "Generic desc", List.of("Keep head up", "Drive with weak foot"), "PLATFORM", null);
        Drill drill2 = buildDrill("Drill B", "Generic desc", List.of("Look for space"), "PLATFORM", null);
        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(drill1, drill2));
        when(drillRepository.findClonesBySourceIdsAndCoach(anyList(), any(UUID.class))).thenReturn(List.of());

        List<DrillResponse> results = service.listDrills("PLATFORM", "weak foot", null, null, null, null, COACH_USER_ID);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("Drill A");
    }

    @Test
    void listDrills_qWithNoMatch_returnsEmptyList() {
        Drill drill = buildDrill("Basic Drill", "Some description", List.of("Point 1"), "PLATFORM", null);
        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(drill));

        List<DrillResponse> results = service.listDrills("PLATFORM", "xyznonexistent", null, null, null, null, COACH_USER_ID);

        assertThat(results).isEmpty();
    }

    // ── skill filter ──────────────────────────────────────────────────────────────

    @Test
    void listDrills_skillFilterMatchesPrimarySkill_returnsMatchingDrill() {
        Drill drill = buildDrillWithSkills("Dribbling Drill", List.of("dribbling"), List.of(), "U12");
        Drill other = buildDrillWithSkills("Passing Drill", List.of("passing"), List.of(), "U12");
        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(drill, other));
        when(drillRepository.findClonesBySourceIdsAndCoach(anyList(), any(UUID.class))).thenReturn(List.of());

        List<DrillResponse> results = service.listDrills("PLATFORM", null, "dribbling", null, null, null, COACH_USER_ID);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("Dribbling Drill");
    }

    @Test
    void listDrills_skillFilterMatchesSecondarySkill_returnsMatchingDrill() {
        Drill drill = buildDrillWithSkills("Combo Drill", List.of("passing"), List.of("dribbling"), "U14");
        Drill other = buildDrillWithSkills("Shooting Drill", List.of("shooting"), List.of("finishing"), "U14");
        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(drill, other));
        when(drillRepository.findClonesBySourceIdsAndCoach(anyList(), any(UUID.class))).thenReturn(List.of());

        List<DrillResponse> results = service.listDrills("PLATFORM", null, "dribbling", null, null, null, COACH_USER_ID);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("Combo Drill");
    }

    // ── difficultyTier filter ─────────────────────────────────────────────────────

    @Test
    void listDrills_difficultyTierFilter_returnsExactMatch() {
        Drill u10 = buildDrillWithSkills("U10 Drill", List.of("ball_mastery"), List.of(), "U10");
        Drill u14 = buildDrillWithSkills("U14 Drill", List.of("ball_mastery"), List.of(), "U14");
        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(u10, u14));
        when(drillRepository.findClonesBySourceIdsAndCoach(anyList(), any(UUID.class))).thenReturn(List.of());

        List<DrillResponse> results = service.listDrills("PLATFORM", null, null, "U14", null, null, COACH_USER_ID);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("U14 Drill");
    }

    // ── equipment filter ──────────────────────────────────────────────────────────

    @Test
    void listDrills_equipmentFilter_returnsDrillsWithMatchingEquipment() {
        Drill conesDrill = buildDrillWithEquipment("Cones Drill", List.of("cones", "ball"));
        Drill ballOnlyDrill = buildDrillWithEquipment("Ball Only Drill", List.of("ball"));
        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(conesDrill, ballOnlyDrill));
        when(drillRepository.findClonesBySourceIdsAndCoach(anyList(), any(UUID.class))).thenReturn(List.of());

        List<DrillResponse> results = service.listDrills("PLATFORM", null, null, null, List.of("cones"), null, COACH_USER_ID);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("Cones Drill");
    }

    // ── weakFootBias filter ───────────────────────────────────────────────────────

    @Test
    void listDrills_weakFootBiasTrue_returnsOnlyWeakFootDrills() {
        Drill weakFoot = buildDrillWithWeakFoot("Weak Foot Drill", true);
        Drill regular = buildDrillWithWeakFoot("Regular Drill", false);
        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(weakFoot, regular));
        when(drillRepository.findClonesBySourceIdsAndCoach(anyList(), any(UUID.class))).thenReturn(List.of());

        List<DrillResponse> results = service.listDrills("PLATFORM", null, null, null, null, true, COACH_USER_ID);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("Weak Foot Drill");
    }

    @Test
    void listDrills_weakFootBiasFalse_returnsOnlyNonWeakFootDrills() {
        Drill weakFoot = buildDrillWithWeakFoot("Weak Foot Drill", true);
        Drill regular = buildDrillWithWeakFoot("Regular Drill", false);
        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(weakFoot, regular));
        when(drillRepository.findClonesBySourceIdsAndCoach(anyList(), any(UUID.class))).thenReturn(List.of());

        List<DrillResponse> results = service.listDrills("PLATFORM", null, null, null, null, false, COACH_USER_ID);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("Regular Drill");
    }

    // ── combined filters ──────────────────────────────────────────────────────────

    @Test
    void listDrills_combinedQAndSkillFilter_appliesBoth() {
        // Both q="dribbling" AND skill="dribbling" must match simultaneously
        Drill both1 = buildDrillWithSkills("Dribbling Focus", List.of("dribbling"), List.of(), "U12");
        Drill both2 = buildDrillWithSkills("Dribbling Advanced", List.of("dribbling"), List.of(), "U12");
        // Matches name q but not skill
        Drill qOnlyMatch = buildDrillWithSkills("Dribbling Move", List.of("passing"), List.of(), "U12");
        // Matches skill but not name q
        Drill skillOnlyMatch = buildDrillWithSkills("Random Drill", List.of("dribbling"), List.of(), "U12");
        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE"))
            .thenReturn(List.of(both1, both2, qOnlyMatch, skillOnlyMatch));
        when(drillRepository.findClonesBySourceIdsAndCoach(anyList(), any(UUID.class))).thenReturn(List.of());

        List<DrillResponse> results = service.listDrills("PLATFORM", "dribbling", "dribbling", null, null, null, COACH_USER_ID);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(DrillResponse::name).containsExactlyInAnyOrder("Dribbling Focus", "Dribbling Advanced");
    }

    // ── passthrough ───────────────────────────────────────────────────────────────

    @Test
    void listDrills_noFilters_returnsAllActiveDrills() {
        Drill d1 = buildDrill("Drill 1", "Desc", List.of("Cp1"), "PLATFORM", null);
        Drill d2 = buildDrill("Drill 2", "Desc", List.of("Cp2"), "PLATFORM", null);
        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(d1, d2));
        when(drillRepository.findClonesBySourceIdsAndCoach(anyList(), any(UUID.class))).thenReturn(List.of());

        List<DrillResponse> results = service.listDrills("PLATFORM", null, null, null, null, null, COACH_USER_ID);

        assertThat(results).hasSize(2);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private Drill buildDrill(String name, String description, List<String> coachingPoints, String libraryType, UUID ownerCoachId) {
        DrillMetadata metadata = new DrillMetadata(
            List.of("ball_mastery"), List.of(),
            Map.of("ball_mastery", 100), 10, 2, 1, 1, 1, false,
            "U12", List.of("ball"), "2–6", coachingPoints, null
        );
        return Instancio.of(Drill.class)
            .set(field(Drill::getName), name)
            .set(field(Drill::getDescription), description)
            .set(field(Drill::getLibraryType), libraryType)
            .set(field(Drill::getStatus), "ACTIVE")
            .set(field(Drill::getOwnerCoachId), ownerCoachId)
            .set(field(Drill::getMetadata), metadata)
            .create();
    }

    private Drill buildDrillWithSkills(String name, List<String> primary, List<String> secondary, String tier) {
        DrillMetadata metadata = new DrillMetadata(
            primary, secondary,
            Map.of("skill", 100), 10, 2, 1, 1, 1, false,
            tier, List.of("ball"), "2–6", List.of("Focus"), null
        );
        return Instancio.of(Drill.class)
            .set(field(Drill::getName), name)
            .set(field(Drill::getDescription), "Generic description")
            .set(field(Drill::getLibraryType), "PLATFORM")
            .set(field(Drill::getStatus), "ACTIVE")
            .set(field(Drill::getMetadata), metadata)
            .create();
    }

    private Drill buildDrillWithEquipment(String name, List<String> equipment) {
        DrillMetadata metadata = new DrillMetadata(
            List.of("ball_mastery"), List.of(),
            Map.of("ball_mastery", 100), 10, 2, 1, 1, 1, false,
            "U12", equipment, "2–6", List.of("Focus"), null
        );
        return Instancio.of(Drill.class)
            .set(field(Drill::getName), name)
            .set(field(Drill::getDescription), "Desc")
            .set(field(Drill::getLibraryType), "PLATFORM")
            .set(field(Drill::getStatus), "ACTIVE")
            .set(field(Drill::getMetadata), metadata)
            .create();
    }

    private Drill buildDrillWithWeakFoot(String name, boolean weakFoot) {
        DrillMetadata metadata = new DrillMetadata(
            List.of("ball_mastery"), List.of(),
            Map.of("ball_mastery", 100), 10, 2, 1, 1, 1, weakFoot,
            "U12", List.of("ball"), "2–6", List.of("Focus"), null
        );
        return Instancio.of(Drill.class)
            .set(field(Drill::getName), name)
            .set(field(Drill::getDescription), "Desc")
            .set(field(Drill::getLibraryType), "PLATFORM")
            .set(field(Drill::getStatus), "ACTIVE")
            .set(field(Drill::getMetadata), metadata)
            .create();
    }
}
