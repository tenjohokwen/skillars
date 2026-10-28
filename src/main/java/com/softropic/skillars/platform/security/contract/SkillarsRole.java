package com.softropic.skillars.platform.security.contract;

public enum SkillarsRole {
    COACH, PARENT, PLAYER, ADMIN,

    /**
     * Not a persistable business role — {@code User.skillarsRole} (JPA {@code @Enumerated(STRING)})
     * is audited via Envers into {@code main.user_aud}, whose {@code skillars_role} column carries a
     * CHECK constraint restricted to {@code ('COACH','PARENT','PLAYER','ADMIN')}
     * (V145__user_aud_role_verification_status.sql). Never call
     * {@code user.setSkillarsRole(ANONYMOUS)} — it would fail that constraint on save, and would be
     * silently treated as PLAYER by GdprErasureService's role branching, which has no ANONYMOUS case.
     * This value existed only as a non-privileged {@code skp} fallback for the removed
     * {@code JwtManagerImpl.setSkillarsProfileCookie} (the retired {@code /otp} completion path),
     * for a caller whose JWT {@code ROLES} claim mapped to no real business role; nothing writes it
     * today, and the frontend treats it as "no known role".
     * Distinct from {@code AuthoritiesConstants.ANONYMOUS} ({@code "ROLE_ANONYMOUS"}), which is
     * Spring Security's unrelated pre-login anonymous-session username sentinel.
     */
    ANONYMOUS
}
