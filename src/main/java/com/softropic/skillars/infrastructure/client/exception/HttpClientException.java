package com.softropic.skillars.infrastructure.client.exception;




import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.infrastructure.exception.ErrorCode;

import org.springframework.http.HttpMethod;
import org.springframework.web.client.HttpStatusCodeException;

import java.io.Serializable;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Holds information that may help in finding out the problem. e.g vendor, httpcode, and the request url
 */
public class HttpClientException extends ApplicationException {
    private final ErrorCtx errorCtx;

    public HttpClientException(HttpClientException cause, ErrorCode errorCode) {
        super(cause.getMessage(), cause, cause.logContext, errorCode);
        this.errorCtx = cause.errorCtx;
        errorCtx.errorCode = errorCode;
    }

    private HttpClientException(String msg, Map<String, Object> logContext, ErrorCtx errorCtx){
        super(msg, errorCtx.exception, logContext, errorCtx.errorCode);
        this.errorCtx = errorCtx;
    }


    public String getHttpStatusCode() {
        return this.errorCtx.httpStatusCode;
    }

    public URI getUri() {
        return this.errorCtx.uri;
    }

    public HttpMethod getMethod() {
        return this.errorCtx.method;
    }

    public String getRequestId() {
        return this.errorCtx.requestId;
    }

    public Exception getException() {
        return this.errorCtx.exception;
    }

    public String getResponse() {
        return this.errorCtx.response;
    }

    @Override
    public ErrorCode getErrorCode() {
        return this.errorCtx.errorCode;
    }

    public static Builder builder(String requestId) {
        return new Builder(requestId);
    }

    private static class ErrorCtx implements Serializable {
        private String httpStatusCode;
        private URI uri;
        private HttpMethod method;
        private String requestId;
        private Exception exception;
        private String response;
        private ErrorCode errorCode;


        private ErrorCtx() {
        }

    }
    public static class Builder {

        private ErrorCtx      errorCtx;


        private Builder(String requestId) {
            errorCtx = new ErrorCtx();
            errorCtx.requestId = requestId;
        }

        public Builder withStatusCode(String status) {
            errorCtx.httpStatusCode = status;
            return this;
        }

        public Builder withUri(URI uri) {
            errorCtx.uri = uri;
            return this;
        }

        public Builder withHttpMethod(HttpMethod httpMethod) {
            errorCtx.method = httpMethod;
            return this;
        }

        public Builder withException(Exception exception) {
            errorCtx.exception = exception;
            return this;
        }

        public Builder withErrorCode(ErrorCode errorCode) {
            errorCtx.errorCode = errorCode;
            return this;
        }

        public Builder withResponse(String str) {
            errorCtx.response = str;
            return this;
        }

        public HttpClientException build() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("httpStatusCode", errorCtx.httpStatusCode);
            ctx.put("uri", errorCtx.uri);
            ctx.put("HttpMethod", errorCtx.method);
            ctx.put("requestId", errorCtx.requestId);
            ctx.put("cause", errorCtx.exception);
            ctx.put("errorCode", errorCtx.errorCode);
            ctx.put("errorResponse", errorCtx.response);
            return new HttpClientException(toMsg(), ctx, errorCtx);
        }

        private  String toMsg() {
            final String clientExData = Optional.ofNullable(errorCtx.exception)
                    .<String>map(this::buildErrorFragment)
                    .orElse(String.format("Http status code: '%s' ", errorCtx.httpStatusCode));
            //httpStatusCode uri method requestId responseHeaders
            final String requestMetaDataAsString = String.format("URI: '%s' method: '%s' requestId: '%s'"
                    , errorCtx.uri, errorCtx.method, errorCtx.requestId);
            return String.format("Error occurred upon restful call. %s, %s",requestMetaDataAsString, clientExData);
        }

        private String buildErrorFragment(Exception e) {
            if(e instanceof HttpStatusCodeException ex) {
                return String.format("http status Code: %s status text: %s headers: %s response body: %s",
                        ex.getStatusCode(), ex.getStatusText(),  ex.getResponseHeaders(), ex.getResponseBodyAsString());
            }
            return e.getMessage();
        }
    }


}
