package com.softropic.skillars.platform.security.contract;

/**
 * @param limitedAdmin S-28: true for a ROLE_LTD_ADMIN account without ROLE_ADMIN. {@code role} stays
 *                     {@code "ADMIN"} for both tiers (routing); the SPA uses this flag to hide full-admin-only UI.
 *                     The server enforces the tier independently.
 */
public record LoginResponse(Long userId, String role, String displayName, boolean limitedAdmin) {
    public LoginResponse(Long userId, String role, String displayName) {
        this(userId, role, displayName, false);
    }
}
