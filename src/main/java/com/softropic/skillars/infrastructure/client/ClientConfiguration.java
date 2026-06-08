package com.softropic.skillars.infrastructure.client;

import org.springframework.http.HttpMethod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClientConfiguration {

    private Map<String, String> headers = new HashMap<>();

    private Map<String, Endpoint> endpoints = new HashMap<>();

    private Map<String, String> attributes = new HashMap<>();

    private TcpConfiguration tcpConfig;

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public Map<String, Endpoint> getEndpoints() {
        return endpoints;
    }

    public Endpoint getEndpoint(String endpointName) {
        return endpoints.get(endpointName);
    }

    public void setEndpoints(Map<String, Endpoint> endpoints) {
        this.endpoints = endpoints;
    }

    public static class Endpoint {
        private String uri;
        private HttpMethod method;
        private Map<String, List<String>> maskedPaths;
        private boolean disabled = false;

        public Endpoint() {}

        public Endpoint(String uri, HttpMethod method, Map<String, List<String>> maskedPaths) {
            this.uri = uri;
            this.method = method;
            this.maskedPaths = maskedPaths;
        }

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public HttpMethod getMethod() {
            return method;
        }

        public void setMethod(HttpMethod method) {
            this.method = method;
        }

        public Map<String, List<String>> getMaskedPaths() {
            return maskedPaths;
        }

        public void setMaskedPaths(Map<String, List<String>> maskedPaths) {
            this.maskedPaths = maskedPaths;
        }

        public boolean isDisabled() {
            return disabled;
        }

        public void setDisabled(boolean disabled) {
            this.disabled = disabled;
        }

        @Override
        public String toString() {
            return method + " " + uri;
        }
    }

    public TcpConfiguration getTcpConfig() {
        return tcpConfig;
    }

    public void setTcpConfig(TcpConfiguration tcpConfiguration) {
        this.tcpConfig = tcpConfiguration;
    }
}
