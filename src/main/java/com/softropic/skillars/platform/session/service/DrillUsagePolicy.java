package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.session.repo.Drill;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * skillars-deferred-162 AC4.1: the one rule for which drills a coach may put in front of a player.
 * <p>
 * A coach may use every {@code PLATFORM} drill and their own {@code PRIVATE} drills; another coach's
 * {@code PRIVATE} drill is never usable. Homework additionally requires {@code ACTIVE}
 * ({@code BookingCompletionService}, skillars-deferred-159), mirroring {@code DrillSuggestionService}. A
 * session plan does not (D162-E): the Session Builder re-sends every drill on save, so an {@code ACTIVE}
 * rule would block saving a plan that holds a since-archived drill of the coach's own.
 */
public final class DrillUsagePolicy {

    private DrillUsagePolicy() {}

    /** Ownership only: PLATFORM, or PRIVATE owned by {@code coachId}. Any status. */
    public static boolean isUsableBy(Drill drill, UUID coachId) {
        if (drill == null) return false;
        return "PLATFORM".equals(drill.getLibraryType())
            || ("PRIVATE".equals(drill.getLibraryType()) && Objects.equals(drill.getOwnerCoachId(), coachId));
    }

    /** Ownership plus {@code ACTIVE}: the homework rule. */
    public static boolean isAssignableAsHomework(Drill drill, UUID coachId) {
        return isUsableBy(drill, coachId) && "ACTIVE".equals(drill.getStatus());
    }

    /**
     * Throws unless every requested id is non-null and resolves (in {@code found}) to a drill
     * {@link #isUsableBy usable by} {@code coachId}. Unknown, foreign-PRIVATE and null ids all get the same
     * 404, so the response never confirms that another coach's drill exists.
     */
    public static void requireAllUsable(Collection<UUID> requested, Collection<Drill> found, UUID coachId) {
        if (requested.stream().anyMatch(Objects::isNull)) {
            throw new ResourceNotFoundException("Drill not found", "drill");
        }
        Set<UUID> usable = found.stream()
            .filter(d -> isUsableBy(d, coachId))
            .map(Drill::getId)
            .collect(Collectors.toSet());
        if (!usable.containsAll(requested)) {
            throw new ResourceNotFoundException("Drill not found", "drill");
        }
    }

    /** Read-side defence for rows saved before this rule existed: drops drills {@code coachId} may not use. */
    public static List<Drill> usableOnly(Collection<Drill> drills, UUID coachId) {
        return drills.stream().filter(d -> isUsableBy(d, coachId)).toList();
    }
}
