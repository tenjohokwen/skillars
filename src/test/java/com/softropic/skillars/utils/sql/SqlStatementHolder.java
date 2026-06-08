package com.softropic.skillars.utils.sql;

public class SqlStatementHolder {
    private static final ThreadLocal<Statement> contextHolder = ThreadLocal.withInitial(() -> new Statement());

    public static Statement getStatement() {
        return contextHolder.get();
    }

    public static Statement initStatement() {
        final Statement statement = contextHolder.get();
        statement.clearAll();
        return statement;
    }
}
