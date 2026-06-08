package com.softropic.skillars.utils.sql.matcher;

public abstract class CountStrategy {
    protected final long limit;

    protected CountStrategy(long limit) {this.limit = limit;}

    public abstract boolean isMatch(long actualCount);

    public long getLimit() {
        return limit;
    }

    public abstract String getClause();
}
