package com.softropic.skillars.platform.security.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.softropic.skillars.infrastructure.security.CookieUtil;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.REFRESH_TOKEN_TTL;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.SKILLARS_PROFILE_COOKIE;

/**
 * The single owner of the {@code skp} cookie's wire format: a quoted-id JSON payload
 * ({@code {"id":"...","role":"..."}}), percent-encoded for a non-HttpOnly cookie the frontend
 * decodes with {@code decodeURIComponent}. Previously open-coded at three call sites
 * ({@code AuthService.login()}, {@code AuthService.refresh()}, {@code JwtManagerImpl
 * .setSkillarsProfileCookie}) — two of them byte-for-byte identical. See
 * skillars-deferred-144 for the consolidation and skillars-deferred-142/the quoted-id
 * comment this type now owns for why {@code id} must be quoted: an unquoted id silently
 * corrupts the frontend's {@code authStore.userId} via IEEE-754 double rounding.
 *
 * <p>The payload is built with Jackson ({@code ObjectNode}), not string concatenation — Code
 * review 2026-10-06 flagged that a hand-built {@code "{\"id\":\"" + id + ...}"} string lets a
 * value containing a quote (e.g. {@code role = "X\",\"id\":\"999"}) inject an extra key, which
 * {@code JSON.parse}'s last-key-wins semantics would then let silently overwrite {@code id} on
 * the frontend. Not reachable today — every caller passes a numeric id or an enum name — but this
 * type is explicitly positioned as the extension point for the next field, so the escaping must
 * hold even when a caller's input doesn't.
 */
public record SkillarsProfileCookie(String id, String role) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public void writeTo(HttpServletResponse res) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("id", id);
        payload.put("role", role);
        String json = payload.toString();
        // java.net.URLEncoder is FORM encoding (space -> '+'); the frontend decodes with
        // decodeURIComponent, which leaves '+' literal. The two are not inverse functions.
        // Today's payload (a numeric id, an enum name) can never contain a space, so this has
        // never fired — but the next field added here must not reintroduce the mismatch.
        String encoded = URLEncoder.encode(json, StandardCharsets.UTF_8).replace("+", "%20");
        CookieUtil.addCookie(res, SKILLARS_PROFILE_COOKIE, encoded, false,
            (int) REFRESH_TOKEN_TTL.toSeconds(), "Lax");
    }

    public static void removeFrom(HttpServletResponse res) {
        CookieUtil.removeCookie(SKILLARS_PROFILE_COOKIE, res, false, "Lax");
    }
}
