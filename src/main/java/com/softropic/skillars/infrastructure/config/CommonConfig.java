package com.softropic.skillars.infrastructure.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class CommonConfig {

    //TODO consider using clockProvider
    @Bean
    public Clock clock(@Value("${zoneId:UTC}")String zoneId) {
        return Clock.system(ZoneId.of(zoneId));
    }

    //Method validation registration
    @Bean
    public MethodValidationPostProcessor methodValidationPostProcessor() {
        return new MethodValidationPostProcessor();
    }

    @Bean
    public Jackson2ObjectMapperBuilder objectMapperBuilder() {
        return new Jackson2ObjectMapperBuilder()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .defaultViewInclusion(true) //set to false by default (by spring and not jackson) using ppty MapperFeature.DEFAULT_VIEW_INCLUSION
                .modulesToInstall(new JavaTimeModule(), longToStringModule());
    }


    private SimpleModule longToStringModule() {
        //This module is used to serialize longs to strings. This is useful when we want to serialize longs to json.
        //By default, jackson serializes longs to json as numbers. However, js cannot represent numbers larger than 16 digits.
        //So we serialize longs to strings.
        //We also deserialize strings to longs.
        //This is useful when we want to deserialize longs from json.
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addDeserializer(Long.class, new LongFromStringDeserializer());
        return module;
    }


}
