package com.softropic.skillars.platform.booking.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BookingSseServiceTest {

    private BookingSseService sseService;

    @BeforeEach
    void setUp() {
        sseService = new BookingSseService();
    }

    @Test
    void subscribeTerminal_doesNotRegisterInEmittersMap() {
        sseService.subscribeTerminal("REFUNDED");

        Map<?, ?> emitters = (Map<?, ?>) ReflectionTestUtils.getField(sseService, "emitters");
        assertThat(emitters).isEmpty();
    }

    @Test
    void subscribeTerminal_completesTheReturnedEmitter() {
        SseEmitter emitter = sseService.subscribeTerminal("CANCELLED");

        boolean complete = (boolean) ReflectionTestUtils.getField(emitter, "complete");
        assertThat(complete).isTrue();
    }
}
