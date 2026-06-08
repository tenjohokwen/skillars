package com.softropic.skillars.infrastructure.threadpool;

import org.slf4j.MDC;

import java.util.Map;

/**
 * When a null context is passed to MDC.setContextMap() a null pointer exception will be thrown. This wrapper is meant to avoid that.
 * The issue arises when you call MDC.getContextMap() on a thread that does not have a context map (you get a null) and then set that context map on the MDC of a different thread
 */
public class MdcWrapper {
    private MdcWrapper(){}

    static void setContextMap(Map<String, String> currentThreadCxt) {
        if(currentThreadCxt != null ) {
            MDC.setContextMap(currentThreadCxt);
        } else {
            MDC.clear();
        }
    }
}
