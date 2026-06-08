package com.softropic.skillars.utils.sql.matcher;

public class CountStrategyFactory {
    private static final String ERROR_MSG = "Expecting a non-negative number for the count but got %s";
    private CountStrategyFactory() {}

    public static CountStrategy times(long count) {
        validate(count);
        return new Times(count);
    }

    public static CountStrategy atLeast(long count) {
        validate(count);
        return new AtLeast(count);
    }

    public static CountStrategy never() {
        return new Times(0);
    }

    public static CountStrategy once() {
        return new Times(1);
    }

    private static void validate(long count) {
        if(count < 0) {
            throw new AssertionError(ERROR_MSG.formatted(count));
        }
    }
}
