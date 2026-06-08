package com.softropic.skillars.infrastructure.threadpool;

import org.slf4j.MDC;

import java.util.Collections;
import java.util.Map;

/*
This class is used to carry over caller context when thread pools are used
The stack call and Mapped Diagnostic Context (MDC) which are usually lost, are carried over by this class to the new thread from the pool
 */
public class ClientThreadContext {
    public final Exception           clientStack;
    public final String              clientThreadName;
    public final Map<String, String> mdcCtxt;

    private ClientThreadContext(Exception clientStack, String clientThreadName, Map<String, String> mdcCxt) {
        this.clientStack = clientStack;
        this.clientThreadName = clientThreadName;
        this.mdcCtxt = mdcCxt == null ? Collections.emptyMap() : mdcCxt;
    }

    static ClientThreadContext instance() {
        return new ClientThreadContext(new Exception(), Thread.currentThread().getName(), MDC.getCopyOfContextMap());
    }


    void setClientMDCContext() {
        if(mdcCtxt != null ) {
            MdcWrapper.setContextMap(mdcCtxt);
        } else {
            MDC.clear();
        }
    }
}
