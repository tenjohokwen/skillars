package com.softropic.skillars.infrastructure.client;

import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Container object for building Http request to the partner host.
 */
public class ClientRequest {

    private String endpoint;
    private final Map<String, Object> uriParams = new LinkedHashMap<>();
    private final Map<String, Object> queryParams = new LinkedHashMap<>();
    private Map<String, Object> body;
    private String      requestId;
    private HttpHeaders dynamicHttpHeaders = new HttpHeaders(new LinkedMultiValueMap<>());

    public String getEndpoint() {
        return endpoint;
    }

    public Map<String, Object> getUriParams() {
        return uriParams;
    }
    public Map<String, Object> getQueryParams() {
        return queryParams;
    }

    public Map<String, Object> getBody() {
        return body;
    }

    public String getRequestId() {
        return requestId;
    }

    public HttpHeaders getDynamicHttpHeaders() {
        return dynamicHttpHeaders;
    }

    public static ClientRequestBuilder builderFor(String endpoint) {
        return new ClientRequestBuilder(endpoint);
    }

    public static class ClientRequestBuilder {

        private final ClientRequest clientRequest = new ClientRequest();

        private ClientRequestBuilder(String endpoint) {
                this.clientRequest.endpoint = endpoint;
        }

        public ClientRequest build() {
            return clientRequest;
        }


        public ClientRequestBuilder withBody(Map<String, Object> body) {
            clientRequest.body = body;
            return this;
        }

        public ClientRequestBuilder withQueryParam(String name, Object value) {
            clientRequest.queryParams.put(name, value);
            return this;
        }

        public ClientRequestBuilder withUriParam(String name, Object value) {
            clientRequest.uriParams.put(name, value);
            return this;
        }

        public ClientRequestBuilder withRequestId(String requestId) {
            clientRequest.requestId = requestId;
            return this;
        }

        public ClientRequestBuilder withDynamicHeaders(HttpHeaders headers) {
            clientRequest.dynamicHttpHeaders.addAll(headers);
            return this;
        }
    }
}
