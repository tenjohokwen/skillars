package com.softropic.skillars.platform.security.config;



import com.softropic.skillars.platform.security.contract.util.AuthoritiesConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;


public final class AppEndpoints {

    public static final String                    SECURED           = "/v1/**";
    public static final String                    SECURED_API           = "/api/**";
    public static final String                    ACTUATOR           = "/manage/**";
    public static final String                    REFRESH           = "/refresh";
    public static final Map<String, String[]> SECURED_MAPPINGS;
    public static final List<String>          SECURED_ENDPOINTS; //"/api/register"
    public static final String FROM_CHROME = "/.well-known/appspecific/com.chrome.devtools.json"; //TODO investigate how to handle this
    public static final List<String> PUBLIC_STATIC_RESOURCES = List.of("/", "/assets/**", "/scripts/**", "/i18n/**", "/favicon.ico", "/icons/**", "/index.html", FROM_CHROME);
    public static final List<String> PUBLIC_ENDPOINTS = List.of(
        "/v1/account/register**", "/v1/account/regislink**", "/v1/account/activate/**",
        "/v1/account/reset_password/init", "/v1/account/reset_password/finish",
        "/api/v1/emails/**", "/authenticate"
    );
    public static final List<String> PUBLIC_MGMT_ENDPOINTS = List.of("/manage/prometheus", "/manage/health", "/manage/info");
    public static final List<String> ALL_UNRESTRICTED;

    private static final String[] SECURED_AUTHORITIES = new String[]{AuthoritiesConstants.ADMIN, AuthoritiesConstants.USER, AuthoritiesConstants.LTD_ADMIN};


    static {

        SECURED_MAPPINGS = Map.of(SECURED, Arrays.copyOf(SECURED_AUTHORITIES, SECURED_AUTHORITIES.length),
                                  SECURED_API, Arrays.copyOf(SECURED_AUTHORITIES, SECURED_AUTHORITIES.length),
                                  ACTUATOR, new String[]{AuthoritiesConstants.ADMIN},
                                  REFRESH, Arrays.copyOf(SECURED_AUTHORITIES, SECURED_AUTHORITIES.length));
        SECURED_ENDPOINTS = List.copyOf(SECURED_MAPPINGS.keySet());
        ALL_UNRESTRICTED = new ArrayList<>(PUBLIC_STATIC_RESOURCES);
        ALL_UNRESTRICTED.addAll(PUBLIC_ENDPOINTS);
        ALL_UNRESTRICTED.addAll(PUBLIC_MGMT_ENDPOINTS);
    }
    private AppEndpoints(){}

}
