package com.softropic.skillars.infrastructure.validation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.type.LogicalType;

import java.io.IOException;

/**
 * skillars-deferred-170 AC4: an {@link Integer} field that refuses a JSON number with a fractional part or an
 * exponent ({@code 2.5}, {@code 2.0}, {@code 1e1}) instead of truncating it. Jackson's default
 * {@code ACCEPT_FLOAT_AS_INT} binds {@code 2.5} as {@code 2}, boxed or not, before any constraint runs.
 *
 * <p>Every other token goes through Jackson's own integer parsing, so whole numbers, {@code null} and
 * today's string handling ({@code "2"} accepted, {@code "2.5"} already rejected) are unchanged. Applied per
 * field with {@code @JsonDeserialize(using = StrictIntegerDeserializer.class)}; the global coercion is left
 * alone because it covers every integer field of every endpoint.
 *
 * <p>The refusal is an {@code InvalidFormatException}, which Spring MVC reports as
 * {@code HttpMessageNotReadableException}: {@code ApiAdvice} answers 400 {@code validation.badRequest}, with
 * no field error. The pack forms only ever send whole numbers, so only a direct API call sees it.
 */
public class StrictIntegerDeserializer extends StdScalarDeserializer<Integer> {

    public StrictIntegerDeserializer() {
        super(Integer.class);
    }

    @Override
    public LogicalType logicalType() {
        return LogicalType.Integer;
    }

    @Override
    public Integer deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) {
            return (Integer) ctxt.handleWeirdNumberValue(Integer.class, p.getNumberValue(),
                "a whole number is required");
        }
        return _parseInteger(p, ctxt, Integer.class);
    }
}
