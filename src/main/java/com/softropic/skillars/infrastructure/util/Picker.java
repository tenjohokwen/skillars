package com.softropic.skillars.infrastructure.util;

import java.util.Arrays;
import java.util.List;

public interface Picker<T> {
    default boolean isOneOf(List<T> states) {
        return states.contains(this);
    }

    default boolean isOneOf(T... states) {
        return Arrays.asList(states).contains(this);
    }

    default boolean isNotOneOf(List<T> states) {
        return states.stream().filter(this::equals).findFirst().isEmpty();
    }

    default boolean isNotOneOf(T... states) {
        return Arrays.stream(states).filter(this::equals).findFirst().isEmpty();
    }

}
