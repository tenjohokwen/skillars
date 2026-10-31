package com.softropic.skillars.infrastructure.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Slf4jAccessLogValveTest {

    @Test
    void redactsCredentialQueryParametersInTheRequestLine() {
        assertThat(Slf4jAccessLogValve.redact("2026 1.2.3.4 'GET /api/activate?key=abc123 HTTP/1.1' 200"))
            .isEqualTo("2026 1.2.3.4 'GET /api/activate?key=REDACTED HTTP/1.1' 200");
        assertThat(Slf4jAccessLogValve.redact("'GET /api/verify-email?token=3f1a-22&lang=en HTTP/1.1'"))
            .isEqualTo("'GET /api/verify-email?token=REDACTED&lang=en HTTP/1.1'");
    }

    @Test
    void redactsBunnySignedUrlParametersAndIsCaseInsensitive() {
        assertThat(Slf4jAccessLogValve.redact("'GET /v/1/play.m3u8?Token=aa&expires=17&sig=zz HTTP/1.1'"))
            .isEqualTo("'GET /v/1/play.m3u8?Token=REDACTED&expires=REDACTED&sig=REDACTED HTTP/1.1'");
    }

    @Test
    void redactsTheRefererHeaderToo() {
        assertThat(Slf4jAccessLogValve.redact("'GET /x HTTP/1.1' 200 'https://h/unsubscribe?a=1&token=secret' 'UA'"))
            .isEqualTo("'GET /x HTTP/1.1' 200 'https://h/unsubscribe?a=1&token=REDACTED' 'UA'");
    }

    @Test
    void leavesOtherParametersAndSimilarNamesAlone() {
        String entry = "'GET /api/x?page=2&tokenType=bearer&monkey=1&size=5 HTTP/1.1' 200";
        assertThat(Slf4jAccessLogValve.redact(entry)).isEqualTo(entry);
    }
}
