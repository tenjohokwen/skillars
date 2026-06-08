package com.softropic.skillars.utils.sql;

import com.github.mnadeem.TableNameParser;
import com.softropic.skillars.utils.sql.matcher.CountStrategy;

import org.assertj.core.api.Assertions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public abstract class SqlQuery {
    protected final List<String> queryStrings = new ArrayList<>();

    /**
     * Assert the number of times a given query type is invoked
     * @param expectedCount Expected number of times
     * @return
     */
    public SqlQuery hasCount(int expectedCount) {
        if(expectedCount < 0) {
            final String msg = "Expected a non-negative number to check query count but got %s".formatted(expectedCount);
            throw new AssertionError(msg);
        }
        Assertions.assertThat(queryStrings).hasSize(expectedCount);
        return this;
    }

    /**
     * Assert that a given query is done at least once on the target table
     * @param tableName The name of the target table
     * @return
     */
    public SqlQuery executedOnTable(String tableName) {
        if(tableName == null || tableName.trim().isEmpty()) {
            final String msg = "Expected non-null, non-blank table name but got %s".formatted(tableName);
            throw new AssertionError(msg);
        }
        Assertions.assertThat(extractTables()).contains(tableName);
        return this;
    }

    /**
     *
     * Assert the number of times a query is executed on a given table
     * @param countStrategy How the count is done.
     * @param tableName Name of the target table
     * @return
     */
    public SqlQuery executedOnTable(CountStrategy countStrategy, String tableName) {
        if(countStrategy == null) {
            final String msg = "Expected non-null count strategy.";
            throw new AssertionError(msg);
        }
        if(tableName == null || tableName.trim().isEmpty()) {
            final String msg = "Expected non-null, non-blank table name but got %s.".formatted(tableName);
            throw new AssertionError(msg);
        }
        final Collection<String> tables = extractNonJoinTables();
        final long count = tables.stream().filter(tName -> tName.equals(tableName)).count();
        if(!countStrategy.isMatch(count)) {
            final String msg = String.format("Expected %s query count on %s to be %s %s but actual count is %s",
                                             this.getQueryType(), tableName, countStrategy.getClause(), countStrategy.getLimit(), count);
            throw new AssertionError(msg);
        }

        return this;
    }

    /** Returns the number of recorded query strings. */
    public int size() {
        return queryStrings.size();
    }

    void registerQuery(String qStr) {
        queryStrings.add(qStr);
    }

    public void clear() {
        queryStrings.clear();
    }

    protected Collection<String> extractTables() {
        return extractTables(queryStrings);
    }

    protected Collection<String> extractNonJoinTables() {
        final List<String> queries = queryStrings.stream()
                                                 .filter(str -> !str.toLowerCase().contains("join")).toList();
        return extractTables(queries);
    }

    protected Collection<String> extractTables(Collection<String> qStrings) {
        return qStrings.stream()
                       .map(qStr -> new TableNameParser(qStr).tables())
                       .flatMap(Collection::stream)
                       .toList();
    }

    protected abstract String getQueryType();
}
