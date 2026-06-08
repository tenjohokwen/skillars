package com.softropic.skillars.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.infrastructure.config.CommonConfig;

class JacksonTest {

    //@Test
    void testJackson() {
        ObjectMapper mapper = new CommonConfig().objectMapperBuilder().build();
        //mapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        //mapper.enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
    }
}
