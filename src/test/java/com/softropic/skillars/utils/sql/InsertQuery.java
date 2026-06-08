package com.softropic.skillars.utils.sql;

public class InsertQuery extends SqlQuery {
    @Override
    protected String getQueryType() {
        return "INSERT";
    }
}
