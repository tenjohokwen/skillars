package com.softropic.skillars.platform.security.contract.util;

import java.util.Set;

/**
 * Constants for Spring Security authorities.
 */
public final class AuthoritiesConstants {

    public static final String ADMIN = "ROLE_ADMIN";

    public static final String LTD_ADMIN = "ROLE_LTD_ADMIN";

    public static final String USER = "ROLE_USER";

    public static final String COACH = "ROLE_COACH";

    public static final String PARENT = "ROLE_PARENT";

    public static final String ANONYMOUS = "ROLE_ANONYMOUS";

    public static final Set<String> CORE_ROLES = Set.of(ADMIN, LTD_ADMIN, USER, COACH, PARENT, ANONYMOUS);

    private AuthoritiesConstants() {
    }
}
