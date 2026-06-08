package com.softropic.skillars.utils.sql;

import com.github.mnadeem.TableNameParser;
import com.softropic.skillars.utils.sql.matcher.CountStrategy;

import java.util.Arrays;
import java.util.List;

public class SelectQuery extends SqlQuery {

    private static boolean queryContainsTables(String str, String[] tableNames) {
        return new TableNameParser(str).tables().containsAll(Arrays.stream(tableNames).toList());
    }

    /**
     * Assert the number of times a join query is executed on given tables
     * @param countStrategy How the count is done.
     * @param tableNames Names of the target tables
     * @return
     */
    public SqlQuery executedOnJoinTables(CountStrategy countStrategy, String... tableNames) {
        final List<String> joins = queryStrings.stream()
                                               .filter(str -> str.toLowerCase().contains("join"))
                                               .filter(str -> queryContainsTables(str, tableNames)).toList();
        final int joinCount = joins.size();
        if(!countStrategy.isMatch(joinCount)) {
            final List<String> tables = Arrays.stream(tableNames).toList();
            final String msg = String.format("Expected count on join tables %s to be %s %s but actual count is %s",
                                             tables, countStrategy.getClause(), countStrategy.getLimit(), joinCount);
            throw new AssertionError(msg);
        }

        return this;
    }

    @Override
    protected String getQueryType() {
        return "SELECT";
    }
}
