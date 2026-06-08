package com.softropic.skillars.infrastructure.security;


import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;

import org.springframework.http.ResponseCookie;
import org.springframework.web.util.WebUtils;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class CookieUtil {

    private CookieUtil(){}

    public static void addCookie(HttpServletResponse res, String name, String value, boolean httpOnly, int maxAge ) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                                              .path("/")
                                              .maxAge(maxAge)
                                              .httpOnly(httpOnly )
                                              .secure(RequestMetadataProvider.getClientInfo().isHttps())
                                              .sameSite("Lax")
                                              .build();
        res.addHeader("Set-Cookie", cookie.toString());
    }

    public static String getCookieValue(final HttpServletRequest req, final String cookieName) {
        final Cookie cookie = WebUtils.getCookie(req, cookieName);
        return cookie != null ? cookie.getValue() : null;
    }

    public static void removeCookie(String cookieName, HttpServletResponse res, boolean httpOnly) {
        ResponseCookie springCookie = ResponseCookie.from(cookieName)
                                                    .path("/")
                                                    .maxAge(0)
                                                    .httpOnly(httpOnly) // or false
                                                    .secure(RequestMetadataProvider.getClientInfo().isHttps()) // based on HTTPS
                                                    .sameSite("Lax") // or "Strict" or "None" (if "None", Secure must be true)
                                                    .build();
        res.addHeader("Set-Cookie", springCookie.toString());
    }

}
