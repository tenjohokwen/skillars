package com.softropic.skillars.utils.sql.matcher;

public class Times extends CountStrategy {
    private static final String CLAUSE = "EQUAL TO";

    protected Times(long limit) {
        super(limit);
    }

    @Override
    public boolean isMatch(long actualCount) {
        return this.limit == actualCount;
    }

    @Override
    public String getClause() {
        return CLAUSE;
    }

}
