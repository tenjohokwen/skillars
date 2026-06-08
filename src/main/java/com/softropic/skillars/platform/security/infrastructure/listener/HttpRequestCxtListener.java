package com.softropic.skillars.platform.security.infrastructure.listener;



import com.softropic.skillars.infrastructure.security.event.PreAuthEvent;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextListener;

import jakarta.servlet.ServletRequestEvent;


/**
 * Fills up the request context as early as possible.
 * Uses listeners to be notified when the various request context metadata are available
 */
@Component
public class HttpRequestCxtListener extends RequestContextListener {


    @Override
    public void requestDestroyed(ServletRequestEvent sre) {
        super.requestDestroyed(sre);
        //cleanup is done at the SecurityAdviceFilter. Sending json responses from the filter is straight-forward.
    }

    /**
     * Sets the principal on the RequestMetadata object as soon as the name is figured out.
     * @param //preAuthenticationEvent contains the principal
     *
     * @see //JWTAuthenticationFilter
     * @see //JWTAuthorizationFilter
     * */
    @EventListener
    public void principalSet(PreAuthEvent preAuthEvent) {
        RequestMetadataProvider.setUserName(preAuthEvent.getAuthentication().getName());
    }
}
