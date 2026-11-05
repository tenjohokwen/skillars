package com.softropic.skillars.infrastructure.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** skillars-deferred-170 AC4: a fractional count is refused, everything else binds as Jackson binds it today. */
class StrictIntegerDeserializerTest {

    record Holder(@JsonDeserialize(using = StrictIntegerDeserializer.class) Integer count) {}

    record Lenient(Integer count) {}

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"2.5", "2.0", "1e1", "-0.5"})
    void aFractionalOrExponentNumber_isRefused(String json) {
        assertThatThrownBy(() -> mapper.readValue("{\"count\":" + json + "}", Holder.class))
            .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void theDefaultBinding_truncates_whichIsWhatThisClosesFor() throws Exception {
        // Mutation guard: if Jackson ever stops truncating, this documents that the deserializer is redundant.
        assertThat(mapper.readValue("{\"count\":2.5}", Lenient.class).count()).isEqualTo(2);
    }

    @Test
    void aWholeNumber_binds() throws Exception {
        assertThat(mapper.readValue("{\"count\":2}", Holder.class).count()).isEqualTo(2);
    }

    @Test
    void nullAndAbsent_bindAsNull_soNotNullStillDecides() throws Exception {
        assertThat(mapper.readValue("{\"count\":null}", Holder.class).count()).isNull();
        assertThat(mapper.readValue("{}", Holder.class).count()).isNull();
    }

    @Test
    void stringHandling_isUnchanged() throws Exception {
        assertThat(mapper.readValue("{\"count\":\"2\"}", Holder.class).count()).isEqualTo(2);
        assertThatThrownBy(() -> mapper.readValue("{\"count\":\"2.5\"}", Holder.class))
            .isInstanceOf(InvalidFormatException.class);
    }
}
