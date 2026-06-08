package com.softropic.skillars.e2e;

import com.google.common.collect.ImmutableList;

import com.softropic.skillars.infrastructure.client.Client;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

import jakarta.validation.constraints.NotNull;


@Component
public class HttpTestClient {

    @Autowired
    private RestTemplate testRestTemplate;

    private Client client;

    /**
     * General method for doing http call.
     * If any common parameters have to be added, they are added here. E.g. Accept header.
     *
     * @param uri          endpoint to hit
     * @param method       method to use
     * @param body         body to send
     * @param headers      http headers of the request
     * @param clazz the type of the return value
     * @return http call response
     */
    public <T> ResponseEntity<T> makeHttpRequest(String uri,
                                                 HttpMethod method,
                                                 Map<String, Object> body,
                                                  @NotNull HttpHeaders headers,
                                                 Class<T> clazz) {
        HttpEntity<Map<String, Object>> requestEntity;
        if (body != null) {
            requestEntity = new HttpEntity<>(body, headers);
        } else {
            requestEntity = new HttpEntity<>(headers);
        }
        return testRestTemplate.exchange(uri, method, requestEntity, clazz);
    }

    /**
     * General method for doing http call.
     *      * If any common parameters have to be added, they are added here. E.g. Accept header.
     * @param uri     endpoint to hit
     * @param method  http method to use
     * @param body    request body to send
     * @param clazz   the type of the return value
     * @return
     * @param <T>
     */
    public <T> ResponseEntity<T> makeHttpRequest(String uri,
                                                 HttpMethod method,
                                                 Map<String, Object>  body,
                                                 Class<T> clazz) {
        HttpHeaders headersToSend = new HttpHeaders();
        headersToSend.setAccept(ImmutableList.of(MediaType.APPLICATION_JSON));
        return makeHttpRequest(uri, method, body, headersToSend, clazz);
    }

    private URI createUriComponentsBuilder(String uri) {
        return UriComponentsBuilder.fromHttpUrl(uri).build(Map.of());
    }



}
