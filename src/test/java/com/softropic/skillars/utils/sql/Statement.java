package com.softropic.skillars.utils.sql;

public class Statement {
    private final InsertQuery insertQuery = new InsertQuery();
    private final SelectQuery selectQuery = new SelectQuery();
    private final UpdateQuery updateQuery = new UpdateQuery();
    private final DeleteQuery deleteQuery = new DeleteQuery();

    public void clearAll() {
        insertQuery.clear();
        selectQuery.clear();
        updateQuery.clear();
        deleteQuery.clear();
    }

    void registerInsert(String str) {
        insertQuery.registerQuery(str);
    }

    void registerSelect(String str) {
        selectQuery.registerQuery(str);
    }

    void registerUpdate(String str) {
        updateQuery.registerQuery(str);
    }

    void registerDelete(String str) {
        deleteQuery.registerQuery(str);
    }

    public InsertQuery assertThatInsert() {
        return insertQuery;
    }

    public SelectQuery assertThatSelect() {
        return selectQuery;
    }

    public UpdateQuery assertThatUpdate() {
        return updateQuery;
    }

    public DeleteQuery assertThatDelete() {
        return deleteQuery;
    }
}
