package com.softropic.skillars.platform.security.repo;

import com.softropic.skillars.platform.security.contract.SkillarsRole;
import com.softropic.skillars.platform.security.contract.SkillarsVerificationStatus;

import java.time.Instant;

/**
 * Admin user-search row (closed interface projection over {@link User}). A projection rather than
 * the entity on purpose: {@code User}'s inherited {@code addresses} {@code @ElementCollection} is
 * EAGER, so paging entities would issue one extra addresses query per row.
 */
public interface UserAdminSummaryView {
    Long getId();
    String getLogin();
    String getEmail();
    String getFirstName();
    String getLastName();
    SkillarsRole getSkillarsRole();
    Boolean getActivated();
    Boolean getLocked();
    SkillarsVerificationStatus getVerificationStatus();
    Instant getCreatedDate();
}
