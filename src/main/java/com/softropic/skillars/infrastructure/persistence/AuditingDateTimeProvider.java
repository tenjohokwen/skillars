package com.softropic.skillars.infrastructure.persistence;




import com.softropic.skillars.infrastructure.util.ClockProvider;

import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;


@Component(AuditingDateTimeProvider.NAME)
public class AuditingDateTimeProvider implements DateTimeProvider {

    public static final String NAME = "dateTimeProvider";

    @Override
    public Optional<TemporalAccessor> getNow() {
        return Optional.of(Instant.now(ClockProvider.getClock()));
    }

}
