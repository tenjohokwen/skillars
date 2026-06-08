package com.softropic.skillars.infrastructure.util;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EnvUtil {

    private EnvUtil(){}


    public static String getBaseUrl() {
        try {
            HttpServletRequest request =
                    ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                            .getRequest();
            return ServletUriComponentsBuilder.fromRequestUri(request)
                                              .replacePath(null)
                                              .build()
                                              .toUriString();
        } catch (IllegalStateException e) {
            log.error("NOT_WEB: Not in a web request context. Responding with localhost", e);
            return "http://localhost";
        }
    }

}
