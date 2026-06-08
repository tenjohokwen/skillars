package com.softropic.skillars.utils.sql;

public class DeleteQuery extends SqlQuery {
    @Override
    protected String getQueryType() {
        return "DELETE";
    }
}
