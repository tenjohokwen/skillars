package com.softropic.skillars.utils.sql;

public class UpdateQuery extends SqlQuery {
    @Override
    protected String getQueryType() {
        return "UPDATE";
    }
}
