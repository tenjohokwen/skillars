package com.softropic.skillars.platform.booking.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HomeworkDrillIdsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Logger logger = (Logger) LoggerFactory.getLogger(HomeworkDrillIds.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    void parse_nullAndBlank_returnEmptyWithoutWarning() {
        assertThat(HomeworkDrillIds.parse(mapper, null)).isEmpty();
        assertThat(HomeworkDrillIds.parse(mapper, "  ")).isEmpty();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void parse_validJson_returnsIdsInOrder() throws Exception {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        assertThat(HomeworkDrillIds.parse(mapper, mapper.writeValueAsString(ids))).isEqualTo(ids);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void parse_malformedJson_returnsEmptyAndWarns() {
        assertThat(HomeworkDrillIds.parse(mapper, "{not json")).isEmpty();
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
    }
}
