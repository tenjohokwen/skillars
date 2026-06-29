package com.softropic.skillars.platform.marketplace.service;

import com.softropic.skillars.platform.marketplace.contract.CoachSearchParams;
import com.softropic.skillars.platform.marketplace.repo.CoachAgeGroup;
import com.softropic.skillars.platform.marketplace.repo.CoachPricing;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachSpecialty;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.security.contract.AgeTier;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.UUID;

public final class CoachSearchSpecification {

    private CoachSearchSpecification() {}

    public static Specification<CoachProfile> build(CoachSearchParams p) {
        return Specification
            .where(isActive())
            .and(inCity(p.city()))
            .and(inDistrict(p.district()))
            .and(hasSkill(p.skill()))
            .and(hasAgeGroup(p.ageGroup()))
            .and(minPrice(p.minPrice()))
            .and(maxPrice(p.maxPrice()))
            .and(hasMinRating(p.minRating()));
        // Note: language filter is applied separately via native query in CoachSearchService
        // because PostgreSQL array ANY() predicate is not directly expressible in JPA Criteria API
    }

    private static Specification<CoachProfile> isActive() {
        // REDUCED coaches appear in search results but are sorted after ACTIVE coaches (AC 9 / Task 7.1)
        return (root, q, cb) -> root.get("status").in(CoachProfileStatus.ACTIVE, CoachProfileStatus.REDUCED);
    }

    private static Specification<CoachProfile> inCity(String city) {
        // city is required — this spec always produces a predicate
        return (root, q, cb) -> cb.equal(cb.lower(root.get("city")), city.toLowerCase());
    }

    private static Specification<CoachProfile> inDistrict(String district) {
        if (!StringUtils.hasText(district)) return null;
        return (root, q, cb) -> cb.equal(cb.lower(root.get("district")), district.toLowerCase());
    }

    private static Specification<CoachProfile> hasSkill(String skill) {
        if (!StringUtils.hasText(skill)) return null;
        return (root, q, cb) -> {
            Subquery<UUID> sub = q.subquery(UUID.class);
            Root<CoachSpecialty> spec = sub.from(CoachSpecialty.class);
            sub.select(spec.get("coachId"))
               .where(cb.and(
                   cb.equal(spec.get("coachId"), root.get("id")),
                   cb.equal(cb.lower(spec.get("skill")), skill.toLowerCase())
               ));
            return cb.exists(sub);
        };
    }

    private static Specification<CoachProfile> hasAgeGroup(AgeTier ageGroup) {
        if (ageGroup == null) return null;
        return (root, q, cb) -> {
            Subquery<UUID> sub = q.subquery(UUID.class);
            Root<CoachAgeGroup> ag = sub.from(CoachAgeGroup.class);
            sub.select(ag.get("coachId"))
               .where(cb.and(
                   cb.equal(ag.get("coachId"), root.get("id")),
                   cb.equal(ag.get("ageTier"), ageGroup)
               ));
            return cb.exists(sub);
        };
    }

    private static Specification<CoachProfile> minPrice(BigDecimal min) {
        if (min == null) return null;
        return (root, q, cb) -> {
            Subquery<BigDecimal> sub = q.subquery(BigDecimal.class);
            Root<CoachPricing> pricing = sub.from(CoachPricing.class);
            sub.select(pricing.get("perSessionPrice"))
               .where(cb.and(
                   cb.equal(pricing.get("coachId"), root.get("id")),
                   cb.greaterThanOrEqualTo(pricing.get("perSessionPrice"), min)
               ));
            return cb.exists(sub);
        };
    }

    private static Specification<CoachProfile> maxPrice(BigDecimal max) {
        if (max == null) return null;
        return (root, q, cb) -> {
            Subquery<BigDecimal> sub = q.subquery(BigDecimal.class);
            Root<CoachPricing> pricing = sub.from(CoachPricing.class);
            sub.select(pricing.get("perSessionPrice"))
               .where(cb.and(
                   cb.equal(pricing.get("coachId"), root.get("id")),
                   cb.lessThanOrEqualTo(pricing.get("perSessionPrice"), max)
               ));
            return cb.exists(sub);
        };
    }

    private static Specification<CoachProfile> hasMinRating(Double minRating) {
        if (minRating == null || minRating <= 0.0) return null;
        return (root, q, cb) ->
            cb.greaterThanOrEqualTo(root.<Double>get("averageRating"), minRating);
    }
}
