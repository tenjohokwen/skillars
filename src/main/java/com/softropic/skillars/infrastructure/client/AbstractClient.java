package com.softropic.skillars.infrastructure.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.infrastructure.client.exception.HttpClientException;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import static com.softropic.skillars.infrastructure.util.Constants.HEARTBEAT_OK;
import static com.softropic.skillars.infrastructure.util.Constants.REQUEST_ID_HEADER_NAME;


/**
 * Functionality common to clients
 */
public abstract class AbstractClient {

    public static final String API_VERSION_HEADER_KEY = "x-api-version";

    /** Connect timeout for outbound provider calls (10s). */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /** Read timeout for outbound provider calls (30s — mobile money responses can be slow). */
    private static final int READ_TIMEOUT_MS = 30_000;

    private final String host;

    protected final RestTemplate restTemplate;

    protected final RestRequestInterceptor restRequestInterceptor;

    private final String heartbeatPath;

    protected AbstractClient(RestRequestInterceptor restRequestInterceptor, String host, String heartbeatPath) {
        this.restRequestInterceptor = restRequestInterceptor;
        SimpleClientHttpRequestFactory inner = new SimpleClientHttpRequestFactory();
        inner.setConnectTimeout(CONNECT_TIMEOUT_MS);
        inner.setReadTimeout(READ_TIMEOUT_MS);
        restTemplate = new RestTemplate(new BufferingClientHttpRequestFactory(inner));
        restTemplate.setInterceptors(Collections.singletonList(this.restRequestInterceptor));
        restTemplate.setMessageConverters(messageConverters());
        this.host = host;
        this.heartbeatPath = heartbeatPath;
    }


    public RestTemplate getRestTemplate() {
        return this.restTemplate;
    }

    public String getHost() {
        return host;
    }

    protected List<HttpMessageConverter<?>> messageConverters() {
        List<HttpMessageConverter<?>> messageConverters = new ArrayList<>();
        MappingJackson2HttpMessageConverter jsonMessageConverter = new MappingJackson2HttpMessageConverter();

        jsonMessageConverter.setObjectMapper(objectMapper());

        messageConverters.add(jsonMessageConverter);
        return messageConverters;
    }

    protected ObjectMapper objectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    protected String buildClientURL(String path) {
        return createUriComponentsBuilder(path).toUriString();
    }

    protected String buildClientURL(String path, Map<String, ?> uriVariables) {
        return createUriComponentsBuilder(path).buildAndExpand(uriVariables).toUriString();
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

    private UriComponentsBuilder createUriComponentsBuilder(String path) {
        return UriComponentsBuilder.fromUriString(host + path);
    }

    /**
     * Endpoint to check the health of the client.
     * The assumption here is that a 2xx/3xx http response status code implies that the client is up.
     * If a 4xx or 5xx is returned an exception is thrown to the invoker.
     * @return "OK" string is the expected return type for health checks.
     */
    public String healthCheck() {
        final String url = buildClientURL(heartbeatPath);
        final HttpMethod httpMethod = HttpMethod.GET;
        final String reqId = UUID.randomUUID().toString();
        final HttpHeaders httpHeaders = toHttpHeaders(Map.of(REQUEST_ID_HEADER_NAME, reqId));
        final ResponseEntity<String> responseEntity = restTemplate.exchange(url,
                                                                            httpMethod,
                                                                            new HttpEntity<>(httpHeaders),
                                                                            String.class);
        if(responseEntity.getStatusCode().is2xxSuccessful()) {
            return HEARTBEAT_OK;
        }
        throw HttpClientException.builder(reqId)
                                 .withHttpMethod(httpMethod)
                                 .withStatusCode(String.valueOf(responseEntity.getStatusCode()))
                                 .withUri(URI.create(url))
                                 .build();
    }

    /**
     * default impl wherein no headers needed
     * @return HttpHeaders headers needed by downstream server
     */
    protected HttpHeaders httpHeaders() {
        return HttpHeaders.EMPTY;
    }

    protected static HttpHeaders toHttpHeaders(Map<String, String> keyValues) {
        final HttpHeaders httpHeaders = new HttpHeaders();
        if(keyValues != null) {
            httpHeaders.setAll(keyValues);
            return httpHeaders;
        }
        return httpHeaders;
    }

    /**
     * Generic method for doing http call.
     * If any common paramters have to be added, they are added here. E.g. Accept header.
     * @param uri endpoint to hit
     * @param method method to use
     * @param body body to send
     * @param responseType type of the response
     * @param headers to be included in the http request
     * @param <B> is the body
     * @param <R> The type of the response expected
     * @return response wrapped in ResponseEntity
     */
    protected <B, R> ResponseEntity<R> makeHttpRequest(String uri, HttpMethod method, B body,
                                                       @NotNull Class<R> responseType, @NotNull HttpHeaders headers) {
        HttpEntity<B> requestEntity;
        if (body != null) {
            requestEntity = new HttpEntity<>(body, headers);
        } else {
            requestEntity = new HttpEntity<>(headers);
        }
        return restTemplate.exchange(uri, method, requestEntity, responseType);
    }


    /**
     * Generic method for doing http call.
     * @param xRequestId identifies a given request
     * @param uri endpoint to hit
     * @param method is the http method
     * @param body  of the payload
     * @param responseType expected
     * @param <B> body type
     * @param <R> response type
     * @return response wrapped in ResponseEntity
     */
    protected <B, R> ResponseEntity<R> makeHttpRequest(String xRequestId, String uri, HttpMethod method, B body, Class<R> responseType) {
        HttpHeaders headersToSend = new HttpHeaders();
        headersToSend.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (xRequestId != null) {
            headersToSend.add(REQUEST_ID_HEADER_NAME, xRequestId);
        }
        if (httpHeaders() != null) {
            headersToSend.putAll(httpHeaders());
        }
        return makeHttpRequest(uri, method, body, responseType, headersToSend);
    }



    protected <B> ResponseEntity<Map> makeHttpRequest(String xRequestId, String uri, HttpMethod method, B body) {
        return makeHttpRequest(xRequestId, uri, method, body, Map.class);
    }

    protected <B> ResponseEntity<Map> makeHttpRequest(String uri, HttpMethod method, B body) {
        return makeHttpRequest(null, uri, method, body, Map.class);
    }

}
