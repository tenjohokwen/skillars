package com.softropic.skillars.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static com.softropic.skillars.infrastructure.util.Constants.REQUEST_ID_HEADER_NAME;

/**
 * Shared class for external partners clients and internal services.
 */
public class Client {
    public static final String HEARTBEAT = "heartbeat";
    public static final String ENDPOINT_HEADER_NAME = "X-Endpoint";

    /**
     * Create default implementation of Rest template for each client.
     * Then it's configured in the init method.
     */
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ClientConfiguration configuration;


    protected Client(ClientConfiguration clientConfig, ObjectMapper objectMapper, RestTemplate restTemplate) {
        this.configuration = clientConfig;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    /**
     * We have to expose it, because of Mock in the tests.
     * @return restTemplate
     */
    public RestTemplate getRestTemplate() {
        return this.restTemplate;
    }

    /**
     * Endpoint to check the health of the client.
     * The assumption here is that a 2xx/3xx http response status code implies that the client is up.
     * If a 4xx or 5xx is returned an exception is thrown to the invoker.
     *
     * @return {@link HealthResponse} is the expected return type for health checks.
     */
    public HealthResponse healthCheck() {
        Optional.ofNullable(configuration.getEndpoints()).map(endpoints -> endpoints.get(HEARTBEAT)).ifPresent(heartbeat -> {
            URI uri = buildClientURI(heartbeat.getUri(), Collections.emptyMap(), Collections.emptyMap());
            final HttpHeaders headers = httpHeaders();
            headers.setAccept(Collections.singletonList(MediaType.ALL));
            restTemplate.exchange(uri, heartbeat.getMethod(), new HttpEntity<>(headers), Void.class);
        });
        return HealthResponse.OK;
    }

    @SuppressWarnings("unchecked")
    protected <T> T call(ClientRequest request, Class<T> clazz) {
        final String requestEndpoint = request.getEndpoint();
        Supplier<IllegalArgumentException> endpointNotFoundException =
                () -> new IllegalArgumentException("Endpoint '" + requestEndpoint + " could not be found " );

        ClientConfiguration.Endpoint endpoint = Optional.ofNullable(configuration.getEndpoint(requestEndpoint)).orElseThrow(endpointNotFoundException);

        if(endpoint.isDisabled()) {
            throw new UnsupportedOperationException("Endpoint " + requestEndpoint + " is disabled");
        }

        URI uri = buildClientURI(endpoint.getUri(), request.getUriParams(), request.getQueryParams());
        final HttpHeaders httpHeaders = new HttpHeaders(new LinkedMultiValueMap<>());
        httpHeaders.add(Client.ENDPOINT_HEADER_NAME, requestEndpoint);
        httpHeaders.putAll(request.getDynamicHttpHeaders());
        final ResponseEntity<T> responseEntity = makeHttpRequest(uri,
                                                                 request.getRequestId(),
                                                                 endpoint.getMethod(),
                                                                 request.getBody(),
                                                                 clazz,
                                                                 httpHeaders);

        return responseEntity.getBody();
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> call(ClientRequest request) {
        return call(request, Map.class);
    }

    /**
     * Build URI for the given path and parameters
     *
     * @param uri URI path
     * @param uriParams uri parameters
     * @param queryParams query parameters
     *
     * @return URI object
     */
    protected URI buildClientURI(String uri, Map<String, Object> uriParams, Map<String, Object> queryParams) {
        UriComponentsBuilder uriComponentsBuilder = createUriComponentsBuilder(uri);
        queryParams.forEach(uriComponentsBuilder::queryParam);
        return uriComponentsBuilder.buildAndExpand(uriParams).toUri();
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    protected HttpHeaders httpHeaders() {
        HttpHeaders headers = new HttpHeaders();
        if (configuration.getHeaders() != null) {
            headers.setAll(configuration.getHeaders());
            return headers;
        }
        return headers;
    }

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
    private <T> ResponseEntity<T> makeHttpRequest(URI uri, HttpMethod method, Map<String, Object> body,
                                                                  HttpHeaders headers, Class<T> clazz) {
        HttpEntity<Map<String, Object>> requestEntity;
        if (body != null) {
            requestEntity = new HttpEntity<>(body, headers);
        } else {
            requestEntity = new HttpEntity<>(headers);
        }
        return restTemplate.exchange(uri, method, requestEntity, clazz);
    }

    private <T> ResponseEntity<T> makeHttpRequest(URI uri, String xRequestId, HttpMethod method, Map<String, Object>  body, Class<T> clazz, HttpHeaders headers) {
        HttpHeaders headersToSend = new HttpHeaders();
        headersToSend.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (xRequestId != null) {
            headersToSend.add(REQUEST_ID_HEADER_NAME, xRequestId);
        }
        if (httpHeaders() != null ) {
            headersToSend.putAll(httpHeaders());
        }
        if (headers != null) {
            headersToSend.putAll(headers);
        }
        return makeHttpRequest(uri, method, body, headersToSend, clazz);
    }

    private UriComponentsBuilder createUriComponentsBuilder(String uri) {
        return UriComponentsBuilder.fromUriString(uri);
    }

    public enum HealthResponse {
        OK,
        DOWN
    }

}
