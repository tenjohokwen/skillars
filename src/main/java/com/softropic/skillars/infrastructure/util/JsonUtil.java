package com.softropic.skillars.infrastructure.util;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.infrastructure.config.CommonConfig;
import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.infrastructure.exception.ErrorCode;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;

public class JsonUtil {
    public static final ObjectMapper OBJECT_MAPPER = getDefaultObjectMapper();

    private static final String ROOT_PATH = "config/";

    /**
     * Private constructor to prevent instantiation of utility class
     */
    private JsonUtil() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    public static String fetchResource(String resourceName) {
        return fetchResource(resourceName, ".json");
    }

    public static String fetchResource(String resourceName, String extension) {
        try {
            URI uri = Objects.requireNonNull(Thread.currentThread()
                                                   .getContextClassLoader()
                                                   .getResource(ROOT_PATH + resourceName + extension)).toURI();
            return new String(Files.readAllBytes(Paths.get(uri)), StandardCharsets.UTF_8);
        } catch (IOException | URISyntaxException ex) {
            throw new ApplicationException("Could not read file.",
                                           ex,
                                           Map.of("resourceName", resourceName, "extension", extension),
                                           JsonError.FILE_READ_ERROR);
        }
    }

    public static <T> T fetchResourceAsObject(String resourceName, Class<T> type) {
        return toObject(fetchResource(resourceName), type);
    }

    public static <T> T fetchResourceAsObject(String resourceName, TypeReference<T> type) {
        return toObject(fetchResource(resourceName), type);
    }

    private static ObjectMapper getDefaultObjectMapper() {
        CommonConfig contextConfig = new CommonConfig();
        return contextConfig.objectMapperBuilder().build();
    }

    public  static <T> String toJson(T object) {
        String json;
        try {
            json =  OBJECT_MAPPER.writeValueAsString(object);
        } catch (JsonProcessingException ex) {
            throw new ApplicationException("Could not write object as json",
                                           ex,
                                           Map.of("Object", object),
                                           JsonError.OBJ_TO_JSON_ERROR);
        }
        return json;
    }

    public  static <T> T toObject(String json, Class<T> type) {
        try {
            return OBJECT_MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new ApplicationException("Could not convert json to given type",
                                           e,
                                           Map.of("json", json, "type", type),
                                           JsonError.JSON_TO_OBJ_ERROR);
        }
    }

    public  static <T> T toObject(String json, TypeReference<T> type) {
        try {
            return OBJECT_MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new ApplicationException("Could not convert json to given type reference",
                                           e,
                                           Map.of("json", json, "typeReference", type),
                                           JsonError.JSON_TO_OBJ_ERROR);
        }
    }

    enum JsonError implements ErrorCode {
        OBJ_TO_JSON_ERROR,
        JSON_TO_OBJ_ERROR,
        FILE_READ_ERROR;

        @Override
        public String getErrorCode() {
            return this.name();
        }
    }

}
