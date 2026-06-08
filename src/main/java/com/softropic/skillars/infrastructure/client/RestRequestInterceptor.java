package com.softropic.skillars.infrastructure.client;


import com.softropic.skillars.infrastructure.util.TransactionIdProvider;
import com.softropic.skillars.infrastructure.client.exception.HttpClientException;
import com.softropic.skillars.infrastructure.client.exception.MomoError;
import com.softropic.skillars.infrastructure.util.BodySanitizer;
import com.softropic.skillars.infrastructure.security.RequestIdProvider;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.net.UnknownHostException;

import lombok.extern.slf4j.Slf4j;

import static com.softropic.skillars.infrastructure.util.Constants.HTTP_REQUEST_ID_DELIM;
import static com.softropic.skillars.infrastructure.util.Constants.REQUEST_ID_HEADER_NAME;
import static net.logstash.logback.argument.StructuredArguments.kv;


@Slf4j
@Component
public class RestRequestInterceptor implements ClientHttpRequestInterceptor {

    //private final MetricRegistry metricRegistry;

    @Autowired
    public RestRequestInterceptor() {
        //TODO add timer metrics
        //this.metricRegistry = metricRegistry;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        //Histogram latencyHistogram = metricRegistry.histogram(name(request.getMethod().name(), request.getURI().toASCIIString(), "latency"));
        long startTime = System.currentTimeMillis();
        try {
            addTransactionIdToThread(request.getHeaders());
            if (log.isDebugEnabled()) {
                log.debug("Provider HTTP request",
                        kv("operation", "provider_http_call"),
                        kv("method", request.getMethod()),
                        kv("url", request.getURI().toASCIIString()),
                        kv("body", HttpMethod.POST.equals(request.getMethod())
                                ? BodySanitizer.sanitize(body,
                                        request.getHeaders().getContentType() != null
                                                ? request.getHeaders().getContentType().toString() : null)
                                : ""));
            }
            ClientHttpResponse httpResponse = execution.execute(request, body);
            //latencyHistogram.update(System.currentTimeMillis() - startTime);
            final HttpStatus httpStatus = HttpStatus.resolve(httpResponse.getStatusCode().value());
            markResponse(request, (HttpStatus)httpResponse.getStatusCode());
            logRequestMetrics(request, httpStatus.name(), startTime);
            if(httpResponse.getStatusCode().value() > 399) {
                markResponse(request, httpStatus);
                logRequestMetrics(request, httpStatus.name(), startTime);
                final byte[] responseBytes = StreamUtils.copyToByteArray(httpResponse.getBody());
                final String response = BodySanitizer.sanitize(responseBytes,
                        httpResponse.getHeaders().getContentType() != null
                                ? httpResponse.getHeaders().getContentType().toString() : null);
                log.error("Provider API error response",
                        kv("operation", "provider_http_call"),
                        kv("httpStatus", httpResponse.getStatusCode().value()),
                        kv("status", "ERROR"),
                          kv("serverResponseBody", response),
                          kv("requestUrl", request.getURI().toASCIIString()),
                          kv("requestMethod", request.getMethod()));

                throw HttpClientException.builder(RequestIdProvider.provideRequestId())
                                         .withHttpMethod(request.getMethod())
                                         .withUri(request.getURI())
                                         .withStatusCode(String.valueOf(httpResponse.getStatusCode()))
                                         .withResponse(response)
                                         .build();

            }
            if (log.isDebugEnabled()) {
                byte[] responseBodyBytes = StreamUtils.copyToByteArray(httpResponse.getBody());
                String sanitizedBody = BodySanitizer.sanitize(responseBodyBytes,
                        httpResponse.getHeaders().getContentType() != null
                                ? httpResponse.getHeaders().getContentType().toString() : null);
                log.debug("Provider response body", kv("body", sanitizedBody));
            }

            return httpResponse;
        } catch (UnknownHostException uhe){
            throw HttpClientException.builder(RequestIdProvider.provideRequestId())
                                     .withHttpMethod(request.getMethod())
                                     .withUri(request.getURI())
                                     .withErrorCode(MomoError.CLIENT_NOT_REACHABLE)
                                     .withException(uhe)
                                     .build();
        }
        finally {
            TransactionIdProvider.removeTransactionIdFromThread();
        }

    }

    private void markResponse(HttpRequest request, HttpStatus status) {
        //Meter responseMeter = metricRegistry.meter(name(request.getMethod().name(), request.getURI().toASCIIString(), status.name()));
        //responseMeter.mark();
    }

    private void logRequestMetrics(HttpRequest request, String status, long startTime) {
        log.info("Provider HTTP response",
                kv("operation", "provider_http_call"),
                kv("method", request.getMethod()),
                kv("url", request.getURI().toASCIIString()),
                kv("httpStatus", StringUtils.trim(status)),
                kv("latencyMs", System.currentTimeMillis() - startTime));
    }

    public void addTransactionIdToThread(HttpHeaders httpHeaders) {
        try {
            String txnId;
            if(httpHeaders == null ||
                    httpHeaders.get(REQUEST_ID_HEADER_NAME) == null ||
                    StringUtils.isBlank(httpHeaders.get(REQUEST_ID_HEADER_NAME).get(0))) {
                txnId = "";
            } else {
                String[] fragments = httpHeaders.get(REQUEST_ID_HEADER_NAME).get(0).split(HTTP_REQUEST_ID_DELIM);
                txnId = fragments.length == 2 ? fragments[1] : "";
            }
            TransactionIdProvider.addTransactionIdToThread(txnId);
        } catch (Exception e) {
            log.warn("Error occurred while trying to set txnId for http request",
                    kv("operation", "provider_http_call"),
                    kv("status", "TXN_ID_SET_ERROR"));
        }
    }

}